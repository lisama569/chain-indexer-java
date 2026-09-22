package com.github.lisama569.chainindexerjava.simulator;

import com.github.lisama569.chainindexerjava.model.Block;
import com.github.lisama569.chainindexerjava.model.Transaction;
import com.github.lisama569.chainindexerjava.util.HashUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 本地确定性“链”数据生成器，用生成的区块模拟链上输入。
 *
 * <p>同一 {@code seed} 生成的链逐字节一致（便于测试复现）；也可以从某个既有区块之后
 * 分叉，用不同的 {@code forkSeed} 生成竞争链，并提供构造“父哈希错误/高度跳跃”
 * 畸形区块的便捷方法，用于区分拒绝原因。
 */
@Component
public class LocalChainGenerator {

    private static final String GENESIS_PARENT = HashUtils.ZERO_HASH;

    /** 从高度 0 开始生成 count 个顺序相连的区块。 */
    public List<Block> generate(long seed, int count) {
        List<Block> chain = new ArrayList<>(count);
        String parentHash = GENESIS_PARENT;
        Random rnd = new Random(seed);
        for (int i = 0; i < count; i++) {
            Block block = buildBlock(rnd, i, parentHash, seed);
            chain.add(block);
            parentHash = block.hash();
        }
        return chain;
    }

    /**
     * 从父区块 parent 之后分叉，生成 count 个区块。
     * 第一个区块高度为 parent.height+1，parentHash 指向 parent.hash，
     * 但由于使用了不同的 forkSeed，其内容（从而哈希）与原链不同。
     */
    public List<Block> generateFork(Block parent, long forkSeed, int count) {
        List<Block> chain = new ArrayList<>(count);
        String parentHash = parent.hash();
        long height = parent.height() + 1;
        Random rnd = new Random(forkSeed ^ 0x9E3779B97F4A7C15L);
        for (int i = 0; i < count; i++) {
            Block block = buildBlock(rnd, height, parentHash, forkSeed + height);
            chain.add(block);
            parentHash = block.hash();
            height++;
        }
        return chain;
    }

    /** 构造一个高度正确但父哈希错误的区块（用于拒绝原因区分）。 */
    public Block blockWithWrongParent(Block intendedParent, long seed) {
        Random rnd = new Random(seed);
        long height = intendedParent.height() + 1;
        // 故意指向一个与真实父块无关的哈希。
        String wrongParent = HashUtils.sha256Hex("wrong-parent:" + seed);
        return buildBlock(rnd, height, wrongParent, seed);
    }

    /** 构造一个高度跳跃的区块（parentHash 正确但高度 +2，跳过一个高度）。 */
    public Block blockWithHeightGap(Block intendedParent, long seed) {
        Random rnd = new Random(seed);
        long height = intendedParent.height() + 2;
        return buildBlock(rnd, height, intendedParent.hash(), seed);
    }

    /** 构造一个同高度但内容不同的冲突区块（模拟同一高度的竞争内容）。 */
    public Block conflictingBlockAtHeight(long height, String parentHash, long seed) {
        Random rnd = new Random(seed ^ 0xD1B54A32D192ED03L);
        return buildBlock(rnd, height, parentHash, seed ^ 0x5DEECE66DL);
    }

    private Block buildBlock(Random rnd, long height, String parentHash, long seedSalt) {
        List<Transaction> txs = buildTransactions(new Random(rnd.nextLong()), height);
        String body = height + "|" + parentHash + "|" + seedSalt + "|" + summarizeTxs(txs);
        String hash = HashUtils.sha256Hex(body);
        return new Block(height, hash, parentHash, txs);
    }

    private List<Transaction> buildTransactions(Random rnd, long height) {
        int txCount = 1 + rnd.nextInt(3);
        List<Transaction> txs = new ArrayList<>(txCount);
        for (int i = 0; i < txCount; i++) {
            String from = addr(rnd);
            String to = rnd.nextInt(5) == 0 ? null : addr(rnd);
            long amount = rnd.nextInt(10) == 0 ? 0L : 1L + rnd.nextInt(100);
            String txBody = height + ":" + i + ":" + from + "->" + to + ":" + amount;
            txs.add(new Transaction(HashUtils.sha256Hex(txBody), from, to, amount));
        }
        return txs;
    }

    private String addr(Random rnd) {
        return "0xADDR" + String.format("%04d", rnd.nextInt(20));
    }

    private String summarizeTxs(List<Transaction> txs) {
        StringBuilder sb = new StringBuilder();
        for (Transaction tx : txs) {
            sb.append(tx.txHash(), 0, 16).append(';');
        }
        return sb.toString();
    }
}
