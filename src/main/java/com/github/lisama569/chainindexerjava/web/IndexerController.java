package com.github.lisama569.chainindexerjava.web;

import com.github.lisama569.chainindexerjava.domain.Block;
import com.github.lisama569.chainindexerjava.domain.Receipt;
import com.github.lisama569.chainindexerjava.domain.Transaction;
import com.github.lisama569.chainindexerjava.indexer.ChainSnapshot;
import com.github.lisama569.chainindexerjava.indexer.IndexerService;
import com.github.lisama569.chainindexerjava.ingest.IngestResult;
import com.github.lisama569.chainindexerjava.metrics.IndexerMetrics;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 摄入与查询 REST 入口。
 * 每个查询响应都携带 snapshot 段（version/tipHeight/tipHash/chainId），
 * 明确本次结论对应的确定高度与链标识。
 */
@RestController
@RequestMapping("/api")
public class IndexerController {

    private final IndexerService indexerService;
    private final IndexerMetrics metrics;

    public IndexerController(IndexerService indexerService, IndexerMetrics metrics) {
        this.indexerService = indexerService;
        this.metrics = metrics;
    }

    @PostMapping("/blocks")
    public Map<String, Object> ingest(@RequestBody Block block) {
        IngestResult r = indexerService.ingest(block);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accepted", r.accepted());
        body.put("reorg", r.reorg());
        body.put("reason", r.reason() == null ? null : r.reason().name());
        body.put("detail", r.detail());
        body.put("tipHeight", r.tipHeight());
        body.put("tipHash", r.tipHash());
        body.put("chainId", r.chainId());
        return body;
    }

    @GetMapping("/chain/head")
    public Map<String, Object> head() {
        return withSnapshot(Map.of("head", headInfo(snapshot())));
    }

    @GetMapping("/blocks/{height}")
    public Map<String, Object> block(@PathVariable long height) {
        long t = metrics.startQuery();
        try {
            ChainSnapshot snap = snapshot();
            return withSnapshot(Map.of("block", snap.blockAt(height).orElse(null)));
        } finally {
            metrics.endQuery(t);
        }
    }

    @GetMapping("/transactions/{txHash}")
    public Map<String, Object> transaction(@PathVariable String txHash) {
        long t = metrics.startQuery();
        try {
            ChainSnapshot snap = snapshot();
            return withSnapshot(Map.of("transaction", snap.transaction(txHash).orElse(null)));
        } finally {
            metrics.endQuery(t);
        }
    }

    @GetMapping("/receipts/{txHash}")
    public Map<String, Object> receipt(@PathVariable String txHash) {
        long t = metrics.startQuery();
        try {
            ChainSnapshot snap = snapshot();
            Map<String, Object> data = new LinkedHashMap<>();
            Receipt receipt = snap.receipt(txHash).orElse(null);
            data.put("receipt", receipt);
            if (receipt != null) {
                data.put("transaction", snap.transaction(txHash).orElse(null));
            }
            return withSnapshot(data);
        } finally {
            metrics.endQuery(t);
        }
    }

    @GetMapping("/addresses/{address}/transactions")
    public Map<String, Object> addressTransactions(@PathVariable String address) {
        long t = metrics.startQuery();
        try {
            ChainSnapshot snap = snapshot();
            List<String> hashes = snap.transactionsOf(address);
            List<Transaction> txs = hashes.stream()
                    .map(h -> snap.transaction(h).orElse(null))
                    .toList();
            return withSnapshot(Map.of(
                    "address", address,
                    "count", hashes.size(),
                    "transactions", txs));
        } finally {
            metrics.endQuery(t);
        }
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> body = new LinkedHashMap<>(this.metrics.snapshot());
        body.put("snapshot", snapshotInfo(snapshot()));
        return body;
    }

    private ChainSnapshot snapshot() {
        return indexerService.currentSnapshot();
    }

    private Map<String, Object> withSnapshot(Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>(data);
        body.put("snapshot", snapshotInfo(snapshot()));
        return body;
    }

    private Map<String, Object> snapshotInfo(ChainSnapshot snap) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", snap.version());
        m.put("tipHeight", snap.tipHeight());
        m.put("tipHash", snap.tipHash());
        m.put("chainId", snap.chainId());
        return m;
    }

    private Map<String, Object> headInfo(ChainSnapshot snap) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tipHeight", snap.tipHeight());
        m.put("tipHash", snap.tipHash());
        m.put("chainId", snap.chainId());
        m.put("blockCount", snap.blocks().size());
        return m;
    }
}
