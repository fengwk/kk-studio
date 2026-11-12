# Web 模块

[`WebApplication`](../../web/src/main/java/fun/fengwk/kkstudio/web/WebApplication.java)
是生产 Spring Boot 组合根：装配 Platform、Project、Canvas/Harness runtime、
Contributor 和构建期 Plugin，提供 REST、静态 SPA、浏览器事件与 Daemon WebSocket。
Platform 与 Harness 的 Controller、DTO 和编排位于 `platform/runtime`，Chat、Canvas、Project 与 IDE 的
Controller 和 DTO 位于各自产品模块。Web 装配这些接口以及组合根自身的传输入口；领域规则、映射和事务由所属模块持有。
HTTP 边界统一配置 JSON、参数绑定与错误映射，Web 不直连业务表或业务 repository。

## 组合与生命周期

[`web/pom.xml`](../../web/pom.xml) 声明应用模块、Web transport、Flyway 和 runtime Plugin。
产品、Canvas、Harness 与 Builtin 模块由 web 直接声明；Common 经 Platform compile 依赖传递进入发行物。
Plugin JAR 通过自己的 AutoConfiguration.imports 创建 bean，
[`PluginCatalogConfiguration`](../../web/src/main/java/fun/fengwk/kkstudio/web/runtime/PluginCatalogConfiguration.java)
装配唯一的 `PluginCatalogRuntime`：它为新安装的 Plugin 写入默认配置，收集真正内置的
Contributor / Adapter，按当前配置构建完整 `PluginCatalogSnapshot`。HarnessCatalog 由平台聚合，
Canvas 目录则作为产品目录投影在同一次构建里冻结（[`PluginCanvasFunctionCatalog`](../../apps/canvas/runtime/src/main/java/fun/fengwk/kkstudio/canvas/infra/platform/PluginCanvasFunctionCatalog.java)）；
任一目录校验失败都会释放已建插件实例并保留旧快照。容器不暴露静态快照或目录 bean。
同一个冻结视图还注册为平台的 `PluginFunctionNamesProjection`，插件详情的 `canvasFunctionNames`
因此与执行使用同一份目录，平台不需要 import Canvas 类型。
`PluginCatalogRefresher` 用单线程每 5 秒回读配置，并合并本节点提交后的唤醒和共享
`PostgresqlNotificationLoop` 的 `plugin_changed` 通知；建连/重连同样触发对账。
通知只排队，不信任 payload，也不在 LISTEN 线程上配置插件；漏通知由周期回读追平。
修订变化时整体重建并原子发布，旧快照直到最后一个活动租用释放才关闭。
存取见 [Plugin 配置存储](platform.md#plugin-配置存储)。
Platform 的 RuntimeToolCatalogConfiguration 加入现读数据库的 MCP 目录。
Canvas HTTP 目录查询在一次 `PluginCanvasFunctionCatalogSource` 租用内完成，输出定义与可用性来自同一快照；
Run 的完整定义只保存在执行状态中，普通 Run DTO 不回显它。
目录每项都携带 `version`（`definitionFingerprint` 字符串）；
`POST /api/canvas/canvases/{canvasId}/nodes/{nodeId}/function-run` 必带同形状 `expectedVersion`，
缺失或非法 fingerprint 一律 400，
该函数已移除或定义已变化一律 409，且不产生 Run、pin 或外部调用；cancel 只带 `requestId`。

[`HarnessRuntimeConfiguration`](../../web/src/main/java/fun/fengwk/kkstudio/web/runtime/HarnessRuntimeConfiguration.java)
是 Harness 组合根：把 datasource/transaction manager 接入 PostgresqlBuiltinHarnessStore，
装配唯一的 Builtin Driver、资源工具目录、PluginResourceGateway、HarnessRuntime、
Harness 变更订阅源（`HarnessChangeNotifications` 同时充当内部观察者的纯 wake 源）、请求诊断/预览，以及 Work dispatcher 和它的 drain/worker/poll 执行器与生命周期。
Processor、realtime 与 Environment 会话由它显式导入，不在核心类里再声明一遍。

[`HarnessProcessorConfiguration`](../../web/src/main/java/fun/fengwk/kkstudio/web/runtime/HarnessProcessorConfiguration.java)
装配重试与模型 HTTP 策略、lease/processor 配置、三个 Processor、
processor scheduler、heartbeat worker、model executor，以及退出确认 journal、confirmer 与 retry。
timer 只唤醒和分派，heartbeat worker 执行续租事务，model executor 执行 checkpoint 提交。

[`HarnessRealtimeConfiguration`](../../web/src/main/java/fun/fengwk/kkstudio/web/runtime/HarnessRealtimeConfiguration.java)
装配通知 codec、超限 payload 缓冲、本地优先 realtime 总线，以及流式追帧 codec 与应用服务。

[`HarnessEnvironmentConfiguration`](../../web/src/main/java/fun/fengwk/kkstudio/web/runtime/HarnessEnvironmentConfiguration.java)
装配远端退出对账专用虚拟线程 Executor 与 RemoteExitReconciler，并把 dispatcher 唤醒、远端退出事实对账与 IDE 租约心跳收进同一个会话监听器。
另一个 Daemon 实例接管时，连接服务关闭前一代会话，其隧道子流随之失败；旧代际 IDE 票据与 cookie 在下次使用时按 `IdeGenerationState` 的 `REPLACED` 拒绝，会话探测返回 409。
PluginResourceGateway 留在 Harness 组合内，因为它需要 Harness 会话或 store。各动作在监听器内通过 ObjectProvider 延迟解析并各自隔离运行时异常。

| 生命周期 owner | 启停职责 |
| --- | --- |
| `HarnessRuntimeLifecycle` | 节点优雅退出排空编排（Phase `Integer.MAX_VALUE`，早于 `ClusterNodeLifecycle`）：1) 停止 claim 与本地执行准入并标记排空；2) 按 `LocalExecutionLifecycle` 的物理句柄在 `drainTimeout` 内等待 body 与资源结束；3) 使用同一总 deadline 的剩余预算中断未结束的本地 body、`EnvironmentDaemonServer.drain()` GOAWAY 并释放环境租约、交由 `ToolProcessor.drain()` 移交可 reattach 的远端工具并 release TOOL Work；4) 已产生的退出证据先持久化，无法证明退出时保留日志目录锁到 JVM 退出。默认 `60s + 30s = 90s`，下层不得各自重开预算；线程池由同一 deadline 的 shutdown/await 收尾，不另做无界 close 等待；`workersEnabled=false` 时不启动 dispatcher |
| `issueReconcileRuntimeLifecycle` | workers-enabled 控制 dispatcher 启动，控制/查询 bean 仍可用 |
| `canvasFunctionDispatcherLifecycle` | 启动 wake，周期 poll，stop 关闭 drain；独立于 Harness worker 开关 |
| PostgresqlNotificationLoop | 专用连接 LISTEN，断线重连/resync，关闭 abort、interrupt、有界等待 |
| ApplicationEventHub、sender 与 heartbeat scheduler | 关闭订阅与发送围栏，释放连接和 timer |
| StorageMaintenance、PluginCredentialRefreshDispatcher | 各自持有调度器与在途任务的收尾责任 |
| PluginCatalogRuntime | 唯一拥有当前快照与插件实例，关闭禁止新租用，已有租用释放后回收实例 |
| PluginCatalogRefresher | 启动立即对账，周期与通知合并排队；停止后不开始待执行对账，有界等待在途任务，前代退出前禁止重启 |

