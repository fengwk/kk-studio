# Canvas Core 模块

Canvas 编辑器里的每一次拖动、建节点、连线，最终都要落到同一个 durable graph 上；Function 节点还要保存「用哪个模型、引用哪些上游资源、跑到了哪一步」。这些事实的读法、写法与合法性判定必须与数据库、HTTP 和 Provider 无关，否则它们会在多个模块里各写一遍。`canvas-core` 就是这份契约：不可变的 graph 聚合、用户可见的 typed command、版本与幂等语义、Function 能力与执行边界，全部只用 JDK 类型表达。它不连接 PostgreSQL、不装配 Spring、不认识 HTTP DTO；[canvas-infra](canvas-infra.md) 实现它的持久化端口，[platform](platform.md) 在事务里驱动命令与资源生命周期，[web](web.md) 负责 DTO 映射。

模块的生产依赖为空，只有 JUnit 在 test scope（见 [`canvas/core/pom.xml`](../../canvas/core/pom.xml)）。[`CanvasCoreArchitectureTest.java`](../../canvas/core/src/test/java/fun/fengwk/kkstudio/canvas/CanvasCoreArchitectureTest.java) 扫描全部主源码，禁止 `java.sql`、`javax.sql`、`jakarta.persistence`、Spring、MyBatis 以及 Harness、Platform、Share、Web 包前缀，并断言 Catalog 只有一处事实源。

## Graph 聚合

聚合根 [`CanvasDocument`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasDocument.java) 只保存 identity、标题、版本与时间：`version` 从 0 起单调递增，`updatedAt` 不得早于 `createdAt`。它不冗余存储 Thread 或 Session 引用，Canvas 可以持有任意数量 [`CanvasSession`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasSession.java)，全局唯一归属由 `session_owner.session_id` 主键保证（见 [Schema](schema.md)）。

```text
CanvasDocument
├── CanvasResourceNode[]          唯一业务节点形态：普通资源节点或 Function 节点
│   ├── CanvasResource[]          blobId 与 textContent 恰好一个非空
│   ├── CanvasFunction?           modelKey + configJson
│   └── CanvasFunctionRun?        节点当前/最后一次运行
├── CanvasGroup[]                 world 坐标，不嵌套
└── CanvasLink[]                  以 (canvasId, sourceNodeId, targetNodeId) 为身份
```

[`CanvasResourceNode`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasResourceNode.java) 是唯一的节点形态，构造期强制三类一致性：资源必须同属一个 Canvas 且 `ownerNodeId` 指向本节点、同一节点的资源内容类型一致、`run` 只能挂在带 Function 的节点上；没有 Function 的节点必须至少有一个 Resource。Function 节点在首次成功前允许 `resources` 为空——产出资源由 Run 决定。

[`CanvasResource`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasResource.java) 是已完成校验的不可变资源行：`ownerNodeId` 与 `resourceIndex` 同存同缺，`blobId` 与 `textContent` 恰好一个非空。可见资源由 `ownerNodeId + resourceIndex` 直接归属节点；Function Run 物化中的目标资源、以及源节点已删除但仍被 pin 的输入资源，可以暂时无 owner，直到成功挂接或最后一个 pin 释放。

[`CanvasGroup`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasGroup.java) 使用 world 坐标、不嵌套。[`CanvasLink`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasLink.java) 的身份是三元组，只禁止自环。Link 是引用关系而不是自动执行图：真正触发执行的是 Function run service；「target 必须是 Function 节点、source 必须至少拥有一个当前资源」这类跨行规则由执行命令的应用服务判定。几何统一用 [`CanvasTransform`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasTransform.java)，四个值必须有限且宽高为正。

## Typed command 与版本语义

[`CanvasCommand`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasCommand.java) 是 sealed interface，共 15 个子类型，覆盖创建/更新/删除文本节点与资源节点、创建/更新 Function 节点、重命名节点、批量 transform、删除节点、建/删 Link、建组、移动组、解组、删组、改组名。每个子类型在构造期校验非空集合、非负 index、有限坐标与端点关系，并对集合做 defensive copy；`UpdateNodeTransforms` 与 `Ungroup` 还要求成员不重复。

写入口是 [`CanvasCommandService`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasCommandService.java)：

```text
applyCommands(canvasId, expectedVersion, idempotencyKey, commands)
  -> 锁 document 行
  -> 相同 idempotencyKey + 相同 request hash：返回当前版本的空 Patch（精确回放）
  -> 相同 idempotencyKey + 不同 hash：IDEMPOTENCY_CONFLICT
  -> expectedVersion 不匹配当前版本：VERSION_CONFLICT
  -> 应用整批命令、version + 1、写入 dedup
  -> 返回一次连续前进的 CanvasPatch
```

