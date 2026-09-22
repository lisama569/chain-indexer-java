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
import com.github.lisama569.chainindexerjava.model.TransactionReceipt;
import com.github.lisama569.chainindexerjava.model.TxRef;
import com.github.lisama569.chainindexerjava.query.QueryResult;
import com.github.lisama569.chainindexerjava.query.QueryService;
import com.github.lisama569.chainindexerjava.simulator.LocalChainGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** 正常顺序摄入：高度递增、父哈希相连时建立全部索引，查询携带确定版本与链标识。 */
class NormalIngestionTest {

    private static final Logger log = LoggerFactory.getLogger(NormalIngestionTest.class);

    private IndexStore store;
    private BlockIngestor ingestor;
    private QueryService queryService;
    private LocalChainGenerator generator;
    private IndexerMetrics metrics;

    @BeforeEach
    void setUp() {
        store = new IndexStore();
        metrics = new IndexerMetrics();
        ingestor = new BlockIngestor(store, new ForkRegistry(), metrics, new IndexerProperties());
        queryService = new QueryService(store, metrics);
        generator = new LocalChainGenerator();
    }

    @Test
    void ingestOrderedChain_buildsIndexesAndConsistentSnapshots() {
        List<Block> chain = generator.generate(42L, 6);
        log.info("== 正常摄入测试：生成 {} 个区块 ==", chain.size());

        String previousChainId = ChainState.EMPTY_CHAIN_ID;
        long expectedTxTotal = 0;
        for (Block b : chain) {
            log.info("提交 {}", BlockIngestor.summarize(b));
            IngestionOutcome outcome = ingestor.ingestCanonical(b);
            expectedTxTotal += b.transactions().size();

            assertAll(
                    () -> assertEquals(IngestionOutcome.Status.ACCEPTED, outcome.status()),
                    () -> assertEquals(b.height(), outcome.tipHeight()),
                    () -> assertNotNull(outcome.chainId()));
            assertNotEquals(previousChainId, outcome.chainId(), "每追加一个区块，链标识应改变");
            previousChainId = outcome.chainId();
        }

        ChainState tip = store.current();
        assertEquals(5L, tip.tipHeight());
        assertEquals(chain.get(5).hash(), tip.tipHash());
        assertEquals(expectedTxTotal, tip.receipts().size(), "全部交易都应有回执");

        // 交易哈希 -> 回执
        Transaction sampleTx = chain.get(2).transactions().get(0);
        QueryResult<Optional<TransactionReceipt>> receipt = queryService.receipt(sampleTx.txHash());
        log.info("回执查询 txHash={} -> version={}, tipHeight={}, chainId={}, receipt={}",
                sampleTx.txHash(), receipt.version(), receipt.tipHeight(),
                receipt.chainId(), receipt.data());
        assertTrue(receipt.data().isPresent());
        assertEquals(2L, receipt.data().get().height());
        assertEquals(chain.get(2).hash(), receipt.data().get().blockHash());
        assertEquals(tip.chainId(), receipt.chainId());
        assertEquals(tip.version(), receipt.version());

        // 地址 -> 交易引用：挑一个实际出现过的地址，引用必须高度升序、计数与快照一致
        String someAddress = sampleTx.from();
        QueryResult<List<TxRef>> refs = queryService.txsByAddress(someAddress);
        log.info("地址查询 address={} version={} tipHeight={} 命中 {} 条",
                someAddress, refs.version(), refs.tipHeight(), refs.data().size());
        long expectedCount = chain.stream()
                .flatMap(b -> b.transactions().stream())
                .filter(tx -> tx.involvedAddresses().contains(someAddress))
                .count();
        assertEquals(expectedCount, refs.data().size());
        for (int i = 1; i < refs.data().size(); i++) {
            assertTrue(refs.data().get(i - 1).height() <= refs.data().get(i).height(),
                    "地址索引必须按高度升序");
        }

        // 高度/哈希两种区块检索
        assertEquals(chain.get(0).hash(),
                queryService.blockAtHeight(0).data().orElseThrow().hash());
        assertEquals(3L,
                queryService.blockByHash(chain.get(3).hash()).data().orElseThrow().height());

        var m = metrics.snapshot();
        log.info("最终指标 {}", m);
        assertEquals(6L, m.get("ingestAcceptedBlocks"));
        assertEquals(expectedTxTotal, m.get("ingestAcceptedTransactions"));
        assertEquals(0L, m.get("ingestRejectedBlocks"));
        assertTrue(((Number) m.get("queryCount")).longValue() >= 4);
        assertTrue(((Number) m.get("ingestThroughputBlocksPerSec")).doubleValue() > 0);
    }
}
