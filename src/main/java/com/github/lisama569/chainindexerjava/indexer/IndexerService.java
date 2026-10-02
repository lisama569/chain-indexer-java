package com.github.lisama569.chainindexerjava.indexer;

import com.github.lisama569.chainindexerjava.config.IndexerProperties;
import com.github.lisama569.chainindexerjava.domain.Block;
import com.github.lisama569.chainindexerjava.domain.Receipt;
import com.github.lisama569.chainindexerjava.domain.Transaction;
import com.github.lisama569.chainindexerjava.ingest.IngestResult;
import com.github.lisama569.chainindexerjava.ingest.RejectReason;
import com.github.lisama569.chainindexerjava.ingest.ThresholdUpdateResult;
import com.github.lisama569.chainindexerjava.metrics.IndexerMetrics;
import com.github.lisama569.chainindexerjava.storage.ChainStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 索引服务：摄入区块（校验、拒绝、重组），并对外发布不可变查询快照。
 * <p>
 * 并发模型：单写者（{@link ReentrantLock} 串行化所有摄入/重组/门槛调整）+
 * 多读者（读取 {@code volatile} 当前快照引用，无锁）。
 * 查询拿到某个快照后，其结论永远对应快照上确定的高度与链标识，
 * 且扫描过程不与摄入争用任何写锁。
 * <p>
 * 竞争链逐块到达：凡是不能直接延伸规范链的区块都先收存进 {@link ChainStore}
 * （绝不当作脏数据丢弃），随后立即做一次"接管判定"——在区块池中寻找已连通
 * 规范链、且链尖领先达到换链门槛 {@code reorgMinLead} 的竞争链；满足则回滚到
 * 共同祖先并重放接管，否则继续观察（候选信息保留，后续每块到达都会重判）。
 */
@Service
public class IndexerService {

    private static final Logger log = LoggerFactory.getLogger(IndexerService.class);
    public static final String GENESIS_PARENT = "0x0";

    private final ChainStore store;
    private final IndexerMetrics metrics;
    private final ReentrantLock ingestLock = new ReentrantLock();

    /** 换链门槛：竞争链链尖至少领先规范链多少块才允许接管（>=1，运行时可调）。 */
    private volatile int reorgMinLead;

    /** 当前已发布快照，volatile 引用整体替换保证读者可见性。 */
    private volatile ChainSnapshot current;

    public IndexerService(ChainStore store, IndexerMetrics metrics) {
        this(store, metrics, IndexerProperties.DEFAULT_REORG_MIN_LEAD);
    }

    @Autowired
    public IndexerService(ChainStore store, IndexerMetrics metrics, IndexerProperties properties) {
        this(store, metrics,
                IndexerProperties.sanitizeMinLead(properties.getReorgMinLead(), log));
    }

    public IndexerService(ChainStore store, IndexerMetrics metrics, int reorgMinLead) {
        this.store = store;
        this.metrics = metrics;
        this.reorgMinLead = IndexerProperties.sanitizeMinLead(reorgMinLead, log);
        this.current = emptySnapshot();
    }

    private static ChainSnapshot emptySnapshot() {
        return new ChainSnapshot(0L, -1L, GENESIS_PARENT, null,
                Map.of(), Map.of(), Map.of(), Map.of());
    }

    public ChainSnapshot currentSnapshot() {
        return current;
    }

    /** 当前生效的换链门槛。 */
    public int reorgMinLead() {
        return reorgMinLead;
    }

