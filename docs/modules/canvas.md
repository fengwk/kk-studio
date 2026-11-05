# Canvas

Canvas 是建立在平台之上的图形编辑应用：用户在画布上摆放资源节点与 Function 节点，Function 节点显式运行一次插件提供的能力，把产出物化为新的资源。

| 模块 | 职责 | 依赖 |
| --- | --- | --- |
| [`apps/canvas/spi`](../../apps/canvas/spi/) | Canvas Function 能力 SPI：adapter、函数定义与参数 schema、冻结运行计划、执行上下文与严格 JSON 模型 | [Common](common.md)、Jackson 3 流式 API |
| [`apps/canvas/runtime`](../../apps/canvas/runtime/) | graph、typed command、领域端口、PostgreSQL 持久化、Function Run 运行时、目录冻结与投影、revision 通知纵向切片、HTTP 边界、DTO 映射与平台适配 | [Canvas SPI](#canvas-function-spi)、[Common](common.md)、[Platform Plugin API](platform-plugin-api.md)、[Share](share.md)、[Platform](platform.md)、[Durable Work](work.md)、Spring JDBC、Spring Web |

平台从不依赖 Canvas：Canvas 经平台的公开服务使用全局 Blob 与插件快照。[Canvas DTO](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/dto/) 和领域间的映射在 [`CanvasDtoMapper`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/infra/dto/CanvasDtoMapper.java)，由 [`CanvasPlatformAutoConfiguration`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/infra/platform/CanvasPlatformAutoConfiguration.java) 使用平台 Storage 显式装配；独立 Canvas 运行时不加载它。[Web](web.md) 是组合根，Canvas 自己的 [`CanvasHttpAutoConfiguration`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/http/CanvasHttpAutoConfiguration.java) 把控制器注册进宿主的 MVC。Canvas 与 Project 并列存在，各自拥有领域数据，跨域数据约束与 ER 关系见 [Schema](schema.md)。

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

[`CanvasDocument`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/CanvasDocument.java) 的 `revision` 从 0 起单调递增，只用于同步排序、补漏和确认接受位置，不是普通编辑的整图前置版本。Canvas 独立存在，不冗余存储会话或归属引用（见 [Schema](schema.md)）。

[`CanvasResourceNode`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/CanvasResourceNode.java) 构造期强制一致性：资源同属一个 Canvas 且 `ownerNodeId` 指向本节点，槽位 `resourceIndex` 从 0 连续递增，同一节点的资源全为文本或全为媒体；`run` 只能挂在 Function 节点上；普通资源节点至少持有一个 Resource，Function 节点首次成功前可以没有资源。[`CanvasResource`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/CanvasResource.java) 的 `blobId` 与 `textContent` 恰好一个非空；物化中的目标资源、被其他 Run pin 的输入资源或源节点已删除的历史资源可以暂时没有 owner，直到成功挂接或最后一个 pin 释放。

节点名称按 NFKC、去首尾空格和小写得到唯一键，要求画布内非空且唯一，显示名可保留大小写。引用使用 UUID，重命名保持绑定。分组是单层视觉组织。

TEXT 使用内联正文，媒体引用不可变 Blob；每条 Resource 恰好选择一种内容来源。节点资源数组保持内容分支一致：全为内联文本或全为媒体。编辑文字或替换媒体创建新 Resource，复制媒体时新建 Resource 并共享 Blob 字节。Resource 由节点挂接或 Function pin 保护，解除挂接且无 pin 后可回收。

### Function 配置与引用

Function 保存为 `{name, args}`，具体函数声明参数 Schema、输入限制和有界输出计划（1..8 个槽位）。例如裁剪函数：

```json
{
  "name": "image.crop",
  "args": {
    "source": {"type": "resource", "nodeId": "...", "index": 0},
    "x": 100,
    "y": 50,
    "width": 640,
    "height": 480
  }
}
```

`{"type":"resource","nodeId":"...","index":0}` 是核心资源引用值，可出现在 args 的任意嵌套位置。连线由配置投影，插件未安装时仍可识别引用。`index` 从零开始，指向源节点当前输出位置；运行接受时解析为实际 Resource 并冻结。

- 保存引用时校验同画布目标；尚无输出的位置可保存为显式失效引用，启动前要求完整解析并校验类型。
- 位置失效需用户修正；删除被引用节点需显式解除引用，可以放入同一原子编辑批。
- 配置大小、引用数和遍历深度均有上限。
- 上游输出更新后，由用户显式启动下游 Function。

引用连线是读取投影而不是自动执行 DAG：上游输出变化不会触发下游，真正触发执行的是显式的 Function run。[`CanvasReference`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/CanvasReference.java) 禁止自环。

## Typed Command 与并发语义

[`CanvasCommand`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/CanvasCommand.java) 是 sealed interface，每个原子命令只修改一个语义组，并携带该组在编辑起点的基线：

| 语义组 | 前置条件 |
| --- | --- |
| 名称 | 旧名称 `expectedName` / `expectedTitle` |
| 资源数组 | 有序 Resource ID 列表 `expectedResourceIds`，槽位意图为 [`CanvasResourceInput`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/CanvasResourceInput.java) 的 `Keep` / `Text` / `Blob` |
| Function | 编辑起点的完整 [`CanvasFunction`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/CanvasFunction.java)，按 `CanvasJson` 严格语义比较 |
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

[`CanvasConflict`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/CanvasConflict.java) 有 `TargetMissing`、`TargetPresent`、`StaleNode` / `StaleGroup`（携带当前权威投影）、`NodeRunning`（执行中禁止换输出或删除）与 `NodeReferenced`（须在同批中解除引用）。冲突不写入任何数据，服务端不会用旧基线偷偷重试。[`CanvasPatch`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/CanvasPatch.java) 携带结果 revision 与节点、分组的 upsert/remove；精确重放只携带当时的 revision。

浏览器按三层状态展示：

```text
服务端已确认快照 + 待确认操作 + 编辑草稿 = 当前展示
```

草稿记录编辑基线与 generation。远端更新只推进确认层，ACK 只清除对应操作和 generation；dirty 输入、编辑器和选择保留。请求发送后冻结 ID、参数与前置条件，网络重试沿用原请求；解决冲突时创建新请求。去重记录与业务修改同事务提交，精确重试返回原接受回执。浏览器草稿、IndexedDB 存储与冲突恢复细节见 [Frontend](frontend.md)。

## 持久化与读模型

持久化适配使用 `JdbcClient`：[`PostgresqlCanvasStore`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresqlCanvasStore.java) 聚合 document、node、group 与幂等记账的行操作，[`PostgresqlCanvasCommandService`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresqlCanvasCommandService.java) 执行上面的写协议，资源与 pin 由各自的 Repository 写入。`canvas_*` 的外键全部 RESTRICT，删除顺序由代码显式保证，不依赖 cascade。

HTTP 不提供画布删除入口。删除节点或资源时，无 pin 的资源行会被删除并释放其全局 Blob 引用；解除挂接后仍被 Run pin 的资源继续保留。Blob 引用释放失败使整个事务回滚。

[`PostgresqlCanvasQueryService.findSnapshot`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresqlCanvasQueryService.java) 装配 document、runs、按 owner 分组的资源、node、group 与 references，并在返回前重读对比 document 与 runs：读取窗口内有并发提交就重新读取，保证 graph 与 Run 投影属于同一代次。单资源读取由 [`CanvasQueryService.findResource`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/CanvasQueryService.java) 直接按 `(canvasId, resourceId)` 查询已挂载资源（`ownerNodeId != null`），供 [`CanvasResourceUrlService`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/infra/platform/CanvasResourceUrlService.java) 定位资源并签发下载或预览 URL，无需全量加载画布快照。

## Revision 通知

Canvas 独占 `canvas_revision` 通知通道：通道常量、revision 事件源与进程内 Hub 都在
[`notification`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/notification/) 包。

- [`CanvasVersionNotifications`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/notification/CanvasVersionNotifications.java)
  是通道名（`CHANNEL = "canvas_revision"`）与 `canvasId:revision` payload 协议的唯一定义处：写侧
  [`PostgresqlCanvasChangeNotifier`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresqlCanvasChangeNotifier.java)
  在同一事务内用该常量 `pg_notify`，宿主的 LISTEN 注册也取同一常量，因此不存在第二处通道定义。
- 注册、cursor 与 fan-out 复用 `platform.notification` 的 `VersionNotificationHub`：本类只经
  [`CanvasQueryService.findDocument`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/CanvasQueryService.java) 读权威
  `revision` 并映射 revision 事件。`subscribe` 先注册 consumer 再读当前 revision 返回 ack cursor，返回的 cursor 之后的变化保证送达；
  畸形 payload 与 LISTEN 建连/重连退化为全量 resync；退订幂等，已关闭的订阅者不再收到投递。查询失败释放刚建立的注册并保留原始异常。
  本类不持有 LISTEN 连接，也不认识浏览器传输。
- 生产方入口由 [`CanvasInfraAutoConfiguration`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/infra/CanvasInfraAutoConfiguration.java)
  显式注册，`notification` 包不参与组件扫描。宿主只把 `CanvasVersionNotifications.CHANNEL` 登记到共享 LISTEN 连接，把通知与 resync
  交给该入口，再把 revision 事件转发给浏览器（见 [Web 的通知与浏览器事件](web.md#通知与浏览器事件)）。

## Canvas Function SPI

Function 的能力契约（adapter、函数定义、冻结运行计划、执行上下文与严格 JSON 模型）位于独立模块 [`apps/canvas/spi`](../../apps/canvas/spi/)，包名 `fun.fengwk.kkstudio.canvas.function`。它不依赖 Platform：插件与 Canvas 都直接依赖它，[Platform Plugin API](platform-plugin-api.md) 只按类型透传实例贡献。

`fun.fengwk.kkstudio.canvas.function.CanvasArguments` 读取字符串参数，仅在字段缺失时使用默认值，显式 null 或错误类型仍拒绝。

[`CanvasFunctionAdapter`](../../apps/canvas/spi/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionAdapter.java) 提供 `functions/enabled/unavailableReason/preflight/submit/execute/cancel`。[`CanvasFunctionDefinition`](../../apps/canvas/spi/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionDefinition.java) 声明小写 canonical token 形式的 `name`、`description`、[`CanvasFunctionArgsSchema`](../../apps/canvas/spi/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionArgsSchema.java)、输出计划与 [`CanvasFunctionReferencePolicy`](../../apps/canvas/spi/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionReferencePolicy.java)：

- 输出计划是 1..8 个 [`CanvasFunctionOutputSpec`](../../apps/canvas/spi/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionOutputSpec.java) 槽位，每个槽位声明 `kind`（`TEXT|IMAGE|VIDEO|AUDIO`，见 [`CanvasResourceKind`](../../apps/canvas/spi/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasResourceKind.java)）与可选显式资源名：`TEXT` 以内联文本发布，其余以 Blob 发布；显式名在函数内唯一，缺省名由节点名加类型后缀推导。
- 参数 schema 是严格 JSON Schema 子集：根必须是 object，每层 object 都显式 `additionalProperties: false`；关键字支持 `type/description/properties/required/additionalProperties/enum/default/minimum/maximum/items/minItems/maxItems`，类型支持 `object|string|integer|number|boolean|array|resourceReference`；嵌套深度上限 8，array 必须声明 `items` 且 `maxItems <= 32`。
- 参数与 schema 都用 [`CanvasJson`](../../apps/canvas/spi/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasJson.java) 表示：严格解析（拒绝重复键、尾随内容与超限嵌套）与规范序列化，JSON 语义相等的值得到相同文本，`MAX_LENGTH` 约束原文长度。

Run 启动时冻结的事实写在 [`CanvasFunctionFrozenRun`](../../apps/canvas/spi/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionFrozenRun.java)：canvas/node 身份、保存完整函数定义的 [`CanvasFunctionBinding`](../../apps/canvas/spi/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionBinding.java)、args、引用 manifest、按槽位预分配的 [`CanvasFunctionFrozenOutput`](../../apps/canvas/spi/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionFrozenOutput.java)、[`CanvasFunctionSubmitState`](../../apps/canvas/spi/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionSubmitState.java)、stage 与 adapter state。[`CanvasFunctionFrozenReference`](../../apps/canvas/spi/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionFrozenReference.java) 保存启动瞬间的 source node、resource、blob 身份与权威媒体事实，图中的引用关系或源资源之后的变化不会改写它们。

Adapter 两阶段执行：`submit` 提交外部异步任务并返回初始状态；`execute` 查询并按计划顺序物化输出、返回 `run.outputResourceIds()`。[`CanvasFunctionExecutionContext`](../../apps/canvas/spi/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionExecutionContext.java) 只给最小能力：`checkpoint(stage, adapterState)`、`isRunning()`、只读冻结原件的 `openOriginal`/`presignOriginal`，以及只接受计划内槽位的 `materializeOutput`（媒体 Blob）与 `materializeTextOutput`（内联文本）；槽位身份或类型与冻结计划不一致时立即拒绝。`checkpoint` 只用于需要跨进程恢复外部任务身份或已完成外部产物的 adapter（ComfyUI/H3、OpenCLI 付费任务与引用上传）；本地确定且可重复执行的内置 fake 与 `image.crop` 不写 checkpoint，提交事实由 Runtime 的 `SUBMITTING`/`SUBMITTED` 两阶段状态表达。外部结果无法确认时抛 [`CanvasFunctionUnknownException`](../../apps/canvas/spi/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionUnknownException.java)，Run 转入人工决议而不是重试提交。adapter 看不到数据库、对象 key 或 pin 表。

### 目录冻结与插件投影

插件在 `PluginInstance.contributions(CanvasFunctionAdapter.class)` 交出自己冻结的 adapter 列表；Canvas 把内置 adapter 与全部插件贡献交给 [`CanvasFunctionCatalog.from(...)`](../../apps/canvas/spi/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionCatalog.java) 冻结成唯一目录：每个 adapter 的 `enabled()`、`unavailableReason()`、`functions()` 各读取一次；adapter 未声明函数、function name 重复即失败，集合按字典序排列；enabled adapter 不得声明 unavailable reason，disabled adapter 必须有非空原因。每次快照构建只做一次这次校验与读取，租用快照的执行路径只读已冻结目录，不重复读取 adapter 定义。每项目录暴露版本身份 `definitionFingerprint`：按固定字段顺序对完整定义（name、description、argsSchema、outputs、referencePolicy）取 64 位小写 SHA-256，与对象键序、集合顺序无关，只随定义内容变化。

[`PluginCanvasFunctionCatalog`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/infra/platform/PluginCanvasFunctionCatalog.java) 是这次冻结的完整视图：目录本身加上按 adapter 身份记录的插件归属。它由 Web 组合根作为产品目录投影注入 `PluginCatalogRuntime`，因此新快照构建期目录校验失败时整份候选被拒绝、候选实例全部关闭、旧快照继续服务；校验成功后视图随快照一起发布，并按快照租用引用计数存活，在途 Run 不会看到已关闭的 adapter。

管理面不需要解释 Canvas 类型：平台把「快照 + 已配置插件」交给注册为 `PluginFunctionNamesProjection` 的投影，Canvas 从已冻结视图按 adapter 归属返回函数名，因此插件详情里的 `canvasFunctionNames` 始终与执行使用的目录一致。

## Function Run

Canvas 负责 Run 的状态、调度、资源保护与人工决议；能力契约见上一节。

### 状态与资源保护

[`CanvasFunctionRun`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/CanvasFunctionRun.java) 只描述业务状态：`READY` / `RUNNING` 可被调度，`SUCCEEDED` / `CANCELLED` / `FAILED` / `UNKNOWN` 退出自动调度；`FAILED` 与 `UNKNOWN` 必须带 `error`，`SUCCEEDED` 不得带。`canvas_function_run` 以 `node_id` 为主键，每个 Function 节点只有一行当前/最后一次 Run；何时可领取、谁持有执行租约由共享的 [Durable Work](work.md) 中 `CANVAS_RUN` 行管理。

[`CanvasFunctionResourcePin`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/CanvasFunctionResourcePin.java) 保护 Run 用到的资源：`INPUT` 保护启动时冻结的引用资源，`OUTPUT` 在目标资源物化时写入；pin 按 `(canvasId, nodeId, requestId)` 整体释放，只保护无 owner 资源不被回收，绝不参与 `storage_blob.ref_count`。

### 启动

[`CanvasFunctionRunTransactions.start`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionRunTransactions.java) 的锁序固定为 document → node → run：

```text
同 requestId：返回既有 Run（精确重放优先于一切校验）
现有 Run 活跃且 requestId 不同：conflict
租用目录 -> 按名找函数（已移除即 conflict）-> 比较完整定义指纹与 expectedVersion
-> 严格解码 args -> 冻结引用 manifest（节点存在、index 在范围内且是 blob）与媒体事实
-> 按输出计划预分配 resource id，构造 submitState=PENDING 的冻结计划 -> adapter.preflight
-> 释放上一个 Run 的 pin -> 写入 READY Run（同事务请求 Work）-> 写 INPUT pin -> revision + 1
```

客户端必须回传它从目录读到的定义指纹；函数被移除或同名函数换了定义只会得到 conflict，既不自动重试，也不退回当前同名定义。目录租用保持到事务 `afterCompletion`。冻结计划保存完整函数定义，之后用户改配置、解除引用或替换源资源都不改变这个 Run 的输入。

### 领取、执行与终态

[`CanvasFunctionWorkStore.claimNext`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/CanvasFunctionWorkStore.java) 从 `durable_work` 以 `SKIP LOCKED` 领取一条到期 Work，再在单次启动的短事务中按 Run → Work 顺序锁行，确认同一 lease token 在数据库时间下仍有效，才把 Run 推进为 `RUNNING` 并递增 attempt；失权立即返回无任务，终态、`UNKNOWN` 或已删除的 Run 则清理 Work 后继续扫描。执行中的持有者写入都在 SQL 里要求同一 `request_id`、`status='RUNNING'`，且同一 lease token 在数据库时钟下仍持有租约；token 过期或被接管时写入影响 0 行，旧 worker 的回调只能变成 no-op 或静默退出。

[`CanvasFunctionRuntimeConfiguration`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionRuntimeConfiguration.java) 直接装配 [`BoundedWorkDispatcher`](../../work/src/main/java/fun/fengwk/kkstudio/work/BoundedWorkDispatcher.java)，由周期 poll 驱动；`durable_work` 通道上的 `CANVAS_RUN` 提示只降低延迟，正确性来自 poll 与租约。没有第二层内存队列，本地容量满时把 Run 还回并推迟。

[`CanvasFunctionWorker.run`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionWorker.java) 执行两阶段协议：

1. 从 state 解码冻结计划，不查询当前目录；
2. `submitState == SUBMITTING` 说明提交意图已持久化但进程崩溃，结果不明，转 `UNKNOWN` 并保留 pin 与目标资源，绝不重新提交；
3. 租用当前目录：尚未提交的 Run 要求同名定义与冻结定义完全一致，已提交的 Run 只要求同名函数仍存在；先持久化 `SUBMITTING`，再调用 adapter `submit`，确认后持久化 `SUBMITTED`；
4. 调用 `execute` 查询并物化预分配槽位，返回值必须恰好是冻结的目标 id，然后 `completeSuccess`。

heartbeat 按固定间隔续租，不触碰业务事实；续租失败即停止后续写入。adapter 抛普通异常时 Run 进入 `FAILED`，抛 `CanvasFunctionUnknownException` 时进入 `UNKNOWN`。外部计算（HTTP、轮询、媒体处理）永远在数据库事务之外。

- **成功**要求输出计划的每个槽位都已物化且与槽位一一对应：媒体槽位是同画布、无 owner、有 blob 且 MIME 与 kind 匹配的资源，`TEXT` 槽位只有文本；随后整组替换节点当前资源，Run 置为 `SUCCEEDED` 并释放 pin。绝不发布半成品数组。
- **失败与取消**先写终态，再释放 pin，最后丢弃无 owner 的计划输出；取消来自客户端，CAS 只要求 Run 活跃，不需要租约。
- **人工决议**由 [`CanvasFunctionService`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/CanvasFunctionService.java) 接收：必须给出核对说明，[`CanvasFunctionUnknownResolution`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/CanvasFunctionUnknownResolution.java) 的 `RESUME` 把 Run 还回 `READY`（只重新查询，不重新提交），`FAILED` / `CANCELLED` 释放 pin 与未挂接输出。取消与 `resolve(CANCELLED)` 在事务完成后 best-effort 调用 adapter 的 `cancel`。UNKNOWN 的取消与删除要求先完成核实；租约围栏防止旧 Worker 发布结果，外部是否执行由查询或人工核查确认，取消请求的接受与外部取消成功分别判断。

同一槽位重复物化返回既有 Resource 行，不新增行、不重复 pin，因此部分物化后崩溃的 Run 恢复后只补齐缺失槽位。

## 宿主适配

依赖平台能力的适配集中在 [`platform`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/infra/platform/) 包，由 [`CanvasPlatformAutoConfiguration`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/infra/platform/CanvasPlatformAutoConfiguration.java) 与 Canvas 运行时分开装配，因此 Canvas 模块测试可以只加载运行时本身：

- [`PluginCanvasFunctionCatalogSource`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/infra/platform/PluginCanvasFunctionCatalogSource.java) 是 Canvas Function 目录的唯一生产实现：每次租用当前插件快照，并读取其中已冻结的 [`PluginCanvasFunctionCatalog`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/infra/platform/PluginCanvasFunctionCatalog.java) 目录；租用期间 adapter 只随快照一起退役。它的嵌套 `Lease` 就是执行路径的资源约定：`catalog` 读取冻结目录，`bind` 冻结完整定义，`require(binding)` 要求定义完全一致，`close` 幂等释放快照租用。Web 组合根以插件运行时构造它。
- 同一个冻结视图也服务管理面：Web 把 `PluginCanvasFunctionCatalog::functionNames` 注册为平台的 `PluginFunctionNamesProjection`，插件详情只从目录投影名称。
- [`PlatformCanvasFunctionBlobAccess`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/infra/platform/PlatformCanvasFunctionBlobAccess.java) 提供媒体事实、原件流与短期 URL。
- [`CanvasBlobResourceMaterializer`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/infra/platform/CanvasBlobResourceMaterializer.java) 物化输出：媒体写入全局 Blob，TEXT 内联在 Resource 行。媒体 `stage` 前后各一个短事务，按 document → run 加锁并复验当前 RUNNING owner 与数据库时钟下的租约；读流与对象存储暂存不持有数据库锁；后置事务插入无 owner Resource、写 OUTPUT pin 并转移上传引用。Worker 通过可选 provider 获取该具体组件，创建 Function 执行上下文时要求它存在。Resource 删除时经 [`CanvasBlobReleaser`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/infra/platform/CanvasBlobReleaser.java) 在同一事务释放 Blob 引用。媒体探针与预览属于平台存储（见 [Platform](platform.md#storageblob-与-resource)）。

媒体暂存后的竞争或事务失败会尽力释放未消费 upload；清理失败记录 upload ID 与异常类型，
不覆盖原结果，最终由上传过期清理兜底。

内置 adapter 只保留测试与 E2E 栈使用的 fake（[`FakeCanvasFunctionConfiguration`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/infra/function/fake/FakeCanvasFunctionConfiguration.java)，`KK_STUDIO_CANVAS_FUNCTION_FAKE_ENABLED=true` 才注册），它与插件贡献一样在快照构建期并入同一目录且不归属任何插件；`image.crop` 由 Media 插件提供，ComfyUI/H3 与 OpenCLI 函数由各自的构建期插件提供。

## HTTP 边界

[`canvas.http`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/http/) 是 Canvas 的 HTTP 适配层，由
[`CanvasHttpAutoConfiguration`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/http/CanvasHttpAutoConfiguration.java)
自动配置扫描并注册到宿主 MVC，因此宿主不需要知道具体控制器类型：

- [`CanvasController`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/http/CanvasController.java)：`/api/canvases` 的列表、快照与 typed command 批；接受时返回 patch，语义组前置条件过期返回 409 与受影响对象及服务端权威值。
- [`CanvasFunctionController`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/http/CanvasFunctionController.java)：`/api/canvas-functions` 目录与 `/api/canvases/{canvasId}/nodes/{nodeId}/function-run` 的启动、查询、取消与 UNKNOWN 决议。
- [`CanvasResourceController`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/http/CanvasResourceController.java)：资源下载与预览 URL 直读，从不返回 bucket 或对象 key。

三个控制器统一返回共享响应信封
[`ApiResponse`](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/http/ApiResponse.java)，边界错误以
`ResponseStatusException` 表达；信封状态同步、错误 envelope 与 locale 由宿主的全局 advice 统一处理（见
[Web 的 JSON 与错误边界](web.md#json错误与信任边界)）。控制器只做 HTTP 适配，不承载领域规则。

## 部署参数

[`CanvasFunctionRuntimeProperties`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionRuntimeProperties.java) 的前缀是 `kk-studio.canvas.function.runtime`：

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

- 领域与命令：[`CanvasCommandPlanner.java`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/CanvasCommandPlanner.java) 与同包的领域值、端口。
- HTTP 边界：[`http`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/http/) 包只做适配，新增或修改端点先改领域服务，再同步控制器与其 standalone MockMvc 测试。
- SQL：先看 [`V1__schema.sql`](../../schema/src/main/resources/db/migration/V1__schema.sql) 的 canvas 段与 [Schema](schema.md) 的重建规则，再改 [`postgresql`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/) 下对应的 JdbcStore 与集成测试。
- Function 运行时：[`function`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/infra/function/) 目录下的 dispatcher、worker、transactions、codec 是一个整体，改所有权语义时同步检查 `CanvasFunctionRunJdbcStore` 的租约围栏与锁序。
- 新增能力：在插件模块实现 `CanvasFunctionAdapter`，由平台发布到插件快照。

测试入口：HTTP 边界由 [`CanvasControllerTest.java`](../../apps/canvas/runtime/src/test/java/fun/fengwk/kkstudio/canvas/http/CanvasControllerTest.java)、[`CanvasFunctionControllerTest.java`](../../apps/canvas/runtime/src/test/java/fun/fengwk/kkstudio/canvas/http/CanvasFunctionControllerTest.java)、[`CanvasResourceControllerTest.java`](../../apps/canvas/runtime/src/test/java/fun/fengwk/kkstudio/canvas/http/CanvasResourceControllerTest.java) 在 standalone MockMvc 上覆盖，[`CanvasFunctionWireContractTest.java`](../../apps/canvas/runtime/src/test/java/fun/fengwk/kkstudio/canvas/http/CanvasFunctionWireContractTest.java) 锁定版本与请求体的 wire golden（前端测试共用同一份资源）；[`CanvasCommandPlannerTest.java`](../../apps/canvas/runtime/src/test/java/fun/fengwk/kkstudio/canvas/CanvasCommandPlannerTest.java) 与同包规划测试覆盖前置条件与冲突判定；[`CanvasFunctionStartTest.java`](../../apps/canvas/runtime/src/test/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionStartTest.java)、[`CanvasFunctionSubmitRecoveryTest.java`](../../apps/canvas/runtime/src/test/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionSubmitRecoveryTest.java)、[`CanvasFunctionTerminalWritebackTest.java`](../../apps/canvas/runtime/src/test/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionTerminalWritebackTest.java) 与 [`CanvasFunctionOutputPublishingTest.java`](../../apps/canvas/runtime/src/test/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionOutputPublishingTest.java) 在真实库上按不变量验证启动、两阶段提交与恢复、终态围栏和输出发布，共享夹具集中在 [`CanvasFunctionRuntimeTestSupport.java`](../../apps/canvas/runtime/src/test/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionRuntimeTestSupport.java)；[`CanvasFunctionWorkStoreIntegrationTest.java`](../../apps/canvas/runtime/src/test/java/fun/fengwk/kkstudio/canvas/infra/postgresql/CanvasFunctionWorkStoreIntegrationTest.java) 验证多实例领取、租约恢复与围栏；[`CanvasBlobResourceMaterializerIntegrationTest.java`](../../apps/canvas/runtime/src/test/java/fun/fengwk/kkstudio/canvas/infra/platform/CanvasBlobResourceMaterializerIntegrationTest.java) 在平台测试宿主（`platform-runtime` test-jar）上验证输出物化与 Blob 引用转移。数据库测试需要 Docker（或本地 PostgreSQL），不可用即失败；revision 通知 Hub 的注册竞态、resync 与退订语义由 [`CanvasVersionNotificationsTest.java`](../../apps/canvas/runtime/src/test/java/fun/fengwk/kkstudio/canvas/notification/CanvasVersionNotificationsTest.java) 覆盖，通道上的真实 `pg_notify` 投递由 [`CanvasRevisionNotificationIntegrationTest.java`](../../apps/canvas/runtime/src/test/java/fun/fengwk/kkstudio/canvas/infra/postgresql/CanvasRevisionNotificationIntegrationTest.java) 覆盖。

---

上级：[系统设计](../system-design.md)。相关文档：[Platform Plugin API](platform-plugin-api.md)、[Platform](platform.md)、[Durable Work](work.md)、[Schema](schema.md)、[Web](web.md)。
