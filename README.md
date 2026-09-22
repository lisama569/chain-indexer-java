# 本地增量链上数据索引服务（chain-indexer-java）

用**本地确定性生成的区块数据**模拟链上输入，提供一个支持增量摄入、链重组（reorg）、
分叉抖动抑制与并发快照查询的内存索引服务。目标是让**分叉、重复提交与并发查询下的
索引结论保持一致且可解释**。

- Java 21 + Spring Boot 3.4，无外部数据库依赖，开箱即用。
- 单写者串行化摄入；版本化、不可变快照 + 原子引用切换实现无锁读与快照一致性。

---

## 1. 快速开始

```bash
mvn spring-boot:run          # 启动服务（默认 8080 端口）
mvn test                     # 运行全部测试（9 个用例，覆盖摄入/拒绝/重组/并发/Web）
```

测试日志会打印每次提交的**输入摘要**（高度、区块哈希前缀、父哈希前缀、交易数、
交易哈希前缀）与**判定依据**（接受/拒绝原因、回滚高度、重组前后末端、链标识）。

---

## 2. 索引数据模型

所有核心结构都是不可变值对象（`record`）：

| 类型 | 含义 | 关键字段 |
| --- | --- | --- |
| `Block` | 区块 | `height, hash, parentHash, transactions[]` |
| `Transaction` | 交易 | `txHash, from, to, amount` |
| `TransactionReceipt` | 交易回执 | `txHash, height, blockHash, status(SUCCESS/FAILURE)` |
| `TxRef` | 地址索引引用 | `txHash, height, blockHash` |
| `ChainState` | **某一时刻的不可变整链快照** | `version, tipHeight, tipHash, chainId` + 四类索引 |

`ChainState` 内维护四张索引表：

1. **高度 → 区块**（`TreeMap`，天然按高度有序）；
2. **区块哈希 → 区块**；
3. **交易哈希 → 回执**；
4. **地址 → 交易引用列表**（列表按高度升序，同一交易同一地址只出现一次）。

`chainId` 是对“高度 0..tip 上每个区块哈希”序列的 SHA-256 摘要：

- 只要规范链上任意一个区块内容变化（包括重组换链），`chainId` 必然改变；
- 内容相同的链必然得到相同的 `chainId`，因此查询结果携带它即可声明
  “我对应哪一条链”。

每次追加 / 重组都会**构造一个全新的 `ChainState`**（`version` 单调递增），
再由 `IndexStore` 做一次 `AtomicReference` 原子发布；旧快照保持不变，
正在扫描旧快照的查询线程不受影响。

### 回执状态规则（模拟）

`amount > 0` → `SUCCESS`；`amount == 0`（生成器约 1/10 概率）→ `FAILURE`。

---

## 3. 摄入规则与可区分的拒绝原因

摄入入口为 `BlockIngestor`，内部用单把写锁串行化所有判定与发布。
顺序判定规则如下（先命中的原因优先返回）：

| 判定顺序 | 条件 | 结论 / `RejectionReason` |
| --- | --- | --- |
| 1 | 该高度已有区块，且哈希相同 | **拒绝：`DUPLICATE_BLOCK`**（安全幂等重复，不重复计数） |
| 2 | 该高度已有区块，但哈希不同 | **拒绝：`CONFLICT_AT_HEIGHT`**（同高度内容冲突，**绝不静默覆盖**） |
| 3 | `height != tipHeight + 1`（空链时要求为 0） | **拒绝：`HEIGHT_GAP`**（高度跳跃） |
| 4 | 高度正确，但 `parentHash != tipHash` | **拒绝：`PARENT_HASH_MISMATCH`**（父哈希不连续） |
| — | 以上均通过 | **接受：`ACCEPTED`**，追加并发布新快照 |

空链的创世块（高度 0）必须声明全零父哈希（64 个 `0`），否则同样判
`PARENT_HASH_MISMATCH`。

返回体 `IngestionOutcome` 含：`status / rejectionReason / detail（判定依据）/
tipHeight / chainId`，HTTP 层将拒绝统一映射为 **409 Conflict**，
原因枚举原样回传，调用方可区分不同失败类型。

分叉候选接口 `POST /api/blocks/fork` 的额外规则：