    /**
     * 运行时调整换链门槛（非法值打印可读 WARN 并回退安全默认）。
     * 调整后立即对已收存的竞争链候选重判：若此前因门槛按住不发的分叉
     * 现已满足条件，则当场接管。
     * <p>
     * 返回结果如实反映本次调整：只有接管真正完成（新快照已发布）
     * {@code tookOver} 才为 true；若达标候选在重放时被拒绝而接管未完成，
     * {@code tookOver=false}，索引数据保持完全不变，{@code detail} 说明原因。
     */
    public ThresholdUpdateResult setReorgMinLead(int newMinLead) {
        ingestLock.lock();
        try {
            int sanitized = IndexerProperties.sanitizeMinLead(newMinLead, log);
            int old = this.reorgMinLead;
            this.reorgMinLead = sanitized;
            log.info("换链门槛更新: {} -> {}（请求值 {}）", old, sanitized, newMinLead);
            ChainSnapshot snap = current;
            List<List<Block>> eligible = eligibleForks(snap, sanitized);
            String firstClash = null;
            for (List<Block> fork : eligible) {
                TakeoverAttempt attempt = attemptTakeover(fork);
                if (attempt.succeeded()) {
                    ChainSnapshot after = attempt.snapshot();
                    String detail = ("门槛调整 %d -> %d 后立即重判，竞争链满足条件并已成功接管；%s")
                            .formatted(old, sanitized, attempt.detail());
                    return new ThresholdUpdateResult(newMinLead, sanitized, true, detail,
                            after.tipHeight(), after.tipHash(), after.chainId());
                }
                log.warn("门槛调整后候选链重放被拒，接管未发生（索引不变）: {}", attempt.detail());
                if (firstClash == null) {
                    firstClash = attempt.detail();
                }
            }
            if (!eligible.isEmpty()) {
                // 有达到门槛的候选，但重放均被拒绝：绝不能谎报成功，索引保持完全不变
                String detail = ("门槛已更新为 %d（请求值 %d）；存在领先达到 %d 块的竞争链，"
                        + "但接管未能完成——重放校验被拒：%s；本次未发生换链，索引数据保持完全不变")
                        .formatted(sanitized, newMinLead, sanitized, firstClash);
                return new ThresholdUpdateResult(newMinLead, sanitized, false, detail,
                        snap.tipHeight(), snap.tipHash(), snap.chainId());
            }
            String detail = ("门槛已更新为 %d（请求值 %d）；当前没有连通规范链且领先达到 %d 块的"
                    + "竞争链，本次未发生换链，索引数据不变")
                    .formatted(sanitized, newMinLead, sanitized);
            return new ThresholdUpdateResult(newMinLead, sanitized, false, detail,
                    snap.tipHeight(), snap.tipHash(), snap.chainId());
        } finally {
            ingestLock.unlock();
        }
    }

    // ------------------------------------------------------------------
    // 摄入
    // ------------------------------------------------------------------

    public IngestResult ingest(Block block) {
        ingestLock.lock();
        try {
            return doIngest(block);
        } finally {
            ingestLock.unlock();
        }
    }

    private IngestResult doIngest(Block block) {
        log.info("ingest input -> {}", block == null ? "null" : block.summary());

        IngestResult invalid = validateShape(block);
        if (invalid != null) {
            return reject(invalid.reason(), invalid.detail(), block);
        }

        ChainSnapshot snap = current;
        long h = block.height();

        // 同哈希区块再次出现：内容必须完全一致（哈希重放保护）
        Block existing = store.findByHash(block.hash()).orElse(null);
        if (existing != null && !sameBlock(existing, block)) {
            return reject(RejectReason.INVALID_BLOCK,
                    "hash %s 已存在但区块内容不一致".formatted(block.hash()), block);
        }

        if (h <= snap.tipHeight()) {
            Block canonical = snap.blocks().get(h);
            if (canonical != null && Objects.equals(canonical.hash(), block.hash())) {
                // 已在规范链上：重复提交被拒绝（原因可区分），数据不变、不重复计数
                log.info("ingest DUPLICATE -> {} 已在规范链 h={}", block.hash(), h);
                return reject(RejectReason.DUPLICATE_BLOCK,
                        "重复提交：区块 hash=%s 已在规范链高度 %d，拒绝但不改变任何数据（幂等）"
                                .formatted(block.hash(), h),
                        block);
            }
            // 高度已被规范链占用但内容不同：同高度冲突 / 分叉抖动。
            // 收存为分叉候选（绝不覆盖规范链数据），随后做接管判定：
            // 若它所在的竞争链已补齐并领先到门槛，这里就会当场接管。
            store.put(block);
            return observeOrTakeover(block, RejectReason.HEIGHT_CONFLICT,
                    ("高度 %d 已存在规范区块 hash=%s，收到不同 hash=%s（分叉抖动，"
                            + "规范数据不覆盖，已收存为分叉候选）")
                            .formatted(h, canonical == null ? "?" : canonical.hash(),
                                    block.hash()));
        }

        if (h == snap.tipHeight() + 1) {
            if (!Objects.equals(block.parentHash(), snap.tipHash())) {
                // 父哈希不连续：竞争链延伸块，收存候选并做接管判定
                store.put(block);
                return observeOrTakeover(block, RejectReason.PARENT_HASH_MISMATCH,
                        "高度 %d 的父哈希 %s 与规范链尖 %s 不连续（已收存为分叉候选）"
                                .formatted(h, block.parentHash(), snap.tipHash()));
            }
            String clash = findTxClash(block, snap);
            if (clash != null) {
                store.put(block);
                return reject(RejectReason.INVALID_BLOCK, clash, block);
            }
            store.put(block);
            publish(applyBlock(snap, block));
            metrics.recordAccepted(block.transactions().size());
            log.info("ingest accepted(extend) h={} hash={} txCount={}",
                    h, block.hash(), block.transactions().size());
            return IngestResult.accepted(
                    "顺序延伸：h=%d 父哈希与链尖连续".formatted(h),
                    h, block.hash(), block.chainId());
        }

        // h > tip+1：跳跃输入或竞争链超前块，收存候选并做接管判定
        store.put(block);
        return observeOrTakeover(block, RejectReason.HEIGHT_GAP,
                ("高度 %d 跳跃（当前链尖 %d），已收存；若无法沿父哈希回溯到规范链上的共同祖先，"
                        + "待中间区块补齐后会自动重判")
                        .formatted(h, snap.tipHeight()));
    }

