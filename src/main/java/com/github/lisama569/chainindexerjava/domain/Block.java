package com.github.lisama569.chainindexerjava.domain;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 区块（本地模拟链上输入）。
 *
 * @param height       区块高度
 * @param hash         本区块哈希
 * @param parentHash   父区块哈希
 * @param chainId      链标识（区分不同的链/分叉来源）
 * @param transactions 区块内交易（有序，顺序即索引顺序）
 */
public record Block(
        long height,
        String hash,
        String parentHash,
        String chainId,
        List<Transaction> transactions
) {
    /** 输入摘要：高度、哈希、父哈希、链标识与交易哈希列表，供日志打印判定依据。 */
    public String summary() {
        String txs = transactions == null
                ? "[]"
                : transactions.stream().map(Transaction::txHash).collect(Collectors.joining(","));
        return "block{h=%d,hash=%s,parent=%s,chain=%s,txs=[%s]}".formatted(
                height, hash, parentHash, chainId, txs);
    }
}
