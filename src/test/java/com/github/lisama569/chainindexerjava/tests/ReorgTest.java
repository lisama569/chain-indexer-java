package com.github.lisama569.chainindexerjava.tests;

import com.github.lisama569.chainindexerjava.config.IndexerProperties;
import com.github.lisama569.chainindexerjava.index.IndexStore;
import com.github.lisama569.chainindexerjava.ingest.BlockIngestor;
import com.github.lisama569.chainindexerjava.ingest.ForkRegistry;
import com.github.lisama569.chainindexerjava.ingest.IngestionOutcome;
import com.github.lisama569.chainindexerjava.metrics.IndexerMetrics;
import com.github.lisama569.chainindexerjava.model.Block;
import com.github.lisama569.chainindexerjava.model.ChainState;
import com.github.lisama569.chainindexerjava.model.Transaction;
import com.github.lisama569.chainindexerjava.model.TxRef;
import com.github.lisama569.chainindexerjava.simulator.LocalChainGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 链重组：等长分叉是抖动不得重组；更长竞争链触发回滚到共同祖先并重放，索引不重不漏。 */
class ReorgTest {

    private static final Logger log = LoggerFactory.getLogger(ReorgTest.class);

    private IndexStore store;
    private BlockIngestor ingestor;
    private LocalChainGenerator generator;
    private IndexerMetrics metrics;
    private ForkRegistry forks;

    @BeforeEach
    void setUp() {
        store = new IndexStore();
        metrics = new IndexerMetrics();
        forks = new ForkRegistry();
        ingestor = new BlockIngestor(store, forks, metrics, new IndexerProperties());
        generator = new LocalChainGenerator();
    }

    @Test
    void equalLengthForkIsJitter_andLongerForkTriggersReorgWithoutLeakOrDoubleCount() {
        // 规范链 A：高度 0..5
        List<Block> chainA = generator.generate(11L, 6);
        for (Block b : chainA) {
            ingestor.ingestCanonical(b);
        }
        ChainState beforeReorg = store.current();
        log.info("规范链 A 摄入完成 tip={} chainId={}", beforeReorg.tipHeight(),
                beforeReorg.chainId());

        // 从 A2 分叉出 B：B3,B4,B5 与 A 等长 -> 抖动，全部暂存，规范链不动
        List<Block> forkB = generator.generateFork(chainA.get(2), 77L, 4); // 高度 3..6
        assertEquals(3L, forkB.get(0).height());
        assertEquals(6L, forkB.get(3).height());

        for (int i = 0; i < 3; i++) {
            IngestionOutcome parked = ingestor.ingestForkCandidate(forkB.get(i));
            log.info("抖动候选 input={} -> {}", BlockIngestor.summarize(forkB.get(i)),
                    parked.status() + " (" + parked.detail() + ")");
            assertEquals(IngestionOutcome.Status.FORK_PARKED, parked.status());
            assertEquals(5L, store.current().tipHeight(), "等长分叉不得移动规范链末端");
            assertEquals(beforeReorg.chainId(), store.current().chainId(),
                    "抖动期间链标识不得改变");
        }

        // 再提交 B6：竞争链末端 6 严格领先规范末端 5 -> 触发重组
        Block b6 = forkB.get(3);
        IngestionOutcome reorg = ingestor.ingestForkCandidate(b6);
        log.info("重组候选 input={} -> status={}, rollbackTo={}, {} -> {}, chainId={}",
                BlockIngestor.summarize(b6), reorg.status(), reorg.rollbackToHeight(),
                reorg.reorgedFrom(), reorg.reorgedTo(), reorg.chainId());
        assertEquals(IngestionOutcome.Status.REORG_APPLIED, reorg.status());
        assertEquals(2L, reorg.rollbackToHeight(), "共同祖先应为 A2 高度 2");
        assertEquals(5L, reorg.reorgedFrom());
        assertEquals(6L, reorg.reorgedTo());

        ChainState after = store.current();
        assertEquals(6L, after.tipHeight());
        assertEquals(b6.hash(), after.tipHash());
        assertNotEquals(beforeReorg.chainId(), after.chainId());

        // 共同祖先 A0..A2 保留；A3..A5 必须从所有索引中消失（无旧链残留）
        for (long h = 0; h <= 2; h++) {
            assertEquals(chainA.get((int) h).hash(),
                    after.blocksByHeight().get(h).hash(), "共同祖先区块应保留");
        }
        for (long h = 3; h <= 5; h++) {
            Block old = chainA.get((int) h);
            Block nowAtHeight = after.blocksByHeight().get(h);
            assertNotNull(nowAtHeight, "高度 " + h + " 在新链上应有区块(B" + h + ")");
            assertNotEquals(old.hash(), nowAtHeight.hash(),
                    "高度 " + h + " 必须已从旧链 A 切换到新链 B");
            assertFalse(after.blocksByHash().containsKey(old.hash()),
                    "旧链区块哈希索引必须清除: " + old.hash());
            for (Transaction tx : old.transactions()) {
                assertFalse(after.receipts().containsKey(tx.txHash()),
                        "旧链交易回执必须清除");
            }
        }
        // 新链 B3..B6 必须完整存在
        for (Block b : forkB) {
            assertSame(b, after.blocksByHash().get(b.hash()));
            for (Transaction tx : b.transactions()) {
                assertNotNull(after.receipts().get(tx.txHash()), "新链交易回执必须存在");
            }
        }

        // 地址索引不重不漏：对每个地址，引用集合必须恰好等于新链(A0..A2 + B3..B6)的交易集合
        Map<String, Integer> expectedCounts = new HashMap<>();
        for (long h = 0; h <= 2; h++) {
            for (Transaction tx : chainA.get((int) h).transactions()) {
                for (String addr : tx.involvedAddresses()) {
                    expectedCounts.merge(addr, 1, Integer::sum);
                }
            }
        }
        for (Block b : forkB) {
            for (Transaction tx : b.transactions()) {
                for (String addr : tx.involvedAddresses()) {
                    expectedCounts.merge(addr, 1, Integer::sum);
                }
            }
        }
        expectedCounts.forEach((addr, count) -> {
            List<TxRef> refs = after.addressIndex().get(addr);
            assertNotNull(refs, "地址 " + addr + " 应有索引");
            assertEquals(count, refs.size(), "地址 " + addr + " 引用计数不得重复或遗漏");
            long distinct = refs.stream().map(TxRef::txHash).distinct().count();
            assertEquals(refs.size(), distinct, "地址 " + addr + " 不得有重复交易引用");
            for (int i = 1; i < refs.size(); i++) {
                assertTrue(refs.get(i - 1).height() <= refs.get(i).height());
            }
        });
        // 任何只出现在 A3..A5 的地址都不应再被索引
        for (Block old : chainA.subList(3, 6)) {
            for (Transaction tx : old.transactions()) {
                for (String addr : tx.involvedAddresses()) {
                    if (!expectedCounts.containsKey(addr)) {
                        assertFalse(after.addressIndex().containsKey(addr),
                                "仅存在于旧链的地址索引必须清除: " + addr);
                    }
                }
            }
        }

        var m = metrics.snapshot();
        log.info("重组后指标 {}", m);
        assertEquals(1L, m.get("reorgs"));
        assertEquals(3L, m.get("reorgBlocksRolledBack"));
        assertEquals(4L, m.get("reorgBlocksReplayed"));
        assertEquals(3L, m.get("ingestForkParkedBlocks"));
    }
}
