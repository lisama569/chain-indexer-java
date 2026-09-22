package com.github.lisama569.chainindexerjava.model;

import com.github.lisama569.chainindexerjava.util.HashUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 某一时刻规范链（canonical chain）的不可变快照状态。
 *
 * <p>快照一旦发布就不再被修改：追加区块、回滚重组都会构造一个全新的
 * {@code ChainState}，再由存储层做一次原子引用切换。因此查询线程要么完整看到
 * 旧状态、要么完整看到新状态，绝不会读到半成品；新旧快照的索引结构彼此独立，
 * 重组后旧链数据不可能从新快照中漏出。
 *
 * <p>快照内含四类索引：
 * <ul>
 *   <li>高度 -> 区块</li>
 *   <li>区块哈希 -> 区块</li>
 *   <li>交易哈希 -> 回执</li>
 *   <li>地址 -> 该地址参与的交易引用（高度升序）</li>
 * </ul>
 */
public record ChainState(long version,
                         long tipHeight,
                         String tipHash,
                         String chainId,
                         NavigableMap<Long, Block> blocksByHeight,
                         Map<String, Block> blocksByHash,
                         Map<String, TransactionReceipt> receipts,
                         Map<String, List<TxRef>> addressIndex) {

    /** 空链使用的稳定链标识。 */
    public static final String EMPTY_CHAIN_ID = HashUtils.sha256Hex("empty-chain");

    private static final ChainState EMPTY = new ChainState(
            0L, -1L, null, EMPTY_CHAIN_ID,
            new TreeMap<>(), Map.of(), Map.of(), Map.of());

    /** 空链（尚未摄入任何区块）的初始状态。 */
    public static ChainState empty() {
        return EMPTY;
    }

    /** 是否一个区块都没有摄入。 */
    public boolean isEmpty() {
        return tipHeight < 0;
    }

    /**
     * 在当前快照末端追加一个区块，返回新快照（不修改当前对象）。
     * 调用方负责保证高度/父哈希连续，这里只做纯索引构建。
     */
    public ChainState withAppended(Block block, long newVersion) {
        TreeMap<Long, Block> newByHeight = new TreeMap<>(blocksByHeight);
        newByHeight.put(block.height(), block);

        var newByHash = new java.util.HashMap<>(blocksByHash);
        newByHash.put(block.hash(), block);

        var newReceipts = new java.util.HashMap<>(receipts);
        var newAddressIndex = new java.util.HashMap<>(addressIndex);

        for (Transaction tx : block.transactions()) {
            String status = tx.amount() > 0 ? "SUCCESS" : "FAILURE";
            newReceipts.put(tx.txHash(),
                    new TransactionReceipt(tx.txHash(), block.height(), block.hash(), status));
            TxRef ref = new TxRef(tx.txHash(), block.height(), block.hash());
            for (String address : tx.involvedAddresses()) {
                List<TxRef> oldRefs = newAddressIndex.getOrDefault(address, List.of());
                List<TxRef> refs = new ArrayList<>(oldRefs.size() + 1);
                refs.addAll(oldRefs);
                refs.add(ref);
                newAddressIndex.put(address, List.copyOf(refs));
            }
        }

        NavigableMap<Long, Block> immutableHeights = java.util.Collections.unmodifiableNavigableMap(newByHeight);
        return new ChainState(
                newVersion,
                block.height(),
                block.hash(),
                computeChainId(newByHeight),
                immutableHeights,
                Map.copyOf(newByHash),
                Map.copyOf(newReceipts),
                Map.copyOf(newAddressIndex));
    }

    /**
     * 回滚：保留高度 0..throughHeight（含）的区块作为前缀，返回该前缀的新快照。
     * 共同祖先之后的区块、交易回执、地址引用全部从新快照中剔除。
     */
    public ChainState prefixThrough(long throughHeight, long newVersion) {
        if (throughHeight < 0) {
            return new ChainState(newVersion, -1L, null, EMPTY_CHAIN_ID,
                    new TreeMap<>(), Map.of(), Map.of(), Map.of());
        }
        ChainState prefix = new ChainState(newVersion, -1L, null, EMPTY_CHAIN_ID,
                new TreeMap<>(), Map.of(), Map.of(), Map.of());
        for (Map.Entry<Long, Block> e : blocksByHeight.headMap(throughHeight, true).entrySet()) {
            prefix = prefix.withAppended(e.getValue(), newVersion);
        }
        return prefix;
    }

    /** 由当前规范链（高度 0..tip 的哈希序列）派生稳定的链标识。 */
    public String computeChainId() {
        return chainId;
    }

    private static String computeChainId(NavigableMap<Long, Block> ordered) {
        StringBuilder sb = new StringBuilder("chain:");
        for (Block b : ordered.values()) {
            sb.append(b.height()).append(':').append(b.hash()).append('|');
        }
        return HashUtils.sha256Hex(sb.toString());
    }
}
