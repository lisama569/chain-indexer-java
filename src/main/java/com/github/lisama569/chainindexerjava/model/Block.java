package com.github.lisama569.chainindexerjava.model;

import java.util.List;

/**
 * 本地模拟的区块数据。链上区块是不可变的，故这里只暴露读方法。
 *
 * @param height       区块高度
 * @param hash         区块哈希（内容摘要）
 * @param parentHash   父区块哈希
 * @param transactions 区块内的交易（按顺序）
 */
public record Block(long height, String hash, String parentHash, List<Transaction> transactions) {

    public Block {
        transactions = transactions == null ? List.of() : List.copyOf(transactions);
    }
}
