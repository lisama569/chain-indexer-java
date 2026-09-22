package com.github.lisama569.chainindexerjava.ingest;

/**
 * 区块被拒绝的可区分原因。
 */
public enum RejectionReason {
    /** 完全重复提交：同一高度、同一哈希的区块已在规范链上。 */
    DUPLICATE_BLOCK,
    /** 同一高度内容冲突：同高度已存在不同哈希的区块。 */
    CONFLICT_AT_HEIGHT,
    /** 高度跳跃：区块的高度不衔接当前规范链末端。 */
    HEIGHT_GAP,
    /** 父哈希不连续：parentHash 与当前规范链末端哈希不一致。 */
    PARENT_HASH_MISMATCH
}
