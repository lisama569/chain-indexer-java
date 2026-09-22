package com.github.lisama569.chainindexerjava.storage;

import com.github.lisama569.chainindexerjava.domain.Block;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 区块底层存储（本地内存模拟）。
 * <p>
 * 只负责按哈希/按高度存放所有"已见到"的区块（含分叉侧链区块），
 * 是重组发生时沿 parentHash 找回共同祖先与分叉区块的依据。
 * 区块一旦存入即不可变，重复写入相同内容是幂等操作。
 */
@Component
public class ChainStore {

    /** 区块哈希 -> 区块（内容不可变） */
    private final Map<String, Block> byHash = new ConcurrentHashMap<>();

    /** 高度 -> 该高度下所有已见到的区块哈希（同一高度可能存在分叉） */
    private final Map<Long, Set<String>> hashesByHeight = new ConcurrentHashMap<>();

    /** 存入区块；相同哈希重复写入幂等返回，内容永不覆盖。 */
    public void put(Block block) {
        Block old = byHash.putIfAbsent(block.hash(), block);
        if (old == null) {
            hashesByHeight
                    .computeIfAbsent(block.height(), h -> ConcurrentHashMap.newKeySet())
                    .add(block.hash());
        }
    }

    public boolean contains(String hash) {
        return byHash.containsKey(hash);
    }

    public Optional<Block> findByHash(String hash) {
        return Optional.ofNullable(byHash.get(hash));
    }

    /** 某高度下已见到的全部区块（含分叉）。 */
    public List<Block> findAllByHeight(long height) {
        Set<String> hashes = hashesByHeight.get(height);
        if (hashes == null) {
            return List.of();
        }
        List<Block> blocks = new ArrayList<>(hashes.size());
        for (String hash : hashes) {
            Block b = byHash.get(hash);
            if (b != null) {
                blocks.add(b);
            }
        }
        return blocks;
    }

    /** 兼容骨架接口：取该高度任意一个区块（主要用于测试/诊断）。 */
    public Optional<Block> findByHeight(long height) {
        return findAllByHeight(height).stream().findFirst();
    }

    /**
     * 从给定链尖沿 parentHash 回溯，返回从创世到该链尖的有序区块列表。
     * 遇到未知父哈希即终止（用于重组时重建一条完整链）。
     */
    public List<Block> blocksOnChain(String tipHash) {
        List<Block> ascending = new ArrayList<>();
        Block cursor = byHash.get(tipHash);
        Set<String> guard = new HashSet<>();
        while (cursor != null && guard.add(cursor.hash())) {
            ascending.add(cursor);
            String parent = cursor.parentHash();
            if (parent == null || parent.isBlank() || "0x0".equals(parent)) {
                break;
            }
            cursor = byHash.get(parent);
        }
        Collections.reverse(ascending);
        return ascending;
    }
}