- 候选哈希若已在规范链上 → 拒绝 `DUPLICATE_BLOCK`；
- 候选恰好衔接当前末端（高度 +1 且父哈希正确）→ 等价于规范追加，结论与走
  `/api/blocks` 完全一致；
- 其余候选进入 `ForkRegistry` 暂存区（按 `hash → block`、
  `parentHash → 子块` 组织），返回 **202 Accepted / `FORK_PARKED`**。

---

## 4. 链重组与回滚规则

### 4.1 什么情况下才算重组

竞争链（分叉候选沿父指针回溯能找到与规范链的**共同祖先**）只有在其末端高度

```
forkTipHeight >= canonicalTipHeight + indexer.reorg-ahead-margin   (默认 margin=1)
```

时，才被确认为“更长的链”，触发重组。

- **等长或更短的竞争链 = 分叉抖动**：只暂存，规范链的末端、链标识、计数
  全部不变。这样短暂的分叉抖动不会被误判成已确认的重组。
- 可用 `indexer.reorg-ahead-margin=2`（或更大）模拟“确认数”语义：
  竞争链必须领先满 N 个区块才换链（见 `ReorgMarginTest`）。
- 回溯不到任何共同祖先的孤立链不会触发重组（无法安全定位回滚点）。

### 4.2 重组执行过程（单原子发布）

1. 沿候选链父指针回溯，定位共同祖先高度/哈希，收集“祖先之后到竞争链末端”
   的新区块序列，得到 `ReorgPlan(commonAncestorHeight, oldTip, newTip, suffix)`；
2. 基于当前快照取 **0..共同祖先** 的前缀（`ChainState.prefixThrough`，
   祖先之后的区块、回执、地址引用全部从新快照中剔除）；
3. 依次重放新链后缀（`withAppended`，重建四类索引）；
4. **一次** `AtomicReference` 原子切换到新快照。

因此：

- 重组期间查询线程只可能看到**完整旧链**或**完整新链**，看不到“回滚一半”
  的中间态（并发测试 `ConcurrentReorgQueryTest` 在一次重组期间执行了上万次
  随机读取并交叉校验，未出现脏读）；
- 重组后旧链区块哈希、旧链交易回执、仅属于旧链的地址引用都从新快照消失，
  新链数据完整存在，地址索引按交易哈希去重——**无旧链残留、无重复计数、
  无遗漏**（`ReorgTest` 对每个地址做了精确计数断言）；
5. 重组完成后清理已被吸收进规范链的分叉候选。

---

## 5. 并发一致性语义

模型可以概括为**单写者 + 多版本不可变快照（MVCC 简化版）**：

- **单写者**：所有摄入/重组判定在 `BlockIngestor` 的写锁内串行完成，避免并发写冲突；
- **无锁读**：`QueryService` 只读 `IndexStore.current()` 返回的不可变快照，
  **不获取写锁**。单次大范围查询（如扫描整个地址索引）只读取不可变结构，
  摄入线程可同时继续构建并发布新快照，因此**大查询不阻塞后续摄入**；
- **快照一致性**：每个查询方法在入口抓取一次快照引用，随后全部数据都来自
  该快照；响应信封 `QueryResult{version, tipHeight, chainId, data}`
  **明确声明该结果对应的确定高度与链标识**。同一信封内的数据必然自洽，
  绝不会出现“区块来自新链、回执来自旧链”的混搭；
- 快照发布是原子的：读线程要么观察到发布前版本，要么观察到发布后版本。

---

## 6. 可观测性

`GET /api/metrics` 导出（`IndexerMetrics`，全部原子计数）：

- 摄入吞吐：`ingestThroughputBlocksPerSec`、`ingestThroughputTxPerSec`、
  `ingestLastElapsedMs`；
- 累计计数：`ingestAcceptedBlocks / ingestAcceptedTransactions /
  ingestRejectedBlocks / ingestForkParkedBlocks`；
- 重组：`reorgs / reorgBlocksRolledBack / reorgBlocksReplayed`；
- 查询延迟：`queryCount / queryAvgLatencyMs / queryMaxLatencyMs`。

---

## 7. HTTP API

