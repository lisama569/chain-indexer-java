package com.github.lisama569.chainindexerjava.tests;

import com.github.lisama569.chainindexerjava.domain.Block;
import com.github.lisama569.chainindexerjava.indexer.IndexerService;
import com.github.lisama569.chainindexerjava.metrics.IndexerMetrics;
import com.github.lisama569.chainindexerjava.storage.ChainStore;
import com.github.lisama569.chainindexerjava.support.ChainGenerator;
import com.github.lisama569.chainindexerjava.web.IndexerController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 查询不存在 / 已被换链替换的数据：
 * 接口必须正常返回（HTTP 200）可判定的空结果（found=false、负载为 null），
 * 并照常携带本次结论对应的快照上下文（version/tipHeight/tipHash/chainId），
 * 四类查询（区块/交易/回执/地址）在数据缺失时表现一致，绝不抛服务端错误。
 */
class NotFoundQueryTest {

    private static final Logger log = LoggerFactory.getLogger(NotFoundQueryTest.class);

    private final ChainGenerator gen = new ChainGenerator();
    private IndexerService svc;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        svc = new IndexerService(new ChainStore(), new IndexerMetrics());
        mvc = MockMvcBuilders.standaloneSetup(new IndexerController(svc, new IndexerMetrics()))
                .build();
    }

    private void seedChainA(int n) {
        gen.genesisChain("chain-A", n).forEach(svc::ingest);
    }

    @Test
    void blockAtMissingHeightReturnsDecidableEmptyWithSnapshot() throws Exception {
        seedChainA(3); // 规范链 h=0..2

        // 超出当前链尖的高度
        var beyondTip = mvc.perform(get("/api/blocks/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.block").doesNotExist())
                .andExpect(jsonPath("$.snapshot.tipHeight").value(2))
                .andExpect(jsonPath("$.snapshot.tipHash").value(ChainGenerator.hash("chain-A", 2)))
                .andExpect(jsonPath("$.snapshot.chainId").value("chain-A"))
                .andExpect(jsonPath("$.snapshot.version").exists())
                .andReturn();
        log.info("[case] 查询超出链尖的高度 h=10 -> HTTP 200 found=false, body={}",
                beyondTip.getResponse().getContentAsString());

        // 从未摄入过的中间空洞高度（先造一个空洞：直接摄入到 h=5 会被拒，这里用空库验证）
        IndexerService empty = new IndexerService(new ChainStore(), new IndexerMetrics());
        MockMvc emptyMvc = MockMvcBuilders.standaloneSetup(
                new IndexerController(empty, new IndexerMetrics())).build();
        var noData = emptyMvc.perform(get("/api/blocks/0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.block").doesNotExist())
                .andExpect(jsonPath("$.snapshot.tipHeight").value(-1))
                .andExpect(jsonPath("$.snapshot.version").exists())
                .andReturn();
        log.info("[case] 空索引查询 h=0 -> HTTP 200 found=false, body={}",
                noData.getResponse().getContentAsString());

        // 存在的区块仍然正常返回，且快照上下文与之一致
        mvc.perform(get("/api/blocks/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.block.hash").value(ChainGenerator.hash("chain-A", 1)))
                .andExpect(jsonPath("$.snapshot.tipHeight").value(2))
                .andExpect(jsonPath("$.snapshot.chainId").value("chain-A"));
    }

    @Test
    void missingTransactionReceiptAndAddressReturnDecidableEmpty() throws Exception {
        seedChainA(3); // 含交易 chain-A-tx0..tx2

        // 交易哈希不存在
        var tx = mvc.perform(get("/api/transactions/no-such-tx"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.transaction").doesNotExist())
                .andExpect(jsonPath("$.snapshot.tipHeight").value(2))
                .andExpect(jsonPath("$.snapshot.chainId").value("chain-A"))
                .andExpect(jsonPath("$.snapshot.version").exists())
                .andReturn();
        log.info("[case] 查询不存在的交易 -> HTTP 200 found=false, body={}",
                tx.getResponse().getContentAsString());

        // 回执不存在：与交易查询表现一致
        mvc.perform(get("/api/receipts/no-such-tx"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.receipt").doesNotExist())
                .andExpect(jsonPath("$.snapshot.tipHeight").value(2))
                .andExpect(jsonPath("$.snapshot.chainId").value("chain-A"));

        // 地址没有任何交易：count=0、空列表，同样可判定
        mvc.perform(get("/api/addresses/nobody/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.transactions").isArray())
                .andExpect(jsonPath("$.snapshot.tipHeight").value(2))
                .andExpect(jsonPath("$.snapshot.chainId").value("chain-A"));

        // 存在的交易/回执/地址不受影响
        mvc.perform(get("/api/transactions/chain-A-tx1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.transaction.txHash").value("chain-A-tx1"));
        mvc.perform(get("/api/receipts/chain-A-tx1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.receipt.blockHash").value(ChainGenerator.hash("chain-A", 1)));
        mvc.perform(get("/api/addresses/addrA/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.count").value(3));
    }

    @Test
    void transactionReplacedByReorgReturnsDecidableEmpty() throws Exception {
        seedChainA(3); // 规范链 chain-A h=0..2，含 chain-A-tx0..tx2

        // chain-B 从 A#h0 之后分叉，h=1..3 逐块到达，严格更长后接管
        for (Block b : gen.forkChain("chain-B", 0, 3, ChainGenerator.hash("chain-A", 0))) {
            svc.ingest(b);
        }
        // 前置校验：确实发生了换链，chain-A 的 h=1..2 段已被替换
        var head = mvc.perform(get("/api/chain/head"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot.chainId").value("chain-B"))
                .andExpect(jsonPath("$.snapshot.tipHeight").value(3))
                .andReturn();
        log.info("[case] 换链后链尖 -> {}", head.getResponse().getContentAsString());

        // 被替换段落的交易：正常返回可判定的空结果，而不是服务端错误
        var replaced = mvc.perform(get("/api/transactions/chain-A-tx1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.transaction").doesNotExist())
                .andExpect(jsonPath("$.snapshot.tipHeight").value(3))
                .andExpect(jsonPath("$.snapshot.chainId").value("chain-B"))
                .andExpect(jsonPath("$.snapshot.version").exists())
                .andReturn();
        log.info("[case] 查询已被换链替换的交易 chain-A-tx1 -> HTTP 200 found=false, body={}",
                replaced.getResponse().getContentAsString());

        // 被替换段落的回执：同样可判定为空
        mvc.perform(get("/api/receipts/chain-A-tx2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.receipt").doesNotExist())
                .andExpect(jsonPath("$.snapshot.chainId").value("chain-B"));

        // 被替换高度上的区块查询：返回的是新规范链的区块，而不是旧块或报错
        mvc.perform(get("/api/blocks/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.block.hash").value(ChainGenerator.forkHash("chain-B", 1)))
                .andExpect(jsonPath("$.snapshot.chainId").value("chain-B"));

        // 共同祖先（h=0）的交易仍然在索引中
        mvc.perform(get("/api/transactions/chain-A-tx0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.transaction.txHash").value("chain-A-tx0"));

        // 新链交易可查
        mvc.perform(get("/api/transactions/chain-B-tx3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.snapshot.chainId").value("chain-B"));
    }
}
