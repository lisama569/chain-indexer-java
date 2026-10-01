package com.github.lisama569.chainindexerjava.tests;

import com.github.lisama569.chainindexerjava.domain.Block;
import com.github.lisama569.chainindexerjava.indexer.ChainSnapshot;
import com.github.lisama569.chainindexerjava.indexer.IndexerService;
import com.github.lisama569.chainindexerjava.ingest.IngestResult;
import com.github.lisama569.chainindexerjava.metrics.IndexerMetrics;
import com.github.lisama569.chainindexerjava.storage.ChainStore;
import com.github.lisama569.chainindexerjava.support.ChainGenerator;
import com.github.lisama569.chainindexerjava.support.SnapshotAssertions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 两条链反复互相超越的长期场景：
 * - 链 A / 链 B 交替延伸、互相追上又超过，连续发生多次接管；
 * - 任何时刻对外只暴露唯一一条规范链，索引不混链、不重复计数、无悬空地址引用；
 * - 连续切换过程中并发查询照常可用，每个快照都对应确定高度与链标识；
 * - 接管次数、最近回滚起点、重放块数可从指标接口读出。
 */
class AlternatingChainsTest {

    private static final Logger log = LoggerFactory.getLogger(AlternatingChainsTest.class);
    private final ChainGenerator gen = new ChainGenerator();

    /** 生成并逐块投递一段延伸块，返回最后一次摄入结果。 */
    private IngestResult extendOneByOne(IndexerService svc, String chainId,
                                        int fromHeight, int count, String parentHash) {
        List<Block> blocks = gen.chain(chainId, fromHeight, count, parentHash);
        IngestResult last = null;
        for (Block b : blocks) {
            last = svc.ingest(b);
            log.info("[case] 逐块延伸 {} -> accepted={} reorg={} reason={} tip={}",
                    b.summary(), last.accepted(), last.reorg(), last.reason(), last.tipHeight());
        }
        return last;
    }

    @Test
    void repeatedTakeoversKeepSingleCanonicalChain() {
        IndexerMetrics metrics = new IndexerMetrics();
        IndexerService svc = new IndexerService(new ChainStore(), metrics);
        gen.genesisChain("chain-A", 4).forEach(svc::ingest); // A: h0..h3

        // 第 1 次接管：B 从 h1 后分叉，逐块延伸到 h5
        IngestResult r1 = extendOneByOne(svc, "chain-B", 2, 4, ChainGenerator.hash("chain-A", 1));
        assertEquals("chain-B", svc.currentSnapshot().chainId());
        assertEquals(5, svc.currentSnapshot().tipHeight());

        // 第 2 次接管：A 从自己旧链尖 h3 继续，延伸到 h6 反超
        IngestResult r2 = extendOneByOne(svc, "chain-A", 4, 3, ChainGenerator.hash("chain-A", 3));
        assertTrue(r2.reorg(), "A 反超必须触发第 2 次接管");
        assertEquals("chain-A", svc.currentSnapshot().chainId());
        assertEquals(6, svc.currentSnapshot().tipHeight());

        // 第 3 次接管：B 从自己旧链尖 h5 继续，延伸到 h7 再次反超
        IngestResult r3 = extendOneByOne(svc, "chain-B", 6, 2, ChainGenerator.hash("chain-B", 5));
        assertTrue(r3.reorg(), "B 再次反超必须触发第 3 次接管");
        assertEquals("chain-B", svc.currentSnapshot().chainId());
        assertEquals(7, svc.currentSnapshot().tipHeight());

        ChainSnapshot snap = svc.currentSnapshot();
        SnapshotAssertions.assertInternallyConsistent(snap);

        // 唯一规范链：共同祖先 h0,h1 来自 A，h2..h7 全部来自 B，绝无两链混杂
        assertEquals(ChainGenerator.hash("chain-A", 0), snap.blocks().get(0L).hash());
        assertEquals(ChainGenerator.hash("chain-A", 1), snap.blocks().get(1L).hash());
        for (long h = 2; h <= 7; h++) {
            assertEquals(ChainGenerator.hash("chain-B", h), snap.blocks().get(h).hash(),
                    "高度 " + h + " 必须属于当前规范链 B");
        }
        // 被替换段（A 的 h2..h6）无任何残留：交易、回执、地址引用全部消失
        for (long h = 2; h <= 6; h++) {
            String staleTx = "chain-A-tx%d".formatted(h);
            assertTrue(snap.transaction(staleTx).isEmpty(), "旧链交易 " + staleTx + " 必须消失");
            assertTrue(snap.receipt(staleTx).isEmpty(), "旧链回执 " + staleTx + " 必须消失");
        }
        // 地址索引不得指向已不存在的交易，且无重复计数
        List<String> addrBTxs = snap.transactionsOf("addrB");
        assertEquals(addrBTxs.stream().distinct().count(), addrBTxs.size(), "地址索引不得重复计数");
        assertTrue(addrBTxs.stream().noneMatch(t -> t.matches("chain-A-tx[2-9]")),
                "地址索引不得引用已被回滚的交易");
        // 当前链上交易 = 祖先 2 笔 + B 链 6 笔
        assertEquals(8, snap.txByHash().size());

        // 接管统计对外可见：3 次切换，最近一次从 h1 回滚，重放 6 块（B 的 h2..h7）
        var m = metrics.snapshot();
        assertEquals(3L, m.get("reorgs"), "接管次数必须准确累计");
        assertEquals(1L, m.get("lastReorgForkPoint"), "最近一次接管回滚起点 = 共同祖先 h1");
        assertEquals(6L, m.get("lastReorgReplayedBlocks"), "最近一次接管重放块数");
        assertEquals(ChainGenerator.hash("chain-B", 7), m.get("lastReorgTipHash"));
        assertEquals("chain-B", m.get("lastReorgChainId"));
    }