| 方法与路径 | 说明 | 成功状态 |
| --- | --- | --- |
| `POST /api/blocks` | 规范顺序摄入一个区块（JSON 即 `Block`） | 200 `ACCEPTED` / 409 `REJECTED` |
| `POST /api/blocks/fork` | 提交分叉候选 | 200 `ACCEPTED`/`REORG_APPLIED`，202 `FORK_PARKED`，409 `REJECTED` |
| `GET /api/blocks/height/{h}` | 按高度查区块 | 200（`data` 可能为 null） |
| `GET /api/blocks/hash/{hash}` | 按哈希查区块 | 200 |
| `GET /api/receipts/{txHash}` | 按交易哈希查回执 | 200 |
| `GET /api/addresses/{address}/transactions` | 地址 → 交易引用列表 | 200 |
| `GET /api/tip` | 当前末端高度 | 200 |
| `GET /api/metrics` | 吞吐/延迟/重组指标 | 200 |

区块提交示例：

```bash
curl -s -X POST localhost:8080/api/blocks \
  -H 'Content-Type: application/json' \
  -d '{"height":0,"hash":"<sha256>","parentHash":"0000...0000",
       "transactions":[{"txHash":"<sha256>","from":"0xADDR0001","to":"0xADDR0002","amount":10}]}'
```

---

## 8. 验证方法（测试清单）

`src/test/java/.../tests/` 下：

| 测试类 | 覆盖点 |
| --- | --- |
| `NormalIngestionTest` | 顺序摄入；四类索引正确；回执状态；链标识随追加变化；地址引用升序；吞吐/查询指标 |
| `RejectedInputTest` | 重复提交 / 同高度冲突 / 高度跳跃 / 父哈希错误 / 非法创世块，**拒绝原因两两可区分**；冲突后原数据不被覆盖；末端不移动 |
| `ReorgTest` | 等长分叉只暂存（抖动）；更长竞争链回滚到共同祖先(A2)并重放；旧链区块/回执/地址引用全部清除；新链完整；地址级精确计数（不重不漏）；重组指标 |
| `ReorgMarginTest` | `margin=2` 时领先 1 个高度仍是抖动，领先满 2 个高度才重组 |
| `ConcurrentSnapshotTest` | 3 查询线程 + 摄入线程并发追加：快照版本/链标识自洽，回执/地址引用可解析、无悬挂无重复；摄入不被查询阻塞 |
| `ConcurrentReorgQueryTest` | 重组进行中上万次并发读取：只能观察到完整旧链或完整新链，区块父子连续，无脏读/重复/残留 |
| `WebApiIntegrationTest` | MockMvc 端到端：200/202/409 状态码、拒绝原因枚举、查询信封字段、分叉暂存与重组响应 |
| `ApplicationTests` | Spring 上下文加载冒烟测试 |

运行：

```bash
mvn test                       # 全部
mvn test -Dtest=ReorgTest      # 单个
```

### 本地生成器

`LocalChainGenerator` 使用固定 seed 的确定性伪随机：

- `generate(seed, n)`：从高度 0 生成 n 个顺序区块；同 seed 逐字节复现；
- `generateFork(parent, forkSeed, n)`：从指定父块分叉（高度衔接、父哈希指向父块，
  但内容不同），用于构造竞争链；
- `blockWithWrongParent / blockWithHeightGap / conflictingBlockAtHeight`：
  分别构造父哈希错误、高度跳跃、同高度内容冲突的畸形输入。

---

## 9. 关键包结构

```
model/       Block, Transaction, TransactionReceipt, TxRef, ChainState(不可变快照)
index/       IndexStore(AtomicReference 版本化快照发布 + 索引查询)
ingest/      BlockIngestor(单写者判定/重组), ForkRegistry(分叉暂存),
             RejectionReason, IngestionOutcome, ReorgPlan
query/       QueryService, QueryResult(快照信封)
simulator/   LocalChainGenerator(本地确定性链数据)
metrics/     IndexerMetrics(吞吐/延迟/重组可观测指标)
config/      IndexerProperties(indexer.reorg-ahead-margin)
web/         IndexerController(HTTP API)
```

> 说明：当前实现为**内存索引**，重启后状态清空（适合本地模拟与测试）；
> 快照与 WAL 持久化不在本版本范围内。
