package com.github.lisama569.chainindexerjava.index;

import com.github.lisama569.chainindexerjava.ingest.ReorgPlan;
import com.github.lisama569.chainindexerjava.model.Block;
import com.github.lisama569.chainindexerjava.model.ChainState;
import com.github.lisama569.chainindexerjava.model.TransactionReceipt;
import com.github.lisama569.chainindexerjava.model.TxRef;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 索引存储：持有版本化、不可变的 {@link ChainState} 引用。
 *
 * <p>写入（追加/重组）必须在摄入层的单写锁内调用，写入过程是“基于旧快照构建完整新快照
 * -> {@code compareAndSet} 单次发布”，发布点之前查询只能读到旧快照，发布点之后一律读到
 * 新快照，天然具备快照一致性。读取路径完全无锁，即使大范围扫描也不会阻塞摄入。
 */
@Component
public class IndexStore {

    private final AtomicReference<ChainState> current = new AtomicReference<>(ChainState.empty());
    private final AtomicLong versionSeq = new AtomicLong(0L);

    /** 当前已发布的快照（绝不返回半成品状态）。 */
    public ChainState current() {
        return current.get();
    }

    /** 在规范链末端顺序追加一个区块，原子发布新快照。 */
    public ChainState append(Block block) {
        return publish(current.get().withAppended(block, nextVersion()));
    }

    /**
     * 按重组计划回滚到共同祖先并重放新链后缀，原子发布新快照。
     * 回滚与重放在同一次发布内完成：查询者要么看到完整旧链，要么看到完整新链，
     * 不会观察到“回滚了一半”的中间态，也不会出现旧链数据残留或双重计数。
     */
    public ChainState applyReorg(ReorgPlan plan) {
        ChainState state = current.get();
        long version = nextVersion();
        ChainState rebuilt = state.prefixThrough(plan.commonAncestorHeight(), version);
        for (Block block : plan.newChainSuffix()) {
            rebuilt = rebuilt.withAppended(block, version);
        }
        return publish(rebuilt);
    }

    public Optional<Block> blockAtHeight(long height) {
        return Optional.ofNullable(current.get().blocksByHeight().get(height));
    }

    public Optional<Block> blockByHash(String hash) {
        return Optional.ofNullable(current.get().blocksByHash().get(hash));
    }

    public Optional<TransactionReceipt> receiptByTxHash(String txHash) {
        return Optional.ofNullable(current.get().receipts().get(txHash));
    }

    /** 地址 -> 该地址参与交易的引用列表（高度升序）。 */
    public List<TxRef> txsByAddress(String address) {
        return current.get().addressIndex().getOrDefault(address, List.of());
    }

    /** 测试/管理用：重置为空链。 */
    public void reset() {
        current.set(ChainState.empty());
        versionSeq.set(0L);
    }

    private long nextVersion() {
        return versionSeq.incrementAndGet();
    }

    private ChainState publish(ChainState next) {
        ChainState previous = current.getAndSet(next);
        // 正常情况下没有并发写（摄入层单写锁）；这里用断言保护原子发布前提。
        if (next.version() <= previous.version() && previous != ChainState.empty()) {
            throw new IllegalStateException("non-monotonic snapshot version");
        }
        return next;
    }
}
