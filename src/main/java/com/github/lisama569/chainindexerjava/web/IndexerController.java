package com.github.lisama569.chainindexerjava.web;

import com.github.lisama569.chainindexerjava.domain.Block;
import com.github.lisama569.chainindexerjava.domain.Receipt;
import com.github.lisama569.chainindexerjava.domain.Transaction;
import com.github.lisama569.chainindexerjava.indexer.ChainSnapshot;
import com.github.lisama569.chainindexerjava.indexer.IndexerService;
import com.github.lisama569.chainindexerjava.ingest.IngestResult;
import com.github.lisama569.chainindexerjava.ingest.ThresholdUpdateResult;
import com.github.lisama569.chainindexerjava.metrics.IndexerMetrics;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("height", height);
            Block block = snap.blockAt(height).orElse(null);
            data.put("found", block != null);
            data.put("block", block);
            if (block == null) {
                data.put("message", ("高度 %d 在当前规范链上没有区块（快照链尖 h=%d, chainId=%s）："
                        + "可能尚未摄入到该高度，或该高度已超出当前链尖；"
                        + "该结论对应下面 snapshot 标识的确定快照").formatted(
                        height, snap.tipHeight(), snap.chainId()));
            }
            return withSnapshot(data);
        } finally {
            metrics.endQuery(t);
        }
    }

    @GetMapping("/transactions/{txHash}")
    public Map<String, Object> transaction(@PathVariable String txHash) {
        long t = metrics.startQuery();
        try {
            ChainSnapshot snap = snapshot();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("txHash", txHash);
            Transaction tx = snap.transaction(txHash).orElse(null);
            data.put("found", tx != null);
            data.put("transaction", tx);
            if (tx == null) {
                data.put("message", missingTxMessage(txHash, snap));
            }
            return withSnapshot(data);
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
            data.put("txHash", txHash);
            Receipt receipt = snap.receipt(txHash).orElse(null);
            data.put("found", receipt != null);
            data.put("receipt", receipt);
            if (receipt != null) {
                data.put("transaction", snap.transaction(txHash).orElse(null));
            } else {
                data.put("message", missingTxMessage(txHash, snap));
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
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("address", address);
            data.put("found", !hashes.isEmpty());
            data.put("count", hashes.size());
            data.put("transactions", txs);
            if (hashes.isEmpty()) {
                data.put("message", ("地址 %s 在当前规范链上没有相关交易（快照链尖 h=%d, chainId=%s）："
                        + "可能从未出现，或其交易所在区块已在换链中被替换").formatted(
                        address, snap.tipHeight(), snap.chainId()));
            }
            return withSnapshot(data);
        } finally {
            metrics.endQuery(t);
        }
    }

    /** 交易/回执缺失时统一的可读说明（含快照链尖与链标识）。 */
    private String missingTxMessage(String txHash, ChainSnapshot snap) {
        return ("交易 %s 在当前规范链上不存在（快照链尖 h=%d, chainId=%s）："
                + "可能从未摄入该交易，或其所在区块已在换链重组中被替换、"
                + "不再属于规范链；该结论对应下面 snapshot 标识的确定快照")
                .formatted(txHash, snap.tipHeight(), snap.chainId());
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> body = new LinkedHashMap<>(this.metrics.snapshot());
        body.put("reorgMinLead", indexerService.reorgMinLead());
        body.put("snapshot", snapshotInfo(snapshot()));
        return body;
    }

    /**
     * 运行时调整换链门槛（运维入口）。非法值回退安全默认并立即按新门槛
     * 重判已收存的竞争链候选；若因此发生接管，响应中 tookOver=true。
     */
    @PostMapping("/config/reorg-min-lead")
    public Map<String, Object> updateReorgMinLead(@RequestParam int value) {
        ThresholdUpdateResult r = indexerService.setReorgMinLead(value);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestedValue", r.requestedValue());
        body.put("reorgMinLead", r.effectiveValue());
        body.put("tookOver", r.tookOver());
        body.put("detail", r.detail());
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
