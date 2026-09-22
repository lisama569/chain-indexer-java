package com.github.lisama569.chainindexerjava.tests;

import com.github.lisama569.chainindexerjava.domain.Block;
import com.github.lisama569.chainindexerjava.domain.Transaction;
import com.github.lisama569.chainindexerjava.indexer.ChainSnapshot;
import com.github.lisama569.chainindexerjava.indexer.IndexerService;
import com.github.lisama569.chainindexerjava.ingest.IngestResult;
import com.github.lisama569.chainindexerjava.metrics.IndexerMetrics;
import com.github.lisama569.chainindexerjava.storage.ChainStore;
import com.github.lisama569.chainindexerjava.support.ChainGenerator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 摄入与查询并发：
 * 1. 每次查询拿到的快照必须内部自洽（高度/哈希/链标识/索引互相对应，无重复计数）；
 * 2. 长时间大范围扫描查询不得阻塞摄入（记录查询期间摄入推进的高度）。
 */
class ConcurrencySnapshotTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencySnapshotTest.class);
    private final ChainGenerator gen = new ChainGenerator();

    private Block linearBlock(long h, String parent) {
        return gen.block(h, ChainGenerator.hash("chain-A", h), parent, "chain-A",
                List.of(ChainGenerator.tx("chain-A-tx%d".formatted(h),
                        "addrA", "addrB%d".formatted(h % 5), 100 + h)));
    }

    /** 快照内部一致性：0..tip 的区块哈希父子相连，每个交易都有回执，地址索引不重复。 */
    private void assertSnapshotInternallyConsistent(ChainSnapshot snap) {
        long tip = snap.tipHeight();
        if (tip < 0) {
            return;
        }
        String expectedParent = "0x0";
        for (long hh = 0; hh <= tip; hh++) {
            final long h = hh;
            Block b = snap.blockAt(h).orElseThrow(() ->
                    new AssertionError("快照 %d 缺少高度 %d".formatted(snap.version(), h)));
            assertEquals(h, b.height());
            assertEquals(expectedParent, b.parentHash(),
                    "快照 %d 高度 %d 父哈希断裂".formatted(snap.version(), h));
            assertEquals(snap.chainId(), b.chainId());
            for (Transaction tx : b.transactions()) {
                assertTrue(snap.transaction(tx.txHash()).isPresent());
                assertEquals(h, snap.transaction(tx.txHash()).orElseThrow().blockHeight());
                var receipt = snap.receipt(tx.txHash()).orElseThrow();
                assertEquals(b.hash(), receipt.blockHash());
            }
            expectedParent = b.hash();
        }
        assertEquals(expectedParent, snap.tipHash());
        snap.txHashesByAddr().forEach((addr, hashes) -> {
            long distinct = hashes.stream().distinct().count();
            assertEquals(hashes.size(), distinct,
                    "快照 %d 地址 %s 索引存在重复计数".formatted(snap.version(), addr));
        });
    }

    @Test
    void concurrentIngestAndQueryYieldsConsistentSnapshots() throws Exception {
        IndexerService svc = new IndexerService(new ChainStore(), new IndexerMetrics());
        // 先放创世块
        svc.ingest(linearBlock(0, "0x0"));

        int total = 400;
        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        AtomicLong maxObservedTip = new AtomicLong();

        // 单写者线程：顺序投递 h=1..total
        pool.submit(() -> {
            try {
                start.await();
                String parent = ChainGenerator.hash("chain-A", 0);
                for (long h = 1; h <= total; h++) {
                    Block b = linearBlock(h, parent);
                    IngestResult r = svc.ingest(b);
                    if (!r.accepted()) {
                        failures.incrementAndGet();
                        log.error("顺序摄入意外失败 h={} reason={}", h, r.reason());
                    }
                    parent = b.hash();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // 多个读者线程：不断取当前快照做全量校验
        List<Thread> readers = new ArrayList<>();
        AtomicBoolean stop = new AtomicBoolean(false);
        for (int r = 0; r < 3; r++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    while (!stop.get()) {
                        ChainSnapshot snap = svc.currentSnapshot();
                        assertSnapshotInternallyConsistent(snap);
                        maxObservedTip.updateAndGet(v -> Math.max(v, snap.tipHeight()));
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                    log.error("读者异常", e);
                }
            }, "reader-" + r);
            t.start();
            readers.add(t);
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "摄入线程应在限时内完成");
        stop.set(true);
        for (Thread t : readers) {
            t.join(5000);
        }

        assertEquals(0, failures.get(), "并发过程不应出现摄入失败或快照不一致");
        ChainSnapshot finalSnap = svc.currentSnapshot();
        assertEquals(total, finalSnap.tipHeight());
        assertSnapshotInternallyConsistent(finalSnap);
        assertEquals(total + 1, finalSnap.txByHash().size(), "交易总数无重复无遗漏");
        log.info("[case] 并发结束 finalTip={} 读者观测最大高度={}",
                finalSnap.tipHeight(), maxObservedTip.get());
    }

    @Test
    void longRangeQueryDoesNotBlockIngestion() throws Exception {
        IndexerService svc = new IndexerService(new ChainStore(), new IndexerMetrics());
        svc.ingest(linearBlock(0, "0x0"));
        for (long h = 1; h <= 50; h++) {
            svc.ingest(linearBlock(h, ChainGenerator.hash("chain-A", h - 1)));
        }

        AtomicLong tipAfterSlowQueryStarted = new AtomicLong(-1);
        CountDownLatch queryInside = new CountDownLatch(1);
        Thread slowReader = new Thread(() -> {
            ChainSnapshot snap = svc.currentSnapshot();
            queryInside.countDown();
            // 模拟基于不可变快照的大范围慢查询（不持任何锁）
            long sum = 0;
            for (long h = 0; h <= snap.tipHeight(); h++) {
                sum += snap.blockAt(h).orElseThrow().transactions().size();
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            log.info("[case] 慢查询在旧快照 v{} 上完成，统计交易数={}, 期间摄入推进至 tip={}",
                    snap.version(), sum, tipAfterSlowQueryStarted.get());
        });
        slowReader.start();
        assertTrue(queryInside.await(5, TimeUnit.SECONDS));

        // 慢查询进行中继续摄入，应当立即推进而不被阻塞
        long before = System.nanoTime();
        String parent = ChainGenerator.hash("chain-A", 50);
        for (long h = 51; h <= 60; h++) {
            Block b = linearBlock(h, parent);
            IngestResult r = svc.ingest(b);
            assertTrue(r.accepted());
            parent = b.hash();
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
        tipAfterSlowQueryStarted.set(60);
        slowReader.join(10_000);

        assertEquals(60, svc.currentSnapshot().tipHeight());
        assertTrue(elapsedMs < 500,
                "摄入 10 块耗时 " + elapsedMs + "ms，疑似被大范围查询阻塞");
        log.info("[case] 慢查询期间摄入 10 块耗时 {}ms（应远小于查询本身耗时）", elapsedMs);
    }
}
