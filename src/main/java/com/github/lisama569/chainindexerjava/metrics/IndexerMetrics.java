package com.github.lisama569.chainindexerjava.metrics;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 可观测指标（本地内存实现，可通过 /api/metrics 查看）：
 * <ul>
 *   <li>摄入吞吐：已接受区块数、交易数，以及自首次摄入起的每秒速率；</li>
 *   <li>拒绝计数：按 {@link com.github.lisama569.chainindexerjava.ingest.RejectReason} 分类；</li>
 *   <li>重组次数；</li>
 *   <li>查询延迟：次数、平均、最大（纳秒）。</li>
 * </ul>
 */
@Component
public class IndexerMetrics {

    private final AtomicLong acceptedBlocks = new AtomicLong();
    private final AtomicLong acceptedTxs = new AtomicLong();
    private final AtomicLong reorgs = new AtomicLong();
    /** 最近一次换链（接管）信息：-1/空表示尚未发生过。 */
    private volatile long lastReorgForkPoint = -1L;
    private volatile long lastReorgReplayedBlocks = 0L;
    private volatile String lastReorgTipHash = null;
    private volatile String lastReorgChainId = null;
    private final AtomicLong queries = new AtomicLong();
    private final AtomicLong queryTotalNanos = new AtomicLong();
    private final AtomicLong queryMaxNanos = new AtomicLong();
    private final Map<String, AtomicLong> rejected = new ConcurrentHashMap<>();
    private final long bornAtNanos = System.nanoTime();
    private volatile long firstIngestNanos = -1L;

    public void recordAccepted(long txCount) {
        acceptedBlocks.incrementAndGet();
        acceptedTxs.addAndGet(txCount);
        if (firstIngestNanos < 0) {
            firstIngestNanos = System.nanoTime();
        }
    }

    public void recordRejected(String reason) {
        rejected.computeIfAbsent(reason, k -> new AtomicLong()).incrementAndGet();
    }

    public void recordReorg() {
        reorgs.incrementAndGet();
    }

    /**
     * 记录一次换链接管：回滚起始高度（共同祖先）、重放块数、新链尖哈希与链标识。
     * 供对外接口直接暴露"切换过几次、最近一次从哪回滚、重放了多少块"。
     */
    public void recordReorg(long forkPoint, long replayedBlocks, String newTipHash, String newChainId) {
        reorgs.incrementAndGet();
        this.lastReorgForkPoint = forkPoint;
        this.lastReorgReplayedBlocks = replayedBlocks;
        this.lastReorgTipHash = newTipHash;
        this.lastReorgChainId = newChainId;
    }

    public long startQuery() {
        return System.nanoTime();
    }

    public void endQuery(long startNanos) {
        long cost = System.nanoTime() - startNanos;
        queries.incrementAndGet();
        queryTotalNanos.addAndGet(cost);
        queryMaxNanos.accumulateAndGet(cost, Math::max);
    }

    private double blocksPerSecond() {
        long first = firstIngestNanos;
        long n = acceptedBlocks.get();
        if (first < 0 || n == 0) {
            return 0.0;
        }
        double seconds = Math.max((System.nanoTime() - first) / 1_000_000_000.0, 1e-9);
        return n / seconds;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("acceptedBlocks", acceptedBlocks.get());
        m.put("acceptedTransactions", acceptedTxs.get());
        m.put("blocksPerSecond", String.format("%.3f", blocksPerSecond()));
        m.put("reorgs", reorgs.get());
        m.put("lastReorgForkPoint", lastReorgForkPoint);
        m.put("lastReorgReplayedBlocks", lastReorgReplayedBlocks);
        m.put("lastReorgTipHash", lastReorgTipHash == null ? "" : lastReorgTipHash);
        m.put("lastReorgChainId", lastReorgChainId == null ? "" : lastReorgChainId);
        Map<String, Long> rej = new LinkedHashMap<>();
        rejected.forEach((k, v) -> rej.put(k, v.get()));
        m.put("rejectedByReason", rej);
        long q = queries.get();
        m.put("queries", q);
        m.put("queryAvgNanos", q == 0 ? 0 : queryTotalNanos.get() / q);
        m.put("queryMaxNanos", queryMaxNanos.get());
        m.put("uptimeSeconds", String.format("%.3f",
                (System.nanoTime() - bornAtNanos) / 1_000_000_000.0));
        return m;
    }

    public String report() {
        StringBuilder sb = new StringBuilder("metrics: ");
        snapshot().forEach((k, v) -> sb.append(k).append('=').append(v).append(' '));
        return sb.toString().trim();
    }
}
