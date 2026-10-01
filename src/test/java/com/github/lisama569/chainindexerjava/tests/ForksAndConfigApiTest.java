package com.github.lisama569.chainindexerjava.tests;

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
 * 端到端 HTTP：分叉观察列表、换链门槛配置（含运行期调整与非法值回退）、
 * 接管统计（次数/回滚起点/重放块数）对外可见。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class ForksAndConfigApiTest {

    @Autowired
    private MockMvc mvc;

    private String blockJson(long h, String hash, String parent, String chainId, String txHash) {
        return ("""
                {"height":%d,"hash":"%s","parentHash":"%s","chainId":"%s",
                 "transactions":[{"txHash":"%s","from":"alice","to":"bob","value":10,"blockHeight":%d}]}
                """).formatted(h, hash, parent, chainId, txHash, h);
    }

    @Test
    void forksConfigAndReorgStatsAreExposed() throws Exception {
        // 规范链 A：h0, h1
        mvc.perform(post("/api/blocks").contentType(MediaType.APPLICATION_JSON)
                        .content(blockJson(0, "A#h0", "0x0", "chain-A", "t0")))
                .andExpect(jsonPath("$.accepted").value(true));
        mvc.perform(post("/api/blocks").contentType(MediaType.APPLICATION_JSON)
                        .content(blockJson(1, "A#h1", "A#h0", "chain-A", "t1")))
                .andExpect(jsonPath("$.accepted").value(true));

        // 竞争链 B 逐块到达：h1 同高度撞击 -> 观察
        mvc.perform(post("/api/blocks").contentType(MediaType.APPLICATION_JSON)
                        .content(blockJson(1, "B#h1", "A#h0", "chain-B", "bt1")))
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.reason").value("HEIGHT_CONFLICT"));

        // 分叉观察：B 被持续跟踪，默认门槛 1，追平未达接管条件
        mvc.perform(get("/api/forks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reorgMinLead").value(1))
                .andExpect(jsonPath("$.forks[0].tipHash").value("B#h1"))
                .andExpect(jsonPath("$.forks[0].connectedToCanonical").value(true))
                .andExpect(jsonPath("$.forks[0].leadHeight").value(0))
                .andExpect(jsonPath("$.forks[0].takeoverReady").value(false))
                .andExpect(jsonPath("$.snapshot.tipHash").value("A#h1"));

        // 门槛配置可读、可运行期调整
        mvc.perform(get("/api/config")).andExpect(jsonPath("$.reorgMinLead").value(1));
        mvc.perform(post("/api/config/reorg-min-lead").param("value", "2"))
                .andExpect(jsonPath("$.reorgMinLead").value(2));

        // B h2：领先 1 < 门槛 2，按住不发
        mvc.perform(post("/api/blocks").contentType(MediaType.APPLICATION_JSON)
                        .content(blockJson(2, "B#h2", "B#h1", "chain-B", "bt2")))
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.reorg").value(false));
        mvc.perform(get("/api/forks"))
                .andExpect(jsonPath("$.forks[0].leadHeight").value(1))
                .andExpect(jsonPath("$.forks[0].takeoverReady").value(false));

        // B h3：领先 2 达到门槛 -> 接管
        mvc.perform(post("/api/blocks").contentType(MediaType.APPLICATION_JSON)
                        .content(blockJson(3, "B#h3", "B#h2", "chain-B", "bt3")))
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.reorg").value(true))
                .andExpect(jsonPath("$.chainId").value("chain-B"));

        // 接管统计：1 次切换，从 h0 回滚，重放 3 块（B h1..h3）
        mvc.perform(get("/api/metrics"))
                .andExpect(jsonPath("$.reorgs").value(1))
                .andExpect(jsonPath("$.lastReorgForkPoint").value(0))
                .andExpect(jsonPath("$.lastReorgReplayedBlocks").value(3))
                .andExpect(jsonPath("$.lastReorgTipHash").value("B#h3"))
                .andExpect(jsonPath("$.lastReorgChainId").value("chain-B"));

        // 旧链被替换段无残留
        mvc.perform(get("/api/transactions/t1"))
                .andExpect(jsonPath("$.transaction").doesNotExist());
        mvc.perform(get("/api/chain/head"))
                .andExpect(jsonPath("$.head.tipHash").value("B#h3"))
                .andExpect(jsonPath("$.head.chainId").value("chain-B"));

        // 非法门槛值：可读提示 + 退回安全默认 1
        mvc.perform(post("/api/config/reorg-min-lead").param("value", "0"))
                .andExpect(jsonPath("$.reorgMinLead").value(1))
                .andExpect(jsonPath("$.warning").exists());
        mvc.perform(get("/api/config")).andExpect(jsonPath("$.reorgMinLead").value(1));
    }
}
