package com.github.lisama569.chainindexerjava.metrics;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 可观测指标：摄入吞吐（区块/秒、交易/秒、最近一次摄入耗时）与查询延迟
 * （次数、平均/最大延迟），另含拒绝、分叉暂存、重组的计数。
 * 所有计数均为原子计数，摄入与查询线程可安全并发更新。
 */
@Component
public class IndexerMetrics {

    private final long startedAtNanos = System.nanoTime();

    private final AtomicLong acceptedBlocks = new AtomicLong();
    private final AtomicLong acceptedTransactions = new AtomicLong();
    private final AtomicLong rejectedBlocks = new AtomicLong();
    private final AtomicLong forkParkedBlocks = new AtomicLong();
    private final AtomicLong reorgs = new AtomicLong();
    private final AtomicLong blocksRolledBack = new AtomicLong();
    private final AtomicLong blocksReplayed = new AtomicLong();
    private final AtomicLong lastIngestElapsedNanos = new AtomicLong();

    private final AtomicLong queryCount = new AtomicLong();
    private final AtomicLong queryLatencyTotalNanos = new AtomicLong();
    private final AtomicLong queryLatencyMaxNanos = new AtomicLong();

    public void recordAcceptedBlock(long txCount, long elapsedNanos) {
        acceptedBlocks.incrementAndGet();
        acceptedTransactions.addAndGet(txCount);
        lastIngestElapsedNanos.set(elapsedNanos);
    }

    public void recordRejected() {
        rejectedBlocks.incrementAndGet();
    }

    public void recordReorg(long rolledBack, long replayed, long elapsedNanos) {
        reorgs.incrementAndGet();
        blocksRolledBack.addAndGet(rolledBack);
        blocksReplayed.addAndGet(replayed);
        lastIngestElapsedNanos.set(elapsedNanos);
    }

    public void recordForkParked() {
        forkParkedBlocks.incrementAndGet();
    }

    /** 返回查询计时起点（纳秒）。 */
    public long startQuery() {
        return System.nanoTime();
    }

    public void endQuery(long startNanos) {
        long elapsed = System.nanoTime() - startNanos;
        queryCount.incrementAndGet();
        queryLatencyTotalNanos.addAndGet(elapsed);
        queryLatencyMaxNanos.accumulateAndGet(elapsed, Math::max);
    }

    /** 导出指标快照（键名稳定，供 /api/metrics 与文档引用）。 */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        long queries = queryCount.get();
        long latencyTotal = queryLatencyTotalNanos.get();
        double elapsedSeconds = (System.nanoTime() - startedAtNanos) / 1_000_000_000.0d;
        double safeElapsed = Math.max(elapsedSeconds, 0.001d);

        m.put("uptimeSeconds", round3(elapsedSeconds));
        m.put("ingestAcceptedBlocks", acceptedBlocks.get());
        m.put("ingestAcceptedTransactions", acceptedTransactions.get());
        m.put("ingestRejectedBlocks", rejectedBlocks.get());
        m.put("ingestForkParkedBlocks", forkParkedBlocks.get());
        m.put("ingestThroughputBlocksPerSec", round3(acceptedBlocks.get() / safeElapsed));
        m.put("ingestThroughputTxPerSec", round3(acceptedTransactions.get() / safeElapsed));
        m.put("ingestLastElapsedMs", round3(lastIngestElapsedNanos.get() / 1_000_000.0d));
        m.put("reorgs", reorgs.get());
        m.put("reorgBlocksRolledBack", blocksRolledBack.get());
        m.put("reorgBlocksReplayed", blocksReplayed.get());
        m.put("queryCount", queries);
        m.put("queryAvgLatencyMs",
                queries == 0 ? 0.0 : round3((latencyTotal / (double) queries) / 1_000_000.0d));
        m.put("queryMaxLatencyMs", round3(queryLatencyMaxNanos.get() / 1_000_000.0d));
        return m;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0d) / 1000.0d;
    }
}
