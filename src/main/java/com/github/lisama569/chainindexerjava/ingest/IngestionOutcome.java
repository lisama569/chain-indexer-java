package com.github.lisama569.chainindexerjava.ingest;

import com.github.lisama569.chainindexerjava.model.Block;

/**
 * 一次区块提交（或分叉候选提交）的判定结果，可解释、可区分。
 *
 * @param status           总体判定
 * @param rejectionReason  被拒绝时的细分原因（其余情况为 null）
 * @param detail           判定依据的文字说明
 * @param tipHeight        判定后规范链末端高度（拒绝时为拒绝前末端，可能为 null）
 * @param chainId          判定后规范链标识
 * @param rollbackToHeight 重组时回滚到的共同祖先高度
 * @param reorgedFrom      重组前末端高度
 * @param reorgedTo        重组后末端高度
 */
public record IngestionOutcome(Status status,
                               RejectionReason rejectionReason,
                               String detail,
                               Long tipHeight,
                               String chainId,
                               Long rollbackToHeight,
                               Long reorgedFrom,
                               Long reorgedTo) {

    public enum Status {
        /** 区块被追加到规范链。 */
        ACCEPTED,
        /** 分叉候选被暂存（尚未构成更长链，不改变规范链结论）。 */
        FORK_PARKED,
        /** 发生链重组：回滚到共同祖先后沿新链重建索引。 */
        REORG_APPLIED,
        /** 输入被拒绝，见 {@link #rejectionReason()}。 */
        REJECTED
    }

    public static IngestionOutcome accepted(Block block, String chainId) {
        return new IngestionOutcome(Status.ACCEPTED, null,
                "block appended to canonical chain",
                block == null ? null : block.height(), chainId, null, null, null);
    }

    public static IngestionOutcome forkParked(Block block, String detail) {
        return new IngestionOutcome(Status.FORK_PARKED, null, detail,
                block == null ? null : block.height(), null, null, null, null);
    }

    public static IngestionOutcome rejected(Block block, RejectionReason reason, String detail) {
        return new IngestionOutcome(Status.REJECTED, reason, detail,
                block == null ? null : block.height(), null, null, null, null);
    }

    public static IngestionOutcome reorgApplied(long commonAncestorHeight,
                                                long oldTip, long newTip, String chainId) {
        return new IngestionOutcome(Status.REORG_APPLIED, null,
                "rolled back to common ancestor at height " + commonAncestorHeight
                        + " and replayed the longer competing chain",
                newTip, chainId, commonAncestorHeight, oldTip, newTip);
    }
}
