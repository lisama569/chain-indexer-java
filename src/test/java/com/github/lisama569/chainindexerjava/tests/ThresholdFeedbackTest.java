package com.github.lisama569.chainindexerjava.tests;

import com.github.lisama569.chainindexerjava.domain.Block;
import com.github.lisama569.chainindexerjava.domain.Transaction;
import com.github.lisama569.chainindexerjava.indexer.ChainSnapshot;
import com.github.lisama569.chainindexerjava.indexer.IndexerService;
import com.github.lisama569.chainindexerjava.ingest.IngestResult;
import com.github.lisama569.chainindexerjava.ingest.RejectReason;
import com.github.lisama569.chainindexerjava.ingest.ThresholdUpdateResult;
import com.github.lisama569.chainindexerjava.metrics.IndexerMetrics;
import com.github.lisama569.chainindexerjava.storage.ChainStore;
import com.github.lisama569.chainindexerjava.support.ChainGenerator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 业务：换链门槛调整结果反馈必须与实际一致。
 * <ul>
 *   <li>接管真正完成：tookOver=true，快照/指标切到新链；</li>
 *   <li>达标候选重放被拒（交易哈希冲突）：tookOver=false，索引完全不变、指标不记换链，
 *       detail 给出可读说明；</li>
 *   <li>没有达标候选：tookOver=false，detail 可读；</li>
 *   <li>同上场景在摄入路径发生时，也不得谎报 reorg。</li>
 * </ul>
 */
class ThresholdFeedbackTest {

    private static final Logger log = LoggerFactory.getLogger(ThresholdFeedbackTest.class);
    private final ChainGenerator gen = new ChainGenerator();

    private Block block(long h, String hash, String parent, String chain, String txHash) {
        return gen.block(h, hash, parent, chain,
                List.of(ChainGenerator.tx(txHash, "addrA", "addrB", 100 + h)));
    }

    /** 复刻线上故障：门槛按住期间收存的竞争链与共同祖先有重复交易，调低门槛时重放失败。 */
    @Test
    void failedTakeoverOnThresholdChangeIsReportedHonestlyAndLeavesIndexUntouched() {
        IndexerMetrics metrics = new IndexerMetrics();
        IndexerService svc = new IndexerService(new ChainStore(), metrics, 3);

        // chain-A h=0..2，h=0 的交易为 dup
        svc.ingest(block(0, "A#h0", "0x0", "chain-A", "dup"));
        svc.ingest(block(1, "A#h1", "A#h0", "chain-A", "a1"));
        svc.ingest(block(2, "A#h2", "A#h1", "chain-A", "a2"));

        // chain-B 从 h=1 分叉，B1 复用共同祖先交易 dup（重放必然冲突）；门槛 3 下被按住
        svc.ingest(block(1, "B#h1p", "A#h0", "chain-B", "dup"));
        svc.ingest(block(2, "B#h2p", "B#h1p", "chain-B", "b2"));
        IngestResult held = svc.ingest(block(3, "B#h3p", "B#h2p", "chain-B", "b3"));
        assertFalse(held.reorg());
        assertEquals(0L, metrics.snapshot().get("reorgs"));

        ChainSnapshot before = svc.currentSnapshot();
        // 调低门槛：候选达到门槛，但接管重放因 dup 冲突而失败
        ThresholdUpdateResult r = svc.setReorgMinLead(1);
        log.info("[业务:门槛反馈] 重放冲突场景 -> tookOver={} effective={} detail={}",
                r.tookOver(), r.effectiveValue(), r.detail());

        assertFalse(r.tookOver(), "接管没做成绝不能报告 tookOver=true");
        assertEquals(1, r.requestedValue());
        assertEquals(1, r.effectiveValue());
        assertNotNull(r.detail());
        assertTrue(r.detail().contains("未能完成") && r.detail().contains("dup"),
                "失败说明必须可读且指出冲突交易，实际: " + r.detail());

        // 索引完全不变：链尖、快照版本、各项索引都与调整前一致
        ChainSnapshot after = svc.currentSnapshot();
        assertSame(before, after, "接管失败时不得发布新快照");
        assertEquals(2, after.tipHeight());
        assertEquals("A#h2", after.tipHash());
        assertEquals("chain-A", after.chainId());
        assertTrue(after.transaction("dup").isPresent());
        assertTrue(after.transaction("a2").isPresent());
        assertTrue(after.transaction("b2").isEmpty(), "竞争链数据绝不能进入索引");
        assertEquals(0L, metrics.snapshot().get("reorgs"), "失败的接管不得计入 reorgs");
        assertEquals(3L, metrics.snapshot().get("acceptedBlocks"));
    }

