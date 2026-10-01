package com.github.lisama569.chainindexerjava.tests;

import com.github.lisama569.chainindexerjava.support.ChainGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
