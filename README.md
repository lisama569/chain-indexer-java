# chain-indexer-java：本地增量链上数据索引服务

用**本地确定性生成的区块数据**模拟链上输入，提供：

- 按高度顺序摄入区块/交易，建立 **地址 → 交易**、**交易哈希 → 回执** 检索索引；
- 重复区块、高度跳跃、父哈希不连续、同高度内容冲突均被**拒绝并返回可区分原因**，绝不静默覆盖；
- **竞争链逐块到达自动接管**：分叉链从分叉点之后一块一块陆续投递时，每块都被收存为候选（不当脏数据丢弃、不丢候选信息），每次到达都会重判——一旦竞争链补齐且领先达到**换链门槛**，自动回滚到共同祖先并重放接管；追平或同高度碰撞只观察、不换链；
- 按最长链规则处理**链重组（reorg）**：回滚共同祖先并重放新链，重组期间/之后无旧链残留、无重复计数、无遗漏；短暂分叉抖动不会被误判为重组；两条链反复互相超越时，任何时刻对外只暴露唯一一条规范链；
- **可配置的换链门槛** `indexer.reorg-min-lead`：控制竞争链至少领先多少块才允许接管，默认 1（严格更长即接管），非法值给出明确提示并回退安全默认；
- 摄入与查询并发下的**快照一致性**：每次查询明确对应某个确定高度与链标识，大范围扫描不阻塞摄入，连续换链过程中也读不到"重放了一半"的中间态；
- 摄入吞吐、拒绝分类计数、重组次数（含最近一次回滚起点与重放块数）、查询延迟**可观测**。

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

**数据缺失时的返回语义（四类查询一致）**

查询的数据不存在时（高度尚未摄入 / 超出链尖、交易哈希从未出现、
交易/回执/地址所属段落已被换链替换而不再属于规范链），接口**正常返回 HTTP 200**，
以可判定的空结果表达，绝不抛服务端错误：

- 响应带 `found: false`，对应负载为 `null`（地址查询为 `count: 0` 与空列表）；
- 响应照常携带 `snapshot{version, tipHeight, tipHash, chainId}`——即本次
  “没有该数据”这一结论对应的确定快照高度与链标识，结论可解释、可复现；
- 数据存在时 `found: true`，负载与快照上下文来自同一个快照，严格自洽。

---

## 2. 摄入校验、拒绝原因与重组/回滚规则

### 2.1 摄入判定顺序（`IndexerService.ingest`，全程单写锁）

1. **形状校验** → 不合法返回 `INVALID_BLOCK`：高度为负、hash/parentHash/chainId 为空、非创世块父哈希为 `0x0`、区块内交易为空或哈希重复；
2. 同哈希区块再次出现但**内容不一致** → `INVALID_BLOCK`（重放/碰撞保护）；
3. 设当前规范链尖为 `tip`：
   - `height ≤ tip` 且哈希与规范块相同 → **`DUPLICATE_BLOCK`**：拒绝、不改变任何数据、交易不重复计数；
   - `height ≤ tip` 且哈希不同 → **`HEIGHT_CONFLICT`**：同高度内容冲突（分叉抖动）。区块**收存**到 `ChainStore` 作为分叉候选，规范数据**绝不覆盖**，随后做接管判定（见 2.3）；
   - `height = tip+1` 且 `parentHash ≠ tipHash` → **`PARENT_HASH_MISMATCH`**：收存为分叉候选（多为竞争链的延伸块），拒绝延伸，随后做接管判定；
   - `height = tip+1` 且父哈希连续（创世另判 `0x0`），且交易哈希未与既有交易冲突 → 接受并顺序延伸；
   - `height > tip+1`：收存候选后做接管判定；若无法沿 `parentHash` 回溯到规范链上的共同祖先 → **`HEIGHT_GAP`**（缺中间块；候选保留，后续每块到达都会自动重判）。

> **竞争链逐块到达**：一条从分叉点之后按高度逐块投递的竞争链，每一块的归属都是
> 上面某条"收存候选"路径——候选信息不会丢，也不会被当成脏数据清掉。
> 每次收存后都会立即在区块池中重判：是否存在已连通规范链、且链尖领先达到
> 换链门槛的竞争链。满足则当场接管（`reorg=true`）；追平、同高度碰撞或领先不足
> 时只继续观察，拒绝原因仍按该块与规范链的位置关系给出（可区分）。

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

### 2.3 重组与回滚规则（最长链 + 换链门槛）

1. 每次有候选块收存（或换链门槛被调整）时，在区块池中考察所有高度高于规范链尖的区块，
   沿 `parentHash` 回溯，找出能落到规范链共同祖先（或创世）的**最长竞争链**
   （等长/更短的分叉不参与判定，天然不可能满足门槛）；
