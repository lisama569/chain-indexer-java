package com.github.lisama569.chainindexerjava.tests;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 业务：数据缺失时的查询语义（HTTP 端到端）。
 * <ul>
 *   <li>高度尚未摄入 / 超出链尖：区块查询 200 + found=false（而不是 500）；</li>
 *   <li>交易从未出现 / 已在换链中被替换：交易、回执查询 200 + found=false；</li>
 *   <li>地址无相关交易：found=false、count=0；</li>
 *   <li>所有空结果都带可读 message 与确定的 snapshot{tipHeight,tipHash,chainId,version}。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class MissingDataQueryTest {

    private static final Logger log = LoggerFactory.getLogger(MissingDataQueryTest.class);

    @Autowired
    private MockMvc mvc;

    private String blockJson(long h, String hash, String parent, String chain,
                             String txHash, String from, String to) {
        return ("""
                {"height":%d,"hash":"%s","parentHash":"%s","chainId":"%s",
                 "transactions":[{"txHash":"%s","from":"%s","to":"%s","value":10,"blockHeight":%d}]}
                """).formatted(h, hash, parent, chain, txHash, from, to, h);
    }

    private void ingest(String json) throws Exception {
        mvc.perform(post("/api/blocks").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
    }

    @Test
    void missingBlockReturnsJudgeableEmptyWithSnapshotContext() throws Exception {
        // 空索引：任何高度都无区块，但结论仍带确定快照（tipHeight=-1）
        mvc.perform(get("/api/blocks/42"))
                .andDo(r -> log.info("[业务:缺失区块查询] 空索引查 h=42 -> status={} body={}",
                        r.getResponse().getStatus(), r.getResponse().getContentAsString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.block").isEmpty())
                .andExpect(jsonPath("$.height").value(42))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.snapshot.tipHeight").value(-1))
                .andExpect(jsonPath("$.snapshot.version").exists());

        // 摄入一块后：未摄入到的高度（9）与负高度都返回可判定空结果
        ingest(blockJson(0, "M#h0", "0x0", "chain-M", "mtx0", "alice", "bob"));
        for (long height : new long[]{9, 1}) {
            mvc.perform(get("/api/blocks/" + height))
                    .andDo(r -> log.info("[业务:缺失区块查询] 链尖=0 查 h={} -> {}",
                            height, r.getResponse().getContentAsString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.found").value(false))
                    .andExpect(jsonPath("$.block").isEmpty())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("没有区块")))
                    .andExpect(jsonPath("$.snapshot.tipHeight").value(0))
                    .andExpect(jsonPath("$.snapshot.tipHash").value("M#h0"))
                    .andExpect(jsonPath("$.snapshot.chainId").value("chain-M"));
        }

        // 存在的高度照常返回
        mvc.perform(get("/api/blocks/0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.block.hash").value("M#h0"));
    }

    @Test
    void missingAndReplacedTransactionReturnJudgeableEmpty() throws Exception {
        // chain-A：h=0(mtx0)、h=1(atx1)
        ingest(blockJson(0, "N#h0", "0x0", "chain-A", "ntx0", "alice", "bob"));
        ingest(blockJson(1, "A#h1", "N#h0", "chain-A", "atx1", "alice", "carol"));

        // 从未出现的交易
        mvc.perform(get("/api/transactions/ghost"))
                .andDo(r -> log.info("[业务:缺失交易查询] 未出现交易 ghost -> {}",
                        r.getResponse().getContentAsString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.transaction").isEmpty())
                .andExpect(jsonPath("$.txHash").value("ghost"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("不存在")))
                .andExpect(jsonPath("$.snapshot.tipHeight").value(1))
                .andExpect(jsonPath("$.snapshot.chainId").value("chain-A"));

        // 回执查询同样可判定为空，与交易查询一致
        mvc.perform(get("/api/receipts/ghost"))
                .andDo(r -> log.info("[业务:缺失回执查询] 未出现交易 ghost -> {}",
                        r.getResponse().getContentAsString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.receipt").isEmpty())
                .andExpect(jsonPath("$.snapshot.tipHash").value("A#h1"));

        // 换链：chain-B 从 h=1 分叉，h=1,2，领先 1 块（默认门槛）当场接管，
        // 接管后 atx1 所在区块被替换，atx1 不再属于规范链
        ingest(blockJson(1, "B#h1p", "N#h0", "chain-B", "btx1", "dave", "bob"));
        ingest(blockJson(2, "B#h2p", "B#h1p", "chain-B", "btx2", "dave", "carol"));

        mvc.perform(get("/api/chain/head"))
                .andExpect(jsonPath("$.head.chainId").value("chain-B"))
                .andExpect(jsonPath("$.head.tipHash").value("B#h2p"));

        // 已被替换掉的交易：空结果 + 快照指向新链
        mvc.perform(get("/api/transactions/atx1"))
                .andDo(r -> log.info("[业务:被替换交易查询] 换链后查旧链 atx1 -> {}",
                        r.getResponse().getContentAsString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.transaction").isEmpty())
                .andExpect(jsonPath("$.snapshot.tipHeight").value(2))
                .andExpect(jsonPath("$.snapshot.tipHash").value("B#h2p"))
                .andExpect(jsonPath("$.snapshot.chainId").value("chain-B"));
        mvc.perform(get("/api/receipts/atx1"))
                .andDo(r -> log.info("[业务:被替换回执查询] 换链后查旧链 atx1 -> {}",
                        r.getResponse().getContentAsString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.receipt").isEmpty())
                .andExpect(jsonPath("$.snapshot.chainId").value("chain-B"));

        // 共同祖先上的交易仍可查到；新链交易齐全
        mvc.perform(get("/api/transactions/ntx0"))
                .andExpect(jsonPath("$.found").value(true));
        mvc.perform(get("/api/transactions/btx1"))
                .andExpect(jsonPath("$.found").value(true));
    }

    @Test
    void addressWithoutTransactionsReturnsJudgeableEmpty() throws Exception {
        ingest(blockJson(0, "Q#h0", "0x0", "chain-Q", "qtx0", "alice", "bob"));

        // 从无交易的地址：与交易/回执缺失语义一致
        mvc.perform(get("/api/addresses/nobody/transactions"))
                .andDo(r -> log.info("[业务:缺失地址索引查询] 地址 nobody -> {}",
                        r.getResponse().getContentAsString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.transactions").isArray())
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.snapshot.tipHeight").value(0))
                .andExpect(jsonPath("$.snapshot.chainId").value("chain-Q"));

        mvc.perform(get("/api/addresses/alice/transactions"))
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.count").value(1));
    }
}
