package com.github.lisama569.chainindexerjava.tests;

import com.github.lisama569.chainindexerjava.config.IndexerProperties;
import com.github.lisama569.chainindexerjava.domain.Block;
import com.github.lisama569.chainindexerjava.indexer.ChainSnapshot;
import com.github.lisama569.chainindexerjava.indexer.ForkStatus;
import com.github.lisama569.chainindexerjava.indexer.IndexerService;
import com.github.lisama569.chainindexerjava.ingest.IngestResult;
import com.github.lisama569.chainindexerjava.ingest.RejectReason;
import com.github.lisama569.chainindexerjava.metrics.IndexerMetrics;
import com.github.lisama569.chainindexerjava.storage.ChainStore;
import com.github.lisama569.chainindexerjava.support.ChainGenerator;
import com.github.lisama569.chainindexerjava.support.SnapshotAssertions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 竞争链逐块到达 + 换链门槛：
 * 1. 分叉一块一块陆续到达，过程中只被观察、不丢候选，补齐且严格更长后自动接管；
 * 2. 追平/同高度撞击不换链；
 * 3. 门槛调高后领先不足不换链（FORK_NOT_AHEAD 可区分），差距够了立即切；
 * 4. 门槛运行期调整后对后续判定生效；非法门槛退回安全默认。
 */
class CompetingChainTest {

    private static final Logger log = LoggerFactory.getLogger(CompetingChainTest.class);
    private final ChainGenerator gen = new ChainGenerator();

    private IndexerService seedChainA(IndexerService svc, int n) {
        gen.genesisChain("chain-A", n).forEach(svc::ingest);
        return svc;
    }

    /** chain-B 从 chain-A 的 forkPoint 高度之后分叉，构造 forkLen 个块（h=forkPoint+1..）。 */
    private List<Block> forkB(int forkPoint, int forkLen) {
        return gen.chain("chain-B", forkPoint + 1, forkLen, ChainGenerator.hash("chain-A", forkPoint));
    }

    @Test
    void blockByBlockCompetingChainTakesOverAutomatically() {
        IndexerService svc = seedChainA(new IndexerService(new ChainStore(), new IndexerMetrics()), 4);
        List<Block> fork = forkB(1, 4); // h2..h5，逐块到达

        // h2、h3：同高度撞击 -> 仅观察，不换链，候选入池
        IngestResult r2 = svc.ingest(fork.get(0));
        assertEquals(RejectReason.HEIGHT_CONFLICT, r2.reason());
        IngestResult r3 = svc.ingest(fork.get(1));
        assertEquals(RejectReason.HEIGHT_CONFLICT, r3.reason());
        assertEquals(ChainGenerator.hash("chain-A", 3), svc.currentSnapshot().tipHash(),
                "追平阶段不得换链");

        // 观察中的分叉对外可见：已连通、尚未领先、未达接管条件
        List<ForkStatus> observed = svc.observedForks();
        assertEquals(1, observed.size(), "分叉候选必须被持续观察而不是丢弃");
        ForkStatus f = observed.get(0);
        assertEquals(ChainGenerator.hash("chain-B", 3), f.tipHash());
        assertTrue(f.connectedToCanonical());
        assertEquals(0, f.leadHeight(), "与规范链追平，领先为 0");
        assertFalse(f.takeoverReady());

        // h4：竞争链首次严格更长（领先 1，达到默认门槛）-> 当次提交即自动接管
        IngestResult r4 = svc.ingest(fork.get(2));
        log.info("[case] 逐块到达接管: accepted={} reorg={} detail={}", r4.accepted(), r4.reorg(), r4.detail());
        assertTrue(r4.accepted());
        assertTrue(r4.reorg(), "竞争链补齐且严格更长时必须自动接管");
        assertEquals(4, r4.tipHeight());
        assertEquals(ChainGenerator.hash("chain-B", 4), r4.tipHash());
        assertEquals("chain-B", r4.chainId());

        // h5：在新链上顺序延伸
        IngestResult r5 = svc.ingest(fork.get(3));
        assertTrue(r5.accepted());
        assertFalse(r5.reorg());

        ChainSnapshot snap = svc.currentSnapshot();
        SnapshotAssertions.assertInternallyConsistent(snap);
        // 共同祖先保留，被替换段无残留
        assertEquals(ChainGenerator.hash("chain-A", 1), snap.blocks().get(1L).hash());
        assertTrue(snap.transaction("chain-A-tx2").isEmpty(), "旧链被替换段交易必须消失");
        assertTrue(snap.transaction("chain-A-tx3").isEmpty());
        assertTrue(snap.receipt("chain-A-tx2").isEmpty());
        for (long h = 2; h <= 5; h++) {
            assertTrue(snap.transaction("chain-B-tx%d".formatted(h)).isPresent());
        }
        // 地址索引：addrB 在祖先段(chain-A h0,h1) + 新链(chain-B h2..h5)，旧段不得重复计数
        assertEquals(2, snap.transactionsOf("addrB").stream()
                .filter(t -> t.startsWith("chain-A-tx")).count());
        assertEquals(4, snap.transactionsOf("addrB").stream()
                .filter(t -> t.startsWith("chain-B-tx")).count());
        // 被替换的旧链尖仍作为竞争分叉被观察（落后 2 块、未达接管条件），候选信息不丢
        List<ForkStatus> after = svc.observedForks();
        assertEquals(1, after.size());
        assertEquals(ChainGenerator.hash("chain-A", 3), after.get(0).tipHash());
        assertEquals(-2, after.get(0).leadHeight());
        assertFalse(after.get(0).takeoverReady());
    }

