package com.github.lisama569.chainindexerjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.chainindexerjava.index.IndexStore;
import com.github.lisama569.chainindexerjava.model.Block;
import com.github.lisama569.chainindexerjava.simulator.LocalChainGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 端到端：HTTP 摄入/拒绝/查询信封/指标。每个测试前重置内存索引。 */
@SpringBootTest
@AutoConfigureMockMvc
class WebApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(WebApiIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private LocalChainGenerator generator;
    @Autowired
    private IndexStore store;

    @BeforeEach
    void reset() {
        store.reset();
    }

    @Test
    void ingestQueryAndRejectOverHttp() throws Exception {
        List<Block> chain = generator.generate(3001L, 3);

        for (Block b : chain) {
            mockMvc.perform(post("/api/blocks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(b)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACCEPTED"))
                    .andExpect(jsonPath("$.chainId").isNotEmpty());
        }
        log.info("HTTP 正常摄入 3 个区块成功");

        // 重复 -> 409 + DUPLICATE_BLOCK
        mockMvc.perform(post("/api/blocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(chain.get(1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("DUPLICATE_BLOCK"));

        // 高度跳跃 -> 409 + HEIGHT_GAP
        Block gapped = generator.blockWithHeightGap(chain.get(2), 500L);
        mockMvc.perform(post("/api/blocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(gapped)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.rejectionReason").value("HEIGHT_GAP"));
        log.info("HTTP 重复与跳跃均被拒绝且原因可区分");

        // 查询信封携带 version/tipHeight/chainId
        mockMvc.perform(get("/api/tip"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").isNumber())
                .andExpect(jsonPath("$.tipHeight").value(2))
                .andExpect(jsonPath("$.chainId").isNotEmpty())
                .andExpect(jsonPath("$.data").value(2));

        String txHash = chain.get(0).transactions().get(0).txHash();
        mockMvc.perform(get("/api/receipts/{txHash}", txHash))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.txHash").value(txHash))
                .andExpect(jsonPath("$.data.height").value(0))
                .andExpect(jsonPath("$.chainId").isNotEmpty());

        String address = chain.get(0).transactions().get(0).from();
        mockMvc.perform(get("/api/addresses/{address}/transactions", address))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].txHash").isNotEmpty());

        mockMvc.perform(get("/api/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ingestAcceptedBlocks").value(3))
                .andExpect(jsonPath("$.ingestRejectedBlocks").value(2))
                .andExpect(jsonPath("$.queryCount").isNumber());
        log.info("HTTP 查询信封与指标断言通过");
    }

    @Test
    void forkParkedReturns202AndReorgReturns200() throws Exception {
        List<Block> chainA = generator.generate(4002L, 4); // 高度 0..3
        for (Block b : chainA) {
            mockMvc.perform(post("/api/blocks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(b)))
                    .andExpect(status().isOk());
        }

        // 从 A2 分叉 B3（末端 3 与规范等长）-> 202 FORK_PARKED
        List<Block> forkB = generator.generateFork(chainA.get(2), 404L, 1);
        mockMvc.perform(post("/api/blocks/fork")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(forkB.get(0))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("FORK_PARKED"));

        // B4 末端 4 领先 -> 200 REORG_APPLIED
        List<Block> forkB2 = generator.generateFork(forkB.get(0), 405L, 1);
        mockMvc.perform(post("/api/blocks/fork")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(forkB2.get(0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REORG_APPLIED"))
                .andExpect(jsonPath("$.rollbackToHeight").value(2))
                .andExpect(jsonPath("$.reorgedTo").value(4));

        mockMvc.perform(get("/api/tip"))
                .andExpect(jsonPath("$.tipHeight").value(4))
                .andExpect(jsonPath("$.data").value(4));
        log.info("HTTP 分叉暂存(202)与重组(200)状态码及结论正确");
    }
}
