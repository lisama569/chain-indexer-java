package com.github.lisama569.chainindexerjava.ingest;

import com.github.lisama569.chainindexerjava.config.IndexerProperties;
import com.github.lisama569.chainindexerjava.index.IndexStore;
import com.github.lisama569.chainindexerjava.metrics.IndexerMetrics;
import com.github.lisama569.chainindexerjava.model.Block;
import com.github.lisama569.chainindexerjava.model.ChainState;
import com.github.lisama569.chainindexerjava.util.HashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 区块摄入入口：单写者（{@code writeLock}）串行化所有判定与快照发布。
 *
 * <p><b>规范提交 {@link #ingestCanonical(Block)}</b> 只接受按高度顺序、父子相连的区块，
 * 其余输入一律拒绝并给出可区分原因，绝不静默覆盖已有数据：
 * <ul>
 *   <li>{@link RejectionReason#DUPLICATE_BLOCK}：高度与哈希都已存在（安全的幂等重复）；</li>
 *   <li>{@link RejectionReason#CONFLICT_AT_HEIGHT}：同一高度已存在不同哈希；</li>
 *   <li>{@link RejectionReason#HEIGHT_GAP}：高度不衔接当前末端；</li>
 *   <li>{@link RejectionReason#PARENT_HASH_MISMATCH}：高度正确但父哈希对不上。</li>
 * </ul>
 *
 * <p><b>分叉提交 {@link #ingestForkCandidate(Block)}</b>：候选先暂存；仅当其所属竞争链
 * 末端严格领先规范链至少 {@code indexer.reorg-ahead-margin} 个高度（默认 1）时才重组。
 * 等长或更短的竞争链被视为分叉抖动，规范链结论保持不变。
 */
@Service
public class BlockIngestor {

    private static final Logger log = LoggerFactory.getLogger(BlockIngestor.class);

    private final IndexStore store;
    private final ForkRegistry forkRegistry;
    private final IndexerMetrics metrics;
    private final IndexerProperties properties;
    private final Object writeLock = new Object();

    public BlockIngestor(IndexStore store, ForkRegistry forkRegistry,
                         IndexerMetrics metrics, IndexerProperties properties) {
        this.store = store;
        this.forkRegistry = forkRegistry;
        this.metrics = metrics;
        this.properties = properties;
    }

    /** 规范链顺序摄入。 */
    public IngestionOutcome ingestCanonical(Block block) {
        synchronized (writeLock) {
            long start = System.nanoTime();
            ChainState state = store.current();

            IngestionOutcome invalid = validateOrNull(block, state);
            if (invalid != null) {
                metrics.recordRejected();
                log.info("摄入拒绝[规范] input={} reason={} basis={}",
                        summarize(block), invalid.rejectionReason(), invalid.detail());
                return invalid;
            }

            ChainState next = store.append(block);
            metrics.recordAcceptedBlock(block.transactions().size(), System.nanoTime() - start);
            log.info("摄入接受[规范] input={} basis=height/tip+1 且 parentHash==tipHash, "
                            + "newTip={}, version={}, chainId={}",
                    summarize(block), next.tipHeight(), next.version(),
                    HashUtils.shortHash(next.chainId()));
            return IngestionOutcome.accepted(block, next.chainId());
        }
    }

    /** 分叉候选摄入：直接衔接末端时按追加处理；否则暂存，或在形成更长链时触发重组。 */
    public IngestionOutcome ingestForkCandidate(Block block) {
        synchronized (writeLock) {
            long start = System.nanoTime();
            ChainState state = store.current();

            if (block == null) {
                IngestionOutcome o = IngestionOutcome.rejected(null,
                        RejectionReason.HEIGHT_GAP, "block is null");
                metrics.recordRejected();
                return o;
            }

            // 已在规范链上：属于重复数据，幂等拒绝，不做任何覆盖。
            Block canonicalSameHash = state.blocksByHash().get(block.hash());
            if (canonicalSameHash != null) {
                IngestionOutcome o = IngestionOutcome.rejected(block, RejectionReason.DUPLICATE_BLOCK,
                        "hash already finalized on canonical chain at height "
                                + canonicalSameHash.height());
                metrics.recordRejected();
                log.info("摄入拒绝[分叉] input={} reason=DUPLICATE_BLOCK basis={}",
                        summarize(block), o.detail());
                return o;
            }

            // 候选直接衔接当前规范链末端：它本质上是下一个规范区块，按追加处理，
            // 使“走哪个接口提交”不影响最终索引结论。
            if (!state.isEmpty()
                    && block.height() == state.tipHeight() + 1
                    && block.parentHash() != null
                    && block.parentHash().equals(state.tipHash())) {
                return ingestCanonical(block);
            }

            boolean alreadyParked = forkRegistry.containsHash(block.hash());
            forkRegistry.park(block);

            var planOpt = forkRegistry.findLongerFork(block, state, properties.getReorgAheadMargin());
            if (planOpt.isEmpty()) {
                metrics.recordForkParked();
                String basis = alreadyParked
                        ? "candidate hash already parked (idempotent)"
                        : "competing chain not strictly longer than canonical tip "
                            + state.tipHeight() + " by margin " + properties.getReorgAheadMargin()
                            + " (treated as fork jitter) or no common ancestor";
                log.info("分叉暂存 input={} basis={}", summarize(block), basis);
                return IngestionOutcome.forkParked(block, basis);
            }

            ReorgPlan plan = planOpt.get();
            ChainState next = store.applyReorg(plan);
            forkRegistry.pruneAfterReorg(plan);
            long rolledBack = state.tipHeight() - plan.commonAncestorHeight();
            long replayed = plan.newChainSuffix().size();
            metrics.recordReorg(rolledBack, replayed, System.nanoTime() - start);
            log.info("重组执行 input={} basis=competing tip {} >= canonical tip {} + margin {}, "
                            + "rollbackTo={} ({} blocks), replay {} blocks, newTip={}, version={}, chainId={}",
                    summarize(block), plan.newTipHeight(), plan.oldTipHeight(),
                    properties.getReorgAheadMargin(), plan.commonAncestorHeight(),
                    rolledBack, replayed, next.tipHeight(), next.version(),
                    HashUtils.shortHash(next.chainId()));
            return IngestionOutcome.reorgApplied(
                    plan.commonAncestorHeight(), plan.oldTipHeight(),
                    next.tipHeight(), next.chainId());
        }
    }

    /** 返回拒绝结果；若输入合法应被接受则返回 null。 */
    private IngestionOutcome validateOrNull(Block block, ChainState state) {
        if (block == null) {
            return IngestionOutcome.rejected(null, RejectionReason.HEIGHT_GAP, "block is null");
        }

        // 同一高度已有内容：哈希相同=重复，哈希不同=冲突。顺序上先于跳跃/父哈希判定。
        Block existingAtHeight = state.blocksByHeight().get(block.height());
        if (existingAtHeight != null) {
            if (existingAtHeight.hash().equals(block.hash())) {
                return IngestionOutcome.rejected(block, RejectionReason.DUPLICATE_BLOCK,
                        "height " + block.height() + " already finalized with identical hash");
            }
            return IngestionOutcome.rejected(block, RejectionReason.CONFLICT_AT_HEIGHT,
                    "height " + block.height() + " already finalized with hash "
                            + HashUtils.shortHash(existingAtHeight.hash())
                            + ", incoming hash " + HashUtils.shortHash(block.hash())
                            + " differs; existing data is never overwritten");
        }

        long expectedHeight = state.isEmpty() ? 0L : state.tipHeight() + 1;
        if (block.height() != expectedHeight) {
            return IngestionOutcome.rejected(block, RejectionReason.HEIGHT_GAP,
                    "expected next height " + expectedHeight + " but got " + block.height()
                            + " (canonical tip=" + state.tipHeight() + ")");
        }

        if (!state.isEmpty()) {
            String expectedParent = state.tipHash();
            if (block.parentHash() == null || !block.parentHash().equals(expectedParent)) {
                return IngestionOutcome.rejected(block, RejectionReason.PARENT_HASH_MISMATCH,
                        "at height " + block.height() + " expected parentHash "
                                + HashUtils.shortHash(expectedParent) + " but got "
                                + HashUtils.shortHash(block.parentHash()));
            }
        } else if (block.parentHash() != null
                && !HashUtils.ZERO_HASH.equals(block.parentHash())) {
            return IngestionOutcome.rejected(block, RejectionReason.PARENT_HASH_MISMATCH,
                    "genesis block at height 0 must declare zero parentHash, got "
                            + HashUtils.shortHash(block.parentHash()));
        }
        return null;
    }

    /** 输入摘要：高度、哈希、父哈希、交易数及交易哈希前缀，用于日志可解释性。 */
    public static String summarize(Block b) {
        if (b == null) {
            return "Block{null}";
        }
        StringBuilder txs = new StringBuilder();
        b.transactions().forEach(tx -> txs.append(HashUtils.shortHash(tx.txHash())).append(','));
        return "Block{h=" + b.height()
                + ", hash=" + HashUtils.shortHash(b.hash())
                + ", parent=" + HashUtils.shortHash(b.parentHash())
                + ", txs=" + b.transactions().size()
                + "[" + txs + "]}";
    }

    ChainState current() {
        return store.current();
    }
}
