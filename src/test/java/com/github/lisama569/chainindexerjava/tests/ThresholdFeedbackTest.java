package com.github.lisama569.chainindexerjava.tests;

import com.github.lisama569.chainindexerjava.domain.Block;
import com.github.lisama569.chainindexerjava.indexer.ChainSnapshot;
import com.github.lisama569.chainindexerjava.indexer.IndexerService;
import com.github.lisama569.chainindexerjava.indexer.ThresholdUpdateResult;
import com.github.lisama569.chainindexerjava.metrics.IndexerMetrics;
import com.github.lisama569.chainindexerjava.storage.ChainStore;
import com.github.lisama569.chainindexerjava.support.ChainGenerator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 换链门槛调整后的结果反馈必须与实际一致：
 * - 真的完成接管才允许 tookOver=true，且指标/快照同步变化；
 * - 接管中途失败（如重放时交易哈希冲突）时 tookOver=false，
 *   索引数据保持完全不变，并给出可读原因，绝不假报成功；
 * - 没有候选 / 领先不足时 tookOver=false 且说明原因。
 */
class ThresholdFeedbackTest {

    private static final Logger log = LoggerFactory.getLogger(ThresholdFeedbackTest.class);
    private final ChainGenerator gen = new ChainGenerator();

    private IndexerService seedChainA(IndexerService svc, int n) {
        gen.genesisChain("chain-A", n).forEach(svc::ingest);
        return svc;
    }

    @Test
    void takeoverSucceedsFeedbackTrueAndStateSwitches() {
        IndexerMetrics metrics = new IndexerMetrics();
        IndexerService svc = seedChainA(
                new IndexerService(new ChainStore(), metrics, 2), 4); // tip=3，门槛=2
        // 竞争链 h=2..4：领先 1 块，被门槛=2 按住
        gen.forkChain("chain-B", 1, 3, ChainGenerator.hash("chain-A", 1)).forEach(svc::ingest);
        assertEquals(3, svc.currentSnapshot().tipHeight());
        assertEquals(0L, metrics.snapshot().get("reorgs"));

        ChainSnapshot before = svc.currentSnapshot();
        ThresholdUpdateResult r = svc.setReorgMinLead(1);
        log.info("[case] 门槛 2->1 接管成功 -> tookOver={} detail={}", r.tookOver(), r.detail());

        assertTrue(r.tookOver(), "接管真实发生时必须反馈 true");
        assertEquals(1, r.effectiveValue());
        assertNotNull(r.detail());
        assertFalse(r.detail().isBlank(), "反馈必须带可读说明");
        // 反馈与后续查询/指标一致：链尖、链标识、换链统计都已切换
        ChainSnapshot after = svc.currentSnapshot();
        assertNotSame(before, after);
        assertEquals(4, after.tipHeight());
        assertEquals(ChainGenerator.forkHash("chain-B", 4), after.tipHash());
        assertEquals("chain-B", after.chainId());
        assertEquals(1L, metrics.snapshot().get("reorgs"));
        assertEquals(1L, metrics.snapshot().get("lastReorgForkPoint"));
        assertEquals(3L, metrics.snapshot().get("lastReorgReplayedBlocks"));
    }

    @Test
    void failedTakeoverFeedbackFalseAndStateUntouched() {
        IndexerMetrics metrics = new IndexerMetrics();
        IndexerService svc = seedChainA(
                new IndexerService(new ChainStore(), metrics, 2), 3); // tip=2，门槛=2

        // 竞争链 h=1..3，但 h=1 携带与规范链 h=0 重复的交易哈希：
        // 收存候选时不做交易校验，接管重放时才会冲突失败
        List<Block> fork = List.of(
                gen.block(1, ChainGenerator.forkHash("chain-B", 1),
                        ChainGenerator.hash("chain-A", 0), "chain-B",
                        List.of(ChainGenerator.tx("chain-A-tx0", "addrX", "addrY", 1))),
                gen.block(2, ChainGenerator.forkHash("chain-B", 2),
                        ChainGenerator.forkHash("chain-B", 1), "chain-B",
                        List.of(ChainGenerator.tx("chain-B-tx2", "addrX", "addrY", 2))),
                gen.block(3, ChainGenerator.forkHash("chain-B", 3),
                        ChainGenerator.forkHash("chain-B", 2), "chain-B",
                        List.of(ChainGenerator.tx("chain-B-tx3", "addrX", "addrY", 3))));
        fork.forEach(svc::ingest); // 领先 1 块，被门槛=2 按住
        assertEquals(2, svc.currentSnapshot().tipHeight());

        ChainSnapshot before = svc.currentSnapshot();
        ThresholdUpdateResult r = svc.setReorgMinLead(1); // 门槛降到 1，触发接管尝试
        log.info("[case] 门槛 2->1 接管失败 -> tookOver={} detail={}", r.tookOver(), r.detail());

        assertFalse(r.tookOver(), "接管未真正完成时绝不允许反馈 true");
        assertEquals(1, r.effectiveValue(), "门槛本身仍然生效");
        assertNotNull(r.detail());
        assertFalse(r.detail().isBlank(), "失败必须给出可读原因");
        assertTrue(r.detail().contains("chain-A-tx0"),
                "说明应指出冲突的交易哈希，实际: " + r.detail());

        // 索引数据必须保持完全不变：快照引用都没换
        assertSame(before, svc.currentSnapshot(), "接管失败时索引数据必须完全不变");
        assertEquals(2, svc.currentSnapshot().tipHeight());
        assertEquals("chain-A", svc.currentSnapshot().chainId());
        assertEquals(0L, metrics.snapshot().get("reorgs"), "失败的接管不得计入换链统计");
        assertTrue(svc.currentSnapshot().transaction("chain-B-tx2").isEmpty(),
                "未完成的接管不得把竞争链数据写进索引");
    }

    @Test
    void noCandidateOrInsufficientLeadFeedbackFalseWithReason() {
        IndexerMetrics metrics = new IndexerMetrics();
        IndexerService svc = seedChainA(
                new IndexerService(new ChainStore(), metrics, 3), 3); // tip=2，门槛=3

        // 情形 1：区块池里没有任何竞争链候选
        ThresholdUpdateResult noCandidate = svc.setReorgMinLead(2);
        log.info("[case] 无候选 -> tookOver={} detail={}", noCandidate.tookOver(), noCandidate.detail());
        assertFalse(noCandidate.tookOver());
        assertEquals(2, noCandidate.effectiveValue());
        assertFalse(noCandidate.detail().isBlank());

        // 情形 2：有候选但领先不足新门槛（forkTip=3 < tip2+门槛3=5）
        gen.forkChain("chain-B", 0, 3, ChainGenerator.hash("chain-A", 0)).forEach(svc::ingest);
        ChainSnapshot before = svc.currentSnapshot();
        ThresholdUpdateResult held = svc.setReorgMinLead(3);
        log.info("[case] 领先不足 -> tookOver={} detail={}", held.tookOver(), held.detail());
        assertFalse(held.tookOver(), "领先不足门槛不得接管");
        assertFalse(held.detail().isBlank());
        assertSame(before, svc.currentSnapshot(), "未接管时索引数据必须完全不变");
        assertEquals(0L, metrics.snapshot().get("reorgs"));
    }
}
