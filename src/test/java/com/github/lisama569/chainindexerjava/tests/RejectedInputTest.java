package com.github.lisama569.chainindexerjava.tests;

import com.github.lisama569.chainindexerjava.config.IndexerProperties;
import com.github.lisama569.chainindexerjava.index.IndexStore;
import com.github.lisama569.chainindexerjava.ingest.BlockIngestor;
import com.github.lisama569.chainindexerjava.ingest.ForkRegistry;
import com.github.lisama569.chainindexerjava.ingest.IngestionOutcome;
import com.github.lisama569.chainindexerjava.ingest.RejectionReason;
import com.github.lisama569.chainindexerjava.metrics.IndexerMetrics;
import com.github.lisama569.chainindexerjava.model.Block;
import com.github.lisama569.chainindexerjava.simulator.LocalChainGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 非法输入必须被拒绝，且拒绝原因彼此可区分；已有数据绝不被覆盖。 */
class RejectedInputTest {

    private static final Logger log = LoggerFactory.getLogger(RejectedInputTest.class);

    private IndexStore store;
    private BlockIngestor ingestor;
    private LocalChainGenerator generator;
    private IndexerMetrics metrics;

    @BeforeEach
    void setUp() {
        store = new IndexStore();
        metrics = new IndexerMetrics();
        ingestor = new BlockIngestor(store, new ForkRegistry(), metrics, new IndexerProperties());
        generator = new LocalChainGenerator();
    }

    @Test
    void allRejectionReasonsAreDistinguishableAndNothingIsOverwritten() {
        List<Block> chain = generator.generate(7L, 4); // 高度 0..3
        for (Block b : chain) {
            assertEquals(IngestionOutcome.Status.ACCEPTED, ingestor.ingestCanonical(b).status());
        }
        log.info("基线链已摄入至高度 3，tipHash={}", store.current().tipHash());

        // 1) 完全重复提交：同高度同哈希
        IngestionOutcome duplicate = ingestor.ingestCanonical(chain.get(2));
        log.info("用例-重复提交 input={} -> status={}, reason={}, basis={}",
                BlockIngestor.summarize(chain.get(2)),
                duplicate.status(), duplicate.rejectionReason(), duplicate.detail());
        assertRejected(duplicate, RejectionReason.DUPLICATE_BLOCK);

        // 2) 同一高度内容冲突：同高度、不同哈希、不同内容
        Block conflict = generator.conflictingBlockAtHeight(
                2L, chain.get(1).hash(), 99L);
        IngestionOutcome conflictOutcome = ingestor.ingestCanonical(conflict);
        log.info("用例-高度冲突 incoming={} existingAtHeight2={} -> status={}, reason={}, basis={}",
                BlockIngestor.summarize(conflict),
                BlockIngestor.summarize(chain.get(2)),
                conflictOutcome.status(), conflictOutcome.rejectionReason(),
                conflictOutcome.detail());
        assertRejected(conflictOutcome, RejectionReason.CONFLICT_AT_HEIGHT);

        // 已有数据必须保持不变（未被静默覆盖）
        assertEquals(chain.get(2).hash(),
                store.blockAtHeight(2).orElseThrow().hash(),
                "冲突输入不得覆盖高度 2 已有区块");

        // 3) 高度跳跃：高度 5（期望 4）
        Block gapped = generator.blockWithHeightGap(chain.get(3), 100L); // 高度 = 3 + 2 = 5
        IngestionOutcome gapOutcome = ingestor.ingestCanonical(gapped);
        log.info("用例-高度跳跃 input={} -> status={}, reason={}, basis={}",
                BlockIngestor.summarize(gapped),
                gapOutcome.status(), gapOutcome.rejectionReason(), gapOutcome.detail());
        assertRejected(gapOutcome, RejectionReason.HEIGHT_GAP);

        // 4) 父哈希不连续：高度正确(4)，但 parentHash 指向别处
        Block wrongParent = generator.blockWithWrongParent(chain.get(3), 101L);
        IngestionOutcome parentOutcome = ingestor.ingestCanonical(wrongParent);
        log.info("用例-父哈希错误 input={} expectedParent={} -> status={}, reason={}, basis={}",
                BlockIngestor.summarize(wrongParent),
                chain.get(3).hash().substring(0, 12),
                parentOutcome.status(), parentOutcome.rejectionReason(), parentOutcome.detail());
        assertRejected(parentOutcome, RejectionReason.PARENT_HASH_MISMATCH);

        // 5) 在独立空链上：高度为 0 但 parentHash 非零 -> 父哈希错误
        IndexStore emptyStore = new IndexStore();
        BlockIngestor emptyIngestor = new BlockIngestor(
                emptyStore, new ForkRegistry(), new IndexerMetrics(), new IndexerProperties());
        Block fakeGenesis = generator.blockWithWrongParent(
                new Block(-1, null, null, List.of()), 102L); // 高度 0，父哈希非零
        IngestionOutcome genesisOutcome = emptyIngestor.ingestCanonical(fakeGenesis);
        log.info("用例-错误创世块(空链) input={} -> status={}, reason={}, basis={}",
                BlockIngestor.summarize(fakeGenesis),
                genesisOutcome.status(), genesisOutcome.rejectionReason(), genesisOutcome.detail());
        assertRejected(genesisOutcome, RejectionReason.PARENT_HASH_MISMATCH);
        assertEquals(-1L, emptyStore.current().tipHeight());

        // 结论稳定：主链末端仍为高度 3，主链拒绝计数=4
        assertEquals(3L, store.current().tipHeight());
        assertEquals(4L, metrics.snapshot().get("ingestRejectedBlocks"));
        log.info("拒绝用例全部可区分，末端未移动：tipHeight={}", store.current().tipHeight());
    }

    private void assertRejected(IngestionOutcome o, RejectionReason reason) {
        assertEquals(IngestionOutcome.Status.REJECTED, o.status());
        assertEquals(reason, o.rejectionReason());
        assertNotNull(o.detail());
    }
}
