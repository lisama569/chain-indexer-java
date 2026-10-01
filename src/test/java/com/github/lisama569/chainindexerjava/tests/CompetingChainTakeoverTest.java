package com.github.lisama569.chainindexerjava.tests;

import com.github.lisama569.chainindexerjava.domain.Block;
import com.github.lisama569.chainindexerjava.indexer.ChainSnapshot;
import com.github.lisama569.chainindexerjava.indexer.IndexerService;
import com.github.lisama569.chainindexerjava.ingest.IngestResult;
import com.github.lisama569.chainindexerjava.ingest.RejectReason;
import com.github.lisama569.chainindexerjava.metrics.IndexerMetrics;
import com.github.lisama569.chainindexerjava.storage.ChainStore;
import com.github.lisama569.chainindexerjava.support.ChainGenerator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 竞争链逐块到达与换链门槛：
 * 1. 分叉链一块一块陆续到达，未超过规范链时只观察不丢候选，严格更长即自动接管；
 * 2. 换链门槛调高后，领先不足必须按住不发；
 * 3. 门槛调低后立即重判，此前按住的分叉当场接管；
 * 4. 非法门槛值回退安全默认（1），行为可预期。
 */
class CompetingChainTakeoverTest {

    private static final Logger log = LoggerFactory.getLogger(CompetingChainTakeoverTest.class);
    private final ChainGenerator gen = new ChainGenerator();

    private IndexerService seedChainA(IndexerService svc, int n) {
        gen.genesisChain("chain-A", n).forEach(svc::ingest);
        return svc;
    }

    /** chain-B 从 chain-A 的 forkPoint 高度之后分叉，共 count 块。 */
    private List<Block> forkB(int forkPoint, int count) {
        return gen.forkChain("chain-B", forkPoint, count,
                ChainGenerator.hash("chain-A", forkPoint));
    }

    @Test
    void blockByBlockCompetingChainTakesOverOnceStrictlyLonger() {
        IndexerMetrics metrics = new IndexerMetrics();
        IndexerService svc = seedChainA(
                new IndexerService(new ChainStore(), metrics), 5); // 规范链 h=0..4
        List<Block> fork = forkB(1, 4); // 共同祖先 h=1，竞争链 h=2..5 逐块到达

        // h=2,3：同高度冲突，只收存观察，不换链、数据不进索引
        for (int i = 0; i < 2; i++) {
            IngestResult r = svc.ingest(fork.get(i));
            log.info("[case] 逐块到达 h={} -> accepted={} reason={} reorg={}",
                    fork.get(i).height(), r.accepted(), r.reason(), r.reorg());
            assertFalse(r.accepted());
            assertFalse(r.reorg());
            assertEquals(RejectReason.HEIGHT_CONFLICT, r.reason());
            assertEquals(4, svc.currentSnapshot().tipHeight(), "未超过规范链不得换链");
            assertEquals("chain-A", svc.currentSnapshot().chainId());
        }

        // h=4：与规范链追平，仍然只观察（等长不换链）
        IngestResult tie = svc.ingest(fork.get(2));
        log.info("[case] 追平 h=4 -> reason={} reorg={} detail={}",
                tie.reason(), tie.reorg(), tie.detail());
        assertFalse(tie.reorg());
        assertEquals(RejectReason.HEIGHT_CONFLICT, tie.reason());
        assertEquals(ChainGenerator.hash("chain-A", 4), svc.currentSnapshot().tipHash());
        assertTrue(svc.currentSnapshot().transaction("chain-B-tx4").isEmpty(),
                "候选链数据在接管前不得进入索引");

        // h=5：严格更长（领先 1 块，达到默认门槛），当场自动接管
        IngestResult win = svc.ingest(fork.get(3));
        log.info("[case] 反超 h=5 -> accepted={} reorg={} detail={}",
                win.accepted(), win.reorg(), win.detail());
        assertTrue(win.accepted());
        assertTrue(win.reorg(), "竞争链严格更长时必须自动接管");
        assertEquals(5, win.tipHeight());
        assertEquals(ChainGenerator.forkHash("chain-B", 5), win.tipHash());
        assertEquals("chain-B", win.chainId());

        ChainSnapshot snap = svc.currentSnapshot();
        // 共同祖先保留
        assertEquals(ChainGenerator.hash("chain-A", 0), snap.blocks().get(0L).hash());
        assertEquals(ChainGenerator.hash("chain-A", 1), snap.blocks().get(1L).hash());
        // h=2..5 全部来自新链，旧链段落无残留
        for (long h = 2; h <= 5; h++) {
            assertEquals(ChainGenerator.forkHash("chain-B", h), snap.blocks().get(h).hash());
            assertTrue(snap.transaction("chain-B-tx%d".formatted(h)).isPresent());
        }
        for (long h = 2; h <= 4; h++) {
            String oldTx = "chain-A-tx%d".formatted(h);
            assertTrue(snap.transaction(oldTx).isEmpty(), "旧链交易 %s 必须移除".formatted(oldTx));
            assertTrue(snap.receipt(oldTx).isEmpty(), "旧链回执 %s 必须移除".formatted(oldTx));
        }
        // 地址索引不重复计数：addrB 只保留共同祖先的 2 笔 chain-A 交易
        assertEquals(2, snap.transactionsOf("addrB").stream()
                .filter(t -> t.startsWith("chain-A-tx")).count());
        assertEquals(4, snap.transactionsOf("addrC").size());

        // 换链统计可从指标读取
        assertEquals(1L, metrics.snapshot().get("reorgs"));
        assertEquals(1L, metrics.snapshot().get("lastReorgForkPoint"));
        assertEquals(4L, metrics.snapshot().get("lastReorgReplayedBlocks"));
    }