## HTTP 入口

JSON Controller 的成功响应直接返回资源，status 与业务结果对齐；
health、资源下载和 WebSocket 使用各自传输形式。业务路由按所属命名空间组织，完整方法、参数与响应以
生成的 [`ApiRoutes`](../../frontend/src/shared/api/generated/contracts.ts) 为准：

| family | 路径 | 用途 |
| --- | --- | --- |
| Health | `/actuator/health` | 健康探针 |
| Catalog | `/api/platform/{providers,models,model-catalog,tools,agents}` | Provider/MODEL/GROUP/Agent CRUD、工具候选、Provider 远端模型 ID（`providers/{name}/remote-models`）、新建模型的目录候选与手动刷新（`model-catalog`、`model-catalog/refresh`）；Provider credential 用 SecretUpdate 且只回 `configured`；Provider/Model/Agent 列表直接返回数组，Model 的 PUT/DELETE 为 `/models/{*name}`，名称可含斜杠；变更使用 expectedVersion |
| Skill | `/api/platform/skill-packages`、`/api/platform/skill-package-stagings` | CRUD、branch check、exact commit 发布、上传暂存与发布、归档读取；Git 令牌用 SecretUpdate，响应只回 `configured` |
| MCP | `/api/platform/mcp-servers` | name-keyed CRUD、摘要数组、headers 用 SecretUpdate 且只回 `configured`、同步 discover |
| Plugin | `/api/platform/plugins` | 安全投影、普通配置 PUT、auth/prepare、auth/complete、删除 auth；`{pluginId}/check-connection` 按 expected 版本执行一次显式只读连接检查 |
| Chat | `/api/chat/chats` | 由 [Chat](chat.md) 模块提供的 CRUD、整体替换 PUT、YOLO 子资源与归属 Session；Web 只做装配 |
| Harness command | `/api/harness/command-batches`、`/api/harness/threads/{threadId}/command-batches` | 创建型 Chat 命令接受（owner-aware）与既有 Thread 的 owner-free 续写；都返回 202 |
| Session | `/api/harness/sessions/{sessionId}` | threads、entries（keyset 分页，含读取时 `usageCost`）与 name |
| Session files | `/api/harness/sessions/{sessionId}/files` | 只读会话文件面板：列目录、文本窗口（`/content`）、下载（`/download`）；会话坐标只在路径中，不携带产品 owner |
| Thread | `/api/harness/threads/{threadId}` | Snapshot、执行树、name、compact、yolo、stop、tool approval |
| Builtin | `/api/harness/builtin/...` | Model Request Debug、历史 Provider 请求重建与活动模型请求追帧 |
| Interaction | `/api/harness/interactions`、`/api/harness/threads/{threadId}/tool-invocations/{toolInvocationId}/input` | 等待分页（条目是 `INPUT`/`APPROVAL`/`ENVIRONMENT_WAIT` 判别联合，`total` 是同一过滤条件下真实可见的待处理计数）与问卷回答/拒答提交 |
| Canvas | `/api/canvas/canvases` | 创建、列表、Snapshot、commands、resource download/preview URL、节点 Function Run |
| Function catalog | `/api/canvas/functions` | 函数 schema、输出计划、可用性 |
| Storage | `/api/platform/storage/uploads`、`/api/platform/storage/blobs/{blobId}` | reserve/complete/delete 与短期 download/preview URL |
| Project | `/api/project/projects` | CRUD、workflow/yolo、archive/unarchive、Snapshot、Issue 创建（控制器在 [apps/project](project.md)） |
| Issue | `/api/project/issues/{issueId}` | CRUD、活动/证据、流转、阻塞/恢复、stop/继续、额度重置、重开（控制器在 [apps/project](project.md)） |
| Settings | `/api/platform/settings` | 聚合 GET/CAS PUT、编辑 schema |
| Configuration sync | `/api/platform/config-sync` | 清单、选择导出、导入检查与确认执行；响应禁止缓存 |
| Environment | `/api/platform/environments`、`/{id}/runtime`、`/{id}/install-codes`、`/{id}/install-script`、`/{id}/uninstall-script`、`/{id}/rotate-token`、`/{id}/update-daemon`、`/{id}/events` | 创建、读取与整体替换（`note` + `daemonConfig`）、当前 owner 运行快照、注册令牌轮换、一次性安装码与按卡片安装/卸载脚本、受管二进制在线更新、运维事件；创建和轮换响应及安装脚本含凭据 |
| Environment IDE | `/api/ide/environments/{id}/views`、`/ide/{environmentId}/{viewId}/` | 打开/关闭视图、切换目录与终端；同源 IDE 固定入口、bootstrap 票据与 HTTP/WebSocket 数据面 |
| Environment Daemon | `/ws/daemon` | Daemon 准入裁决与 WebSocket 升级端点，承载控制与数据传输 |

