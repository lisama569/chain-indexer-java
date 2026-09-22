package com.github.lisama569.chainindexerjava.ingest;

/**
 * 一次区块摄入的判定结果，可解释。
 *
 * @param accepted   是否被接受（DUPLICATE_BLOCK 也视为幂等成功）
 * @param reorg      是否触发了链重组
 * @param reason     被拒绝时的原因（接受时为 null）
 * @param detail     判定依据的人类可读描述（含关键高度/哈希）
 * @param tipHeight  判定完成后规范链的最新高度
 * @param tipHash    判定完成后规范链的最新区块哈希
 * @param chainId    判定完成后规范链标识
 */
public record IngestResult(
        boolean accepted,
        boolean reorg,
        RejectReason reason,
        String detail,
        long tipHeight,
        String tipHash,
        String chainId
) {
    public static IngestResult rejected(RejectReason reason, String detail,
                                        long tipHeight, String tipHash, String chainId) {
        return new IngestResult(false, false, reason, detail, tipHeight, tipHash, chainId);
    }

    public static IngestResult accepted(String detail, long tipHeight, String tipHash, String chainId) {
        return new IngestResult(true, false, null, detail, tipHeight, tipHash, chainId);
    }

    public static IngestResult reorged(String detail, long tipHeight, String tipHash, String chainId) {
        return new IngestResult(true, true, null, detail, tipHeight, tipHash, chainId);
    }
}
