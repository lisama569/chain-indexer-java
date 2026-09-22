package com.github.lisama569.chainindexerjava.ingest;

/**
 * 区块被拒绝/判定的原因类型，彼此可区分。
 */
public enum RejectReason {
    /** 区块哈希与已存在区块完全相同（重复提交，幂等，不覆盖数据） */
    DUPLICATE_BLOCK,
    /** 高度跳跃：新区块高度不是 tip+1，且其链上缺少必要的中间区块 */
    HEIGHT_GAP,
    /** 父哈希与规范链上对应高度的区块哈希不连续 */
    PARENT_HASH_MISMATCH,
    /** 同一高度已存在不同内容（分叉抖动 / 高度冲突），不静默覆盖 */
    HEIGHT_CONFLICT,
    /** 区块自身数据不合法（空哈希、交易重复等） */
    INVALID_BLOCK
}
