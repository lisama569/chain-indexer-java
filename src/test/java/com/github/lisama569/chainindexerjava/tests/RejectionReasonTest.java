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
 * 各类非法/异常输入必须被拒绝，且原因彼此可区分，已有数据不被静默覆盖。
 */
class RejectionReasonTest {

    private static final Logger log = LoggerFactory.getLogger(RejectionReasonTest.class);
    private final ChainGenerator gen = new ChainGenerator();

    private IndexerService seed(int n) {
        IndexerService svc = new IndexerService(new ChainStore(), new IndexerMetrics());
        gen.genesisChain("chain-A", n).forEach(svc::ingest);
        return svc;
    }

    private void assertReject(IndexerService svc, Block b, RejectReason expected) {
        log.info("[case] 输入摘要: {}", b.summary());
        IngestResult r = svc.ingest(b);
        log.info("[case] 判定依据: accepted={} reason={} detail={}",
                r.accepted(), r.reason(), r.detail());
        assertFalse(r.accepted(), "应当被拒绝");
        assertEquals(expected, r.reason(),
                "拒绝原因应为 %s，实际 %s（%s）".formatted(expected, r.reason(), r.detail()));
    }

    @Test
    void heightGapIsRejected() {
        IndexerService svc = seed(3); // tip = 2
        Block jump = gen.block(5, ChainGenerator.hash("chain-A", 5),
                ChainGenerator.hash("chain-A", 4), "chain-A", List.of());
        assertReject(svc, jump, RejectReason.HEIGHT_GAP);
        assertEquals(2, svc.currentSnapshot().tipHeight(), "跳跃输入不得推进链尖");
    }

    @Test
    void parentHashMismatchIsRejected() {
        IndexerService svc = seed(3); // tip = 2, tipHash chain-A#h2
        Block wrong = gen.block(3, "chain-A#h3-bad", "someone-else#h2",
                "chain-A", List.of());
        assertReject(svc, wrong, RejectReason.PARENT_HASH_MISMATCH);
        assertEquals(2, svc.currentSnapshot().tipHeight());
    }

    @Test
    void sameHeightConflictIsRejectedAndNotOverwritten() {
        IndexerService svc = seed(3); // h=2 已存在 chain-A#h2
        Block conflicting = gen.block(2, "chain-A#h2'", "chain-A#h1",
                "chain-A", List.of(ChainGenerator.tx("evil-tx", "x", "y", 1)));
        assertReject(svc, conflicting, RejectReason.HEIGHT_CONFLICT);
        ChainSnapshot snap = svc.currentSnapshot();
        assertEquals(ChainGenerator.hash("chain-A", 2), snap.blocks().get(2L).hash(),
                "同高度冲突不得覆盖既有区块");
        assertTrue(snap.transaction("evil-tx").isEmpty(), "冲突块的交易不得进入索引");
        assertEquals(2, snap.tipHeight());
    }

    @Test
    void invalidBlocksAreRejected() {
        IndexerService svc = seed(1);
        assertReject(svc, gen.block(-1, "h", "0x0", "chain-A", List.of()),
                RejectReason.INVALID_BLOCK);
        assertReject(svc, gen.block(5, "", "p", "chain-A", List.of()),
                RejectReason.INVALID_BLOCK);
        assertReject(svc, gen.block(5, "h", "p", " ", List.of()),
                RejectReason.INVALID_BLOCK);
        // 区块内交易哈希重复
        Block dupTx = gen.block(5, "h5", "p", "chain-A", List.of(
                ChainGenerator.tx("dup", "a", "b", 1),
                ChainGenerator.tx("dup", "c", "d", 2)));
        assertReject(svc, dupTx, RejectReason.INVALID_BLOCK);
    }

    @Test
    void rejectingNonGenesisWithGenesisParent() {
        IndexerService svc = seed(1);
        assertReject(svc, gen.block(3, "h3", "0x0", "chain-A", List.of()),
                RejectReason.INVALID_BLOCK);
    }
}
