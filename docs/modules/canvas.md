# Canvas

Canvas 是建立在平台之上的图形编辑应用：用户在画布上摆放资源节点与 Function 节点，Function 节点显式运行一次插件提供的能力，把产出物化为新的资源。它由两个模块组成：

| 模块 | 职责 | 依赖 |
| --- | --- | --- |
| [`apps/canvas/core`](../../apps/canvas/core/) | 不可变 graph、typed command、纯领域命令规划、冲突语义与全部端口 | [Common](common.md)、[Platform Plugin API](platform-plugin-api.md#canvas-function-spi)（Function SPI 与 `CanvasJson`）、Jackson core |
| [`apps/canvas/infra`](../../apps/canvas/infra/) | PostgreSQL 持久化、Snapshot 一致性读、Function Run 运行时，以及对平台 Storage 与插件目录的适配 | Core、[Platform](platform.md)、[Durable Work](work.md)、Spring JDBC |

平台从不依赖 Canvas：Canvas 经平台的公开服务使用全局 Blob 与插件快照，[Web](web.md) 完成 HTTP/DTO 映射与组装。自动配置入口是 [`CanvasInfraAutoConfiguration`](../../apps/canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/CanvasInfraAutoConfiguration.java)。Canvas 与 Project 的关系、跨域数据约束见 [Canvas / Project](../canvas-project.md)。

## Graph 聚合

```text
CanvasDocument                    identity、标题、revision 同步游标与时间
├── CanvasResourceNode[]          唯一节点形态：普通资源节点或 Function 节点
│   ├── CanvasResource[]          TEXT 内联或 Storage blob 引用（恰好一个非空）
│   ├── CanvasFunction?           {name, args}，args 中保留 {"type":"resource","nodeId":"...","index":0} 引用
│   └── CanvasFunctionRun?        节点当前/最后一次运行
├── CanvasGroup[]                 world 坐标，不嵌套
└── CanvasReference[]             由消费节点 args 投影出的引用连线，不单独持久化
```

[`CanvasDocument`](../../apps/canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasDocument.java) 的 `revision` 从 0 起单调递增，只用于同步排序、补漏和确认接受位置，不是普通编辑的整图前置版本。Canvas 独立存在，不冗余存储会话或归属引用（见 [Schema](schema.md)）。

[`CanvasResourceNode`](../../apps/canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasResourceNode.java) 构造期强制一致性：资源同属一个 Canvas 且 `ownerNodeId` 指向本节点，槽位 `resourceIndex` 从 0 连续递增，同一节点的资源全为文本或全为媒体；`run` 只能挂在 Function 节点上；普通资源节点至少持有一个 Resource，Function 节点首次成功前可以没有资源。[`CanvasResource`](../../apps/canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasResource.java) 的 `blobId` 与 `textContent` 恰好一个非空；物化中的目标资源、被其他 Run pin 的输入资源或源节点已删除的历史资源可以暂时没有 owner，直到成功挂接或最后一个 pin 释放。

引用连线是读取投影而不是自动执行 DAG：上游输出变化不会触发下游，真正触发执行的是显式的 Function run。[`CanvasReference`](../../apps/canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasReference.java) 禁止自环。

## Typed Command 与并发语义

[`CanvasCommand`](../../apps/canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasCommand.java) 是 sealed interface，每个原子命令只修改一个语义组，并携带该组在编辑起点的基线：

| 语义组 | 前置条件 |
| --- | --- |
| 名称 | 旧名称 `expectedName` / `expectedTitle` |
| 资源数组 | 有序 Resource ID 列表 `expectedResourceIds`，槽位意图为 [`CanvasResourceInput`](../../apps/canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasResourceInput.java) 的 `Keep` / `Text` / `Blob` |
| Function | 编辑起点的完整 [`CanvasFunction`](../../apps/canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasFunction.java)，按 `CanvasJson` 严格语义比较 |
| 删除节点 | 资源与 Function 基线，且无活跃 Run、无未解除的引用 |
| 分组归属 | 旧分组 ID 或成员节点集合 |
| 几何布局 | `expectedTransform` 为空时按服务端接受顺序收敛；非空时是重连积压的布局基线，不一致即拒绝 |

`applyCommands` 的事务协议：

```text
锁 document（FOR UPDATE）
  -> 相同 idempotencyKey + 相同 requestHash：返回当时的接受回执（精确重放）
  -> 相同 idempotencyKey + 不同 requestHash：IDEMPOTENCY_CONFLICT
  -> CanvasCommandPlanner.plan(graph, commands)：纯领域规划
  -> 有冲突：返回 Conflicted(conflicts)，整批不写入
  -> 无冲突：执行 CanvasMutation，有变化时 revision + 1，写入 CommandDedup
  -> 返回 Accepted(patch)
```

[`CanvasConflict`](../../apps/canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasConflict.java) 有 `TargetMissing`、`TargetPresent`、`StaleNode` / `StaleGroup`（携带当前权威投影）、`NodeRunning`（执行中禁止换输出或删除）与 `NodeReferenced`（须在同批中解除引用）。冲突不写入任何数据，服务端不会用旧基线偷偷重试。[`CanvasPatch`](../../apps/canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasPatch.java) 携带结果 revision 与节点、分组的 upsert/remove；精确重放只携带当时的 revision。

## 持久化与读模型

Infra 用 `JdbcClient` 实现 Core 的端口：[`PostgresqlCanvasStore`](../../apps/canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresqlCanvasStore.java) 聚合 document、node、group 与幂等记账的行操作，[`PostgresqlCanvasCommandService`](../../apps/canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresqlCanvasCommandService.java) 执行上面的写协议，资源与 pin 由各自的 Repository 写入。`canvas_*` 的外键全部 RESTRICT，删除顺序由代码显式保证，不依赖 cascade。

删除画布先锁 Document：存在 `READY`、`RUNNING` 或 `UNKNOWN` Run 时整体拒绝；否则依次释放 pin、删除终态 Run、删除 Resource 行并释放全局 Blob 引用、删除 Node、Group、幂等记账，最后删除 Document。Blob 引用释放失败使整个事务回滚。

[`PostgresqlCanvasQueryService.findSnapshot`](../../apps/canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresqlCanvasQueryService.java) 装配 document、runs、按 owner 分组的资源、node、group 与 references，并在返回前重读对比 document 与 runs：读取窗口内有并发提交就重新读取，保证 graph 与 Run 投影属于同一代次。

## Function Run

Function 的能力契约（adapter、目录、冻结运行计划、执行上下文）由 [Platform Plugin API](platform-plugin-api.md#canvas-function-spi) 定义；Canvas 负责 Run 的状态、调度、资源保护与人工决议。

### 状态与资源保护

[`CanvasFunctionRun`](../../apps/canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasFunctionRun.java) 只描述业务状态：`READY` / `RUNNING` 可被调度，`SUCCEEDED` / `CANCELLED` / `FAILED` / `UNKNOWN` 退出自动调度；`FAILED` 与 `UNKNOWN` 必须带 `error`，`SUCCEEDED` 不得带。`canvas_function_run` 以 `node_id` 为主键，每个 Function 节点只有一行当前/最后一次 Run；何时可领取、谁持有执行租约由共享的 [Durable Work](work.md) 中 `CANVAS_RUN` 行管理。

[`CanvasFunctionResourcePin`](../../apps/canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasFunctionResourcePin.java) 保护 Run 用到的资源：`INPUT` 保护启动时冻结的引用资源，`OUTPUT` 在目标资源物化时写入；pin 按 `(canvasId, nodeId, requestId)` 整体释放，只保护无 owner 资源不被回收，绝不参与 `storage_blob.ref_count`。

### 启动

[`CanvasFunctionRunTransactions.start`](../../apps/canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionRunTransactions.java) 的锁序固定为 document → node → run：

```text
同 requestId：返回既有 Run（精确重放优先于一切校验）
现有 Run 活跃且 requestId 不同：conflict
租用目录 -> 按名找函数（已移除即 conflict）-> 比较完整定义指纹与 expectedVersion
-> 严格解码 args -> 冻结引用 manifest（节点存在、index 在范围内且是 blob）与媒体事实
-> 按输出计划预分配 resource id，构造 submitState=PENDING 的冻结计划 -> adapter.preflight
-> 释放上一个 Run 的 pin -> 写入 READY Run 与 INPUT pin -> 请求 Work -> revision + 1
```

客户端必须回传它从目录读到的定义指纹；函数被移除或同名函数换了定义只会得到 conflict，既不自动重试，也不退回当前同名定义。目录租用保持到事务 `afterCompletion`。冻结计划保存完整函数定义，之后用户改配置、解除引用或替换源资源都不改变这个 Run 的输入。

### 领取、执行与终态

[`CanvasFunctionWorkStore.claimNext`](../../apps/canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/CanvasFunctionWorkStore.java) 从 `durable_work` 以 `SKIP LOCKED` 领取一条到期 Work，再在短事务里把 Run 推进为 `RUNNING` 并递增 attempt。持有者的每次写入都在 SQL 里要求同一 `request_id`、`status='RUNNING'`，且同一 lease token 在数据库时钟下仍持有租约；token 过期或被接管时写入影响 0 行，旧 worker 的回调只能变成 no-op 或静默退出。

[`CanvasFunctionDispatcher`](../../apps/canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionDispatcher.java) 由周期 poll 驱动，`durable_work` 通道上的 `CANVAS_RUN` 提示只降低延迟，正确性来自 poll 与租约；调度骨架复用 Common 的 [`BoundedWorkDispatcher`](../../common/src/main/java/fun/fengwk/kkstudio/common/concurrent/BoundedWorkDispatcher.java)，没有第二层内存队列，本地容量满时把 Run 还回并推迟。

[`CanvasFunctionWorker.run`](../../apps/canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionWorker.java) 执行两阶段协议：

1. 从 state 解码冻结计划，不查询当前目录；
2. `submitState == SUBMITTING` 说明提交意图已持久化但进程崩溃，结果不明，转 `UNKNOWN` 并保留 pin 与目标资源，绝不重新提交；
3. 租用当前目录：尚未提交的 Run 要求同名定义与冻结定义完全一致，已提交的 Run 只要求同名函数仍存在；先持久化 `SUBMITTING`，再调用 adapter `submit`，确认后持久化 `SUBMITTED`；
4. 调用 `execute` 查询并物化预分配槽位，返回值必须恰好是冻结的目标 id，然后 `completeSuccess`。

heartbeat 按固定间隔续租，不触碰业务事实；续租失败即停止后续写入。adapter 抛普通异常时 Run 进入 `FAILED`，抛 `CanvasFunctionUnknownException` 时进入 `UNKNOWN`。外部计算（HTTP、轮询、媒体处理）永远在数据库事务之外。

- **成功**要求输出计划的每个槽位都已物化且与槽位一一对应：媒体槽位是同画布、无 owner、有 blob 且 MIME 与 kind 匹配的资源，`TEXT` 槽位只有文本；随后整组替换节点当前资源，Run 置为 `SUCCEEDED` 并释放 pin。绝不发布半成品数组。
- **失败与取消**先写终态，再释放 pin，最后丢弃无 owner 的计划输出；取消来自客户端，CAS 只要求 Run 活跃，不需要租约。
- **人工决议**由 [`CanvasFunctionService`](../../apps/canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasFunctionService.java) 接收：必须给出核对说明，[`CanvasFunctionUnknownResolution`](../../apps/canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasFunctionUnknownResolution.java) 的 `RESUME` 把 Run 还回 `READY`（只重新查询，不重新提交），`FAILED` / `CANCELLED` 释放 pin 与未挂接输出。取消与 `resolve(CANCELLED)` 在事务完成后 best-effort 调用 adapter 的 `cancel`。

同一槽位重复物化返回既有 Resource 行，不新增行、不重复 pin，因此部分物化后崩溃的 Run 恢复后只补齐缺失槽位。

## 宿主适配

依赖平台能力的适配集中在 [`platform`](../../apps/canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/platform/) 包，由 [`CanvasPlatformAutoConfiguration`](../../apps/canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/platform/CanvasPlatformAutoConfiguration.java) 与 Canvas 运行时分开装配，因此 Canvas 模块测试可以只加载运行时本身：

- [`PluginCanvasFunctionCatalogSource`](../../apps/canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/platform/PluginCanvasFunctionCatalogSource.java) 实现 [`CanvasFunctionCatalogSource`](../../apps/canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasFunctionCatalogSource.java)：每次租用当前插件快照里冻结的 Function 目录，并在租用期间保护 adapter 资源；`bind` 冻结完整定义，`require(binding)` 要求定义完全一致。Web 组合根以插件运行时构造它。
- [`PlatformCanvasFunctionBlobAccess`](../../apps/canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/platform/PlatformCanvasFunctionBlobAccess.java) 实现 [`CanvasFunctionBlobAccess`](../../apps/canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasFunctionBlobAccess.java)，只暴露媒体事实、原件流与短期 URL。
- [`CanvasBlobResourceMaterializer`](../../apps/canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/platform/CanvasBlobResourceMaterializer.java) 把输出物化为全局 Blob：`stage` 前后各一个短事务，按 document → run 加锁并复验当前 RUNNING owner 与数据库时钟下的租约；读流与对象存储暂存不持有数据库锁；后置事务插入无 owner Resource、写 OUTPUT pin 并转移上传引用。Resource 删除时经 [`CanvasBlobReleaser`](../../apps/canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasBlobReleaser.java) 在同一事务释放 Blob 引用。媒体探针与预览属于平台存储（见 [Platform](platform.md#storageblob-与-resource)）。

内置只有测试与 E2E 栈使用的 fake adapter（[`FakeCanvasFunctionConfiguration`](../../apps/canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/function/fake/FakeCanvasFunctionConfiguration.java)，`KK_STUDIO_CANVAS_FUNCTION_FAKE_ENABLED=true` 才注册）；`image.crop` 由 Media 插件提供，ComfyUI/H3 与 OpenCLI 函数由各自的构建期插件提供。

## 部署参数

[`CanvasFunctionRuntimeProperties`](../../apps/canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionRuntimeProperties.java) 的前缀是 `kk-studio.canvas.function.runtime`：

```text
workerConcurrency       = 2        maxDispatchTasks        = 2
leaseDurationMillis     = 30000    heartbeatIntervalMillis = 10000
pollIntervalMillis      = 1000     rejectionDelayMillis    = 1000
```

校验要求 `1 <= maxDispatchTasks <= workerConcurrency`、时间参数为正，且 heartbeat 间隔小于租约时长。adapter 的 `submit`/`execute` 在固定并发 worker 上同步执行，外部等待会占用 worker 槽位，续租由独立 heartbeat scheduler 维持。

## 不变量

- `revision` 只在产生变化的命令批或 Run 状态前进时 +1；冲突或非法命令整批不写入。
- 所有写入按 document → node → run 加锁；跨 Run、节点、画布的操作在一个事务里完成，revision、pin、Resource 不出现部分提交。
- 活跃 Run 不允许被第二个 requestId 覆盖，终态 Run 可以被新 requestId 取代。
- 迟到回调只能收敛为 no-op；丢通知、listener 重连、进程重启都由 poll 加租约过期恢复。

## 从哪里改

- 领域与命令：[`CanvasCommandPlanner.java`](../../apps/canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasCommandPlanner.java) 与同包的领域值、端口。
- SQL：先看 [`V1__schema.sql`](../../schema/src/main/resources/db/migration/V1__schema.sql) 的 canvas 段与 [Schema](schema.md) 的重建规则，再改 [`postgresql`](../../apps/canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/) 下对应的 JdbcStore 与集成测试。
- Function 运行时：[`function`](../../apps/canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/function/) 目录下的 dispatcher、worker、transactions、codec 是一个整体，改所有权语义时同步检查 `CanvasFunctionRunJdbcStore` 的租约围栏与锁序。
- 新增能力：在插件模块实现 `CanvasFunctionAdapter`，由平台发布到插件快照。

测试入口：[`CanvasCommandPlannerTest.java`](../../apps/canvas/core/src/test/java/fun/fengwk/kkstudio/canvas/CanvasCommandPlannerTest.java) 与同包规划测试覆盖前置条件与冲突判定；[`CanvasFunctionRuntimeFoundationTest.java`](../../apps/canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionRuntimeFoundationTest.java) 在真实库上验证锁序、幂等、冻结与回滚；[`CanvasFunctionWorkStoreIntegrationTest.java`](../../apps/canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/postgresql/CanvasFunctionWorkStoreIntegrationTest.java) 验证多实例领取、租约恢复与围栏；[`CanvasBlobResourceMaterializerIntegrationTest.java`](../../apps/canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/platform/CanvasBlobResourceMaterializerIntegrationTest.java) 在平台测试宿主（`platform-runtime` test-jar）上验证输出物化与 Blob 引用转移。数据库测试需要 Docker（或本地 PostgreSQL），不可用即失败。

---

上级：[系统设计](../system-design.md)。相关文档：[Platform Plugin API](platform-plugin-api.md)、[Platform](platform.md)、[Durable Work](work.md)、[Schema](schema.md)、[Web](web.md)。
