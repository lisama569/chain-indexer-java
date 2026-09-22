# chain-indexer-java：本地增量链上数据索引服务

用**本地确定性生成的区块数据**模拟链上输入，提供：

- 按高度顺序摄入区块/交易，建立 **地址 → 交易**、**交易哈希 → 回执** 检索索引；
- 重复区块、高度跳跃、父哈希不连续、同高度内容冲突均被**拒绝并返回可区分原因**，绝不静默覆盖；
- 按最长链规则处理**链重组（reorg）**：回滚共同祖先并重放新链，重组期间/之后无旧链残留、无重复计数、无遗漏；短暂分叉抖动不会被误判为重组；
- 摄入与查询并发下的**快照一致性**：每次查询明确对应某个确定高度与链标识，大范围扫描不阻塞摄入；
- 摄入吞吐、拒绝分类计数、重组次数、查询延迟**可观测**。

技术栈：Java 21+、Spring Boot 3.4（仅内存实现，无外部依赖）。

---

## 1. 索引数据模型

| 类型 | 字段 | 说明 |
| --- | --- | --- |
| `Block` | `height, hash, parentHash, chainId, transactions[]` | 区块；`chainId` 为链/分叉来源标识，`transactions` 有序 |
| `Transaction` | `txHash, from, to, value, blockHeight` | 交易；`blockHeight` 以所在区块为准（重组后随之改变） |
| `Receipt` | `txHash, blockHeight, blockHash, success, gasUsed` | 交易回执；本地确定性生成，重组后仍可复现 |

存储与索引分两层：

1. **`ChainStore`（只追加的区块池）**
   - `byHash: hash -> Block`：所有“已见到”的区块（含分叉侧链），内容不可变，重复写入幂等；
   - `hashesByHeight: height -> Set<hash>`：同一高度允许存在多个分叉区块；
   - `blocksOnChain(tipHash)`：沿 `parentHash` 回溯一条链，是重组找回共同祖先与分叉区块的依据。

2. **`ChainSnapshot`（不可变查询快照）**
   - `blocks: height -> Block`：当前**规范链**上的区块；
   - `txByHash: txHash -> Transaction`；
   - `receipts: txHash -> Receipt`；
   - `txHashesByAddr: address -> List<txHash>`：地址相关交易，按高度、区块内序号有序，列表去重；
   - 快照头 `version, tipHeight, tipHash, chainId` 明确标识“这份索引对应哪条链的哪个高度”。

**检索能力**

- 交易哈希 → 交易：`GET /api/transactions/{txHash}`
- 交易哈希 → 回执：`GET /api/receipts/{txHash}`
- 地址 → 交易列表：`GET /api/addresses/{address}/transactions`
- 高度 → 区块：`GET /api/blocks/{height}`；链尖：`GET /api/chain/head`

---

## 2. 摄入校验、拒绝原因与重组/回滚规则

### 2.1 摄入判定顺序（`IndexerService.ingest`，全程单写锁）

1. **形状校验** → 不合法返回 `INVALID_BLOCK`：高度为负、hash/parentHash/chainId 为空、非创世块父哈希为 `0x0`、区块内交易为空或哈希重复；
2. 同哈希区块再次出现但**内容不一致** → `INVALID_BLOCK`（重放/碰撞保护）；
3. 设当前规范链尖为 `tip`：
   - `height ≤ tip` 且哈希与规范块相同 → **`DUPLICATE_BLOCK`**：拒绝、不改变任何数据、交易不重复计数；
   - `height ≤ tip` 且哈希不同 → **`HEIGHT_CONFLICT`**：同高度内容冲突（分叉抖动）。区块**收存**到 `ChainStore` 作为潜在分叉候选，但规范数据**绝不覆盖**；
   - `height = tip+1` 且 `parentHash ≠ tipHash` → **`PARENT_HASH_MISMATCH`**：收存为分叉候选，拒绝延伸；
   - `height = tip+1` 且父哈希连续（创世另判 `0x0`），且交易哈希未与既有交易冲突 → 接受并顺序延伸；
   - `height > tip+1`：收存候选后，沿 `parentHash` 在区块池中回溯：
     - 找不到挂在规范链上的共同祖先 → **`HEIGHT_GAP`**（缺中间块；待后续补齐后再次投递即可重组）；
     - 能连通且候选链**严格更长**（候选高度 > tip）→ 触发重组。

> 说明：由于候选高度已大于 `tip+1`，凡能连通规范链的候选必然严格更长；
> 等长/更短的抖动分叉在 `height ≤ tip` 路径已被归类为 `HEIGHT_CONFLICT`。
> 因此“短暂的分叉抖动”永远不会产生 `reorg=true` 的结论。

### 2.2 拒绝原因对照（彼此可区分）

| reason | 语义 | 是否收存为分叉候选 |
| --- | --- | --- |
| `DUPLICATE_BLOCK` | 同哈希区块已在规范链，幂等拒绝 | 已存在 |
| `HEIGHT_GAP` | 高度跳跃且无法回溯到共同祖先 | 是，待补齐 |
| `PARENT_HASH_MISMATCH` | 下一高度的父哈希与链尖不连续 | 是 |
| `HEIGHT_CONFLICT` | 同高度不同内容，不覆盖 | 是 |
| `INVALID_BLOCK` | 数据形状非法 / 交易哈希冲突 / 同哈希内容不一致 | 否 |

`POST /api/blocks` 返回：`accepted, reorg, reason, detail, tipHeight, tipHash, chainId`，
其中 `detail` 给出关键高度/哈希与判定依据。