    @Test
    void raisedThresholdHoldsBackInsufficientLead() {
        IndexerMetrics metrics = new IndexerMetrics();
        IndexerService svc = seedChainA(
                new IndexerService(new ChainStore(), metrics, 2), 4); // tip=3，门槛=2
        assertEquals(2, svc.reorgMinLead());
        List<Block> fork = forkB(1, 4); // h=2..5

        // h=2,3 同高度冲突；h=4 到达时领先仅 1 块（4 < 3+2），必须按住不发
        for (int i = 0; i < 3; i++) {
            IngestResult r = svc.ingest(fork.get(i));
            log.info("[case] 门槛=2 逐块到达 h={} -> reason={} reorg={} detail={}",
                    fork.get(i).height(), r.reason(), r.reorg(), r.detail());
            assertFalse(r.reorg(), "领先不足门槛不得接管");
        }
        ChainSnapshot held = svc.currentSnapshot();
        assertEquals(3, held.tipHeight());
        assertEquals(ChainGenerator.hash("chain-A", 3), held.tipHash());
        assertEquals("chain-A", held.chainId());
        assertTrue(held.transaction("chain-B-tx4").isEmpty(), "被按住的候选不得进索引");
        assertEquals(0L, metrics.snapshot().get("reorgs"));

        // h=5 到达：领先 2 块达到门槛，立即接管
        IngestResult win = svc.ingest(fork.get(3));
        log.info("[case] 差距达标 h=5 -> reorg={} detail={}", win.reorg(), win.detail());
        assertTrue(win.reorg());
        assertEquals(5, svc.currentSnapshot().tipHeight());
        assertEquals("chain-B", svc.currentSnapshot().chainId());
        assertEquals(1L, metrics.snapshot().get("reorgs"));
        assertEquals(1L, metrics.snapshot().get("lastReorgForkPoint"));
        assertEquals(4L, metrics.snapshot().get("lastReorgReplayedBlocks"));
    }

    @Test
    void loweringThresholdRejudgesAndTakesOver() {
        IndexerMetrics metrics = new IndexerMetrics();
        IndexerService svc = seedChainA(
                new IndexerService(new ChainStore(), metrics, 2), 4); // tip=3，门槛=2
        List<Block> fork = forkB(1, 3); // h=2..4：领先 1 块，不足以接管
        fork.forEach(svc::ingest);
        assertEquals(3, svc.currentSnapshot().tipHeight(), "门槛=2 时领先 1 块必须按住");
        assertEquals(0L, metrics.snapshot().get("reorgs"));

        // 门槛调回 1：立即重判，此前按住的分叉当场接管
        boolean tookOver = svc.setReorgMinLead(1);
        log.info("[case] 门槛 2->1 后 tookOver={} tip={}/{}",
                tookOver, svc.currentSnapshot().tipHeight(), svc.currentSnapshot().tipHash());
        assertTrue(tookOver, "门槛调低后已满足条件的候选必须立即接管");
        assertEquals(1, svc.reorgMinLead());
        ChainSnapshot snap = svc.currentSnapshot();
        assertEquals(4, snap.tipHeight());
        assertEquals(ChainGenerator.forkHash("chain-B", 4), snap.tipHash());
        assertEquals("chain-B", snap.chainId());
        assertEquals(1L, metrics.snapshot().get("reorgs"));
        assertEquals(1L, metrics.snapshot().get("lastReorgForkPoint"));
        assertEquals(3L, metrics.snapshot().get("lastReorgReplayedBlocks"));
    }

    @Test
    void invalidMinLeadFallsBackToSafeDefault() {
        // 构造期非法值：0、负数都回退默认 1
        IndexerService zero = new IndexerService(new ChainStore(), new IndexerMetrics(), 0);
        assertEquals(1, zero.reorgMinLead(), "非法门槛 0 必须回退安全默认 1");
        IndexerService negative = new IndexerService(new ChainStore(), new IndexerMetrics(), -5);
        assertEquals(1, negative.reorgMinLead(), "非法门槛 -5 必须回退安全默认 1");

        // 回退后行为按默认口径：严格更长（领先 1 块）即接管
        seedChainA(zero, 3); // tip=2
        List<Block> fork = gen.forkChain("chain-B", 0, 3, ChainGenerator.hash("chain-A", 0));
        IngestResult last = null;
        for (Block b : fork) {
            last = zero.ingest(b);
        }
        assertNotNull(last);
        assertTrue(last.reorg(), "默认门槛下领先 1 块必须接管");
        assertEquals(3, zero.currentSnapshot().tipHeight());
        assertEquals("chain-B", zero.currentSnapshot().chainId());

        // 运行时调整非法值：同样回退默认而不是沿用错误值
        IndexerService svc = new IndexerService(new ChainStore(), new IndexerMetrics(), 3);
        assertEquals(3, svc.reorgMinLead());
        svc.setReorgMinLead(0);
        assertEquals(1, svc.reorgMinLead(), "运行时非法调整必须回退安全默认 1");
    }
}
