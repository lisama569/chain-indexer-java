package com.github.lisama569.chainindexerjava.indexer;

import com.github.lisama569.chainindexerjava.domain.Block;
import com.github.lisama569.chainindexerjava.domain.Receipt;
import com.github.lisama569.chainindexerjava.domain.Transaction;
import com.github.lisama569.chainindexerjava.ingest.IngestResult;
import com.github.lisama569.chainindexerjava.ingest.RejectReason;
import com.github.lisama569.chainindexerjava.metrics.IndexerMetrics;
import com.github.lisama569.chainindexerjava.storage.ChainStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
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
 * 并发模型：单写者（{@link ReentrantLock} 串行化所有摄入/重组）+
 * 多读者（读取 {@code volatile} 当前快照引用，无锁）。
 * 查询拿到某个快照后，其结论永远对应快照上确定的高度与链标识，
 * 且扫描过程不与摄入争用任何写锁。
 */
@Service
public class IndexerService {

    private static final Logger log = LoggerFactory.getLogger(IndexerService.class);
    public static final String GENESIS_PARENT = "0x0";

    private final ChainStore store;
    private final IndexerMetrics metrics;
    private final ReentrantLock ingestLock = new ReentrantLock();

    /** 当前已发布快照，volatile 引用整体替换保证读者可见性。 */
    private volatile ChainSnapshot current;

    public IndexerService(ChainStore store, IndexerMetrics metrics) {
        this.store = store;
        this.metrics = metrics;
        this.current = emptySnapshot();
    }

    private static ChainSnapshot emptySnapshot() {
        return new ChainSnapshot(0L, -1L, GENESIS_PARENT, null,
                Map.of(), Map.of(), Map.of(), Map.of());
    }

    public ChainSnapshot currentSnapshot() {
        return current;
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
            // 存入 ChainStore 作为潜在分叉候选，但绝不覆盖规范链数据。
            store.put(block);
            return reject(RejectReason.HEIGHT_CONFLICT,
                    ("高度 %d 已存在规范区块 hash=%s，收到不同 hash=%s（分叉抖动，"
                            + "按最长链规则暂不覆盖；已收存为分叉候选）")
                            .formatted(h, canonical == null ? "?" : canonical.hash(), block.hash()),
                    block);
        }

        if (h == snap.tipHeight() + 1) {
            if (!Objects.equals(block.parentHash(), snap.tipHash())) {
                // 父哈希不连续：可能是分叉链尖，收存候选但拒绝延伸
                store.put(block);
                return reject(RejectReason.PARENT_HASH_MISMATCH,
                        "高度 %d 的父哈希 %s 与规范链尖 %s 不连续（已收存为分叉候选）"
                                .formatted(h, block.parentHash(), snap.tipHash()),
                        block);
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

        // h > tip+1：可能是跳跃输入，也可能带来一条更长分叉
        store.put(block);
        List<Block> fork = findLongestConnectingFork(block, snap);
        if (fork == null || fork.isEmpty()) {
            return reject(RejectReason.HEIGHT_GAP,
                    ("高度 %d 跳跃（当前链尖 %d），且无法沿父哈希回溯到规范链上的共同祖先，"
                            + "缺少中间区块（已收存，待补齐）")
                            .formatted(h, snap.tipHeight()),
                    block);
        }

        Block forkTip = fork.get(fork.size() - 1);
        // 能回溯连接到规范链的候选块高度一定 > tip，故此处分叉必然严格更长；
        // 等长/更短的抖动分叉在此前 h<=tip 路径已被归类为 HEIGHT_CONFLICT。

        // 严格更长：回滚到共同祖先，重放分叉区块重建索引
        Block anchor = GENESIS_PARENT.equals(fork.get(0).parentHash())
                ? null
                : store.findByHash(fork.get(0).parentHash()).orElse(null);
        long forkPoint = anchor == null ? -1L : anchor.height();
        ChainSnapshot base = rebuildUpTo(forkPoint);
        ChainSnapshot rebuilt = base;
        for (Block b : fork) {
            String clash = findTxClash(b, rebuilt);
            if (clash != null) {
                return reject(RejectReason.INVALID_BLOCK, clash, block);
            }
            rebuilt = applyBlock(rebuilt, b);
        }
        publish(rebuilt);
        metrics.recordReorg();
        for (Block b : fork) {
            metrics.recordAccepted(b.transactions().size());
        }
        log.warn("REORG committed: forkPoint={} oldTipH={} newTipH={} newTipHash={} replayed={}",
                forkPoint, snap.tipHeight(), rebuilt.tipHeight(), rebuilt.tipHash(), fork.size());
        return IngestResult.reorged(
                ("检测到更长分叉：回滚至共同祖先高度 %d，重放 %d 个区块，新链尖 %s")
                        .formatted(forkPoint, fork.size(), rebuilt.tipHash()),
                rebuilt.tipHeight(), rebuilt.tipHash(), forkTip.chainId());
    }

    /**
     * 从候选区块沿 parentHash 搜索一条"挂在当前规范链上"的最长分叉。
     * 返回分叉自身区块列表（共同祖先之后、按高度升序）；无法连接规范链返回 null。
     */
    private List<Block> findLongestConnectingFork(Block candidate, ChainSnapshot snap) {
        List<Block> best = null;
        List<List<Block>> paths = new ArrayList<>();
        collectPaths(candidate, new ArrayList<>(), new HashSet<>(), paths, snap);
        for (List<Block> path : paths) {
            List<Block> asc = new ArrayList<>(path);
            Collections.reverse(asc);
            Block first = asc.get(0);
            Block anchor = GENESIS_PARENT.equals(first.parentHash())
                    ? null
                    : store.findByHash(first.parentHash()).orElse(null);
            boolean connected;
            if (anchor == null) {
                connected = GENESIS_PARENT.equals(first.parentHash())
                        && first.height() == 0;
            } else {
                connected = snap.blocks().containsKey(anchor.height())
                        && Objects.equals(snap.blocks().get(anchor.height()).hash(), anchor.hash());
            }
            if (connected && (best == null
                    || asc.get(asc.size() - 1).height() > best.get(best.size() - 1).height())) {
                best = asc;
            }
        }
        return best;
    }

    /** DFS 收集从 node 向上、直到落到规范链祖先为止的全部父路径（降序）。 */
    private void collectPaths(Block node, List<Block> trail, Set<String> seen,
                              List<List<Block>> out, ChainSnapshot snap) {
        if (node == null || !seen.add(node.hash())) {
            return;
        }
        trail.add(node);
        String parent = node.parentHash();
        boolean anchored = GENESIS_PARENT.equals(parent)
                ? node.height() == 0
                : snap.blocks().values().stream().anyMatch(b -> b.hash().equals(parent));
        if (anchored) {
            out.add(new ArrayList<>(trail));
            trail.remove(trail.size() - 1);
            seen.remove(node.hash());
            return;
        }
        Block p = store.findByHash(parent).orElse(null);
        if (p != null && p.height() == node.height() - 1) {
            collectPaths(p, trail, seen, out, snap);
        }
        // 父哈希未知：路径断开，不上报（高度跳跃待补齐）
        trail.remove(trail.size() - 1);
        seen.remove(node.hash());
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