### 2.3 重组与回滚规则（最长链）

1. 在区块池 DFS 出候选块所有能落到规范链祖先的父路径，取链尖最高者；
2. 找到**共同祖先**（分叉第一块的父，且该父在规范链上哈希一致）；
3. **回滚**：从空索引开始，按规范链重放到共同祖先高度（等价于丢弃共同祖先之后的全部旧块索引）；
4. **重放**：依次应用分叉块，重建区块/交易/回执/地址索引；
5. 校验重放过程中交易哈希唯一性，任一冲突则整体拒绝（本次提交不改变状态）；
6. 以**整体引用替换**发布新的不可变快照，返回 `reorg=true` 与 forkPoint/重放块数。

一致性保证：重组后旧链分叉段的交易、回执、地址索引全部消失；共同祖先数据保留；
新链交易全部入索引且不重复（测试 `ReorgTest.longerForkTriggersReorgWithRollbackAndReplay` 显式断言）。

---

## 3. 并发一致性语义

- **单写者 + 多读者（MVCC 风格）**：所有摄入/重组由一把 `ReentrantLock` 串行化；
  读者只读取 `volatile ChainSnapshot current` 引用，不加锁。
- **快照不可变**：每次摄入都从上一快照复制出新 Map，冻结为不可变 `ChainSnapshot` 后一次性发布。
  读者拿到快照后，看到的 `tipHeight/tipHash/chainId` 与四类索引内容严格对应同一个瞬间。
- **查询不阻塞摄入**：大范围扫描发生在读者自己的旧快照上，不持写锁；
  摄入照常推进并发布新快照（`ConcurrencySnapshotTest.longRangeQueryDoesNotBlockIngestion` 断言
  慢查询期间摄入 10 块耗时远小于查询耗时）。
- **摄入串行化**：并发下区块按到达顺序判定，不会出现交叉写入；
  `concurrentIngestAndQueryYieldsConsistentSnapshots` 在 400 块摄入 + 3 读者持续全量校验下，
  验证每个快照父子哈希相连、交易与回执成对、地址索引无重复计数。
- 每个查询响应都带 `snapshot{version, tipHeight, tipHash, chainId}`，结论**可解释、可复现**。

> 适用边界：内存实现，快照采用整表复制，面向演示与中小数据量；
> 持久化与增量结构（如区块高度前缀的 B+ 树/环形版本）可在保持同一语义下替换。

---

## 4. 可观测性

`GET /api/metrics`（`IndexerMetrics`）：

- `acceptedBlocks / acceptedTransactions / blocksPerSecond`：摄入吞吐；
- `rejectedByReason`：按五种拒绝原因分类计数；
- `reorgs`：重组次数；
- `queries / queryAvgNanos / queryMaxNanos`：查询量与延迟；
- `snapshot`：当前链尖高度/哈希/链标识。

日志在每次摄入时打印**输入摘要**（高度、哈希、父哈希、chainId、交易哈希列表）与
**判定依据**（accepted/reason/reorg/关键高度哈希）；重组打印 `REORG committed` 行。

---

## 5. 验证方法

### 5.1 单元/集成测试

```bash
mvn test
```

| 测试类 | 覆盖点 |
| --- | --- |
| `NormalIngestTest` | 创世与顺序摄入、地址与回执索引、交易不重复计数、重复提交 `DUPLICATE_BLOCK` |
| `RejectionReasonTest` | `HEIGHT_GAP`、`PARENT_HASH_MISMATCH`、`HEIGHT_CONFLICT`、各类 `INVALID_BLOCK`，断言既有数据不被覆盖 |
| `ReorgTest` | 分叉抖动不重组；跳跃投递先 `HEIGHT_GAP`、补齐后严格更长触发重组；回滚/重放后无旧链残留/重复/遗漏；等长分叉不重组 |
| `ConcurrencySnapshotTest` | 400 块并发摄入下每个快照内部自洽；大范围慢查询不阻塞摄入 |
| `WebApiTest` | HTTP 端到端：摄入/拒绝原因/快照上下文/地址索引/metrics |

测试日志（surefire 输出）逐条打印“输入摘要 + 判定依据”，失败原因可区分。

### 5.2 启动内置演示

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--demo"
```

演示自动执行：顺序摄入 chain-A(h=0..3) → 五类拒绝 → chain-B 分叉（抖动 → 更长链重组），
结束时打印最终快照（预期 `tipHash=B#h5, chainId=chain-B`）、
地址索引（预期 `addrB=[A-tx0,A-tx1,B-tx2..B-tx5]`）与指标汇总。

### 5.3 手工 HTTP 验证

```bash
curl -s -XPOST localhost:8080/api/blocks -H 'Content-Type: application/json' -d '{
  "height":0,"hash":"A#h0","parentHash":"0x0","chainId":"chain-A",
  "transactions":[{"txHash":"t0","from":"alice","to":"bob","value":10,"blockHeight":0}]}'
curl -s localhost:8080/api/chain/head
curl -s localhost:8080/api/addresses/alice/transactions
curl -s localhost:8080/api/receipts/t0
curl -s localhost:8080/api/metrics
```

### 5.4 代码地图

```
domain/        Block / Transaction / Receipt
ingest/        RejectReason / IngestResult（判定结论，可解释）
storage/       ChainStore（只追加区块池，含分叉）
indexer/       IndexerService（校验/拒绝/重组/快照发布）、ChainSnapshot（不可变快照）
metrics/       IndexerMetrics
web/           IndexerController（REST）
support/       DemoData        demo/   DemoRunner
test/support/  ChainGenerator（确定性造块与分叉）
```
