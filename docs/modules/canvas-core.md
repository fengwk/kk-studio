# Canvas Core 模块

`canvas-core` 定义 Canvas 的不可变 graph 聚合、typed command、命令规划与冲突语义，以及 Function SPI 与执行边界，全部只用 JDK 类型表达。它不连接 PostgreSQL、不装配 Spring、不认识 HTTP DTO；[canvas-infra](canvas-infra.md) 实现持久化端口与应用服务，[platform](platform.md) 提供宿主 Storage 适配，Platform 或构建期插件提供 Function adapter，[web](web.md) 负责 HTTP/DTO 映射。Canvas 与 Project 的关系、跨域数据约束见 [Canvas / Project](../canvas-project.md)。

模块的生产依赖为空，只有 JUnit 在 test scope（见 [`canvas/core/pom.xml`](../../canvas/core/pom.xml)）。[`CanvasCoreArchitectureTest.java`](../../canvas/core/src/test/java/fun/fengwk/kkstudio/canvas/CanvasCoreArchitectureTest.java) 扫描全部主源码，禁止 `java.sql`、`javax.sql`、`jakarta.persistence`、Spring、MyBatis 以及 Harness、Platform、Share、Web 包前缀，并断言 Catalog 只有一处事实源。

## Graph 聚合

聚合头 [`CanvasDocument`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasDocument.java) 只保存 identity、标题、同步游标与时间：`revision` 从 0 起单调递增，只用于同步排序、补漏和确认接受位置，不是普通编辑的整图前置版本；`updatedAt` 不得早于 `createdAt`。Canvas 独立存在，不冗余存储会话或归属引用（见 [Schema](schema.md)）。

```text
CanvasDocument
├── CanvasResourceNode[]          唯一业务节点形态：普通资源节点或 Function 节点
│   ├── CanvasResource[]          TEXT 内联或 Storage blob 引用（恰好一个非空）
│   ├── CanvasFunction?           {name, args}，args 中保留形如 {"type":"resource","nodeId":"...","index":0} 的引用
│   └── CanvasFunctionRun?        节点当前/最后一次运行
├── CanvasGroup[]                 world 坐标，不嵌套
└── CanvasReference[]             读取投影出的引用连线（由消费节点 args 投影，不单独持久化）
```

[`CanvasResourceNode`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasResourceNode.java) 是唯一的节点形态，构造期强制一致性：资源必须同属一个 Canvas 且 `ownerNodeId` 指向本节点、槽位 `resourceIndex` 从 0 连续非负递增、同一节点的资源内容类型一致（全为文本或全为媒体）；`run` 只能挂在带 Function 的节点上；没有 Function 的普通资源节点必须至少持有一个 Resource。Function 节点在首次成功前允许 `resources` 为空——产出资源由 Run 决定。

[`CanvasResource`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasResource.java) 是已完成校验的不可变资源行：`ownerNodeId` 与 `resourceIndex` 同存同缺，`blobId` 与 `textContent` 恰好一个非空（[`isBlob()`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasResource.java) 与 [`isText()`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasResource.java)）。可见资源由 `ownerNodeId + resourceIndex` 直接归属节点；Function Run 物化中的目标资源、被其他 Run pin 的输入资源或源节点已删除的历史资源可以暂时无 owner（[`withoutOwner()`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasResource.java)），直到成功挂接或最后一个 pin 释放。

[`CanvasGroup`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasGroup.java) 使用 world 坐标、不嵌套。几何统一用 [`CanvasTransform`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasTransform.java)，坐标有限且宽高为正。

引用连线不单独持久化。[`CanvasResourceReference`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasResourceReference.java) 是 `function.args` 中的核心保留资源引用值（`{"type":"resource","nodeId":"...","index":0}`），指向同画布源节点的 UUID 与从零开始的输出位置。[`CanvasReference`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasReference.java) 是读取时投影出的引用连线，禁止自环（`sourceNodeId` 与 `targetNodeId` 必须不同）。连线是读取投影而不是自动执行 DAG：上游输出变化不自动触发下游执行，真正触发执行的是显式的 Function run。

