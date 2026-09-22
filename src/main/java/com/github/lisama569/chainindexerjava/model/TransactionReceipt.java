package com.github.lisama569.chainindexerjava.model;

/**
 * 交易回执：交易被纳入某个区块后产生的可检索结论。
 *
 * @param txHash 交易哈希
 * @param height 所在高度
 * @param blockHash 所在区块哈希
 * @param status 执行状态（"SUCCESS" / "FAILURE"）
 */
public record TransactionReceipt(String txHash, long height, String blockHash, String status) {
}