    @Test
    void successfulTakeoverOnThresholdLoweringIsReportedHonestly() {
        IndexerMetrics metrics = new IndexerMetrics();
        IndexerService svc = new IndexerService(new ChainStore(), metrics, 2);
        gen.genesisChain("chain-A", 4).forEach(svc::ingest); // tip=3
        gen.forkChain("chain-B", 1, 3, ChainGenerator.hash("chain-A", 1))
                .forEach(svc::ingest); // 竞争链 h=2..4，门槛 2 下按住
        assertEquals(3, svc.currentSnapshot().tipHeight());

        ThresholdUpdateResult r = svc.setReorgMinLead(1);
        log.info("[业务:门槛反馈] 正常接管场景 -> tookOver={} detail={}", r.tookOver(), r.detail());
        assertTrue(r.tookOver());
        assertTrue(r.detail().contains("成功接管"));
        assertEquals(4, svc.currentSnapshot().tipHeight());
        assertEquals("chain-B", svc.currentSnapshot().chainId());
        assertEquals(1L, metrics.snapshot().get("reorgs"));
    }

    @Test
    void thresholdChangeWithoutEligibleForkReportsNoTakeoverReadably() {
        IndexerMetrics metrics = new IndexerMetrics();
        IndexerService svc = new IndexerService(new ChainStore(), metrics);
        gen.genesisChain("chain-A", 3).forEach(svc::ingest);

        ThresholdUpdateResult r = svc.setReorgMinLead(2);
        log.info("[业务:门槛反馈] 无候选场景 -> tookOver={} detail={}", r.tookOver(), r.detail());
        assertFalse(r.tookOver());
        assertEquals(2, r.effectiveValue());
        assertTrue(r.detail().contains("没有") && r.detail().contains("未发生换链"));
        assertEquals(2, svc.currentSnapshot().tipHeight(), "无接管时索引不变");
        assertEquals(0L, metrics.snapshot().get("reorgs"));

        // 非法值：回退默认也不谎报接管
        ThresholdUpdateResult invalid = svc.setReorgMinLead(0);
        assertFalse(invalid.tookOver());
        assertEquals(0, invalid.requestedValue());
        assertEquals(1, invalid.effectiveValue());
    }

    /** 竞争链在高门槛下逐块到达，达到门槛的那一刻重放冲突：摄入路径同样不得谎报 reorg。 */
    @Test
    void ingestPathFailingTakeoverIsNotReportedAsReorg() {
        IndexerMetrics metrics = new IndexerMetrics();
        IndexerService svc = new IndexerService(new ChainStore(), metrics, 2);
        svc.ingest(block(0, "A#h0", "0x0", "chain-A", "dup"));
        svc.ingest(block(1, "A#h1", "A#h0", "chain-A", "a1"));
        svc.ingest(block(2, "A#h2", "A#h1", "chain-A", "a2"));

        svc.ingest(block(1, "B#h1p", "A#h0", "chain-B", "dup"));
        svc.ingest(block(2, "B#h2p", "B#h1p", "chain-B", "b2"));
        // h=3,4 到达：竞争链领先 2 块达到门槛，触发重放但 dup 冲突
        svc.ingest(block(3, "B#h3p", "B#h2p", "chain-B", "b3"));
        IngestResult trigger = svc.ingest(block(4, "B#h4p", "B#h3p", "chain-B", "b4"));
        log.info("[业务:摄入接管反馈] 重放冲突 -> accepted={} reorg={} reason={} detail={}",
                trigger.accepted(), trigger.reorg(), trigger.reason(), trigger.detail());

        assertFalse(trigger.accepted());
        assertFalse(trigger.reorg(), "重放失败绝不能报告 reorg=true");
        assertEquals(RejectReason.INVALID_BLOCK, trigger.reason());
        ChainSnapshot snap = svc.currentSnapshot();
        assertEquals(2, snap.tipHeight());
        assertEquals("chain-A", snap.chainId());
        assertEquals(0L, metrics.snapshot().get("reorgs"));
    }
}
