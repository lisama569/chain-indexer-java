package com.github.lisama569.chainindexerjava.domain;

/**
 * 交易回执（本地模拟），通过交易哈希检索。
 *
 * @param txHash       交易哈希
 * @param blockHeight  所在高度
 * @param blockHash    所在区块哈希
 * @param success      是否执行成功
 * @param gasUsed      消耗 gas（模拟）
 */
public record Receipt(
        String txHash,
        long blockHeight,
        String blockHash,
        boolean success,
        long gasUsed
) {
}