    @Test
    void tieAtSameHeightDoesNotTakeOver() {
        IndexerService svc = seedChainA(new IndexerService(new ChainStore(), new IndexerMetrics()), 4);
        List<Block> fork = forkB(1, 2); // h2,h3：追平但不超越

        svc.ingest(fork.get(0));
        IngestResult r = svc.ingest(fork.get(1));
        assertFalse(r.reorg());
        assertEquals(RejectReason.HEIGHT_CONFLICT, r.reason());
        ChainSnapshot snap = svc.currentSnapshot();
        assertEquals(3, snap.tipHeight());
        assertEquals(ChainGenerator.hash("chain-A", 3), snap.tipHash(), "追平不换链");
        assertTrue(snap.transaction("chain-B-tx2").isEmpty(), "观察中的分叉数据不得进入规范索引");

        // 分叉仍在观察列表中，候选信息未丢失
        assertEquals(1, svc.observedForks().size());
        assertFalse(svc.observedForks().get(0).takeoverReady());
    }

    @Test
    void raisedThresholdHoldsTakeoverUntilLeadSatisfied() {
        // 门槛 = 2：竞争链须领先 2 块才允许接管
        IndexerService svc = new IndexerService(new ChainStore(), new IndexerMetrics(),
                new IndexerProperties(2));
        seedChainA(svc, 5); // 规范链 h0..h4
        List<Block> fork = forkB(1, 5); // h2..h6

        svc.ingest(fork.get(0)); // h2 冲突
        svc.ingest(fork.get(1)); // h3 冲突
        svc.ingest(fork.get(2)); // h4 冲突
        IngestResult r5 = svc.ingest(fork.get(3)); // h5：领先 1 < 门槛 2
        assertFalse(r5.reorg());
        assertEquals(RejectReason.PARENT_HASH_MISMATCH, r5.reason());
        assertEquals(ChainGenerator.hash("chain-A", 4), svc.currentSnapshot().tipHash(),
                "领先不足门槛必须按住不发");

        // 再投一个跳跃块 h7（父=h6 未到的暂缺块），先确认连通但不足时的可区分原因：
        // 先补 h6 使分叉领先 2 —— 但门槛 2 下 h6 到达时 lead=2 应立即接管。
        IngestResult r6 = svc.ingest(fork.get(4)); // h6：领先 2，达到门槛
        log.info("[case] 门槛=2 接管: accepted={} reorg={} detail={}", r6.accepted(), r6.reorg(), r6.detail());
        assertTrue(r6.reorg(), "领先达到门槛必须立即接管");
        assertEquals(6, svc.currentSnapshot().tipHeight());
        assertEquals("chain-B", svc.currentSnapshot().chainId());
        SnapshotAssertions.assertInternallyConsistent(svc.currentSnapshot());
    }