    /**
     * 候选已收存后的统一接管判定：若当前存在连通规范链且领先达到换链门槛的
     * 竞争链，则回滚接管并返回 reorg 结果；否则保持观察，按该区块与规范链的
     * 位置关系返回可区分的拒绝原因（候选信息保留，绝不丢弃）。
     * <p>
     * 若存在达到门槛的候选，但全部在重放校验中被拒绝（如交易哈希冲突），
     * 则按 {@code INVALID_BLOCK} 拒绝本次输入，索引完全不变。
     */
    private IngestResult observeOrTakeover(Block trigger, RejectReason pendingReason,
                                           String pendingDetail) {
        ChainSnapshot snap = current;
        List<List<Block>> eligible = eligibleForks(snap, reorgMinLead);
        if (eligible.isEmpty()) {
            List<Block> best = findBestFork(snap);
            if (best != null) {
                long forkTip = best.get(best.size() - 1).height();
                long need = snap.tipHeight() + reorgMinLead;
                pendingDetail += ("（竞争链已连通至 h=%d，未达到接管门槛 h>=%d，继续观察，"
                        + "候选不丢弃）").formatted(forkTip, need);
            }
            return reject(pendingReason, pendingDetail, trigger);
        }
        String firstClash = null;
        for (List<Block> fork : eligible) {
            TakeoverAttempt attempt = attemptTakeover(fork);
            if (attempt.succeeded()) {
                return IngestResult.reorged(attempt.detail(),
                        attempt.snapshot().tipHeight(),
                        attempt.snapshot().tipHash(),
                        attempt.snapshot().chainId());
            }
            if (firstClash == null) {
                firstClash = attempt.detail();
            }
        }
        // 达标候选的重放全部被拒：本次输入按非法拒绝，绝不发布半成品快照
        return reject(RejectReason.INVALID_BLOCK,
                "竞争链虽已达到换链门槛，但接管重放被整体拒绝，未发生换链、索引不变："
                        + firstClash, trigger);
    }

    /**
     * 所有"挂在当前规范链上"、且链尖领先达到 {@code minLead} 块的竞争链，
     * 按链尖高度降序（哈希升序兜底，保证确定性）排列。
     */
    private List<List<Block>> eligibleForks(ChainSnapshot snap, int minLead) {
        long need = snap.tipHeight() + minLead;
        List<Block> candidates = store.findAllAbove(snap.tipHeight());
        candidates.sort(Comparator.comparingLong(Block::height).reversed()
                .thenComparing(Block::hash));
        List<List<Block>> out = new ArrayList<>();
        for (Block tip : candidates) {
            List<Block> fork = traceToCanonical(tip, snap);
            if (fork != null && tip.height() >= need) {
                out.add(fork);
            }
        }
        return out;
    }

    /**
     * 在区块池中寻找"挂在当前规范链上"的最长竞争链（不考虑门槛是否满足），
     * 仅用于在"继续观察"的拒绝说明里给出竞争链已连通到的高度。
     */
    private List<Block> findBestFork(ChainSnapshot snap) {
        List<Block> candidates = store.findAllAbove(snap.tipHeight());
        candidates.sort(Comparator.comparingLong(Block::height).reversed()
                .thenComparing(Block::hash));
        for (Block tip : candidates) {
            List<Block> fork = traceToCanonical(tip, snap);
            if (fork != null) {
                return fork;
            }
        }
        return null;
    }

