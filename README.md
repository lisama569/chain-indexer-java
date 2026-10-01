# chain-indexer-java：本地增量链上数据索引服务

用**本地确定性生成的区块数据**模拟链上输入，提供：

- 按高度顺序摄入区块/交易，建立 **地址 → 交易**、**交易哈希 → 回执** 检索索引；
- 重复区块、高度跳跃、父哈希不连续、同高度内容冲突均被**拒绝并返回可区分原因**，绝不静默覆盖；
- 按最长链规则处理**链重组（reorg）**：回滚共同祖先并重放新链，重组期间/之后无旧链残留、无重复计数、无遗漏；短暂分叉抖动不会被误判为重组；
- **竞争链逐块到达**：分叉一块一块陆续投递时，候选全程保留并对外可见（`/api/forks`），
  一旦补齐且真正超过当前规范链即**自动接管**；追平或同高度撞击只观察、不换链；
- **可配置的换链门槛**（`indexer.reorg.min-lead`）：竞争链须领先规范链 N 块才允许接管，
  默认 1（严格更长即接管）；非法值明确告警并退回安全默认；支持运行期调整；
- **两条链反复互相超越**：连续多次接管下任何时刻只暴露唯一规范链，
  接管次数/最近回滚起点/重放块数可从 `/api/metrics` 直接读出；
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
   - `height ≤ tip` 且哈希不同 → 收存为分叉候选后**统一做换链评估**（见 2.3）：
     评估未触发接管则返回 **`HEIGHT_CONFLICT`**（同高度冲突/分叉抖动，规范数据绝不覆盖）；
   - `height = tip+1` 且 `parentHash ≠ tipHash` → 收存候选并做换链评估：
     未触发接管则返回 **`PARENT_HASH_MISMATCH`**；
   - `height = tip+1` 且父哈希连续（创世另判 `0x0`），且交易哈希未与既有交易冲突 → 接受并顺序延伸；
   - `height > tip+1`：收存候选后做换链评估：
     - 能连通规范链且领先达到换链门槛 → **立即接管（重组）**；
     - 能连通但领先不足门槛 → **`FORK_NOT_AHEAD`**（按住不发，继续观察，候选保留）；
     - 无法沿 `parentHash` 回溯到规范链共同祖先 → **`HEIGHT_GAP`**（缺中间块，待补齐）。

> **换链评估（每次收存候选后都会执行）**：扫描区块池中所有高于链尖的已见区块，
> 找出能沿父哈希连通规范链共同祖先的**最长竞争分叉**；其链尖领先规范链尖
> `>= indexer.reorg.min-lead` 时立即接管。因此竞争链**逐块/乱序到达**时，
> 分叉一旦补齐并真正超过当前链就自动接管，候选信息不会因"暂时没超过"而丢失。
> 追平（等长）或同高度撞击的分叉领先为 0，永远达不到正数门槛，只会被继续观察。

### 2.2 拒绝原因对照（彼此可区分）

| reason | 语义 | 是否收存为分叉候选 |
| --- | --- | --- |
| `DUPLICATE_BLOCK` | 同哈希区块已在规范链，幂等拒绝 | 已存在 |
| `HEIGHT_GAP` | 高度跳跃且无法回溯到共同祖先 | 是，待补齐 |
| `PARENT_HASH_MISMATCH` | 下一高度的父哈希与链尖不连续 | 是 |
| `HEIGHT_CONFLICT` | 同高度不同内容，不覆盖 | 是 |
| `FORK_NOT_AHEAD` | 竞争链已连通但领先不足换链门槛，继续观察 | 是 |
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

### 2.4 换链门槛（`indexer.reorg.min-lead`）

