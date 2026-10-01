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
    /** 最近一次换链的回滚起点（共同祖先高度），-1 表示尚未发生或从创世重放。 */
    private final AtomicLong lastReorgForkPoint = new AtomicLong(-1);
    /** 最近一次换链重放的区块数。 */
    private final AtomicLong lastReorgReplayedBlocks = new AtomicLong();
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

    /**
     * 记录一次换链（重组接管）：回滚到共同祖先 {@code forkPoint}，
     * 并重放 {@code replayedBlocks} 个分叉区块。
     */
    public void recordReorg(long forkPoint, int replayedBlocks) {
        reorgs.incrementAndGet();
        lastReorgForkPoint.set(forkPoint);
        lastReorgReplayedBlocks.set(replayedBlocks);
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
        m.put("lastReorgForkPoint", lastReorgForkPoint.get());
        m.put("lastReorgReplayedBlocks", lastReorgReplayedBlocks.get());
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