2. 若竞争链链尖高度 `≥ tip + reorgMinLead`（换链门槛，见 2.4）则触发接管，否则继续观察；
3. 找到**共同祖先**（分叉第一块的父，且该父在规范链上哈希一致）；
4. **回滚**：从空索引开始，按规范链重放到共同祖先高度（等价于丢弃共同祖先之后的全部旧块索引）；
5. **重放**：依次应用分叉块，重建区块/交易/回执/地址索引；
6. 校验重放过程中交易哈希唯一性，任一冲突则整体拒绝（本次提交不改变状态）；
7. 以**整体引用替换**发布新的不可变快照，返回 `reorg=true`；
   指标记录 `reorgs+1`、最近一次回滚起点 `lastReorgForkPoint` 与重放块数 `lastReorgReplayedBlocks`。

一致性保证：接管后旧链分叉段的交易、回执、地址索引全部消失；共同祖先数据保留；
新链交易全部入索引且不重复。两条链反复互相超越时，被替换下去的链段仍留在区块池中，
当它再次延伸并重新满足门槛时可再次接管——任何时刻快照里只有唯一一条规范链
（`ReorgTest`、`FlipFlopConsistencyTest` 显式断言）。

### 2.4 换链门槛 `indexer.reorg-min-lead`

| 项 | 说明 |
| --- | --- |
| 含义 | 竞争链链尖至少要比当前规范链尖**高出多少块**，才允许回滚接管 |
| 默认值 | `1`：竞争链只要**严格更长**（哪怕只多 1 块）即自动接管——这是默认验收口径 |
| 配置方式 | `application.properties` 中 `indexer.reorg-min-lead=2`，或启动参数 `--indexer.reorg-min-lead=2` |
| 运行时调整 | `POST /api/config/reorg-min-lead?value=2`；调整后**立即重判**已收存的候选：此前因门槛按住不发的竞争链若已满足条件，会当场接管（响应 `tookOver=true`）。`tookOver` **如实反映**本次调整是否真的完成了换链：若接管中途失败（如重放时交易哈希冲突被整体拒绝），索引数据保持完全不变，响应 `tookOver=false` 且 `detail` 给出可读原因，绝不假报成功 |
| 非法值处理 | 非整数、空值、小于 1 的值都会打印**明确可读的 WARN 日志**并回退安全默认 `1`，不会悄悄用错误值继续跑，也不会让进程崩溃 |
| 当前生效值 | `GET /api/metrics` 的 `reorgMinLead` 字段 |

**与既有规则的兼容关系**：门槛最低为 1，即"至少严格更长才换链"是永远成立的底线——

- **同高度抖动不换链**（`HEIGHT_CONFLICT`）：竞争链与规范链在同一高度碰撞时，领先为 0，任何合法门槛下都只收存观察；
- **等长分叉不换链**：竞争链追平规范链尖时领先为 0，同样只观察；
- 把门槛调高（如 2）后，领先 1 块的竞争链会被按住不发、继续盯着，等差距真的够了（或门槛调回）再切。

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
- **连续换链不脏读**：接管时的"回滚 + 重放"在写锁内先构建出完整新快照再一次性发布，
  读者只会看到切换前或切换后的某个完整快照，永远读不到"重放了一半"的中间态；
  `FlipFlopConsistencyTest` 在 6 轮互相超越期间用 3 个读者持续做独立重算校验。
- 每个查询响应都带 `snapshot{version, tipHeight, tipHash, chainId}`，结论**可解释、可复现**。

> 适用边界：内存实现，快照采用整表复制，面向演示与中小数据量；
> 持久化与增量结构（如区块高度前缀的 B+ 树/环形版本）可在保持同一语义下替换。

---

## 4. 可观测性

`GET /api/metrics`（`IndexerMetrics`）：

- `acceptedBlocks / acceptedTransactions / blocksPerSecond`：摄入吞吐；
- `rejectedByReason`：按五种拒绝原因分类计数；
- `reorgs`：换链（重组接管）次数；
- `lastReorgForkPoint`：最近一次换链的回滚起点（共同祖先高度，`-1` 表示尚未发生）；
- `lastReorgReplayedBlocks`：最近一次换链重放的区块数；
- `reorgMinLead`：当前生效的换链门槛（见 2.4）；
- `queries / queryAvgNanos / queryMaxNanos`：查询量与延迟；
- `snapshot`：当前链尖高度/哈希/链标识。

`POST /api/config/reorg-min-lead?value=N`：运行时调整换链门槛，
返回 `reorgMinLead`（生效值）、`tookOver`（本次调整是否**真实**完成了接管）
与 `detail`（可读说明：为何接管 / 为何未接管）。`tookOver=false` 时索引数据
保证完全未变（快照、索引、换链统计均不动）；`tookOver=true` 时随后的查询与
`GET /api/metrics` 的换链统计必然已反映新规范链。

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
| `ReorgTest` | 分叉抖动不重组；跳跃投递先 `HEIGHT_GAP`、逐块补齐后严格更长当场接管；回滚/重放后无旧链残留/重复/遗漏；等长分叉不重组 |
| `CompetingChainTakeoverTest` | 竞争链逐块到达：未反超只观察不丢候选、严格更长自动接管；门槛调高后领先不足按住不发、差距达标再切；门槛调低立即重判并接管；非法门槛回退安全默认 |
| `FlipFlopConsistencyTest` | 两链 6 轮反复互相超越：每轮后索引与"按规范链重算"精确一致（无混链/残留/悬空引用）；连续切换中并发查询读不到中间态 |
| `ConcurrencySnapshotTest` | 400 块并发摄入下每个快照内部自洽；大范围慢查询不阻塞摄入 |
| `WebApiTest` | HTTP 端到端：摄入/拒绝原因/快照上下文/地址索引/metrics；换链门槛与换链统计的对外暴露、非法调整回退默认 |
| `NotFoundQueryTest` | 查询不存在/已被换链替换的区块、交易、回执、地址：HTTP 200 + `found=false` + 负载为空，照常携带快照上下文（高度与链标识），四类查询缺失表现一致 |
| `ThresholdFeedbackTest` | 门槛调整的结果反馈与实际一致：接管成功 `tookOver=true` 且快照/指标同步切换；接管中途失败 `tookOver=false`、索引数据完全不变并给出可读原因；无候选/领先不足时不接管且说明原因 |

