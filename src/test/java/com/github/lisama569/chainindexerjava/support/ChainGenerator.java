package com.github.lisama569.chainindexerjava.support;

import com.github.lisama569.chainindexerjava.domain.Block;
import com.github.lisama569.chainindexerjava.domain.Transaction;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地确定性区块数据生成器，模拟链上输入。
 * 哈希规则：chainId + "#h" + 序号/变体，便于在测试里手工拼出分叉。
 */
public class ChainGenerator {

    public static final String GENESIS_PARENT = "0x0";

    /** 规范链默认哈希：chain-A#h3 */
    public static String hash(String chainId, long height) {
        return "%s#h%d".formatted(chainId, height);
    }

    /** 分叉变体哈希：chain-B#h3' */
    public static String forkHash(String chainId, long height) {
        return "%s#h%d'".formatted(chainId, height);
    }

    public static Transaction tx(String txHash, String from, String to, long value) {
        return new Transaction(txHash, from, to, value, -1L);
    }

    public Block block(long height, String hash, String parentHash, String chainId,
                       List<Transaction> transactions) {
        return new Block(height, hash, parentHash, chainId,
                transactions == null ? List.of() : transactions);
    }

    /** 生成一条每个高度含 1 笔交易的简单链（地址固定 A->B）。 */
    public List<Block> chain(String chainId, int fromHeight, int count, String startParentHash) {
        List<Block> blocks = new ArrayList<>();
        String parent = startParentHash;
        for (int i = 0; i < count; i++) {
            long h = fromHeight + i;
            String hash = hash(chainId, h);
            List<Transaction> txs = List.of(tx(
                    "%s-tx%d".formatted(chainId, h), "addrA", "addrB", 100 + h));
            blocks.add(block(h, hash, parent, chainId, txs));
            parent = hash;
        }
        return blocks;
    }

    /** 从创世开始生成 n 个区块（h=0..n-1）。 */
    public List<Block> genesisChain(String chainId, int n) {
        return chain(chainId, 0, n, GENESIS_PARENT);
    }

    /**
     * 生成一条分叉链：从 forkPoint+1 开始逐块延伸 count 块，
     * 第一块的父哈希为 startParentHash，区块哈希使用分叉变体（h'）。
     * 交易哈希为 chainId-tx{高度}，地址固定 addrB -> addrC。
     */
    public List<Block> forkChain(String chainId, int forkPoint, int count, String startParentHash) {
        List<Block> blocks = new ArrayList<>();
        String parent = startParentHash;
        for (int i = 1; i <= count; i++) {
            long h = forkPoint + i;
            String hash = forkHash(chainId, h);
            blocks.add(block(h, hash, parent, chainId,
                    List.of(tx("%s-tx%d".formatted(chainId, h), "addrB", "addrC", 200 + h))));
            parent = hash;
        }
        return blocks;
    }
}