    /**
     * 从候选链尖沿 parentHash 回溯，直到落到规范链上的共同祖先（或创世）。
     * 返回共同祖先之后的分叉区块（按高度升序）；无法连通规范链返回 null。
     */
    private List<Block> traceToCanonical(Block tip, ChainSnapshot snap) {
        List<Block> path = new ArrayList<>();
        Block cursor = tip;
        Set<String> guard = new HashSet<>();
        while (cursor != null && guard.add(cursor.hash())) {
            Block canonical = snap.blocks().get(cursor.height());
            if (canonical != null && Objects.equals(canonical.hash(), cursor.hash())) {
                // cursor 在规范链上：path 即共同祖先之后的分叉段
                if (path.isEmpty()) {
                    return null;
                }
                Collections.reverse(path);
                return path;
            }
            path.add(cursor);
            String parent = cursor.parentHash();
            if (GENESIS_PARENT.equals(parent)) {
                // 分叉直达创世：仅当确为 h=0 创世块才可连通
                if (cursor.height() == 0) {
                    Collections.reverse(path);
                    return path;
                }
                return null;
            }
            Block p = store.findByHash(parent).orElse(null);
            if (p == null || p.height() != cursor.height() - 1) {
                // 父哈希未知或高度不连续：链断开，待补齐
                return null;
            }
            cursor = p;
        }
        return null;
    }

    /**
     * 一次接管尝试的结果：要么成功并已发布完整新快照，要么失败（含可读原因），
     * 失败时绝不修改当前快照，供调用方如实反馈。
     */
    private record TakeoverAttempt(boolean succeeded, ChainSnapshot snapshot, String detail) {
        static TakeoverAttempt ok(ChainSnapshot snap, String detail) {
            return new TakeoverAttempt(true, snap, detail);
        }

        static TakeoverAttempt failed(String detail) {
            return new TakeoverAttempt(false, null, detail);
        }
    }

    /**
     * 换链接管：回滚到共同祖先，重放竞争链区块重建索引，全部成功后整体发布新快照。
     * 重放中任一交易哈希冲突则整体拒绝：不发布、不计数、索引保持完全不变。
     */
    private TakeoverAttempt attemptTakeover(List<Block> fork) {
        ChainSnapshot before = current;
        long forkPoint = fork.get(0).height() - 1;
        ChainSnapshot rebuilt = rebuildUpTo(forkPoint);
        for (Block b : fork) {
            String clash = findTxClash(b, rebuilt);
            if (clash != null) {
                return TakeoverAttempt.failed(clash);
            }
            rebuilt = applyBlock(rebuilt, b);
        }
        // 只有走到这里才整体发布：调用方任何时候都拿不到"重放了一半"的快照
        publish(rebuilt);
        metrics.recordReorg(forkPoint, fork.size());
        for (Block b : fork) {
            metrics.recordAccepted(b.transactions().size());
        }
        log.warn("REORG committed: forkPoint={} oldTipH={} newTipH={} newTipHash={} "
                        + "replayed={} minLead={}",
                forkPoint, before.tipHeight(), rebuilt.tipHeight(), rebuilt.tipHash(),
                fork.size(), reorgMinLead);
        return TakeoverAttempt.ok(rebuilt,
                ("竞争链达到换链门槛（领先 >= %d 块）：回滚至共同祖先高度 %d，"
                        + "重放 %d 个区块，新链尖 %s")
                        .formatted(reorgMinLead, forkPoint, fork.size(), rebuilt.tipHash()));
    }

    /** 按当前规范链区块重放到指定高度（-1 表示从空状态开始），得到回滚基线快照。 */
    private ChainSnapshot rebuildUpTo(long targetHeight) {
        ChainSnapshot s = emptySnapshot();
        if (targetHeight < 0) {
            return s;
        }
        for (long hh = 0; hh <= targetHeight; hh++) {
            Block canonical = current.blocks().get(hh);
            if (canonical == null) {
                break;
            }
            s = applyBlock(s, canonical);
        }
        return s;
    }

    // ------------------------------------------------------------------
    // 快照构建（整体复制 -> 不可变发布）
    // ------------------------------------------------------------------

