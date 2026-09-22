package com.github.lisama569.chainindexerjava.tests;

import com.github.lisama569.chainindexerjava.config.IndexerProperties;
import com.github.lisama569.chainindexerjava.index.IndexStore;
import com.github.lisama569.chainindexerjava.ingest.BlockIngestor;
import com.github.lisama569.chainindexerjava.ingest.ForkRegistry;
import com.github.lisama569.chainindexerjava.ingest.IngestionOutcome;
import com.github.lisama569.chainindexerjava.metrics.IndexerMetrics;
import com.github.lisama569.chainindexerjava.model.Block;
import com.github.lisama569.chainindexerjava.simulator.LocalChainGenerator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 重组领先阈值：margin=2 时，仅领先 1 个高度的竞争链仍被当作抖动暂存，
 * 必须领先满 2 个高度才确认重组。用于表达“短暂分叉抖动不得被误判为已确认重组”。
 */
class ReorgMarginTest {

    private static final Logger log = LoggerFactory.getLogger(ReorgMarginTest.class);

    @Test
    void marginTwo_requiresTwoBlockLeadBeforeReorg() {
        IndexStore store = new IndexStore();
        IndexerMetrics metrics = new IndexerMetrics();
        IndexerProperties props = new IndexerProperties();
        props.setReorgAheadMargin(2);
        BlockIngestor ingestor = new BlockIngestor(store, new ForkRegistry(), metrics, props);
        LocalChainGenerator generator = new LocalChainGenerator();

        List<Block> chainA = generator.generate(55L, 6); // 高度 0..5
        for (Block b : chainA) {
            ingestor.ingestCanonical(b);
        }

        // B 从 A3 分叉：B4,B5,B6 -> 末端 6 仅领先 1，margin=2 时仍是抖动
        List<Block> forkB = generator.generateFork(chainA.get(3), 88L, 3); // 高度 4..6
        for (Block b : forkB) {
            IngestionOutcome o = ingestor.ingestForkCandidate(b);
            log.info("margin=2 候选 tip={} canonicalTip={} -> {}",
                    b.height(), store.current().tipHeight(), o.status());
            if (b.height() <= 6) {
                assertEquals(IngestionOutcome.Status.FORK_PARKED, o.status(),
                        "领先不足 2 个高度必须被当作抖动暂存");
            }
        }
        assertEquals(5L, store.current().tipHeight(), "抖动不得移动规范链");

        // 再补 B7：末端 7 领先 2，满足阈值 -> 重组
        List<Block> forkB2 = generator.generateFork(forkB.get(forkB.size() - 1), 89L, 1);
        Block b7 = forkB2.get(0);
        IngestionOutcome reorg = ingestor.ingestForkCandidate(b7);
        log.info("margin=2 候选 tip=7 -> {}, rollbackTo={}", reorg.status(),
                reorg.rollbackToHeight());
        assertEquals(IngestionOutcome.Status.REORG_APPLIED, reorg.status());
        assertEquals(3L, reorg.rollbackToHeight());
        assertEquals(7L, store.current().tipHeight());
    }
}
