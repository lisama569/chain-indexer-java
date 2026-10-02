package com.github.lisama569.chainindexerjava.tests;

import com.github.lisama569.chainindexerjava.support.ChainGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端 HTTP：正常摄入、拒绝原因、查询携带快照上下文、指标可观测。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WebApiTest {

    @Autowired
    private MockMvc mvc;

    private String genesisJson() {
        return """
                {"height":0,"hash":"A#h0","parentHash":"0x0","chainId":"chain-A",
                 "transactions":[{"txHash":"t0","from":"alice","to":"bob","value":10,"blockHeight":0}]}
                """;
    }

    private String blockJson(long h, String hash, String parent, String txHash) {
        return ("""
                {"height":%d,"hash":"%s","parentHash":"%s","chainId":"chain-A",
                 "transactions":[{"txHash":"%s","from":"alice","to":"carol","value":20,"blockHeight":%d}]}
                """).formatted(h, hash, parent, txHash, h);
    }

    @Test
    void ingestRejectAndQuery() throws Exception {
        mvc.perform(post("/api/blocks").contentType(MediaType.APPLICATION_JSON).content(genesisJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.tipHeight").value(0))
                .andExpect(jsonPath("$.chainId").value("chain-A"));

        // 重复提交：拒绝且原因可区分
        mvc.perform(post("/api/blocks").contentType(MediaType.APPLICATION_JSON).content(genesisJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.reason").value("DUPLICATE_BLOCK"));

        // 高度跳跃
        String jump = blockJson(5, "A#h5", "A#h4", "t5");
        mvc.perform(post("/api/blocks").contentType(MediaType.APPLICATION_JSON).content(jump))
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.reason").value("HEIGHT_GAP"));

        // 顺序块被接受
        mvc.perform(post("/api/blocks").contentType(MediaType.APPLICATION_JSON)
                        .content(blockJson(1, "A#h1", "A#h0", "t1")))
                .andExpect(jsonPath("$.accepted").value(true));

        // 查询区块：响应携带确定快照上下文
        mvc.perform(get("/api/blocks/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.block.hash").value("A#h1"))
                .andExpect(jsonPath("$.snapshot.tipHeight").value(1))
                .andExpect(jsonPath("$.snapshot.tipHash").value("A#h1"))
                .andExpect(jsonPath("$.snapshot.chainId").value("chain-A"))
                .andExpect(jsonPath("$.snapshot.version").exists());

        // 交易 / 回执
        mvc.perform(get("/api/transactions/t1"))
                .andExpect(jsonPath("$.transaction.from").value("alice"))
                .andExpect(jsonPath("$.snapshot.tipHeight").value(1));
        mvc.perform(get("/api/receipts/t1"))
                .andExpect(jsonPath("$.receipt.blockHash").value("A#h1"))
                .andExpect(jsonPath("$.receipt.success").value(true));

        // 地址索引：alice 有 t0、t1 两笔
        mvc.perform(get("/api/addresses/alice/transactions"))
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.snapshot.chainId").value("chain-A"));

        // 指标
        mvc.perform(get("/api/metrics"))
                .andExpect(jsonPath("$.acceptedBlocks").value(2))
                .andExpect(jsonPath("$.rejectedByReason.DUPLICATE_BLOCK").value(1))
                .andExpect(jsonPath("$.rejectedByReason.HEIGHT_GAP").value(1))
                .andExpect(jsonPath("$.queries").exists())
                .andExpect(jsonPath("$.snapshot.tipHeight").value(1));
    }

    @Test
    void thresholdTakeoverFeedbackMatchesRealityWhenReplayFails() throws Exception {
        // 高门槛按住竞争链；该竞争链与共同祖先有重复交易，调低门槛时接管必然失败
        mvc.perform(post("/api/config/reorg-min-lead").param("value", "3"))
                .andExpect(jsonPath("$.tookOver").value(false));

        mvc.perform(post("/api/blocks").contentType(MediaType.APPLICATION_JSON).content("""
                {"height":0,"hash":"W#h0","parentHash":"0x0","chainId":"chain-W",
                 "transactions":[{"txHash":"wdup","from":"alice","to":"bob","value":10,"blockHeight":0}]}
                """)).andExpect(jsonPath("$.accepted").value(true));
        mvc.perform(post("/api/blocks").contentType(MediaType.APPLICATION_JSON).content("""
                {"height":1,"hash":"W#h1","parentHash":"W#h0","chainId":"chain-W",
                 "transactions":[{"txHash":"w1","from":"alice","to":"bob","value":10,"blockHeight":1}]}
                """)).andExpect(jsonPath("$.accepted").value(true));
        mvc.perform(post("/api/blocks").contentType(MediaType.APPLICATION_JSON).content("""
                {"height":2,"hash":"W#h2","parentHash":"W#h1","chainId":"chain-W",
                 "transactions":[{"txHash":"w2","from":"alice","to":"bob","value":10,"blockHeight":2}]}
                """)).andExpect(jsonPath("$.accepted").value(true));

        // 竞争链 X h=1..3：X1 复用共同祖先交易 wdup；门槛 3 下按住
        for (String json : new String[]{
                """
                {"height":1,"hash":"X#h1p","parentHash":"W#h0","chainId":"chain-X",
                 "transactions":[{"txHash":"wdup","from":"alice","to":"bob","value":10,"blockHeight":1}]}
                """,
                """
                {"height":2,"hash":"X#h2p","parentHash":"X#h1p","chainId":"chain-X",
                 "transactions":[{"txHash":"x2","from":"alice","to":"bob","value":10,"blockHeight":2}]}
                """,
                """
                {"height":3,"hash":"X#h3p","parentHash":"X#h2p","chainId":"chain-X",
                 "transactions":[{"txHash":"x3","from":"alice","to":"bob","value":10,"blockHeight":3}]}
                """
        }) {
            mvc.perform(post("/api/blocks").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(jsonPath("$.reorg").value(false));
        }

        // 调低门槛：接管重放冲突，反馈必须如实为 tookOver=false
        mvc.perform(post("/api/config/reorg-min-lead").param("value", "1"))
                .andDo(r -> org.slf4j.LoggerFactory.getLogger(WebApiTest.class).info(
                        "[业务:门槛反馈HTTP] 重放失败 -> {}", r.getResponse().getContentAsString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedValue").value(1))
                .andExpect(jsonPath("$.reorgMinLead").value(1))
                .andExpect(jsonPath("$.tookOver").value(false))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("未能完成")))
                .andExpect(jsonPath("$.snapshot.chainId").value("chain-W"))
                .andExpect(jsonPath("$.snapshot.tipHash").value("W#h2"));

        // 索引保持不变：链尖仍是 chain-W，竞争链数据未入索引
        mvc.perform(get("/api/chain/head"))
                .andExpect(jsonPath("$.head.chainId").value("chain-W"))
                .andExpect(jsonPath("$.head.tipHeight").value(2));
        mvc.perform(get("/api/transactions/x2"))
                .andExpect(jsonPath("$.found").value(false));
        mvc.perform(get("/api/metrics"))
                .andExpect(jsonPath("$.reorgs").value(0));
    }

    @Test
    void reorgThresholdConfigAndSwitchMetricsAreExposed() throws Exception {
        // 默认门槛与换链统计可从指标接口读取
        mvc.perform(get("/api/metrics"))
                .andExpect(jsonPath("$.reorgMinLead").value(1))
                .andExpect(jsonPath("$.reorgs").exists())
                .andExpect(jsonPath("$.lastReorgForkPoint").exists())
                .andExpect(jsonPath("$.lastReorgReplayedBlocks").exists());

        // 运维调高门槛：生效并回显
        mvc.perform(post("/api/config/reorg-min-lead").param("value", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reorgMinLead").value(2))
                .andExpect(jsonPath("$.tookOver").value(false));
        mvc.perform(get("/api/metrics"))
                .andExpect(jsonPath("$.reorgMinLead").value(2));

        // 非法值（<1）：回退安全默认 1，而不是沿用错误值
        mvc.perform(post("/api/config/reorg-min-lead").param("value", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reorgMinLead").value(1));
    }
}
