package com.github.lisama569.chainindexerjava.support;

import com.github.lisama569.chainindexerjava.domain.Block;
import com.github.lisama569.chainindexerjava.domain.Transaction;
import com.github.lisama569.chainindexerjava.indexer.ChainSnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 快照内部一致性断言（测试共享）：
 * 0..tip 区块父子哈希相连且都属于快照标识的链；每个交易都有对应回执；
 * 地址索引无重复计数；交易总数与链上交易数一致（无残留、无遗漏）。
 */
public final class SnapshotAssertions {

    private SnapshotAssertions() {
    }

    public static void assertInternallyConsistent(ChainSnapshot snap) {
        long tip = snap.tipHeight();
        if (tip < 0) {
            assertTrue(snap.txByHash().isEmpty(), "空快照不应有交易残留");
            return;
        }
        String expectedParent = "0x0";
        long txCountOnChain = 0;
        for (long h = 0; h <= tip; h++) {
            final long height = h;
            Block b = snap.blockAt(h).orElseThrow(() ->
                    new AssertionError("快照 %d 缺少高度 %d".formatted(snap.version(), height)));
            assertEquals(h, b.height());
            assertEquals(expectedParent, b.parentHash(),
                    "快照 %d 高度 %d 父哈希断裂".formatted(snap.version(), h));
            if (h == tip) {
                // 快照链标识以链尖为准（共同祖先段可能来自被替换的旧链，属正常保留）
                assertEquals(snap.chainId(), b.chainId(),
                        "快照 %d 链尖链标识与快照头不一致".formatted(snap.version()));
            }
            for (Transaction tx : b.transactions()) {
                txCountOnChain++;
                Transaction indexed = snap.transaction(tx.txHash()).orElseThrow(() ->
                        new AssertionError("快照 %d 缺少交易 %s".formatted(snap.version(), tx.txHash())));
                assertEquals(h, indexed.blockHeight());
                assertEquals(b.hash(), snap.receipt(tx.txHash()).orElseThrow().blockHash(),
                        "回执必须指向所在区块");
            }
            expectedParent = b.hash();
        }
        assertEquals(expectedParent, snap.tipHash(), "链尖哈希必须与逐块回溯一致");
        assertEquals(txCountOnChain, snap.txByHash().size(),
                "快照 %d 交易总数与链上交易不一致（残留或遗漏）".formatted(snap.version()));
        assertEquals(txCountOnChain, snap.receipts().size(),
                "快照 %d 回执总数与链上交易不一致".formatted(snap.version()));
        snap.txHashesByAddr().forEach((addr, hashes) -> {
            long distinct = hashes.stream().distinct().count();
            assertEquals(hashes.size(), distinct,
                    "快照 %d 地址 %s 索引存在重复计数".formatted(snap.version(), addr));
            // 地址索引中的每笔交易都必须真实存在于当前快照（不得指向已被回滚的交易）
            for (String txHash : hashes) {
                assertTrue(snap.transaction(txHash).isPresent(),
                        "快照 %d 地址 %s 索引指向不存在的交易 %s"
                                .formatted(snap.version(), addr, txHash));
            }
        });
    }
}