只跑新场景：

```bash
mvn test -Dtest=NotFoundQueryTest,ThresholdFeedbackTest,CompetingChainTakeoverTest,FlipFlopConsistencyTest
```

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

换链门槛（含义见 2.4）：

```bash
# 查看当前生效门槛与换链统计
curl -s localhost:8080/api/metrics | grep -E 'reorgMinLead|reorgs|lastReorg'
# 调高门槛为 2（非法值如 0 会回退默认 1 并打印 WARN）
curl -s -XPOST 'localhost:8080/api/config/reorg-min-lead?value=2'
# 以门槛 2 直接启动
mvn spring-boot:run -Dspring-boot.run.arguments="--indexer.reorg-min-lead=2"
```

数据缺失 / 已被换链替换时的查询语义（返回 `found:false` + 空负载 + 快照上下文，均为 HTTP 200）：

```bash
# 高度超出当前链尖：found=false, block=null，snapshot 给出本次结论对应的链尖
curl -s localhost:8080/api/blocks/999
# 交易哈希不存在：found=false, transaction=null
curl -s localhost:8080/api/transactions/no-such-tx
# 回执、地址查询在数据缺失时表现一致
curl -s localhost:8080/api/receipts/no-such-tx
curl -s localhost:8080/api/addresses/nobody/transactions
```

复现“已被换链替换的交易返回可判定空结果”（先有一条规范链，再让竞争链接管）：

```bash
# 1) 顺序摄入 chain-A 的 h=0..1（h=1 含交易 txA1）
curl -s -XPOST localhost:8080/api/blocks -H 'Content-Type: application/json' -d '{
  "height":0,"hash":"A#h0","parentHash":"0x0","chainId":"chain-A",
  "transactions":[{"txHash":"txA0","from":"alice","to":"bob","value":10,"blockHeight":0}]}'
curl -s -XPOST localhost:8080/api/blocks -H 'Content-Type: application/json' -d '{
  "height":1,"hash":"A#h1","parentHash":"A#h0","chainId":"chain-A",
  "transactions":[{"txHash":"txA1","from":"alice","to":"carol","value":20,"blockHeight":1}]}'
# 2) 竞争链 chain-B 从 A#h0 分叉，逐块到达 h=1..2，严格更长后自动接管
curl -s -XPOST localhost:8080/api/blocks -H 'Content-Type: application/json' -d '{
  "height":1,"hash":"B#h1","parentHash":"A#h0","chainId":"chain-B",
  "transactions":[{"txHash":"txB1","from":"dave","to":"erin","value":30,"blockHeight":1}]}'
curl -s -XPOST localhost:8080/api/blocks -H 'Content-Type: application/json' -d '{
  "height":2,"hash":"B#h2","parentHash":"B#h1","chainId":"chain-B",
  "transactions":[{"txHash":"txB2","from":"dave","to":"erin","value":40,"blockHeight":2}]}'
# 3) txA1 已不属于规范链：HTTP 200 + found=false + 快照上下文（chainId=chain-B）
curl -s localhost:8080/api/transactions/txA1
```

门槛调整反馈的含义（`tookOver` 与实际一致，`detail` 可读）：

```bash
# 先把门槛调高到 2，再让竞争链只领先 1 块（被按住），随后调回 1 触发重判：
# - 若接管真实完成：tookOver=true，且 /api/metrics 的 reorgs +1、链尖已切换
# - 若接管未能完成（如重放校验失败）：tookOver=false，索引数据完全不变，detail 说明原因
curl -s -XPOST 'localhost:8080/api/config/reorg-min-lead?value=1'
```

### 5.4 代码地图

```
domain/        Block / Transaction / Receipt
ingest/        RejectReason / IngestResult（判定结论，可解释）
storage/       ChainStore（只追加区块池，含分叉）
indexer/       IndexerService（校验/拒绝/接管判定/重组/快照发布）、ChainSnapshot（不可变快照）、
               ThresholdUpdateResult（门槛调整反馈：生效值/是否真实接管/可读说明）
config/        IndexerProperties（换链门槛配置：解析、校验、非法值回退默认）
metrics/       IndexerMetrics
web/           IndexerController（REST）
support/       DemoData        demo/   DemoRunner
test/support/  ChainGenerator（确定性造块与分叉）
```
