package com.github.lisama569.chainindexerjava.ingest;

import com.github.lisama569.chainindexerjava.model.Block;
import com.github.lisama569.chainindexerjava.model.ChainState;
import com.github.lisama569.chainindexerjava.util.HashUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 分叉候选暂存区：与规范链不能直接顺序衔接的区块先在这里按“链”组织。
 *
 * <p>核心语义：
 * <ul>
 *   <li>只有当竞争链末端高度<strong>严格领先</strong>规范链末端至少 {@code margin}
 *       （默认 1）个高度时，才认定出现了更长的链、给出 {@link ReorgPlan}；</li>
 *   <li>等长或更短的竞争链只是短暂的分叉抖动，候选停留在此处，规范链结论不变；</li>
 *   <li>同一高度内容不同的候选可以共存（它们是不同的哈希），互不覆盖。</li>
 * </ul>
 * 内部用 {@code hash -> block} 保存所有候选，{@code parentHash -> 子块} 建立链连接。
 */
@Component
public class ForkRegistry {

    private final Map<String, Block> byHash = new HashMap<>();
    private final Map<String, List<Block>> childrenByParent = new HashMap<>();

    /** 暂存一个分叉区块。重复哈希是幂等的，不会产生两份数据。 */
    public synchronized boolean park(Block block) {
        if (byHash.containsKey(block.hash())) {
            return true;
        }
        byHash.put(block.hash(), block);
        childrenByParent.computeIfAbsent(block.parentHash(), k -> new ArrayList<>()).add(block);
        return true;
    }

    public synchronized boolean containsHash(String hash) {
        return byHash.containsKey(hash);
    }

    /**
     * 以 forkTip 为末端沿父指针回溯，找到与规范链的共同祖先；
     * 共同祖先之后到 forkTip 的序列构成新链后缀。仅当
     * {@code forkTip.height >= canonical.tipHeight + margin} 时返回重组计划，
     * 否则返回 empty（判定为分叉抖动）。
     */
    public synchronized Optional<ReorgPlan> findLongerFork(Block forkTip, ChainState canonical, int margin) {
        if (forkTip == null) {
            return Optional.empty();
        }
        // 抖动门槛：竞争链必须严格更长。
        if (forkTip.height() < canonical.tipHeight() + margin) {
            return Optional.empty();
        }

        List<Block> suffix = new ArrayList<>();
        Block cursor = forkTip;
        while (cursor != null && !canonical.blocksByHash().containsKey(cursor.hash())) {
            suffix.add(cursor);
            String parent = cursor.parentHash();
            if (parent == null || HashUtils.ZERO_HASH.equals(parent)) {
                cursor = null;
            } else {
                // 父块可能仍是暂存候选，也可能已经属于规范链（即共同祖先）。
                cursor = byHash.get(parent);
                if (cursor == null) {
                    cursor = canonical.blocksByHash().get(parent);
                }
            }
        }

        if (cursor == null) {
            // 回溯不到任何共同祖先（与规范链无关的孤立链），不能安全重组。
            return Optional.empty();
        }

        java.util.Collections.reverse(suffix);
        if (suffix.isEmpty()) {
            return Optional.empty();
        }
        // 新链后缀必须从共同祖先之后高度连续、父子相连（防御内容畸形的候选）。
        Block first = suffix.get(0);
        if (!first.parentHash().equals(cursor.hash())
                || first.height() != cursor.height() + 1) {
            return Optional.empty();
        }
        for (int i = 1; i < suffix.size(); i++) {
            Block prev = suffix.get(i - 1);
            Block cur = suffix.get(i);
            if (!cur.parentHash().equals(prev.hash()) || cur.height() != prev.height() + 1) {
                return Optional.empty();
            }
        }
        return Optional.of(new ReorgPlan(
                cursor.height(), cursor.hash(), canonical.tipHeight(), forkTip.height(), suffix));
    }

    /** 重组完成后清理：被吸收为新规范链的候选，以及所有已不在有效竞争链上的候选。 */
    public synchronized void pruneAfterReorg(ReorgPlan plan) {
        for (Block absorbed : plan.newChainSuffix()) {
            byHash.remove(absorbed.hash());
            List<Block> children = childrenByParent.get(absorbed.parentHash());
            if (children != null) {
                children.removeIf(b -> b.hash().equals(absorbed.hash()));
            }
        }
        // 被回滚的旧链区块不在此暂存区（它们曾是规范链），无需处理；
        // 其余候选若父连接指向已被吸收的区块，仍可保留为未来分叉观察。
    }

    public synchronized List<Block> parkedBlocks() {
        return List.copyOf(byHash.values());
    }

    public synchronized void clear() {
        byHash.clear();
        childrenByParent.clear();
    }
}
