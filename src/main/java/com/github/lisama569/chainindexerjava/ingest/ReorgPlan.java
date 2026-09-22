package com.github.lisama569.chainindexerjava.ingest;

import com.github.lisama569.chainindexerjava.model.Block;

import java.util.List;

/**
 * 一次链重组的执行计划：回滚到共同祖先，然后沿新链重放。
 */
public record ReorgPlan(long commonAncestorHeight,
                        String commonAncestorHash,
                        long oldTipHeight,
                        long newTipHeight,
                        List<Block> newChainSuffix) {
}