冲突只有两种，由 [`CanvasConflictException`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasConflictException.java) 的 `VERSION_CONFLICT` 与 `IDEMPOTENCY_CONFLICT` 表达，HTTP 层直接映射为 409 语义。[`CanvasPatch`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasPatch.java) 用 `baseVersion -> version` 描述一次前进，Group/Node/Link 各自是 `UPSERT`（完整实体投影）或 `REMOVE`（只带身份）的 sealed 列表；版本未前进时返回空 Patch，因此重放不产生副作用。[`CanvasQueryService.findSnapshot`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasQueryService.java) 返回完整 [`CanvasSnapshot`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasSnapshot.java)：document 加 nodes、groups、links。

锁与 CAS 由 [`CanvasStore`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasStore.java) 以原语暴露，而不是在 Core 内实现：`lockDocument`（`FOR UPDATE`）、`lockDocumentForKeyShare`（`FOR KEY SHARE`，用于 owner 归属的轻量校验）、`lockNode`、`advanceDocumentVersion(expectedVersion, newVersion)`，以及 graph 实体 CRUD 与 `CommandDedup` 读写。接口注释把锁语义定义为跨域事务协议的一部分，实现必须保留 PostgreSQL 当前的行锁行为；[`NodeRecord`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasStore.java) 只承载 Core 领域类型无法表达的可变 graph 行状态。事务边界、SQL 影响行数、`FOR UPDATE` 的实际持有者都在 [canvas-infra](canvas-infra.md)，命令的 hash、dedup 判定与 patch 累积在 [platform](platform.md)。

## Function：能力与执行边界

Function 能力在启动装配时由 [`CanvasFunctionCatalog.from(...)`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionCatalog.java) 冻结成唯一快照：每个 adapter 的 `enabled()`、`unavailableReason()`、`functions()` 各读取一次；function name 去重后按字典序排列；enabled adapter 不得声明 unavailable reason，disabled adapter 必须有非空原因。运行期只通过 `require(name)` 读取已冻结的 `RegisteredFunction`，不存在第二份 registry。

[`CanvasFunctionAdapter`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionAdapter.java) 提供 `functions/enabled/unavailableReason/preflight/submit/execute/cancel`。[`CanvasFunctionDefinition`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionDefinition.java) 声明小写 canonical token 形式的 `name`、`description`、[`CanvasFunctionArgsSchema`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionArgsSchema.java)、有界的输出计划与 [`CanvasFunctionReferencePolicy`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionReferencePolicy.java)。输出计划是 [`CanvasFunctionOutputSpec`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionOutputSpec.java) 列表（1..8 个槽位），每个槽位声明 `kind`（`TEXT|IMAGE|VIDEO|AUDIO`）与可选显式资源名：`TEXT` 槽位以内联文本发布，其余槽位以 Blob 发布；显式名必须在函数内唯一，缺省名由节点名加类型后缀推导（单输出函数用 `CanvasFunctionDefinition.of(...)` 声明）。参数 schema 是严格 JSON Schema 子集（根必须是 object，每一层 object 都必须显式 `additionalProperties: false`，关键字支持 `type/description/properties/required/additionalProperties/enum/default/minimum/maximum/items/minItems/maxItems`，类型支持 `object|string|integer|number|boolean|array|resourceReference`；object 与 array 可递归嵌套，深度上限 8，array 必须声明 `items` 且 `maxItems <= 32`，`resourceReference` 可出现在任意嵌套位置）。

参数与运行状态各有一个严格的 codec port。[`CanvasFunctionArgsCodecPort`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionArgsCodecPort.java) 在解码时按 Function 的 schema 严格校验 `JsonObject` 参数，拒绝未知字段、重复键、null、未声明参数与类型错误，并按引用首次出现顺序生成 manifest；[`CanvasFunctionRunStateCodecPort`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionRunStateCodecPort.java) 冻结完整执行计划（含 args、输出计划、[`CanvasFunctionSubmitState`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionSubmitState.java) 与 adapterState）与 checkpoint，版本号不匹配即拒绝。

Run 启动时冻结的东西写在 [`CanvasFunctionFrozenRun`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionFrozenRun.java) 里：canvas/node identity、function definition、args、manifest、**输出计划**（[`CanvasFunctionFrozenOutput`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionFrozenOutput.java) 按槽位保存预分配的 resource id、节点内 index、kind 与解析后的资源名）、submitState、stage 与 adapter state。[`CanvasFunctionFrozenReference`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionFrozenReference.java) 保存的是启动瞬间的 source node、resource 与 blob identity，外加权威媒体事实快照——因此执行期间 graph link、source 资源或 Function 配置的变化都不会改变该 Run 的输入。所有目标资源先以无 owner 形式物化，成功时再按计划顺序原子挂接。