    @Test
    void forkNotAheadIsDistinguishableWhenConnectedButShortOfThreshold() {
        // 门槛 = 3：分叉连通且领先 2 时，跳跃提交必须给出 FORK_NOT_AHEAD 而非 HEIGHT_GAP
        IndexerService svc = new IndexerService(new ChainStore(), new IndexerMetrics(),
                new IndexerProperties(3));
        seedChainA(svc, 5); // tip h4
        List<Block> fork = forkB(1, 5); // h2..h6

        svc.ingest(fork.get(4)); // 先跳投 h6 -> 缺中间块 HEIGHT_GAP
        svc.ingest(fork.get(0)); // h2
        svc.ingest(fork.get(1)); // h3
        svc.ingest(fork.get(2)); // h4
        IngestResult r5 = svc.ingest(fork.get(3)); // h5：父哈希不连续，分叉池中尖 h6 领先 2 < 门槛 3
        assertEquals(RejectReason.PARENT_HASH_MISMATCH, r5.reason());
        assertEquals(ChainGenerator.hash("chain-A", 4), svc.currentSnapshot().tipHash(),
                "领先不足门槛必须按住不发");

        // 重投分叉尖 h6（h > tip+1 路径）：已连通但领先 2 < 门槛 3 -> 原因可区分
        IngestResult r6 = svc.ingest(fork.get(4));
        log.info("[case] 领先不足判定: reason={} detail={}", r6.reason(), r6.detail());
        assertEquals(RejectReason.FORK_NOT_AHEAD, r6.reason(),
                "已连通但领先不足门槛必须可区分（不是 HEIGHT_GAP，也不是 INVALID）");
        assertEquals(ChainGenerator.hash("chain-A", 4), svc.currentSnapshot().tipHash());

        // 门槛调低到 2 后，重投分叉尖 h6 即满足条件 -> 接管
        svc.setReorgMinLead(2);
        IngestResult r6b = svc.ingest(fork.get(4));
        log.info("[case] 门槛调低后重投: reorg={} detail={}", r6b.reorg(), r6b.detail());
        assertTrue(r6b.reorg(), "门槛调整后再次判定必须生效");
        assertEquals(6, svc.currentSnapshot().tipHeight());
        assertEquals("chain-B", svc.currentSnapshot().chainId());
        SnapshotAssertions.assertInternallyConsistent(svc.currentSnapshot());
    }

    @Test
    void invalidThresholdFallsBackToSafeDefault() {
        // 构造期非法值
        assertEquals(1, new IndexerProperties(0).reorgMinLead(), "0 非法，退回安全默认 1");
        assertEquals(1, new IndexerProperties(-3).reorgMinLead(), "负数非法，退回安全默认 1");
        assertEquals(2, new IndexerProperties(2).reorgMinLead());

        // 配置项解析：缺失 / 不可解析 / 非法 -> 安全默认；合法 -> 生效
        MockEnvironment empty = new MockEnvironment();
        assertEquals(1, new IndexerProperties(empty).reorgMinLead(), "未配置时使用默认 1");
        MockEnvironment bad = new MockEnvironment()
                .withProperty(IndexerProperties.REORG_MIN_LEAD_KEY, "not-a-number");
        assertEquals(1, new IndexerProperties(bad).reorgMinLead(), "不可解析配置必须退回默认");
        MockEnvironment neg = new MockEnvironment()
                .withProperty(IndexerProperties.REORG_MIN_LEAD_KEY, "0");
        assertEquals(1, new IndexerProperties(neg).reorgMinLead(), "配置 0 必须退回默认");
        MockEnvironment ok = new MockEnvironment()
                .withProperty(IndexerProperties.REORG_MIN_LEAD_KEY, "3");
        assertEquals(3, new IndexerProperties(ok).reorgMinLead(), "合法配置必须真正生效");

        // 运行期非法调整不生效，退回安全默认
        IndexerProperties props = new IndexerProperties(2);
        assertEquals(1, props.setReorgMinLead(0), "非法调整退回安全默认");
        assertEquals(1, props.reorgMinLead());
        assertEquals(4, props.setReorgMinLead(4));
    }
}
