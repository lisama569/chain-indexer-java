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
 * 正常顺序摄入：创世块、逐块延伸、地址索引、回执索引与重复提交幂等拒绝。
 */
class NormalIngestTest {

    private static final Logger log = LoggerFactory.getLogger(NormalIngestTest.class);
    private final ChainGenerator gen = new ChainGenerator();

    private IndexerService newService() {
        return new IndexerService(new ChainStore(), new IndexerMetrics());
    }

    @Test
    void ingestGenesisThenSequentialBlocks() {
        IndexerService svc = newService();
        List<Block> chain = gen.genesisChain("chain-A", 4);
        for (Block b : chain) {
            log.info("[case] 顺序摄入输入摘要: {}", b.summary());
            IngestResult r = svc.ingest(b);
            log.info("[case] 判定依据: accepted={} reason={} detail={} tip={}/{}",
                    r.accepted(), r.reason(), r.detail(), r.tipHeight(), r.tipHash());
            assertTrue(r.accepted(), "顺序区块应被接受: " + r.detail());
        }
        ChainSnapshot snap = svc.currentSnapshot();
        assertEquals(3, snap.tipHeight());
        assertEquals(ChainGenerator.hash("chain-A", 3), snap.tipHash());
        assertEquals("chain-A", snap.chainId());
        assertEquals(4, snap.blocks().size());

        // 地址 -> 交易 索引
        List<String> aTxs = snap.transactionsOf("addrA");
        List<String> bTxs = snap.transactionsOf("addrB");
        assertEquals(4, aTxs.size(), "addrA 应出现 4 笔");
        assertEquals(4, bTxs.size(), "addrB 应出现 4 笔");
        // 顺序确定（按高度）
        assertEquals("chain-A-tx0", aTxs.get(0));
        assertEquals("chain-A-tx3", aTxs.get(3));

        // 交易哈希 -> 回执 索引
        var receipt = snap.receipt("chain-A-tx2").orElseThrow();
        assertEquals(2, receipt.blockHeight());
        assertEquals(ChainGenerator.hash("chain-A", 2), receipt.blockHash());
        assertTrue(receipt.success());
        var tx = snap.transaction("chain-A-tx2").orElseThrow();
        assertEquals(2, tx.blockHeight(), "交易高度必须以区块为准");
    }

    @Test
    void duplicateBlockIsRejectedButDoesNotChangeData() {
        IndexerService svc = newService();
        List<Block> chain = gen.genesisChain("chain-A", 3);
        chain.forEach(svc::ingest);

        Block duplicate = chain.get(1);
        log.info("[case] 重复提交输入摘要: {}", duplicate.summary());
        IngestResult r = svc.ingest(duplicate);
        log.info("[case] 判定依据: accepted={} reason={} detail={}",
                r.accepted(), r.reason(), r.detail());
        assertFalse(r.accepted());
        assertEquals(RejectReason.DUPLICATE_BLOCK, r.reason());

        // 数据不变、不重复计数
        ChainSnapshot snap = svc.currentSnapshot();
        assertEquals(2, snap.tipHeight());
        assertEquals(1, snap.transactionsOf("addrA").stream()
                .filter("chain-A-tx1"::equals).count(),
                "重复块中的交易不得被重复计数");
    }
}
