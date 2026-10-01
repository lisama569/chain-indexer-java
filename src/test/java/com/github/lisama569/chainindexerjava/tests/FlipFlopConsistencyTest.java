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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 两条链反复互相超越（连续多次换链接管）：
 * 任何时刻对外只暴露唯一一条规范链；每次切换后区块/交易/回执/地址索引
 * 与"按规范链重算"的结果精确一致（无混链、无重复计数、无悬空地址引用）；
 * 连续切换过程中并发查询永远读到完整自洽的快照，不存在"重放了一半"的中间态。
 */
class FlipFlopConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(FlipFlopConsistencyTest.class);
    private final ChainGenerator gen = new ChainGenerator();

    /**
     * 独立重算校验：从快照中的规范链区块出发，重算交易/回执/地址索引应有内容，
     * 与快照实际索引精确比对（键集、高度、所属块哈希、列表顺序与去重）。
     */
    private void assertFullyConsistent(ChainSnapshot snap) {
        long tip = snap.tipHeight();
        if (tip < 0) {
            assertTrue(snap.txByHash().isEmpty());
            assertTrue(snap.receipts().isEmpty());
            assertTrue(snap.txHashesByAddr().isEmpty());
            return;
        }
        Map<String, Long> expectedTxHeight = new HashMap<>();
        Map<String, String> expectedTxBlock = new HashMap<>();
        Map<String, List<String>> expectedAddr = new LinkedHashMap<>();
        String expectedParent = IndexerService.GENESIS_PARENT;
        for (long h = 0; h <= tip; h++) {
            Block b = snap.blocks().get(h);
            assertNotNull(b, "快照 %d 缺少高度 %d".formatted(snap.version(), h));
            assertEquals(expectedParent, b.parentHash(),
                    "快照 %d 高度 %d 父子哈希断裂".formatted(snap.version(), h));
            for (Transaction tx : b.transactions()) {
                expectedTxHeight.put(tx.txHash(), h);
                expectedTxBlock.put(tx.txHash(), b.hash());
                for (String addr : tx.involvedAddresses()) {
                    List<String> list = expectedAddr.computeIfAbsent(addr,
                            k -> new ArrayList<>());
                    if (!list.contains(tx.txHash())) {
                        list.add(tx.txHash());
                    }
                }
            }
            expectedParent = b.hash();
        }
        // 快照头与链内容对应：确定的高度与链标识
        assertEquals(expectedParent, snap.tipHash(), "链尖哈希必须与区块序列一致");
        assertEquals(snap.blocks().get(tip).chainId(), snap.chainId(),
                "链标识必须与链尖区块一致");
        // 交易索引：无旧链残留、无遗漏
        assertEquals(expectedTxHeight.keySet(), snap.txByHash().keySet(),
                "交易索引出现混链/残留/遗漏");
        snap.txByHash().forEach((hash, tx) ->
                assertEquals(expectedTxHeight.get(hash), tx.blockHeight()));
        // 回执与交易一一对应，且指向规范链上的区块
        assertEquals(expectedTxHeight.keySet(), snap.receipts().keySet(),
                "回执索引与交易索引不成对");
        snap.receipts().forEach((hash, r) -> {
            assertEquals(expectedTxBlock.get(hash), r.blockHash());
            assertEquals(expectedTxHeight.get(hash), r.blockHeight());
        });
        // 地址索引：精确等于重算结果（无重复计数、无指向不存在交易的悬空引用）
        assertEquals(expectedAddr, snap.txHashesByAddr(),
                "地址索引与规范链不一致");
    }

    /** 预生成 6 轮互相超越的输入：B、A、B、A、B、A 交替接管。 */
    private List<List<Block>> flipFlopRounds() {
        return List.of(
                // r1: B 从 A#h1 分叉，h=2..5（在 h=4 接管，h=5 顺序延伸）
                gen.forkChain("chain-B", 1, 4, ChainGenerator.hash("chain-A", 1)),
                // r2: A 从 A#h3 继续，h=4..6（在 h=6 夺回）
                gen.chain("chain-A", 4, 3, ChainGenerator.hash("chain-A", 3)),
                // r3: B 从 B#h5' 继续，h=6..7（在 h=7 夺回）
                gen.forkChain("chain-B", 5, 2, ChainGenerator.forkHash("chain-B", 5)),
                // r4: A 从 A#h6 继续，h=7..8（在 h=8 夺回）
                gen.chain("chain-A", 7, 2, ChainGenerator.hash("chain-A", 6)),
                // r5: B 从 B#h7' 继续，h=8..9（在 h=9 夺回）
                gen.forkChain("chain-B", 7, 2, ChainGenerator.forkHash("chain-B", 7)),
                // r6: A 从 A#h8 继续，h=9..10（在 h=10 夺回）
                gen.chain("chain-A", 9, 2, ChainGenerator.hash("chain-A", 8)));
    }

    @Test
    void repeatedTakeoversKeepSingleCanonicalConsistent() {
        IndexerMetrics metrics = new IndexerMetrics();
        IndexerService svc = new IndexerService(new ChainStore(), metrics);
        gen.genesisChain("chain-A", 4).forEach(svc::ingest); // A h=0..3

        List<List<Block>> rounds = flipFlopRounds();
        // 每轮结束后的预期：链标识 / 链尖高度 / 累计换链次数 / 最近一次重放块数
        String[] expectedChain = {"chain-B", "chain-A", "chain-B", "chain-A", "chain-B", "chain-A"};
        long[] expectedTip = {5, 6, 7, 8, 9, 10};
        long[] expectedReplayed = {3, 5, 6, 7, 8, 9};

        for (int round = 0; round < rounds.size(); round++) {
            for (Block b : rounds.get(round)) {
                IngestResult r = svc.ingest(b);
                log.info("[case] 轮次{} 摄入 h={} -> accepted={} reorg={} reason={}",
                        round + 1, b.height(), r.accepted(), r.reorg(), r.reason());
            }
            ChainSnapshot snap = svc.currentSnapshot();
            log.info("[case] 轮次{} 结束 tip={}/{} chainId={} reorgs={}",
                    round + 1, snap.tipHeight(), snap.tipHash(), snap.chainId(),
                    metrics.snapshot().get("reorgs"));
            assertEquals(expectedChain[round], snap.chainId(),
                    "第 %d 轮后规范链标识错误".formatted(round + 1));
            assertEquals(expectedTip[round], snap.tipHeight());
            assertEquals((long) round + 1, metrics.snapshot().get("reorgs"),
                    "每轮应恰好发生一次接管");
            assertEquals(1L, metrics.snapshot().get("lastReorgForkPoint"),
                    "每轮都应回滚到共同祖先 h=1");
            assertEquals(expectedReplayed[round],
                    metrics.snapshot().get("lastReorgReplayedBlocks"));
            assertFullyConsistent(snap);
        }

        // 终态抽查：被替换段落的交易不得有残留（如 chain-B 第 3 轮后被替换的 tx8/tx9）
        ChainSnapshot fin = svc.currentSnapshot();
        assertEquals("chain-A", fin.chainId());
        assertTrue(fin.transaction("chain-B-tx8").isEmpty(), "被替换链的交易必须移除");
        assertTrue(fin.transaction("chain-B-tx9").isEmpty());
        assertTrue(fin.receipt("chain-B-tx8").isEmpty());
        // 当前规范链（A h=0..10）交易齐全
        for (long h = 0; h <= 10; h++) {
            assertTrue(fin.transaction("chain-A-tx%d".formatted(h)).isPresent(),
                    "chain-A-tx%d 必须在索引中".formatted(h));
        }
        // 地址引用不得指向已不存在的交易
        for (String hash : fin.transactionsOf("addrC")) {
            assertTrue(fin.transaction(hash).isPresent(),
                    "地址索引引用了不存在的交易 " + hash);
        }
    }

    @Test
    void concurrentQueriesDuringFlipFlopNeverSeeMixedState() throws Exception {
        IndexerMetrics metrics = new IndexerMetrics();
        IndexerService svc = new IndexerService(new ChainStore(), metrics);
        gen.genesisChain("chain-A", 4).forEach(svc::ingest);
        List<List<Block>> rounds = flipFlopRounds();

        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger failures = new AtomicInteger();
        AtomicReference<String> firstError = new AtomicReference<>();

        // 读者线程：切换全程不断抓取快照做独立重算校验
        List<Thread> readers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    while (!stop.get()) {
                        assertFullyConsistent(svc.currentSnapshot());
                    }
                } catch (AssertionError e) {
                    failures.incrementAndGet();
                    firstError.compareAndSet(null, e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "flipflop-reader-" + i);
            t.start();
            readers.add(t);
        }

        // 写者线程：连续执行 6 轮互相超越
        Thread writer = new Thread(() -> {
            try {
                start.await();
                for (List<Block> round : rounds) {
                    for (Block b : round) {
                        svc.ingest(b);
                        Thread.sleep(1); // 给读者留出在切换窗口内抓快照的机会
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "flipflop-writer");
        writer.start();

        start.countDown();
        writer.join(TimeUnit.SECONDS.toMillis(30));
        stop.set(true);
        for (Thread t : readers) {
            t.join(5000);
        }

        assertEquals(0, failures.get(),
                "并发查询读到不一致快照: " + firstError.get());
        assertEquals(6L, metrics.snapshot().get("reorgs"), "6 轮应各接管一次");
        ChainSnapshot fin = svc.currentSnapshot();
        assertEquals(10, fin.tipHeight());
        assertEquals("chain-A", fin.chainId());
        assertFullyConsistent(fin);
        log.info("[case] 6 轮互相超越完成，并发查询无不一致；最终 tip={}/{} chainId={}",
                fin.tipHeight(), fin.tipHash(), fin.chainId());
    }
}
