package com.github.lisama569.chainindexerjava.indexer;

import com.github.lisama569.chainindexerjava.domain.Block;
import com.github.lisama569.chainindexerjava.domain.Receipt;
import com.github.lisama569.chainindexerjava.domain.Transaction;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 不可变查询快照：每个快照明确对应一个确定的高度与链标识。
 * <p>
 * 快照在摄入完成时一次性发布（整体引用替换），之后不再修改，
 * 因此查询线程在快照上做大范围扫描时，完全不与摄入线程争用写入锁，
 * 即"单次大范围查询不会阻塞后续摄入"。
 *
 * @param version        单调递增的快照版本
 * @param tipHeight      快照对应规范链最新高度
 * @param tipHash        快照对应规范链最新区块哈希
 * @param chainId        链标识
 * @param blocks         高度 -> 区块
 * @param txByHash       交易哈希 -> 交易
 * @param receipts       交易哈希 -> 回执
 * @param txHashesByAddr 地址 -> 相关交易哈希列表（按高度、区块内序号有序）
 */
public record ChainSnapshot(
        long version,
        long tipHeight,
        String tipHash,
        String chainId,
        Map<Long, Block> blocks,
        Map<String, Transaction> txByHash,
        Map<String, Receipt> receipts,
        Map<String, List<String>> txHashesByAddr
) {
    public Optional<Block> blockAt(long height) {
        return Optional.ofNullable(blocks.get(height));
    }

    public Optional<Transaction> transaction(String txHash) {
        return Optional.ofNullable(txByHash.get(txHash));
    }

    public Optional<Receipt> receipt(String txHash) {
        return Optional.ofNullable(receipts.get(txHash));
    }

    /** 返回地址相关的全部交易哈希（快照内不可变列表，顺序确定）。 */
    public List<String> transactionsOf(String address) {
        return txHashesByAddr.getOrDefault(address, List.of());
    }
}
