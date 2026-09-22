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
import com.github.lisama569.chainindexerjava.model.TxRef;
import com.github.lisama569.chainindexerjava.simulator.LocalChainGenerator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 重组进行中并发查询：读线程观察到的每个快照要么是完整旧链、要么是完整新链，
 * 绝不能出现“回滚了一半”的中间态、旧链残留、重复计数或遗漏。
 */
class ConcurrentReorgQueryTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentReorgQueryTest.class);

    @Test
    void queriesDuringReorgSeeOnlyWholeOldOrWholeNewChain() throws Exception {
        IndexStore store = new IndexStore();
        IndexerMetrics metrics = new IndexerMetrics();
        ForkRegistry forks = new ForkRegistry();
        BlockIngestor ingestor = new BlockIngestor(store, forks, metrics, new IndexerProperties());
        LocalChainGenerator generator = new LocalChainGenerator();

        // 规范链 A 高度 0..10
        List<Block> chainA = generator.generate(9001L, 11);
        for (Block b : chainA) {
            ingestor.ingestCanonical(b);
        }
        String oldChainId = store.current().chainId();
        Set<String> oldHashes = new HashSet<>();
        chainA.subList(3, 11).forEach(b -> oldHashes.add(b.hash()));

        // 竞争链 B 从 A2 分叉，长度 9（高度 3..11），严格更长
        List<Block> forkB = generator.generateFork(chainA.get(2), 31337L, 9);
        for (int i = 0; i < forkB.size() - 1; i++) {
            ingestor.ingestForkCandidate(forkB.get(i)); // 末端 10 == 规范末端 10 -> 全部抖动暂存
        }
        assertEquals(10L, store.current().tipHeight());

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        ConcurrentLinkedQueue<AssertionError> failures = new ConcurrentLinkedQueue<>();

        for (int t = 0; t < 4; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
                    while (System.nanoTime() < until) {
                        ChainState s = store.current();
                        validateWholeSnapshot(s);
                        reads.incrementAndGet();
                    }
                } catch (AssertionError e) {
                    failures.add(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        start.countDown();
        IngestionOutcome reorg = ingestor.ingestForkCandidate(forkB.get(forkB.size() - 1));
        assertEquals(IngestionOutcome.Status.REORG_APPLIED, reorg.status());

        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        ChainState after = store.current();
        log.info("重组期间并发读取 {} 次；重组后 tip={}, chainId={}, 指标 {}",
                reads.get(), after.tipHeight(), after.chainId(), metrics.snapshot());
        assertTrue(failures.isEmpty(), () -> "发现脏读: "
                + failures.peek().getMessage());
        assertTrue(reads.get() > 0);
        assertEquals(11L, after.tipHeight());
        assertNotEquals(oldChainId, after.chainId());

        // 重组之后：旧链 A3..A10 的任何哈希都不得残留在新快照任何索引中
        for (String oldHash : oldHashes) {
            assertFalse(after.blocksByHash().containsKey(oldHash), "旧链区块残留: " + oldHash);
        }
    }

    /** 一个快照必须整体自洽：回执/地址引用均可解析、无重复、区块父子连续。 */
    private void validateWholeSnapshot(ChainState s) {
        s.receipts().forEach((txHash, r) -> {
            Block holder = s.blocksByHash().get(r.blockHash());
            if (holder == null) {
                throw new AssertionError("脏读：回执 " + txHash + " 指向缺失区块 " + r.blockHash());
            }
            if (holder.transactions().stream().noneMatch(t -> t.txHash().equals(txHash))) {
                throw new AssertionError("脏读：回执 " + txHash + " 所在区块无此交易");
            }
        });
        s.addressIndex().forEach((addr, refs) -> {
            long distinct = refs.stream().map(TxRef::txHash).distinct().count();
            if (distinct != refs.size()) {
                throw new AssertionError("脏读：地址 " + addr + " 有重复引用");
            }
            for (TxRef ref : refs) {
                if (!s.receipts().containsKey(ref.txHash())
                        || !s.blocksByHash().containsKey(ref.blockHash())) {
                    throw new AssertionError("脏读：地址 " + addr + " 引用无法解析 " + ref.txHash());
                }
            }
        });
        Long prevH = null;
        String prevHash = null;
        for (Block b : s.blocksByHeight().values()) {
            if (prevH != null) {
                if (b.height() != prevH + 1
                        || b.parentHash() == null || !b.parentHash().equals(prevHash)) {
                    throw new AssertionError("脏读：区块链在高度 " + b.height() + " 不连续");
                }
            }
            prevH = b.height();
            prevHash = b.hash();
        }
    }
}
