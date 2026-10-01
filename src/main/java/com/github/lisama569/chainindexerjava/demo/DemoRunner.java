package com.github.lisama569.chainindexerjava.demo;

import com.github.lisama569.chainindexerjava.domain.Block;
import com.github.lisama569.chainindexerjava.indexer.ChainSnapshot;
import com.github.lisama569.chainindexerjava.indexer.IndexerService;
import com.github.lisama569.chainindexerjava.ingest.IngestResult;
import com.github.lisama569.chainindexerjava.metrics.IndexerMetrics;
import com.github.lisama569.chainindexerjava.support.DemoData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动后自动演示：顺序摄入 -> 重复/跳跃/父哈希/冲突拒绝 -> 更长分叉重组。
 * 启用方式：启动参数加 --demo（或 -Dindexer.demo.enabled=true）。
 */
@Component
public class DemoRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoRunner.class);

    private final IndexerService indexer;
    private final IndexerMetrics metrics;
    private final DemoData data;

    public DemoRunner(DemoData data, IndexerService indexer, IndexerMetrics metrics) {
        this.data = data;
        this.indexer = indexer;
        this.metrics = metrics;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean enabled = args.containsOption("demo")
                || Boolean.parseBoolean(System.getProperty("indexer.demo.enabled"));
        if (!enabled) {
            return;
        }
        log.info("==================== 本地链索引演示开始 ====================");

        log.info("---- 阶段1：顺序摄入 chain-A h=0..3 ----");
        for (Block b : data.canonicalChain()) {
            IngestResult r = indexer.ingest(b);
            log.info("结果 accepted={} reason={} tip={} | {}",
                    r.accepted(), r.reason(), r.tipHeight(), r.detail());
        }

        log.info("---- 阶段2：各类异常输入必须被拒绝（原因可区分） ----");
        for (DemoData.Case c : data.rejectionCases()) {
            IngestResult r = indexer.ingest(c.block());
            log.info("[{}] accepted={} reason={} | {}",
                    c.name(), r.accepted(), r.reason(), r.detail());
        }

        log.info("---- 阶段3：chain-B 在 h=1 分叉，抖动后补齐为更长链 ----");
        for (Block b : data.forkBlocks()) {
            IngestResult r = indexer.ingest(b);
            log.info("结果 accepted={} reorg={} reason={} tip={} | {}",
                    r.accepted(), r.reorg(), r.reason(), r.tipHeight(), r.detail());
        }

        ChainSnapshot snap = indexer.currentSnapshot();
        log.info("---- 最终快照 version={} tipHeight={} tipHash={} chainId={} 区块数={} 交易数={}",
                snap.version(), snap.tipHeight(), snap.tipHash(), snap.chainId(),
                snap.blocks().size(), snap.txByHash().size());
        log.info("addrB 相关交易: {}", snap.transactionsOf("addrB"));
        log.info("换链门槛 reorgMinLead={} | 观察中的竞争链: {}",
                indexer.reorgMinLead(), indexer.observedForks());
        log.info(metrics.report());
        log.info("==================== 本地链索引演示结束 ====================");
    }
}
