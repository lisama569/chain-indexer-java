package com.github.lisama569.chainindexerjava.indexer;

/**
 * 一条被观察到的竞争链（分叉）状态。
 * <p>
 * 竞争链逐块到达、尚未超过规范链时不会被丢弃，而是以本结构对外可见；
 * 一旦 {@code takeoverReady} 为真，下一次换链评估即会接管（通常已随摄入自动发生）。
 *
 * @param tipHash              竞争链链尖哈希
 * @param tipHeight            竞争链链尖高度
 * @param chainId              竞争链标识
 * @param connectedToCanonical 是否能沿父哈希回溯到规范链上的共同祖先
 * @param leadHeight           链尖相对当前规范链尖的领先块数（可为负）
 * @param takeoverReady        是否已满足换链门槛（连通且领先 >= 门槛）
 */
public record ForkStatus(
        String tipHash,
        long tipHeight,
        String chainId,
        boolean connectedToCanonical,
        long leadHeight,
        boolean takeoverReady
) {
}