    @Test
    void concurrentQueriesDuringRepeatedTakeoversStayConsistent() throws Exception {
        IndexerMetrics metrics = new IndexerMetrics();
        IndexerService svc = new IndexerService(new ChainStore(), metrics);
        gen.genesisChain("chain-A", 4).forEach(svc::ingest); // A: h0..h3

        // 交替延伸计划：B、A、B、A、B、A 共 6 次反超
        record Round(String chainId, int fromHeight, int count, String parentHash) {}
        List<Round> rounds = List.of(
                new Round("chain-B", 2, 4, ChainGenerator.hash("chain-A", 1)),
                new Round("chain-A", 4, 3, ChainGenerator.hash("chain-A", 3)),
                new Round("chain-B", 6, 3, ChainGenerator.hash("chain-B", 5)),
                new Round("chain-A", 7, 3, ChainGenerator.hash("chain-A", 6)),
                new Round("chain-B", 9, 3, ChainGenerator.hash("chain-B", 8)),
                new Round("chain-A", 10, 3, ChainGenerator.hash("chain-A", 9)));

        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger observedSnapshots = new AtomicInteger();

        // 读者线程：切换全程持续查询，每个快照都必须内部自洽、对应确定高度与链标识
        List<Thread> readers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    while (!stop.get()) {
                        ChainSnapshot snap = svc.currentSnapshot();
                        SnapshotAssertions.assertInternallyConsistent(snap);
                        observedSnapshots.incrementAndGet();
                    }
                } catch (Throwable e) {
                    failures.incrementAndGet();
                    log.error("读者发现脏读/不一致", e);
                }
            }, "alternating-reader-" + i);
            t.start();
            readers.add(t);
        }

        // 写者线程：逐块投递，驱动 6 次接管
        Thread writer = new Thread(() -> {
            try {
                start.await();
                for (Round r : rounds) {
                    extendOneByOne(svc, r.chainId(), r.fromHeight(), r.count(), r.parentHash());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "alternating-writer");
        writer.start();

        start.countDown();
        writer.join(30_000);
        stop.set(true);
        for (Thread t : readers) {
            t.join(5_000);
        }

        assertEquals(0, failures.get(), "连续切换过程中任何查询都不得读到重放一半的中间状态");
        assertTrue(observedSnapshots.get() > 0, "读者必须实际观测过快照");

        ChainSnapshot finalSnap = svc.currentSnapshot();
        SnapshotAssertions.assertInternallyConsistent(finalSnap);
        // 最后一轮 A 从 h9 反超：A h10..h12 为链尖段
        assertEquals("chain-A", finalSnap.chainId());
        assertEquals(12, finalSnap.tipHeight());
        assertEquals(ChainGenerator.hash("chain-A", 12), finalSnap.tipHash());
        assertEquals(6L, metrics.snapshot().get("reorgs"), "必须恰好发生 6 次接管");
        assertEquals(1L, metrics.snapshot().get("lastReorgForkPoint"),
                "A/B 始终分叉自 h1，最近一次接管回滚到共同祖先 h1");
        assertEquals(11L, metrics.snapshot().get("lastReorgReplayedBlocks"),
                "最近一次接管重放 A 的 h2..h12 共 11 块");
        log.info("[case] 并发切换结束: tip={}/{} reorgs={} 读者观测快照数={}",
                finalSnap.tipHeight(), finalSnap.chainId(),
                metrics.snapshot().get("reorgs"), observedSnapshots.get());
    }
}