| 项 | 说明 |
| --- | --- |
| 含义 | 竞争链链尖必须比当前规范链尖**至少领先该值**，才允许接管（重组） |
| 默认值 | `1`：竞争链只要**严格更长**（哪怕只多 1 块）就自动接管 |
| 非法值处理 | 小于 1 或无法解析（如 `abc`）：日志打印**明确告警**并退回安全默认 `1`，绝不静默采用错误值 |
| 配置方式 | `application.properties` 或启动参数 `--indexer.reorg.min-lead=2` |
| 运行期调整 | `POST /api/config/reorg-min-lead?value=2`，立即对后续摄入判定生效；非法值返回可读 `warning` 并退回安全默认 |
| 查询当前值 | `GET /api/config` |

行为示例（门槛 = 2）：竞争链逐块到达，领先 1 块时摄入返回 `PARENT_HASH_MISMATCH`/`FORK_NOT_AHEAD`
（按住不发、候选保留、`/api/forks` 中 `takeoverReady=false`）；再到达一块使领先达到 2 时，
当次提交即完成接管。门槛调高后领先不足绝不换链；门槛调低后，下一个到达（或重投）的候选块
即按新门槛重新判定。

**与既有规则的兼容关系**

- **同高度抖动不换链**：抖动块高度 `≤ tip`，领先为 0 或负数，达不到任何正数门槛，
  依旧归类 `HEIGHT_CONFLICT`，只收存观察（`ReorgTest.shortForkJitterIsNotConfirmedAsReorg` 不回归）；
- **等长分叉不换链**：等长分叉领先为 0，同样永远达不到门槛
  （`ReorgTest.equalLengthForkDoesNotReorg` 不回归）；
- 门槛 = 1（默认）时，语义与"严格更长即重组"完全一致，只是接管评估在**每次收存候选后**都会执行，
  因此逐块到达的竞争链在"补齐且首次严格更长"的那一块到达时即完成接管，
  无需再次重投链尖（`ReorgTest.longerForkTriggersReorgWithRollbackAndReplay` 的接管点相应提前，
  回滚/重放/无残留的一致性断言全部保持）。

### 2.5 竞争链观察与反复超越

- **`GET /api/forks`**：列出区块池中所有"非规范链尖"的竞争链状态：
  `tipHash / tipHeight / chainId / connectedToCanonical / leadHeight / takeoverReady`，
  并附当前换链门槛与快照头。逐块到达、暂未超过的竞争链在这里持续可见；
  被替换下去的旧链尖也会作为落后分叉继续被观察（这正是它能日后反超回来的基础）。
- **反复互相超越**：链 A、链 B 交替延伸时，每次反超都走同一条"回滚到共同祖先 + 重放"路径，
  任何时刻快照只含唯一一条规范链；共同祖先段保留，被替换段无残留、无重复计数、
  地址索引不指向已回滚交易（`AlternatingChainsTest` 覆盖连续 3 次/6 次接管）。
- **接管统计**（`GET /api/metrics`）：`reorgs`（接管次数）、`lastReorgForkPoint`
  （最近一次回滚起始高度）、`lastReorgReplayedBlocks`（重放块数）、
  `lastReorgTipHash / lastReorgChainId`。

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
- `rejectedByReason`：按六种拒绝原因分类计数（含 `FORK_NOT_AHEAD`）；
- `reorgs`：接管（重组）次数；
- `lastReorgForkPoint / lastReorgReplayedBlocks / lastReorgTipHash / lastReorgChainId`：
  最近一次接管从哪个高度开始回滚、重放了多少块、新链尖是谁；
- `queries / queryAvgNanos / queryMaxNanos`：查询量与延迟；
- `snapshot`：当前链尖高度/哈希/链标识。

其他对外接口：

