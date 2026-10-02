package com.github.lisama569.chainindexerjava.ingest;

/**
 * 一次运行时换链门槛调整的结果，可解释。
 * <p>
 * {@code tookOver} 如实反映本次调整后是否真的完成了换链接管：
 * 只有新快照真正整体发布、索引切到竞争链后才为 true；
 * 若达到门槛的候选在重放时被拒绝（如交易哈希冲突）而接管未完成，
 * 则为 false，且索引保持完全不变，{@code detail} 给出可读说明。
 *
 * @param requestedValue 请求的门槛值（非法时与 effectiveValue 不同）
 * @param effectiveValue 实际生效的门槛值（非法输入回退安全默认 1）
 * @param tookOver       本次调整是否真的引发了换链接管
 * @param detail         人类可读的结论说明
 * @param tipHeight      调整/可能的接管完成后规范链最新高度
 * @param tipHash        调整/可能的接管完成后规范链最新区块哈希
 * @param chainId        调整/可能的接管完成后规范链标识
 */
public record ThresholdUpdateResult(
        int requestedValue,
        int effectiveValue,
        boolean tookOver,
        String detail,
        long tipHeight,
        String tipHash,
        String chainId
) {
}