## Typed Command 与并发语义

[`CanvasCommand`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasCommand.java) 是 sealed interface，共 11 个原子命令子类型：`CreateNode`、`RenameNode`、`SetNodeResources`、`SetNodeFunction`、`SetNodeGroup`、`DeleteNode`、`UpdateNodeTransform`、`CreateGroup`、`RenameGroup`、`UpdateGroupTransform`、`DeleteGroup`。每条命令只修改一个语义组，并在构造期校验非空集合、无重复 ID 与有限坐标。

并发编辑不是整图 CAS。每条命令携带自己语义组在编辑起点的前置条件：
- **名称**：前置条件为旧名称（`expectedName` / `expectedTitle`）；
- **资源数组**：前置条件为有序 Resource ID 列表（`expectedResourceIds`），槽位意图由 [`CanvasResourceInput`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasResourceInput.java)（`Keep`、`Text`、`Blob`）表达；
- **Function**：前置条件为编辑起点的完整 [`CanvasFunction`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasFunction.java)（`{name,args}`，由 [`CanvasJson.JsonObject.equals`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasJson.java) 做严格 JSON 语义比较）；
- **删除节点**：同时检查 `expectedResourceIds` 与 `expectedFunction`，还须满足无活跃 Run、无未解除引用等删除前提；
- **分组归属**：前置条件为旧分组 ID（`expectedGroupId`）或成员节点集合（`expectedMemberNodeIds`）；
- **几何布局**：`expectedTransform` 为空时表示在线操作按服务端接受顺序收敛；非空时表示重连积压的布局基线，若与服务端当前值不一致则拒绝，防止重放过期位置。

[`CanvasCommandService`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasCommandService.java) 提供创建、编辑与删除；`applyCommands` 的实现遵循以下事务协议：

```text
applyCommands(canvasId, idempotencyKey, commands)
  -> 锁 document 行（FOR UPDATE）
  -> 相同 idempotencyKey + 相同 requestHash：返回 Accepted(CanvasPatch.receipt(acceptedRevision))（精确重放回执）
  -> 相同 idempotencyKey + 不同 requestHash：抛 CanvasConflictException(IDEMPOTENCY_CONFLICT)
  -> CanvasCommandPlanner.plan(graph, commands) 纯领域规划
  -> 存在冲突（plan.isRejected()）：返回 Conflicted(conflicts)，整批不写入，不返回新基线
  -> 无冲突且有变化（plan.hasChanges()）：按规划步骤执行 CanvasMutation，revision + 1，写入 CommandDedup
  -> 无冲突但无变化（no-op 批）：写入 CommandDedup，revision 不变
  -> 返回 Accepted(plan.toPatch(acceptedRevision))
```

命令规划由 [`CanvasCommandPlanner`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasCommandPlanner.java) 在纯领域内完成，将命令批应用到权威快照 [`CanvasGraph`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasGraph.java) 上，产出 [`CanvasCommandPlan`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasCommandPlan.java) 与底层步骤 [`CanvasMutation`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasMutation.java)（`InsertNode`、`UpdateNode`、`DeleteNode`、`InsertResource`、`AttachResource`、`DetachResource`、`DeleteResource`、`InsertGroup`、`UpdateGroup`、`DeleteGroup`、`ReleaseNodePins`、`DeleteFunctionRun`）。

处理结果由 [`CanvasCommandResult`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasCommandResult.java) 表达：
- `Accepted(patch)`：接受回执。首次接受携带本次前进的 [`CanvasPatch`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasPatch.java) 与结果 revision；精确重放回执只携带当时记录的 revision 与空变化集（`CanvasPatch.receipt(acceptedRevision)`）。
- `Conflicted(conflicts)`：具体冲突清单。[`CanvasConflict`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasConflict.java) 包含 `TargetMissing`、`TargetPresent`、`StaleNode`（携带当前权威节点投影）、`StaleGroup`（携带当前权威分组）、`NodeRunning`（节点处于 READY/RUNNING/UNKNOWN 执行中，禁止换输出或删除）、`NodeReferenced`（节点仍被其他节点的 Function args 引用，须在同批中解除）。冲突不写入任何数据，服务端不使用旧基线偷偷重试。