- `GET /api/forks`：观察中的竞争链列表 + 当前换链门槛（见 2.5）；
- `GET /api/config`：当前换链门槛；
- `POST /api/config/reorg-min-lead?value=N`：运行期调整门槛（非法值返回 `warning` 并退回安全默认）。

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
| `CompetingChainTest` | 竞争链**逐块到达**最终自动接管；追平/同高度撞击不换链；门槛调高后领先不足按住不发（`FORK_NOT_AHEAD` 可区分）；门槛运行期调整后再次判定生效；非法门槛退回安全默认 |
| `AlternatingChainsTest` | 链 A/B **反复互相超越**（连续 3 次/6 次接管）的索引一致性：不混链、无残留、无重复计数、地址索引无悬空引用；连续切换中并发查询不脏读；接管统计（次数/回滚起点/重放块数）准确 |
| `ConcurrencySnapshotTest` | 400 块并发摄入下每个快照内部自洽；大范围慢查询不阻塞摄入 |
| `WebApiTest` | HTTP 端到端：摄入/拒绝原因/快照上下文/地址索引/metrics |
| `ForksAndConfigApiTest` | HTTP 端到端：`/api/forks` 分叉观察、`/api/config` 门槛读取与运行期调整、非法值 `warning` 回退、metrics 接管统计、被替换段查询无残留 |

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

新场景（竞争链逐块到达 + 换链门槛）手工验证：

```bash
# 1) 规范链 A 再补一块到 h1
curl -s -XPOST localhost:8080/api/blocks -H 'Content-Type: application/json' -d '{
  "height":1,"hash":"A#h1","parentHash":"A#h0","chainId":"chain-A",
  "transactions":[{"txHash":"t1","from":"alice","to":"bob","value":11,"blockHeight":1}]}'

# 2) 抬高换链门槛为 2（非法值可试 value=0，会返回 warning 并退回 1）
curl -s -XPOST 'localhost:8080/api/config/reorg-min-lead?value=2'
curl -s localhost:8080/api/config

# 3) 竞争链 B 逐块到达：h1 同高度撞击 -> HEIGHT_CONFLICT；h2 领先 1 < 门槛 2，按住不发
curl -s -XPOST localhost:8080/api/blocks -H 'Content-Type: application/json' -d '{
  "height":1,"hash":"B#h1","parentHash":"A#h0","chainId":"chain-B",
  "transactions":[{"txHash":"b1","from":"carol","to":"dave","value":21,"blockHeight":1}]}'
curl -s -XPOST localhost:8080/api/blocks -H 'Content-Type: application/json' -d '{
  "height":2,"hash":"B#h2","parentHash":"B#h1","chainId":"chain-B",
  "transactions":[{"txHash":"b2","from":"carol","to":"dave","value":22,"blockHeight":2}]}'

# 4) 观察中的竞争链：leadHeight=1、takeoverReady=false
curl -s localhost:8080/api/forks

# 5) B h3 到达：领先 2 达到门槛 -> 当次提交即接管（reorg=true）
curl -s -XPOST localhost:8080/api/blocks -H 'Content-Type: application/json' -d '{
  "height":3,"hash":"B#h3","parentHash":"B#h2","chainId":"chain-B",
  "transactions":[{"txHash":"b3","from":"carol","to":"dave","value":23,"blockHeight":3}]}'

# 6) 接管统计与无残留验证：reorgs=1、lastReorgForkPoint=0、lastReorgReplayedBlocks=3；t1 已查不到
curl -s localhost:8080/api/metrics
curl -s localhost:8080/api/transactions/t1
```

### 5.4 代码地图

```
domain/        Block / Transaction / Receipt
ingest/        RejectReason / IngestResult（判定结论，可解释）
storage/       ChainStore（只追加区块池，含分叉；提供 blocksAbove/tips 供换链评估与观察）
indexer/       IndexerService（校验/拒绝/换链评估/重组/快照发布）、ChainSnapshot（不可变快照）、
               ForkStatus（竞争链观察状态）
config/        IndexerProperties（换链门槛：解析、校验、非法值退回安全默认、运行期调整）
metrics/       IndexerMetrics（含接管统计 lastReorg*）
web/           IndexerController（REST：摄入/查询/forks/config/metrics）
support/       DemoData        demo/   DemoRunner
test/support/  ChainGenerator（确定性造块与分叉）、SnapshotAssertions（快照一致性断言）
```
