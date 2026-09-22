package com.github.lisama569.chainindexerjava.web;

import com.github.lisama569.chainindexerjava.ingest.BlockIngestor;
import com.github.lisama569.chainindexerjava.ingest.IngestionOutcome;
import com.github.lisama569.chainindexerjava.metrics.IndexerMetrics;
import com.github.lisama569.chainindexerjava.model.Block;
import com.github.lisama569.chainindexerjava.query.QueryResult;
import com.github.lisama569.chainindexerjava.query.QueryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * HTTP 接口：规范摄入 / 分叉候选摄入 / 区块 / 回执 / 地址索引 / 指标。
 * 拒绝原因同时体现在响应体（{@code rejectionReason}）与 HTTP 状态码上，便于调用方区分。
 */
@RestController
@RequestMapping("/api")
public class IndexerController {

    private final BlockIngestor ingestor;
    private final QueryService queryService;
    private final IndexerMetrics metrics;

    public IndexerController(BlockIngestor ingestor, QueryService queryService, IndexerMetrics metrics) {
        this.ingestor = ingestor;
        this.queryService = queryService;
        this.metrics = metrics;
    }

    @PostMapping("/blocks")
    public ResponseEntity<IngestionOutcome> ingest(@RequestBody Block block) {
        return toResponse(ingestor.ingestCanonical(block));
    }

    @PostMapping("/blocks/fork")
    public ResponseEntity<IngestionOutcome> ingestFork(@RequestBody Block block) {
        return toResponse(ingestor.ingestForkCandidate(block));
    }

    @GetMapping("/blocks/height/{height}")
    public QueryResult<?> blockAtHeight(@PathVariable long height) {
        return queryService.blockAtHeight(height);
    }

    @GetMapping("/blocks/hash/{hash}")
    public QueryResult<?> blockByHash(@PathVariable String hash) {
        return queryService.blockByHash(hash);
    }

    @GetMapping("/receipts/{txHash}")
    public QueryResult<?> receipt(@PathVariable String txHash) {
        return queryService.receipt(txHash);
    }

    @GetMapping("/addresses/{address}/transactions")
    public QueryResult<?> addressTxs(@PathVariable String address) {
        return queryService.txsByAddress(address);
    }

    @GetMapping("/tip")
    public QueryResult<Long> tip() {
        return queryService.tipHeight();
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return metrics.snapshot();
    }

    private ResponseEntity<IngestionOutcome> toResponse(IngestionOutcome outcome) {
        return switch (outcome.status()) {
            case ACCEPTED, REORG_APPLIED -> ResponseEntity.status(HttpStatus.OK).body(outcome);
            case FORK_PARKED -> ResponseEntity.status(HttpStatus.ACCEPTED).body(outcome);
            case REJECTED -> ResponseEntity.status(HttpStatus.CONFLICT).body(outcome);
        };
    }
}
