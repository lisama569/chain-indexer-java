package com.github.lisama569.chainindexerjava.query;

import com.github.lisama569.chainindexerjava.index.IndexStore;
import com.github.lisama569.chainindexerjava.metrics.IndexerMetrics;
import com.github.lisama569.chainindexerjava.model.Block;
import com.github.lisama569.chainindexerjava.model.ChainState;
import com.github.lisama569.chainindexerjava.model.TransactionReceipt;
import com.github.lisama569.chainindexerjava.model.TxRef;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 查询服务：抓取一次不可变快照后，在该快照上完成整个查询，
 * 保证结果内部自洽，并携带确定的版本/高度/链标识。
 *
 * <p>查询只读不可变结构，不获取摄入写锁；即便地址索引很大、扫描耗时较长，
 * 摄入线程仍可继续构建并发布新快照，不会被查询阻塞（多版本快照各自独立）。
 */
@Service
public class QueryService {

    private final IndexStore store;
    private final IndexerMetrics metrics;

    public QueryService(IndexStore store, IndexerMetrics metrics) {
        this.store = store;
        this.metrics = metrics;
    }

    public ChainState snapshot() {
        return store.current();
    }

    public QueryResult<Optional<Block>> blockAtHeight(long height) {
        long start = metrics.startQuery();
        try {
            ChainState s = store.current();
            return QueryResult.of(s.version(), s.tipHeight(), s.chainId(),
                    Optional.ofNullable(s.blocksByHeight().get(height)));
        } finally {
            metrics.endQuery(start);
        }
    }

    public QueryResult<Optional<Block>> blockByHash(String hash) {
        long start = metrics.startQuery();
        try {
            ChainState s = store.current();
            return QueryResult.of(s.version(), s.tipHeight(), s.chainId(),
                    Optional.ofNullable(s.blocksByHash().get(hash)));
        } finally {
            metrics.endQuery(start);
        }
    }

    public QueryResult<Optional<TransactionReceipt>> receipt(String txHash) {
        long start = metrics.startQuery();
        try {
            ChainState s = store.current();
            return QueryResult.of(s.version(), s.tipHeight(), s.chainId(),
                    Optional.ofNullable(s.receipts().get(txHash)));
        } finally {
            metrics.endQuery(start);
        }
    }

    public QueryResult<List<TxRef>> txsByAddress(String address) {
        long start = metrics.startQuery();
        try {
            ChainState s = store.current();
            return QueryResult.of(s.version(), s.tipHeight(), s.chainId(),
                    s.addressIndex().getOrDefault(address, List.of()));
        } finally {
            metrics.endQuery(start);
        }
    }

    public QueryResult<Long> tipHeight() {
        long start = metrics.startQuery();
        try {
            ChainState s = store.current();
            return QueryResult.of(s.version(), s.tipHeight(), s.chainId(), s.tipHeight());
        } finally {
            metrics.endQuery(start);
        }
    }
}