写入准入异常由 [`CanvasConflictException`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasConflictException.java) 的 `CANVAS_NOT_FOUND` 与 `IDEMPOTENCY_CONFLICT` 表达。[`CanvasPatch`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasPatch.java) 携带 `revision` 以及 [`CanvasNodePatch`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasNodePatch.java)（`Upsert`/`Remove`）和 [`CanvasGroupPatch`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasGroupPatch.java)（`Upsert`/`Remove`）列表。[`CanvasQueryService.findSnapshot`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasQueryService.java) 返回完整 [`CanvasSnapshot`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasSnapshot.java)：document、nodes、groups 与 references。

可变行持久化原语由 [`CanvasStore`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasStore.java) 暴露：`lockDocument`（`FOR UPDATE`）、`lockDocumentForKeyShare`（`FOR KEY SHARE`）、`advanceRevision(expectedRevision, newRevision)`、`NodeRecord` 与 `CommandDedup`。事务边界、SQL 与行锁实现位于 [canvas-infra](canvas-infra.md)。

## Function：能力与执行边界

Function 能力在启动装配时由 [`CanvasFunctionCatalog.from(...)`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionCatalog.java) 冻结成唯一快照：每个 adapter 的 `enabled()`、`unavailableReason()`、`functions()` 各读取一次；function name 重复即启动失败，集合按字典序排列；enabled adapter 不得声明 unavailable reason，disabled adapter 必须有非空原因。运行期只通过 `require(name)` 读取已冻结的 `RegisteredFunction`，不存在第二份 registry。

[`CanvasFunctionAdapter`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionAdapter.java) 提供 `functions/enabled/unavailableReason/preflight/submit/execute/cancel`。[`CanvasFunctionDefinition`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionDefinition.java) 声明小写 canonical token 形式的 `name`、`description`、[`CanvasFunctionArgsSchema`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionArgsSchema.java)、有界的输出计划与 [`CanvasFunctionReferencePolicy`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionReferencePolicy.java)。输出计划是 [`CanvasFunctionOutputSpec`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionOutputSpec.java) 列表（1..8 个槽位），每个槽位声明 `kind`（`TEXT|IMAGE|VIDEO|AUDIO`）与可选显式资源名：`TEXT` 槽位以内联文本发布，其余槽位以 Blob 发布；显式名必须在函数内唯一，缺省名由节点名加类型后缀推导（单输出函数用 `CanvasFunctionDefinition.of(...)` 声明）。参数 schema 是严格 JSON Schema 子集（根必须是 object，每一层 object 都必须显式 `additionalProperties: false`，关键字支持 `type/description/properties/required/additionalProperties/enum/default/minimum/maximum/items/minItems/maxItems`，类型支持 `object|string|integer|number|boolean|array|resourceReference`；object 与 array 可递归嵌套，深度上限 8，array 必须声明 `items` 且 `maxItems <= 32`，`resourceReference` 可出现在任意嵌套位置）。
参数与运行状态各有一个严格的 codec port。[`CanvasFunctionArgsCodecPort`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionArgsCodecPort.java) 在解码时按 Function 的 schema 严格校验 `JsonObject` 参数，拒绝未知字段、重复键、null、未声明参数与类型错误；引用 manifest（按 args 中引用首次出现顺序、去重、上限 32）由 Run 启动事务冻结，见 [canvas-infra](canvas-infra.md)。[`CanvasFunctionRunStateCodecPort`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionRunStateCodecPort.java) 冻结完整执行计划（含 args、输出计划、[`CanvasFunctionSubmitState`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionSubmitState.java) 与 adapterState）与 checkpoint，版本号不匹配即拒绝。

