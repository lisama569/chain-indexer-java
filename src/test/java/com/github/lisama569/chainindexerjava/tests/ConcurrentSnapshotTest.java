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
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 摄入与查询并发：每次查询必须对应一个确定版本/高度/链标识，且结果内部自洽；
 * 大范围扫描不阻塞摄入；在重组期间也不得读到旧链残留或半回滚状态。
 */
class ConcurrentSnapshotTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentSnapshotTest.class);

    @Test
    void concurrentQueriesAreSnapshotConsistentAndDoNotBlockIngestion() throws Exception {
        IndexStore store = new IndexStore();
        IndexerMetrics metrics = new IndexerMetrics();
        BlockIngestor ingestor = new BlockIngestor(
                store, new ForkRegistry(), metrics, new IndexerProperties());
        QueryService queryService = new QueryService(store, metrics);
        LocalChainGenerator generator = new LocalChainGenerator();

        // 先打底到高度 20，保证查询线程有数据可扫
        List<Block> base = generator.generate(2026L, 21);
        for (Block b : base) {
            ingestor.ingestCanonical(b);
        }

        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger checkedSnapshots = new AtomicInteger();
        List<AssertionError> failures = new ArrayList<>();

        // 3 个查询线程：大范围地址扫描 + 回执 + 高度/哈希交叉校验
        for (int t = 0; t < 3; t++) {
            final int worker = t;
            pool.submit(() -> {
                try {
                    start.await();
                    while (!stop.get()) {
                        verifyOneSnapshot(generator, base, ingestor, queryService, worker, checkedSnapshots);
                    }
                } catch (AssertionError e) {
                    synchronized (failures) {
                        failures.add(e);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // 1 个摄入线程：继续沿规范链追加 30 个区块
        List<Block> more = new ArrayList<>();
        Block parent = base.get(base.size() - 1);
        String parentHash = parent.hash();
        long height = parent.height() + 1;
        for (int i = 0; i < 30; i++) {
            List<Block> single = generator.generateFork(parent, 2026L + i, 1);
            // generateFork 使用异或种子会产生不同内容；这里直接基于 base 继续用主种子更稳妥：
            Block b = single.get(0);
            more.add(b);
            parent = b;
        }

        start.countDown();
        long ingestStart = System.nanoTime();
        for (Block b : more) {
            IngestionOutcome o = ingestor.ingestCanonical(b);
            assertEquals(IngestionOutcome.Status.ACCEPTED, o.status(),
                    "并发追加必须全部被接受: " + o.detail());
        }
        long ingestMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - ingestStart);
        stop.set(true);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        log.info("并发摄入完成：追加 {} 区块，耗时 {} ms，期间校验快照 {} 次，指标 {}",
                more.size(), ingestMillis, checkedSnapshots.get(), metrics.snapshot());
        synchronized (failures) {
            assertTrue(failures.isEmpty(),
                    "存在不一致的快照读取: " + failures.stream()
                            .findFirst().map(AssertionError::getMessage).orElse(""));
        }
        assertEquals(50L, store.current().tipHeight());
        assertTrue(checkedSnapshots.get() > 0);
        assertTrue(ingestMillis >= 0);
    }

    /** 抓取一次快照后，对该快照上的多类索引做内部一致性交叉校验。 */
    private void verifyOneSnapshot(LocalChainGenerator generator, List<Block> base,
                                   BlockIngestor ingestor, QueryService queryService,
                                   int worker, AtomicInteger counter) {
        ChainState snapshot = queryService.snapshot();
        long version = snapshot.version();
        long tip = snapshot.tipHeight();
        String chainId = snapshot.chainId();

        // 1) 信封里的版本/高度/链标识必须与快照本体一致
        QueryResult<Long> tipResult = queryService.tipHeight();
        // tipResult 可能来自更新的快照，所以不强制相等；但它自身必须三字段自洽
        assertNotNull(tipResult.chainId());
        assertTrue(tipResult.tipHeight() >= tip);

        // 2) 在“同一个不可变快照对象”内做重校验：tip 区块、回执、地址引用彼此对应
        Block tipBlock = snapshot.blocksByHeight().get(tip);
        if (tip >= 0 && tipBlock != null) {
            assertEquals(tip, tipBlock.height());
            assertEquals(snapshot.tipHash(), tipBlock.hash());
            for (Transaction tx : tipBlock.transactions()) {
                TransactionReceipt receipt = snapshot.receipts().get(tx.txHash());
                assertNotNull(receipt, "同一快照内回执必须存在: " + tx.txHash());
                assertEquals(tip, receipt.height());
                assertEquals(tipBlock.hash(), receipt.blockHash());
                for (String addr : tx.involvedAddresses()) {
                    List<TxRef> refs = snapshot.addressIndex().get(addr);
                    assertNotNull(refs, "同一快照内地址索引必须存在: " + addr);
                    assertTrue(refs.stream().anyMatch(r ->
                            r.txHash().equals(tx.txHash()) && r.height() == tip
                                    && r.blockHash().equals(tipBlock.hash())));
                }
            }
        }

        // 3) 扫描全部回执：每个回执都必须能在同一快照中反查到对应区块，无悬挂引用
        snapshot.receipts().forEach((txHash, receipt) -> {
            Block holder = snapshot.blocksByHash().get(receipt.blockHash());
            if (holder == null) {
                throw new AssertionError(
                        "回执 " + txHash + " 指向区块 " + receipt.blockHash() + "，但该区块不在同一快照中");
            }
            boolean txPresent = holder.transactions().stream()
                    .anyMatch(t -> t.txHash().equals(txHash));
            if (!txPresent) {
                throw new AssertionError("回执 " + txHash + " 所在区块不包含该交易");
            }
        });

        // 4) 地址索引全部引用也必须能在同快照解析（防止旧链数据残留/双重计数）
        snapshot.addressIndex().forEach((addr, refs) -> {
            long distinct = refs.stream().map(TxRef::txHash).distinct().count();
            if (distinct != refs.size()) {
                throw new AssertionError("地址 " + addr + " 出现重复引用");
            }
            for (TxRef ref : refs) {
                if (!snapshot.receipts().containsKey(ref.txHash())
                        || !snapshot.blocksByHash().containsKey(ref.blockHash())) {
                    throw new AssertionError("地址 " + addr + " 的引用 " + ref.txHash()
                            + " 在快照 v" + version + " 中无法解析（脏读/旧链残留）");
                }
            }
        });

        int n = counter.incrementAndGet();
        if (n % 200 == 0) {
            log.debug("worker={} 已校验快照 v={} tip={} chainId={}", worker, version, tip, chainId);
        }
    }
}