Agent 的创建、完整 PUT 和读取使用 `execution: {driver, settings}`。用户 Agent 必须配置，
内置 Agent 可显式为 null；当前 `builtin` 的 settings 为 `{modelName, variant}`。
Driver、模型与 Variant 的校验和引用事务见 [Platform Catalog](platform.md#catalog)。

`POST /api/chat/chats`、`PUT /api/chat/chats/{chatId}` 与 `PUT /api/chat/chats/{chatId}/yolo` 的字段语义见 [Chat](chat.md)。

`GET /api/harness/sessions/{sessionId}/resources/{resourceId}?download=false` 流式返回 Session 持有的资源用于预览；`download=true` 强制作为附件下载。接口仅允许 `session_blob_ref` 中存在该资源引用且 Blob 处于 ACTIVE 状态的 Session，其他情况返回 404；响应使用 Blob 的媒体类型，不返回对象存储地址，并设置禁止缓存与内容嗅探的安全头。

Session 的 `GET /api/harness/sessions/{sessionId}/threads` 返回全部 Thread 摘要，包含执行根和子 Thread，不按名称去重。每项 `parentThreadId` 必须存在：根为 null，子为执行父 Thread 的 canonical UUID string；根与子同名时仍是独立摘要。

Session 的 `GET /api/harness/sessions/{sessionId}/entries?cursor&limit` 按存储追加顺序（创建顺序）返回一页 Entry，
同一 Session 内父节点必先于子节点出现；`cursor` 是上一页最后一条 Entry 的 canonical UUID，
`limit` 默认 100、最大 200，响应为 `{items, nextCursor}`，`nextCursor` 为 null 表示末页。

命令 202 表示数据库已接受，执行由 dispatcher 异步推进。
`/api/harness/command-batches` 只服务允许通用命令直写的创建型产品 owner（当前为 `CHAT`；`NEW_SESSION` / `NEW_THREAD` / `NEW_FORKED_SESSION`；
`ISSUE_AGENT` 由 Issue 工作流拥有，被该端点拒绝）；`/api/harness/threads/{threadId}/command-batches`
服务既有 Thread 的 owner-free 续写（path `threadId`，body 不带 owner 与 target）：单条纯 `USER_MESSAGE` 批次不带 cursor（带了为 400），其余批次必须带精确 cursor。
Issue 输入与控制通过工作流用例，人工审批/问卷通过交互入口。
审批和问卷 actor 来自服务端认证主体；本地无认证模式使用固定 local-user。

Harness 命令、target 与消息内容都是 sealed union：每个 variant 只携带自己的字段（`NEW_SESSION` 携带 `rootSettings`；`NEW_THREAD` 携带 `threadName`；
`NEW_FORKED_SESSION` 携带 `sourceThreadId`），未知 variant/字段由严格 HTTP mapper 拒绝，续写面仍校验精确 cursor。
产品命令批为 SET_AGENT → SET_EXECUTION → SET_ENVIRONMENT 可选前缀和末尾 USER_MESSAGE；
`CUSTOM_MESSAGE` 与 `NOTIFICATION` 不在产品 HTTP 面。
附件在接受事务转为 Resource，图片档位随消息冻结。
Session/Thread 命名是独立控制面；响应状态取 Runtime Snapshot 的分类结果。
完整执行语义见 [Harness Runtime](harness-runtime.md)。

Thread 控制面按认证、资源权限与请求格式收紧，不按 Thread 的产品归属拒绝。
`GET /api/harness/threads/{threadId}` 的 Snapshot 携带 `thread.executionControl`（`RUNNABLE` / `STOPPED`）、
派生的 `thread.status`（`RUNNING` / `EXIT_PENDING` / `WAITING` / `IDLE`，只描述该 Thread 自身，不递归子树）、每条 Entry 的读取时 `usageCost`，以及快照级 `stopReceipts`；
`unconfirmedExecutions` 列出该 Thread 尚未退出的具体执行（`executionId`、`invocationId`、`attempt`、`kind`、`registeredAt`）。
可选的 `afterEntryId`（canonical UUID，否则 400）请求增量历史：游标是当前 head 的祖先时，`baseEntryId` 等于游标，
`entries` 只含游标之后的 Entry（游标即 head 时为空）；游标未知、位于其它分支或 Session 时返回完整历史，`baseEntryId` 为 null。
无论完整还是增量，Thread、队列、当前输出、工具与退出事实都是同一事务的完整当前值，`usageCost` 只投影本次返回的 Entry。
Snapshot 不携带手动压缩可用性：`GET /api/harness/threads/{threadId}/compaction-availability` 按需返回 `available` 与可空的
`disabledReason`，按完整历史判断 token 阈值、ownership 与模型。它只是提示；`POST /api/harness/threads/{threadId}/compact`
仍以 `expectedVersion` 与服务端判断为准。
`POST /api/harness/threads/{threadId}/stop` 以 root `expectedVersion` + 稳定 `stopRequestId` 执行精确 CAS，
回执 `status` 为 `STOPPED` / `REPLAYED`、`thread` 是目标权威投影、`stoppedThreads` 是本次完整受影响集合的
逐 Thread 持久回执（`threadId`、`stopRequestId`、`stoppedTurnEndEntryId`、`cancelledCommandCount`、
仅含 `USER_MESSAGE` 的 `cancelledInputs`）。
`POST /api/harness/threads/{threadId}/executions/{executionId}/abandon` 接受 `{"expectedVersion":"<bigint>"}`，显式放弃一个具体执行代次并返回最新 Snapshot；
它与 Snapshot 使用相同的认证与资源读取边界，子 Thread 也可放弃，首次放弃要求 `STOPPED` 与精确 version，重复放弃或已确认真实退出是安全 replay。

### 请求诊断与预览

`GET /api/harness/threads/{threadId}/tree` 单次返回该 Thread 所属执行树的执行根与全部 `SUBAGENT` 后代，排除内部 `COMPACTION` 节点；执行根可以是 `USER`、`CONTROLLER_TASK` 或 `PLUGIN_TASK`。目标 Thread 本身为 `COMPACTION` 时返回 404。节点字段包括 `threadId`、`kind`、显式可空的 `parentThreadId`、`name`、`agentName`、`execution`（`driver` / `settings`）、`status`（`RUNNING` / `EXIT_PENDING` / `WAITING` / `IDLE`）、`executionControl`（`RUNNABLE` / `STOPPED`）、`turnCount`、`toolCallCount`、`updatedAt` 和显式可空的 `outcome`（非 IDLE 状态一律为 null，严禁在等待子任务或活跃时宣称 COMPLETED）。它不返回 Entry、Command 或 Tool 参数与结果。非法 UUID 为 400，缺失 Thread 为 404。节点顺序为 `createdAt` 再 UUID。

`POST /api/harness/builtin/threads/{threadId}/model-request-debug` 以 UI 当前草稿 `{model, environmentName}`（`model` 为必填的 `{modelName, variant?}` 逻辑选择，`environmentName` 可空，null 表示未选 Environment）在真实 Thread snapshot 上做一次只读规划，返回下一次结构化预览与可空的活动冻结请求；每个 subagent 另带当前配置声明 `tools` / `skills` / `subagents` / `configuration`。冻结请求始终按原始 snapshot 与原路径物化，与草稿选择无关；全程排除 credential 和 Base64，不写库、不发请求。

历史请求重建只有一个只读入口：`GET /api/harness/builtin/sessions/{sessionId}/entries/{entryId}/provider-request`，
按该模型输出记录时间重建当时的请求前缀。它复用正式规划/物化/编码器，不推进游标、不调用 transport；
接受普通 assistant 输出，并对父 COMPACTION 结果通过冻结 child join 的 finalAnswer 前缀重建压缩子最后一次调用。
`kind` 恒为 `HISTORICAL_REQUEST_PREVIEW`，`bodyJson` 只代表当前 catalog / Provider 定义下的重建结果，不等于原始发送字节。
当前事实不允许精确重建时返回 409，客户端只按稳定的顶层 `reason` 恢复，不解析描述文本：

| `reason` | 拒绝事实 |
| --- | --- |
| `PREVIEW_PLANNING_FAILED` | 历史前缀无法规划 |
| `PREVIEW_PROVIDER_UNAVAILABLE` | 当前 Provider 无法解析，包括连接 generation 漂移 |
| `PREVIEW_UNSUPPORTED` | 冻结压缩子执行无法可靠定位，或 adapter 无请求体预览能力 |
| `PREVIEW_ENCODING_FAILED` | 请求体编码失败 |

规划详情、连接凭据与异常原因链不进入错误响应；请求形状或归属错误仍为 400，缺失 Thread 为 404。

Canvas commands 返回 patch，Function start 返回 202；资源读模型按 `type` 分为 `TEXT`（内联文本）与 `BLOB`（blob 引用）。
Canvas Function 的 UNKNOWN resolve 要求 resolution 与非空 verification。Issue 结果未定的停止按 FAILED 收尾并以
ERROR 暂停，`error`/`pauseDetail` 明确说明结果未知，只能由显式 resume 继续。
Evidence 接收 READY uploadId，在事务内转移引用并返回规范资源标识，展示名取权威上传行。

## 同源 IDE 网关

同源 IDE 网关在 Studio 同源下固定 `/ide/{environmentId}/{viewId}/` 路由：
按路径严格解析 canonical UUID 的 `environmentId` 与 `viewId`。跨节点控制命令经由
[`ClusterEnvironmentIdeInternalController`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/http/ClusterEnvironmentIdeInternalController.java)
进行 HMAC 认证与 owner 转发。

- **Bootstrap 与会话隔离**：一次性票据请求由 `__ide/bootstrap?token=...` 消费，并在响应中通过 `Set-Cookie` 下发
  `Path` 限定在 `/ide/{environmentId}/{viewId}/` 的 HttpOnly cookie；重定向严格保持在当前环境 route base 之下。
  Studio 与受管环境属于同一可信浏览器边界（非沙箱隔离），不需要独立 IDE 域名或主机模板。
- **会话探测**：`__ide/session` 只读、不触达 Daemon，返回 204（会话有效）、401（无会话）、409（代际已被另一个 Daemon 实例接管）或 503（暂无 READY 连接，同一实例重连后原 cookie 继续有效）。前端据此决定等待还是重新打开视图。
- **开发代理**：本地开发时 Vite 代理 `/ide` 配置 `changeOrigin: false, ws: true`，保留浏览器原始 Host 与 Origin 以对齐同源校验。

## JSON、错误与信任边界

JSON Controller 直接返回资源本身：读取、更新与动作 200，创建 201，异步受理 202，无内容 204 且无响应体。
下载、HTML 与字节流保留原始响应。

集群内部端点（`/internal/**`）与浏览器 API 使用相同的 problem+json 错误格式。内部认证失败固定为
401 `unauthorized`，不回显认证原因；请求体超限保留 413 `validation`。内部客户端使用有界响应读取和
[`ClusterInternalErrors`](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/cluster/ClusterInternalErrors.java)
解析机器类别、原因和详情。

[`StrictJacksonConfiguration`](../../web/src/main/java/fun/fengwk/kkstudio/web/StrictJacksonConfiguration.java)
定制 Boot4 装配的唯一 Jackson 3 `JsonMapper`（它与其父类型 `ObjectMapper` 是同一 bean）：
启用重复键检测、Long 字符串序列化和 DTO 声明顺序，默认省略 null。
全仓仅装配官方 Jackson 3 mapper，classpath 无 Jackson 2 databind；
自有代码只解析到该 Jackson 3 mapper；HTTP DTO 的严格字段规则直接由 Jackson 3 注解声明
（例如 `protocolOptionsJson` 与网络文本字段只接受 JSON 字符串 token）。`protocolOptionsJson` 的内容语法、64 KiB 上限与 canonical 化
由 harness-model 的 `ProviderProtocolOptions` 在平台解析配置时统一执行，HTTP mapper 只检查 wire 形状，不重复解析选项。
wire DTO 归属各领域模块：平台族在 `platform.runtime` 的 `<feature>.dto`（Harness DTO 在 `platform.harness.dto`），Canvas 与
Project 在各自 `dto` 包。持久化 JSON 的领域 codec 复用
[`JsonCodecSupport`](../../harness/model/src/main/java/fun/fengwk/kkstudio/harness/model/codec/JsonCodecSupport.java)
的节点读取、精确字段集合与“不回声上游字段集合”校验原语，各 codec 只声明字段集合和值类型。
required-nullable 响应字段由 DTO 显式 ALWAYS。[`HttpBoundaryConfiguration`](../../web/src/main/java/fun/fengwk/kkstudio/web/HttpBoundaryConfiguration.java)
从容器 mapper 派生仅供 HTTP 消息转换器使用的配置，拒绝未知字段、标量强转、基本类型 null、重复键和尾随内容，
不改变持久化、集群客户端和插件使用的 mapper。Long 在请求与响应中均为规范非负十进制字符串，
DTO 使用 `Long` / `long`，HTTP 边界统一解析并拒绝非法值。请求体按字段或 record 组件的 `@Nullable`
统一检查必填性并递归检查 DTO、集合与 Map；未标注的属性缺失或为 null 时报告属性路径。
创建请求中的 Provider credential、MCP headers 与 Git 技能包 token 是可空 `SecretUpdate`，非空时只接受
`SET`；更新请求中的对应密钥字段必填，`KEEP` / `CLEAR` 必须显式提交，读取投影只返回 `configured`。
UUID 路径和查询参数统一按 canonical 小写文本解析，非规范形式拒绝。领域 codec 另有严格语法校验。

领域失败由 [`DomainException`](../../common/src/main/java/fun/fengwk/kkstudio/common/error/DomainException.java)
表达，全局 [`ProblemAdvice`](../../web/src/main/java/fun/fengwk/kkstudio/web/advice/ProblemAdvice.java)
按错误类别、原因或框架状态生成 problem 响应。领域版本坐标和 Canvas 冲突详情放在 `errors` 中，内部
`resource` 不进入 wire。请求体含未知字段或标量类型/格式错误时，`detail` 指出字段路径与错误类别；
无法定位到字段或语法本身损坏时使用固定文案，任何情况都不回显输入。未预期异常不暴露内部消息。
locale 支持 en-US/zh-CN，其他回退英文。错误类别与成员见[系统设计的 HTTP 契约](../system-design.md#http-契约)。

用户认证、TLS 和 ingress 访问控制由部署边界提供。
配置同步导出包含所需凭据；三个端点的成功和错误响应均为 `no-store`，请求体与 YAML 不记日志。
Daemon 准入请求同时携带卡片 UUID、进程 UUID 与协议版本，服务端先核对 UUID 与注册凭据所属卡片一致再取得租约；通用 Environment 响应只返回安全投影，不回显 token。
Plugin auth 请求 no-store，凭据只在数据库中保存，任何响应都不回显。
Storage/Canvas 签发有限期 URL，客户端使用受控存储入口。
Provider 上游错误正文沿其协议契约进入持久记录与客户端，可能包含上游回显的敏感信息；
处理边界见 [Harness Model](harness-model.md#上游错误原样透传)。

## 通知与浏览器事件

[`ApplicationEventConfiguration`](../../web/src/main/java/fun/fengwk/kkstudio/web/events/ApplicationEventConfiguration.java)
共享一个 PostgreSQL LISTEN 连接，路由调度器唤醒（Harness Work / Issue Work / Canvas Function）、
Thread version、Canvas revision、Project changed、Settings、执行树、交互、环境失效与 Plugin 目录变更；realtime 走独立的 LISTEN 连接。
建立/重连后通知每个 handler resync；单 handler 失败隔离，durable 恢复仍由回读与 poll/lease 完成。
通道、payload 与进程内 fan-out 归各自生产方所有：Harness 的 Thread version、执行树与交互失效由
[`HarnessChangeNotifications`](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/notification/HarnessChangeNotifications.java)
给出 channel 与回调绑定；Canvas revision 属于 [Canvas](canvas.md#revision-通知) 的 `CanvasVersionNotifications`；
Project 快照失效属于 `ProjectChangeNotifications`；环境失效属于
[`EnvironmentChangeNotifications`](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/environment/EnvironmentChangeNotifications.java)。
Web 只把生产方的 channel 登记到 LISTEN 连接，把通知与 resync 转交给生产方入口，再把事件转发给浏览器。

消费侧 [`ApplicationEventHub`](../../web/src/main/java/fun/fengwk/kkstudio/web/events/ApplicationEventHub.java)
只依赖公开的 Harness 契约 `harness.api.HarnessChangeSubscriptions` 与通用的
`VersionNotificationHub` / `NotificationInvalidationSource` 机制，不持有逐领域的失效中转类。`environment_changed`
payload 是 Environment ID，只驱动环境回读，并在同一 handler 内要求交互失效源全量 resync。

`POST /api/harness/builtin/threads/{threadId}/stream-catchup` 是浏览器追帧入口，body 为
`{invocationId: canonical UUID, attempt: 正整数}`。服务端先用目标 Thread 的 durable 快照确认
invocation 归属与 attempt，再让本地 active owner 立即提交 checkpoint，成功固定返回 202；
请求成功只表示已接受，不承诺仍有 owner 或已经落盘。终态、过期 attempt 与不匹配关联都是 202 no-op，
非法 UUID/请求形状为 400，Thread 不存在为 404。
没有本地 owner 时只有存在其它活跃集群节点才向专用 `harness_stream_catchup` channel 发布一次提示；
该 channel 与 `harness_realtime` 共用独立 realtime LISTEN 连接。接收节点只对本地 active owner 执行
attempt fence，不回广播、不回查 head；连接重连不重放追帧提示。数据库查询/发布失败直接向上传播为 5xx。

`/ws/events` 是唯一的浏览器事件通道，采用无版本帧（严格拒绝携带 `version` 或未知字段）。
连接建立后的第一帧是 `connected`，携带本次物理连接的 `eventNodeId` 与 `connectionId`；每次重连都是新身份，
浏览器据此把 IDE 视图绑定到当前连接，而不是沿用旧代际。
资源为 `{kind:thread|canvas|thread-tree,id}` 或全局 `{kind:projects|interactions|environments}`；
Thread `version` 与 Canvas `revision` 携 durable cursor，Thread `realtime` 是唯一的真负载事件，
`projects` / `thread-tree` / `interactions` / `environments` 只发失效提示，由客户端回读权威事实。
订阅先注册上游再读 cursor，sender 先排 subscribed ack 后 activate，
过滤陈旧版本；缓冲溢出折叠为 resync。

Thread 的首个本地订阅同时向平台订阅服务登记 `(threadId, nodeInstanceId)` 租约，末个订阅释放或 Hub close
时注销；登记失败只影响跨节点投递，本机 realtime 上游照常建立，并向该 Thread 缓冲一次 resync。租约随节点
15 秒心跳续租、45 秒过期，每个 Thread 独立续租，单个 Thread 的外键失败不会阻断其它 Thread。首次续租失败
与恢复都会对对应 Thread 触发 resync；每次成功续租记录单调时钟，心跳提前返回或长停顿使相邻成功续租间隔达到
租约时长时也触发 resync。租约是否有效只由数据库现在时判定，单调时钟仅用于识别本机续租间断。节点退出先封闭
订阅服务生命周期边界，再删除本节点全部订阅，迟到的登记或续租不得复活租约。
只有其它节点存在该 Thread 的有效订阅（订阅租约与 `cluster_node` 租约均未过期）时，普通 realtime 才跨节点
发布；流式追帧请求仍按“其它活跃节点”广播。
Environment 的连接状态不做推送：Environment 的 `status` / `statusExpiresAt` 在读取时由
`min(leaseUntil, lastSeenAt + heartbeatTimeout)` 派生，浏览器只按 `environments` 失效提示回读。
非法帧、过载和关闭按稳定错误与 WebSocket close code 收尾。

IDE 使用证明走同一条通道：客户端发送 `ide-use`，携带 `browserSessionId`、同一连接内单调递增的 `revision`
与 `views[]`（每项是 `environmentId`、`viewId`、`claimId`，已绑定视图再带 `bindingEpoch`）；
服务端回 `ide-use-accepted` 只确认 revision。帧字段集精确，多余或未知字段一律拒绝；
同一 revision 的相同内容幂等，不同内容是连接级冲突错误，更高 revision 原子替换整份列表。
`revision` 只是同一连接内的 usage 序号，不是 Daemon 的视图快照 revision。缺 `bindingEpoch` 的项
只是使用意图，不能用于续租；一个连接被接受后 `browserSessionId` 固定。该身份与当前
`eventNodeId` / `connectionId` 只证明当前页面连接的使用意图，不是绕过认证的授权凭据。

AsyncTextSender 每连接一个 in-flight frame，frame 数和 UTF-8 字节预算包含在途帧，
完成回调驱动下一帧。锁内转移队列，网络发送在锁外；失败关闭入队围栏并清理订阅。

Oversize realtime 事件不在通知里携带正文：owner 把完整 canonical event 放入短期有界内存，
`GET /internal/events/payloads/{payloadId}` 供其它节点按 `cluster_node` 有效租约与内部 HMAC 认证回拉。
端点只接受 canonical UUID，认证先于读取；payload 缺失、过期或被驱逐时返回 404。回拉总超时约 2 秒，
接收方校验 Thread 后本地投递一次，失败只触发 RESYNC，不影响 durable 执行。

## Daemon 端点

[`EnvironmentDaemonAdmissionController`](../../web/src/main/java/fun/fengwk/kkstudio/web/environment/EnvironmentDaemonAdmissionController.java)
提供 `/ws/daemon` 准入端点，使用 Jetty 原生 `JettyWebSocketServerContainer` 升级 HTTP 连接为 WebSocket（`maxBinaryMessageSize = 1 MiB`）。

准入阶段在升级前严格校验握手：
- 缺失或非 `websocket` 升级头：HTTP 400 Bad Request 或 426 Upgrade Required。
- `KK-Daemon-Protocol` 不等于 1：HTTP 426 Upgrade Required，body 为 protobuf 序列化的 `UpgradeRequired`，携带匹配请求平台的目标 Daemon 版本；无法解析发布目标时 body 为空，Daemon 转入失败终止。
- `Authorization: Bearer <token>`：缺失或校验失败返回 HTTP 401 Unauthorized；token 绝不记录入日志或回显到错误响应中。
- `KK-Daemon-Instance` 必须为规范小写 UUID 格式；非法或畸变参数返回 HTTP 400 Bad Request。
- 准入成功后返回 HTTP 101 Switching Protocols，并在锁外注册 10 秒超时定时器：若 Daemon 未在 10 秒内发起 HTTP/2 连接并成功建立控制通道，连接自动抛弃释放。

底层传输与会话裁决由 [Platform Daemon 连接服务](platform.md#daemon-连接服务)负责，协议细节见 [Harness Environment](harness-environment.md)。

## 启动与静态分发

默认 profile 为 dev；prod datasource 全部来自部署变量，缺失直接启动失败。
连接池与 driver 超时约束数据库不可达时的等待。S3 配置和 bucket 可访问性在启动验证。
Flyway 加载 Schema 的 canonical baseline，各 profile 选择受控 seed，见 [Schema](schema.md)。

Web 的 distribution profile 在 prepare-package 执行 npm ci/Vite build，将产物写入 target
再复制到 classes/static，由 Boot repackage 嵌入 Fat JAR。
SPA fallback 仅适用于 GET、非 API/actuator/ws/ide 且末段无扩展名的路径；真实 asset/Controller 优先。

部署参数、secret 与启动操作见 [部署与运行](../operations/deployment.md)，
跨模块配置归属见 [Platform 配置](platform.md#配置)。

## 测试入口

[`web/src/test`](../../web/src/test/java/fun/fengwk/kkstudio/web) 覆盖 Flyway、HTTP mapper/advice、owner 事务、Snapshot、ack-before-event、
通知重连与静态 SPA。Harness 端点测试按端点族拆分，领域夹具集中在 `HarnessRuntimeTestFixtures`；
架构与依赖边界由 [`check.mjs`](../../scripts/dev/verify/repository/check.mjs) 统一检查，模块内不重复写架构测试。
Spring 集成基座使用真实 PostgreSQL。
[`IdeProductionChainAcceptanceTest`](../../web/src/test/java/fun/fengwk/kkstudio/web/environment/ide/IdeProductionChainAcceptanceTest.java) 以真实 Go Daemon 与 code-server 验证受管视图；
Frontend 的 Playwright 回归使用路由夹具，不替代真实 code-server 链路。执行命令和 E2E 分类见 [开发与测试](../operations/development-and-testing.md)。

上级：[系统设计](../system-design.md)。相关文档：[Platform](platform.md)、
[Canvas](canvas.md)、[IDE](ide.md)、[Frontend](frontend.md)。