Run 启动时冻结的东西写在 [`CanvasFunctionFrozenRun`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionFrozenRun.java) 里：canvas/node identity、function definition、args、manifest、**输出计划**（[`CanvasFunctionFrozenOutput`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionFrozenOutput.java) 按槽位保存预分配的 resource id、节点内 index、kind 与解析后的资源名）、submitState、stage 与 adapter state。[`CanvasFunctionFrozenReference`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionFrozenReference.java) 保存的是启动瞬间的 source node、resource 与 blob identity，外加权威媒体事实快照——因此执行期间引用关系、source 资源或 Function 配置的变化都不会改变该 Run 的输入。所有目标资源先以无 owner 形式物化，成功时再按计划顺序原子挂接。
Adapter 分为两阶段执行：`submit` 提交外部异步任务并返回初始状态；`execute` 查询与物化输出计划槽位，并必须按计划顺序返回 `run.outputResourceIds()`。Adapter 通过 [`CanvasFunctionExecutionContext`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionExecutionContext.java) 拿到最小能力：`checkpoint(stage, adapterState)`、`isRunning()`、只读 frozen 原件的 `openOriginal`/`presignOriginal`，以及只接受计划内槽位的 `materializeOutput(output, stream)`（媒体 Blob）与 `materializeTextOutput(output, text)`（`TEXT` 内联文本）；槽位 id/index/kind 与冻结计划不一致、或物化入口与槽位类型不匹配时立即拒绝。媒体事实与字节走 [`CanvasFunctionBlobAccess`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionBlobAccess.java)，它只暴露 `BlobFacts`、原件 stream 和短期 URL。adapter 看不到数据库、对象 key 或 pin 表。

[`CanvasFunctionRun`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasFunctionRun.java) 的状态由 [`CanvasFunctionRunStatus`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasFunctionRunStatus.java) 表达，字段组合由构造期校验固定：

| status | 允许的字段组合 |
| --- | --- |
| `READY` | 必须有 `availableAt`，不得持有 lease |
| `RUNNING` | 必须持有 `leaseToken`/`leaseUntil`，`availableAt` 为空 |
| `SUCCEEDED` / `CANCELLED` | lease 与 availableAt 皆空，终态不可再被 claim；`SUCCEEDED` 不得带 `error`，`CANCELLED` 可以带 |
| `FAILED` / `UNKNOWN` | lease 与 availableAt 皆空，必须持有非空 `error` |

`attempt` 非负，lease token 为 1～128 字符。Run 对资源生命周期的保护由 [`CanvasFunctionResourcePin`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasFunctionResourcePin.java) 表达：`INPUT` 保护启动时冻结的引用资源，`OUTPUT` 在预分配目标资源实际物化后写入；pin 按 `(canvasId, nodeId, requestId)` 整体释放，不引入通用引用计数。执行中若提交意图已持久化（`SUBMITTING`）但进程崩溃，租约到期后转为 `UNKNOWN` 状态并保留 pin 与目标资源，退出自动调度，绝不重试提交。人工核对后可通过 [`CanvasFunctionService.resolve`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionService.java) 传入 [`CanvasFunctionUnknownResolution`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionUnknownResolution.java)（`RESUME` 回到 READY 继续查询，`FAILED` / `CANCELLED` 释放 pin 与未挂接的目标资源）。

## 不变量

- `revision >= 0` 且每次成功命令批（产生变化时）或 Run 状态前进恰好 +1；冲突或命令非法时整批不写入，不产生部分 Patch。
- Resource 内容 XOR（`blobId` 与 `textContent` 恰好一个非空）、owner/index 成对必须成立；节点内资源同属一个 Canvas、owner 指向本节点、槽位从 0 连续递增、内容类型一致。
- Function Run 的状态与 available/lease/error 字段组合必须匹配上表；终态 Run 允许被新的 requestId 取代，活跃 Run 不允许被第二个 requestId 覆盖。
- Catalog 是启动期冻结快照，运行期函数能力与可用性不再变化；args/state codec 对未知字段、重复字段与版本漂移一律 fail closed。
- Core 只表达领域值、规划与可验证的 transition。锁协议、lease、heartbeat、PostgreSQL 短事务与迟到回调的围栏由 [canvas-infra](canvas-infra.md) 负责。

