package com.github.lisama569.chainindexerjava.web;

import com.github.lisama569.chainindexerjava.domain.Block;
import com.github.lisama569.chainindexerjava.domain.Receipt;
import com.github.lisama569.chainindexerjava.domain.Transaction;
import com.github.lisama569.chainindexerjava.indexer.ChainSnapshot;
import com.github.lisama569.chainindexerjava.indexer.IndexerService;
import com.github.lisama569.chainindexerjava.indexer.ThresholdUpdateResult;
import com.github.lisama569.chainindexerjava.ingest.IngestResult;
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
        ChainSnapshot snap = snapshot();
        return withSnapshot(Map.of("head", headInfo(snap)), snap);
    }

    /**
     * 高度 -> 区块。该高度当前没有规范区块时（尚未摄入 / 超出链尖）正常返回
     * {@code found=false, block=null}，并携带本次结论对应的快照上下文，
     * 绝不抛服务端错误。
     */
    @GetMapping("/blocks/{height}")
    public Map<String, Object> block(@PathVariable long height) {
        long t = metrics.startQuery();
        try {
            ChainSnapshot snap = snapshot();
            Block block = snap.blockAt(height).orElse(null);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("found", block != null);
            data.put("block", block);
            return withSnapshot(data, snap);
        } finally {
            metrics.endQuery(t);
        }
    }

    /**
     * 交易哈希 -> 交易。交易不存在或已被换链替换（不再属于规范链）时正常返回
     * {@code found=false, transaction=null}，与回执/地址查询的缺失表现一致。
     */
    @GetMapping("/transactions/{txHash}")
    public Map<String, Object> transaction(@PathVariable String txHash) {
        long t = metrics.startQuery();
        try {
            ChainSnapshot snap = snapshot();
            Transaction tx = snap.transaction(txHash).orElse(null);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("found", tx != null);
            data.put("transaction", tx);
            return withSnapshot(data, snap);
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
            data.put("found", receipt != null);
            data.put("receipt", receipt);
            if (receipt != null) {
                data.put("transaction", snap.transaction(txHash).orElse(null));
            }
            return withSnapshot(data, snap);
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
            return withSnapshot(data, snap);
        } finally {
            metrics.endQuery(t);
        }
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
     * 重判已收存的竞争链候选。
     * <p>
     * 响应中 {@code tookOver} 如实反映本次调整是否真实完成了换链接管：
     * 若接管未能完成（如重放校验失败），索引数据保持完全不变，
     * {@code tookOver=false} 且 {@code detail} 给出可读原因，绝不假报成功。
     */
    @PostMapping("/config/reorg-min-lead")
    public Map<String, Object> updateReorgMinLead(@RequestParam int value) {
        ThresholdUpdateResult result = indexerService.setReorgMinLead(value);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reorgMinLead", result.effectiveValue());
        body.put("tookOver", result.tookOver());
        body.put("detail", result.detail());
        body.put("snapshot", snapshotInfo(snapshot()));
        return body;
    }

    private ChainSnapshot snapshot() {
        return indexerService.currentSnapshot();
    }

    private Map<String, Object> withSnapshot(Map<String, Object> data) {
        return withSnapshot(data, snapshot());
    }

    /**
     * 组装响应：snapshot 段必须来自与数据结论相同的快照，
     * 保证“结论 + 快照上下文”严格对应同一个瞬间。
     */
    private Map<String, Object> withSnapshot(Map<String, Object> data, ChainSnapshot snap) {
        Map<String, Object> body = new LinkedHashMap<>(data);
        body.put("snapshot", snapshotInfo(snap));
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
