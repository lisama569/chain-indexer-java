package com.github.lisama569.chainindexerjava.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 链上交易（本地模拟）。
 *
 * @param txHash       交易哈希（链内唯一）
 * @param from         付款地址
 * @param to           收款地址（可为 null，表示合约创建类交易）
 * @param value        转账金额
 * @param blockHeight  所在区块高度（随区块摄入时已知）
 */
public record Transaction(
        String txHash,
        String from,
        String to,
        long value,
        long blockHeight
) {
    /** 该交易涉及的全部地址（用于地址->交易索引）。 */
    public List<String> involvedAddresses() {
        List<String> addrs = new ArrayList<>(2);
        if (from != null && !from.isBlank()) {
            addrs.add(from);
        }
        if (to != null && !to.isBlank()) {
            addrs.add(to);
        }
        return addrs;
    }

    /** 输入摘要，供日志打印判定依据。 */
    public String summary() {
        return "tx{hash=%s,from=%s,to=%s,value=%d,h=%d}".formatted(
                txHash, from, to, value, blockHeight);
    }
}