## 从哪里改

- 领域类型与命令：[`CanvasDocument.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasDocument.java)、[`CanvasResourceNode.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasResourceNode.java)、[`CanvasResource.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasResource.java)、[`CanvasGroup.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasGroup.java)、[`CanvasReference.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasReference.java)、[`CanvasResourceReference.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasResourceReference.java)、[`CanvasCommand.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasCommand.java)、[`CanvasCommandPlanner.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasCommandPlanner.java)、[`CanvasCommandPlan.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasCommandPlan.java)、[`CanvasCommandResult.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasCommandResult.java)、[`CanvasConflict.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasConflict.java)、[`CanvasConflictException.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasConflictException.java)、[`CanvasPatch.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasPatch.java)。
- 端口：[`CanvasStore.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasStore.java)、[`CanvasCommandService.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasCommandService.java)、[`CanvasQueryService.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasQueryService.java)、[`CanvasResourceRepository.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasResourceRepository.java)、[`CanvasResourceLifecycle.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasResourceLifecycle.java)、[`CanvasResourceMaterializer.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasResourceMaterializer.java)（输出物化，由宿主实现）、[`CanvasFunctionRunRepository.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasFunctionRunRepository.java)、[`CanvasFunctionResourcePinRepository.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasFunctionResourcePinRepository.java)、[`CanvasBlobReleaser.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasBlobReleaser.java)、[`CanvasFunctionService.java`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionService.java)。
- Function 能力：[`function` 目录](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/)；新增 Provider 能力时在 [platform](platform.md) 或插件模块实现 adapter 并让 Catalog 在启动时冻结，而不是在 Core 内加注册表。

测试入口：

- [`CanvasCoreArchitectureTest.java`](../../canvas/core/src/test/java/fun/fengwk/kkstudio/canvas/CanvasCoreArchitectureTest.java) 守卫零生产依赖、包边界与 Catalog 单一事实源；新增第三方 import 会直接失败。
- [`CanvasValueObjectTest.java`](../../canvas/core/src/test/java/fun/fengwk/kkstudio/canvas/CanvasValueObjectTest.java) 覆盖 document/resource/reference/snapshot 的构造约束、集合 defensive copy、命令严格性与结果形态；节点与分组一致性另由命令规划测试覆盖。
- [`CanvasCommandPlannerTest.java`](../../canvas/core/src/test/java/fun/fengwk/kkstudio/canvas/CanvasCommandPlannerTest.java)、[`CanvasCommandPlannerGroupTest.java`](../../canvas/core/src/test/java/fun/fengwk/kkstudio/canvas/CanvasCommandPlannerGroupTest.java) 与 [`CanvasCommandPlannerResourceTest.java`](../../canvas/core/src/test/java/fun/fengwk/kkstudio/canvas/CanvasCommandPlannerResourceTest.java) 覆盖节点、资源与分组的命令规划、前置条件校验与冲突判定。
- [`CanvasJsonTest.java`](../../canvas/core/src/test/java/fun/fengwk/kkstudio/canvas/CanvasJsonTest.java) 覆盖 JSON AST 解析与语义比较。
- [`CanvasFunctionCatalogTest.java`](../../canvas/core/src/test/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionCatalogTest.java) 锁定可用性声明与冻结排序；[`CanvasFunctionDefinitionTest.java`](../../canvas/core/src/test/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionDefinitionTest.java)、[`CanvasFunctionArgsSchemaTest.java`](../../canvas/core/src/test/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionArgsSchemaTest.java) 与 [`CanvasFunctionFrozenTest.java`](../../canvas/core/src/test/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionFrozenTest.java) 锁定 Function 定义、参数 Schema 校验与冻结引用的媒体事实。

---

上级：[系统设计](../system-design.md)。相关文档：[Canvas Infra](canvas-infra.md)、[Schema](schema.md)、[Share](share.md)、[Platform](platform.md)。