Adapter 分为两阶段执行：`submit` 提交外部异步任务并返回初始状态；`execute` 查询与物化输出计划槽位，并必须按计划顺序返回 `run.outputResourceIds()`。Adapter 通过 [`CanvasFunctionExecutionContext`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionExecutionContext.java) 拿到最小能力：`checkpoint(stage, adapterState)`、`isRunning()`、只读 frozen 原件的 `openOriginal`/`presignOriginal`，以及只接受计划内槽位的 `materializeOutput(output, stream)`（媒体 Blob）与 `materializeTextOutput(output, text)`（`TEXT` 内联文本）；槽位 id/index/kind 与冻结计划不一致、或物化入口与槽位类型不匹配时立即拒绝。媒体事实与字节走 [`CanvasFunctionBlobAccess`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionBlobAccess.java)，它只暴露 `BlobFacts`、原件 stream 和短期 URL。adapter 看不到数据库、对象 key 或 pin 表。

[`CanvasFunctionRun`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasFunctionRun.java) 的状态字段组合由构造期校验固定：

| status | 允许的字段组合 |
| --- | --- |
| `READY` | 必须有 `availableAt`，不得持有 lease |
| `RUNNING` | 必须持有 `leaseToken`/`leaseUntil`，`availableAt` 为空 |
| `SUCCEEDED` / `CANCELLED` | 三者皆空，终态不可再被 claim |
| `FAILED` / `UNKNOWN` | lease 与 availableAt 皆空，必须持有非空 `error` |

`attempt` 非负，lease token 为 1～128 字符。Run 对资源生命周期的保护由 [`CanvasFunctionResourcePin`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasFunctionResourcePin.java) 表达：`INPUT` 是启动时冻结的引用资源，`OUTPUT` 是预分配目标资源；pin 按 `(canvasId, nodeId, requestId)` 整体释放，不引入通用引用计数。执行中若提交意图已持久化（`SUBMITTING`）但进程崩溃，租约到期后转为 `UNKNOWN` 状态并保留 pin 与目标资源，退出自动调度，绝不重试提交。人工核对后可通过 [`CanvasFunctionService.resolve`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionService.java) 传入 [`CanvasFunctionUnknownResolution`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionUnknownResolution.java)（`RESUME` 回到 READY 继续查询，`FAILED` / `CANCELLED` 释放 pin 与目标资源）。

## 不变量

- `version >= 0` 且每次成功命令批或 Run 状态前进恰好 +1；CAS 失败或命令非法时不得产生部分 Patch。
- Resource 内容 XOR、owner/index 成对必须成立；节点内资源同属一个 Canvas、owner 指向本节点、内容类型一致。
- Function Run 的状态与 available/lease 字段组合必须匹配上表；终态 Run 只允许被新的 requestId 取代，活跃 Run 不允许被第二个 requestId 覆盖。
- Catalog 是启动期冻结快照，运行期函数能力与可用性不再变化；args/state codec 对未知字段、重复字段与版本漂移一律 fail closed。
- Core 只表达可验证的 transition。lease、heartbeat、S3 字节、PostgreSQL 回滚与迟到回调的围栏由 [canvas-infra](canvas-infra.md) 负责；以 token 失效收敛为 no-op 或内部取消。

## 从哪里改

- 领域类型与命令：[`CanvasDocument.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasDocument.java)、[`CanvasResourceNode.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasResourceNode.java)、[`CanvasResource.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasResource.java)、[`CanvasCommand.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasCommand.java)、[`CanvasPatch.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasPatch.java)。
- 端口：[`CanvasStore.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasStore.java)、[`CanvasCommandService.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasCommandService.java)、[`CanvasQueryService.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasQueryService.java)、[`CanvasFunctionService.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionService.java)、[`CanvasResourceLifecycle.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasResourceLifecycle.java)、[`CanvasResourceMaterializer.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasResourceMaterializer.java)。
- Function 能力：[`function` 包](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/)；新增 Provider 能力时改 [platform](platform.md) 的 adapter 实现并让 Catalog 在启动时冻结，而不是在 Core 内加注册表。

测试入口：

- [`CanvasCoreArchitectureTest.java`](../../canvas/core/src/test/java/fun/fengwk/kkstudio/canvas/CanvasCoreArchitectureTest.java) 守卫零生产依赖、包边界与 Catalog 单一事实源；新增第三方 import 会直接失败。
- [`CanvasDomainSmokeTest.java`](../../canvas/core/src/test/java/fun/fengwk/kkstudio/canvas/CanvasDomainSmokeTest.java) 覆盖 document/resource/node 的构造期不变量、集合 defensive copy、group 命令与 Link 身份。
- [`CanvasFunctionCatalogTest.java`](../../canvas/core/src/test/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionCatalogTest.java) 锁定可用性声明与冻结排序；[`CanvasFunctionDefinitionTest.java`](../../canvas/core/src/test/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionDefinitionTest.java)、[`CanvasFunctionArgsSchemaTest.java`](../../canvas/core/src/test/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionArgsSchemaTest.java) 与 [`CanvasFunctionFrozenTest.java`](../../canvas/core/src/test/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionFrozenTest.java) 锁定 Function 定义、参数 Schema 校验与冻结引用的媒体事实。

---

上级：[系统设计](../system-design.md)。相关文档：[Canvas Infra](canvas-infra.md)、[Schema](schema.md)、[Share](share.md)、[Platform](platform.md)。