    private ChainSnapshot applyBlock(ChainSnapshot prev, Block block) {
        Map<Long, Block> blocks = new LinkedHashMap<>(prev.blocks());
        Map<String, Transaction> txByHash = new HashMap<>(prev.txByHash());
        Map<String, Receipt> receipts = new HashMap<>(prev.receipts());
        Map<String, List<String>> addrIndex = new HashMap<>(prev.txHashesByAddr());

        blocks.put(block.height(), block);

        List<Transaction> txs = block.transactions() == null ? List.of() : block.transactions();
        for (int i = 0; i < txs.size(); i++) {
            Transaction tx = txs.get(i);
            // 交易所属高度以区块为准，保证索引结论一致
            Transaction fixed = new Transaction(tx.txHash(), tx.from(), tx.to(),
                    tx.value(), block.height());
            txByHash.put(fixed.txHash(), fixed);
            // 本地模拟回执：确定性生成，避免重组后回执飘忽
            receipts.put(fixed.txHash(), new Receipt(
                    fixed.txHash(), block.height(), block.hash(),
                    true, 21_000L + (long) i * 100L));
            for (String addr : fixed.involvedAddresses()) {
                List<String> list = new ArrayList<>(
                        addrIndex.getOrDefault(addr, List.of()));
                if (!list.contains(fixed.txHash())) {
                    list.add(fixed.txHash());
                }
                addrIndex.put(addr, List.copyOf(list));
            }
        }

        return new ChainSnapshot(
                prev.version() + 1,
                block.height(),
                block.hash(),
                block.chainId(),
                Collections.unmodifiableMap(blocks),
                Collections.unmodifiableMap(txByHash),
                Collections.unmodifiableMap(receipts),
                Collections.unmodifiableMap(addrIndex));
    }

    private void publish(ChainSnapshot snapshot) {
        this.current = snapshot;
    }

    // ------------------------------------------------------------------
    // 校验与结果封装
    // ------------------------------------------------------------------

    /** 交易哈希必须全局唯一；与已索引交易冲突时返回说明。 */
    private String findTxClash(Block b, ChainSnapshot snap) {
        List<Transaction> txs = b.transactions() == null ? List.of() : b.transactions();
        for (Transaction tx : txs) {
            if (snap.txByHash().containsKey(tx.txHash())) {
                return "交易哈希 %s 已存在于高度 %d，拒绝重复索引（block=%s）".formatted(
                        tx.txHash(), snap.txByHash().get(tx.txHash()).blockHeight(), b.hash());
            }
        }
        return null;
    }

    private IngestResult validateShape(Block b) {
        ChainSnapshot c = current;
        if (b == null) {
            return IngestResult.rejected(RejectReason.INVALID_BLOCK, "区块为 null",
                    c.tipHeight(), c.tipHash(), c.chainId());
        }
        if (b.height() < 0 || b.hash() == null || b.hash().isBlank()) {
            return IngestResult.rejected(RejectReason.INVALID_BLOCK,
                    "区块高度/哈希非法: " + b.summary(),
                    c.tipHeight(), c.tipHash(), c.chainId());
        }
        if (b.parentHash() == null || b.parentHash().isBlank()) {
            return IngestResult.rejected(RejectReason.INVALID_BLOCK,
                    "父哈希为空: " + b.summary(),
                    c.tipHeight(), c.tipHash(), c.chainId());
        }
        if (b.chainId() == null || b.chainId().isBlank()) {
            return IngestResult.rejected(RejectReason.INVALID_BLOCK,
                    "chainId 为空: " + b.summary(),
                    c.tipHeight(), c.tipHash(), c.chainId());
        }
        if (b.height() > 0 && GENESIS_PARENT.equals(b.parentHash())) {
            return IngestResult.rejected(RejectReason.INVALID_BLOCK,
                    "非创世块父哈希不能为 %s: %s".formatted(GENESIS_PARENT, b.summary()),
                    c.tipHeight(), c.tipHash(), c.chainId());
        }
        if (b.transactions() != null) {
            Set<String> uniq = new HashSet<>();
            for (Transaction tx : b.transactions()) {
                if (tx == null || tx.txHash() == null || tx.txHash().isBlank()
                        || !uniq.add(tx.txHash())) {
                    return IngestResult.rejected(RejectReason.INVALID_BLOCK,
                            "区块内交易为空或哈希重复: " + b.summary(),
                            c.tipHeight(), c.tipHash(), c.chainId());
                }
            }
        }
        return null;
    }

    private boolean sameBlock(Block a, Block b) {
        return a.height() == b.height()
                && Objects.equals(a.hash(), b.hash())
                && Objects.equals(a.parentHash(), b.parentHash())
                && Objects.equals(a.chainId(), b.chainId())
                && Objects.equals(a.transactions() == null ? 0 : a.transactions().size(),
                        b.transactions() == null ? 0 : b.transactions().size());
    }

    private IngestResult reject(RejectReason reason, String detail, Block input) {
        metrics.recordRejected(reason.name());
        log.warn("ingest REJECTED reason={} | basis={} | input={}",
                reason, detail, input == null ? "null" : input.summary());
        ChainSnapshot snap = current;
        return IngestResult.rejected(reason, detail,
                snap.tipHeight(), snap.tipHash(), snap.chainId());
    }
}
