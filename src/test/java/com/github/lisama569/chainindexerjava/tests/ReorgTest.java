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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 链重组：共同祖先回滚 + 新链重放；短暂分叉抖动不得误判为重组。
 */
class ReorgTest {

    private static final Logger log = LoggerFactory.getLogger(ReorgTest.class);
    private final ChainGenerator gen = new ChainGenerator();

    private IndexerService seedChainA(int n) {
        IndexerService svc = new IndexerService(new ChainStore(), new IndexerMetrics());
        gen.genesisChain("chain-A", n).forEach(svc::ingest);
        return svc;
    }

    /** 构造分叉：在 forkPoint 高度的父链上接 chain-B，长度为 forkLen。 */
    private List<Block> forkB(int forkPoint, int forkLen) {
        // 分叉第一块的父 = chain-A 在 forkPoint 的哈希
        String parent = ChainGenerator.hash("chain-A", forkPoint);
        List<Block> blocks = new ArrayList<>();
        for (int i = 1; i <= forkLen; i++) {
            long h = forkPoint + i;
            String hash = ChainGenerator.forkHash("chain-B", h);
            List<com.github.lisama569.chainindexerjava.domain.Transaction> txs =
                    List.of(ChainGenerator.tx("chain-B-tx%d".formatted(h),
                            "addrB", "addrC", 200 + h));
            blocks.add(gen.block(h, hash, parent, "chain-B", txs));
            parent = hash;
        }
        return blocks;
    }

    @Test
    void shortForkJitterIsNotConfirmedAsReorg() {
        IndexerService svc = seedChainA(4); // tip h=3
        // 分叉从 h=1 之后开始，第一块 h=2 与规范链 h=2 同高度不同内容 -> HEIGHT_CONFLICT
        Block jitter = forkB(1, 1).get(0);
        log.info("[case] 分叉抖动输入(同高度冲突): {}", jitter.summary());
        IngestResult r = svc.ingest(jitter);
        log.info("[case] 判定依据: accepted={} reason={} reorg={} detail={}",
                r.accepted(), r.reason(), r.reorg(), r.detail());
        assertFalse(r.accepted());
        assertFalse(r.reorg());
        assertEquals(RejectReason.HEIGHT_CONFLICT, r.reason());
        ChainSnapshot snap = svc.currentSnapshot();
        assertEquals(ChainGenerator.hash("chain-A", 3), snap.tipHash(), "抖动不得切换链尖");
        assertTrue(snap.transaction("chain-B-tx2").isEmpty(), "抖动块数据不得进入规范索引");

        // 抖动继续延伸到 h=3（与链尖等长），父哈希也不连续 -> 仍不得重组
        Block jitterNext = gen.block(3, ChainGenerator.forkHash("chain-B", 3),
                ChainGenerator.forkHash("chain-B", 2), "chain-B",
                List.of(ChainGenerator.tx("chain-B-tx3", "addrB", "addrC", 203)));
        IngestResult r2 = svc.ingest(jitterNext);
        log.info("[case] 等长抖动延伸判定: reason={} reorg={} detail={}",
                r2.reason(), r2.reorg(), r2.detail());
        assertFalse(r2.reorg());
        assertEquals(ChainGenerator.hash("chain-A", 3), svc.currentSnapshot().tipHash());
    }

