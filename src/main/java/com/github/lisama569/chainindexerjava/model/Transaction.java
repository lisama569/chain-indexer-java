package com.github.lisama569.chainindexerjava.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 本地模拟的交易数据。
 *
 * @param txHash 交易哈希
 * @param from   付款方地址（参与地址索引）
 * @param to     收款方地址（可为空，代表合约创建等场景）
 * @param amount 模拟金额
 */
public record Transaction(String txHash, String from, String to, long amount) {

    /** 该交易涉及的全部可索引地址（去重保序）。 */
    public List<String> involvedAddresses() {
        Set<String> addresses = new LinkedHashSet<>();
        if (from != null && !from.isBlank()) {
            addresses.add(from);
        }
        if (to != null && !to.isBlank()) {
            addresses.add(to);
        }
        return new ArrayList<>(addresses);
    }
}
