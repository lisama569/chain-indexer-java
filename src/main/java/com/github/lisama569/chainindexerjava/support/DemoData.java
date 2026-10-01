package com.github.lisama569.chainindexerjava.support;

import com.github.lisama569.chainindexerjava.domain.Block;
import com.github.lisama569.chainindexerjava.domain.Transaction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 演示用确定性数据：规范链 chain-A（h=0..3）、各类异常输入、分叉链 chain-B（h=2..4）。
 */
@Component
public class DemoData {

    private static final String GENESIS = "0x0";

    private static Block block(long h, String hash, String parent, String chain,
                               String txHash, String from, String to, long value) {
        List<Transaction> txs = txHash == null
                ? List.of()
                : List.of(new Transaction(txHash, from, to, value, h));
        return new Block(h, hash, parent, chain, txs);
    }

    /** chain-A h=0..3。 */
    public List<Block> canonicalChain() {
        List<Block> chain = new ArrayList<>();
        for (long h = 0; h <= 3; h++) {
            chain.add(block(h, "A#h%d".formatted(h),
                    h == 0 ? GENESIS : "A#h%d".formatted(h - 1),
                    "chain-A", "A-tx%d".formatted(h), "addrA", "addrB", 100 + h));
        }
        return chain;
    }

    public record Case(String name, Block block) {
    }

    /** 异常输入集合（在 tip=3 的状态下投递）。 */
    public List<Case> rejectionCases() {
        return List.of(
                new Case("重复提交", canonicalChain().get(2)),
                new Case("高度跳跃", block(9, "A#h9", "A#h8", "chain-A",
                        "A-tx9", "addrA", "addrB", 900)),
                new Case("父哈希不连续", block(4, "X#h4", "someone-else", "chain-A",
                        "X-tx4", "addrA", "addrB", 400)),
                new Case("同高度内容冲突", block(2, "A#h2'", "A#h1", "chain-A",
                        "evil-tx", "addrX", "addrY", 1)),
                new Case("非法块(空哈希)", block(1, " ", "A#h0", "chain-A",
                        null, null, null, 0))
        );
    }

    /**
     * chain-B 从共同祖先 A#h1 分叉，逐块到达：
     * h=2,3 与规范同高度（HEIGHT_CONFLICT 抖动，收存观察），
     * h=4 到达时分叉接通且严格更长（默认门槛 1），当次提交即触发接管，
     * h=5 在新规范链上顺序延伸。
     */
    public List<Block> forkBlocks() {
        List<Block> fork = new ArrayList<>();
        for (long h = 2; h <= 5; h++) {
            fork.add(block(h, "B#h%d".formatted(h),
                    h == 2 ? "A#h1" : "B#h%d".formatted(h - 1),
                    "chain-B", "B-tx%d".formatted(h), "addrB", "addrC", 200 + h));
        }
        return fork;
    }
}