    @Test
    void longerForkTriggersReorgWithRollbackAndReplay() {
        IndexerService svc = seedChainA(4); // 规范链 h=0..3
        List<Block> fork = forkB(1, 4);    // 共同祖先 h=1，新链 h=2..5（更长）

        // 先制造"高度跳跃"：直接投递新链尖 h=5，中间块缺失 -> HEIGHT_GAP
        Block tipFirst = fork.get(3);
        log.info("[case] 跳跃投递分叉链尖: {}", tipFirst.summary());
        IngestResult gap = svc.ingest(tipFirst);
        log.info("[case] 判定依据: reason={} detail={}", gap.reason(), gap.detail());
        assertEquals(RejectReason.HEIGHT_GAP, gap.reason());
        assertEquals(3, svc.currentSnapshot().tipHeight());

        // 补齐分叉区块（升序）：h=2,h=3 为同高度冲突候选；
        // h=4 到达时分叉接通且严格更长，当次提交即触发接管（无需再次投递链尖）
        IngestResult last = null;
        for (int i = 0; i < fork.size() - 1; i++) {
            log.info("[case] 补分叉块: {}", fork.get(i).summary());
            last = svc.ingest(fork.get(i));
            log.info("[case] 判定依据: accepted={} reason={} reorg={}",
                    last.accepted(), last.reason(), last.reorg());
        }
        // h=4 的提交已完成接管（区块池中 h=5 早已收存，重放包含 h2..h5）
        log.info("[case] 接管判定: accepted={} reorg={} detail={} tip={}/{}",
                last.accepted(), last.reorg(), last.detail(), last.tipHeight(), last.tipHash());

        assertTrue(last.accepted());
        assertTrue(last.reorg(), "严格更长分叉必须触发重组");
        assertEquals(5, last.tipHeight());
        assertEquals(ChainGenerator.forkHash("chain-B", 5), last.tipHash());
        assertEquals("chain-B", last.chainId());

        // 再次投递链尖：已在新规范链上，幂等拒绝、不重复计数
        log.info("[case] 接管后重投链尖: {}", tipFirst.summary());
        IngestResult dup = svc.ingest(tipFirst);
        log.info("[case] 判定依据: accepted={} reason={} detail={}", dup.accepted(), dup.reason(), dup.detail());
        assertEquals(RejectReason.DUPLICATE_BLOCK, dup.reason());
        assertEquals(5, svc.currentSnapshot().tipHeight());

        ChainSnapshot snap = svc.currentSnapshot();
        // 共同祖先保留
        assertEquals(ChainGenerator.hash("chain-A", 0), snap.blocks().get(0L).hash());
        assertEquals(ChainGenerator.hash("chain-A", 1), snap.blocks().get(1L).hash());
        // h>=2 全部来自新链
        for (long h = 2; h <= 5; h++) {
            assertEquals(ChainGenerator.forkHash("chain-B", h), snap.blocks().get(h).hash(),
                    "高度 " + h + " 应为新链区块");
        }
        // 无旧链残留、无重复计数、无遗漏
        for (long h = 2; h <= 3; h++) {
            String oldTx = "chain-A-tx%d".formatted(h);
            assertTrue(snap.transaction(oldTx).isEmpty(),
                    "旧链交易 " + oldTx + " 必须在回滚后移除");
            assertTrue(snap.receipt(oldTx).isEmpty(),
                    "旧链回执 " + oldTx + " 必须移除");
        }
        for (long h = 2; h <= 5; h++) {
            String newTx = "chain-B-tx%d".formatted(h);
            assertTrue(snap.transaction(newTx).isPresent(), "新链交易 " + newTx + " 不得遗漏");
            assertEquals(ChainGenerator.forkHash("chain-B", h),
                    snap.receipt(newTx).orElseThrow().blockHash());
        }
        // 地址索引：addrB 同时在旧链(0..3)与新链(2..5)出现，回滚后只计新链 + 祖先
        long bCountFromA = snap.transactionsOf("addrB").stream()
                .filter(t -> t.startsWith("chain-A-tx")).count();
        // 祖先 h=0,1 两笔仍在；旧链 h=2,3 两笔必须消失
        assertEquals(2, bCountFromA, "旧链分叉段地址索引必须回滚，仅共同祖先保留");
        assertEquals(4, snap.transactionsOf("addrC").size(), "新链 4 笔收款必须全部入索引");
    }

    @Test
    void equalLengthForkDoesNotReorg() {
        IndexerService svc = seedChainA(4); // tip 3
        // 分叉从 h=1 后开始，长度 2：h=2 同高度冲突，h=3 时侧链与规范等长 -> 不重组
        List<Block> fork = forkB(1, 2);
        IngestResult r0 = svc.ingest(fork.get(0));
        assertEquals(RejectReason.HEIGHT_CONFLICT, r0.reason());
        IngestResult r1 = svc.ingest(fork.get(1));
        log.info("[case] 等长分叉判定: reason={} reorg={} detail={}",
                r1.reason(), r1.reorg(), r1.detail());
        assertFalse(r1.reorg());
        assertEquals(RejectReason.HEIGHT_CONFLICT, r1.reason());
        assertEquals(3, svc.currentSnapshot().tipHeight());
    }
}
