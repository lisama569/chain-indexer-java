package com.github.lisama569.chainindexerjava.indexer;

/**
 * 一次换链门槛调整的结果反馈。
 *
 * @param effectiveValue 实际生效的门槛值（非法输入已回退安全默认）
 * @param tookOver       本次调整是否真实完成了一次换链接管；
 *                       为 false 时索引数据保证完全未变
 * @param detail         人类可读的判定说明（为何接管 / 为何未接管）
 */
public record ThresholdUpdateResult(int effectiveValue, boolean tookOver, String detail) {
}
