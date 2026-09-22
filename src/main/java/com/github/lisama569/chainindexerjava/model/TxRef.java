package com.github.lisama569.chainindexerjava.model;

/**
 * 地址索引中的一条引用：某地址在某高度某区块涉及了某交易。
 */
public record TxRef(String txHash, long height, String blockHash) {
}
