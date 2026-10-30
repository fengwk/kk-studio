# Web 模块

[`WebApplication`](../../web/src/main/java/fun/fengwk/kkstudio/web/WebApplication.java)
是生产 Spring Boot 组合根：装配 Platform、Project、Canvas/Harness infra、Runtime、
Contributor 和构建期 Plugin，提供 REST、静态 SPA、浏览器事件与 Daemon WebSocket。
Controller 解析 DTO、调用用例、映射结果；领域行为与事务由相应模块持有。

## 组合与生命周期

[`web/pom.xml`](../../web/pom.xml) 声明应用模块、Web transport、Flyway 和 runtime Plugin。
Builtin/Common 经 Platform compile 依赖传递进入发行物。
Plugin JAR 通过自己的 AutoConfiguration.imports 创建 bean，
[`PluginCatalogConfiguration`](../../web/src/main/java/fun/fengwk/kkstudio/web/runtime/PluginCatalogConfiguration.java)
装配唯一的 `PluginCatalogRuntime`：它为新安装的 Plugin 写入默认配置，收集真正内置的
Contributor / Adapter，按当前配置构建完整 `PluginCatalogSnapshot`，HarnessCatalog 与 CanvasFunctionCatalog
来自同一快照；构建失败会释放已建插件实例。容器不暴露静态快照或目录 bean。
`PluginCatalogRefresher` 用单线程每 5 秒回读配置，并合并本节点提交后的唤醒和共享
`PostgresqlNotificationLoop` 的 `plugin_changed` 通知；建连/重连同样触发对账。
通知只排队，不信任 payload，也不在 LISTEN 线程上配置插件；漏通知由周期回读追平。
修订变化时整体重建并原子发布，旧快照直到最后一个活动租用释放才关闭。
存取见 [Plugin 配置存储](platform.md#plugin-配置存储)。
Platform 的 RuntimeToolCatalogConfiguration 加入现读数据库的 MCP 目录。
Canvas HTTP 目录查询在一次 `CanvasFunctionCatalogSource` 租用内完成，输出定义与可用性来自同一快照；
Run 的完整定义只保存在执行状态中，普通 Run DTO 不回显它。
目录每项都携带 `version`（`definitionFingerprint` 字符串）；
`POST /api/canvases/{canvasId}/nodes/{nodeId}/function-run` 必带同形状 `expectedVersion`，
缺失或非法 fingerprint 一律 400，
该函数已移除或定义已变化一律 409，且不产生 Run、pin 或外部调用；cancel 只带 `requestId`。

[`HarnessRuntimeConfiguration`](../../web/src/main/java/fun/fengwk/kkstudio/web/runtime/HarnessRuntimeConfiguration.java)
将 datasource/transaction manager 接入 PostgresqlHarnessStore，
装配 realtime source/sink、三个 Processor 与 Work dispatcher。
timer 只唤醒和分派，heartbeat worker 执行续租事务，flush executor 执行 checkpoint 提交。
该组合根还装配 Skill 同步和 PluginResourceGateway，因为它们需要 Harness 会话能力。

| 生命周期 owner | 启停职责 |
| --- | --- |
| HarnessRuntimeLifecycle、IssueReconcileRuntimeLifecycle | workers-enabled 控制 dispatcher 启动，控制/查询 bean 仍可用 |
| CanvasFunctionDispatcher | 启动 wake，周期 poll，stop 关闭 drain；独立于 Harness worker 开关 |
| PostgresqlNotificationLoop | 专用连接 LISTEN，断线重连/resync，关闭 abort、interrupt、有界等待 |
| ApplicationEventHub、sender 与 heartbeat scheduler | 关闭订阅与发送围栏，释放连接和 timer |
| StorageMaintenance、PluginCredentialRefreshDispatcher | 各自持有调度器与在途任务的收尾责任 |
| PluginCatalogRuntime | 唯一拥有当前快照与插件实例，关闭禁止新租用，已有租用释放后回收实例 |
| PluginCatalogRefresher | 启动立即对账，周期与通知合并排队；停止后不开始待执行对账，有界等待在途任务，前代退出前禁止重启 |

## HTTP 入口

JSON Controller 使用 ResultEnvelope，status 与业务结果对齐；
health、资源下载和 WebSocket 使用各自传输形式。公开路由如下：

| family | 路径 | 用途 |
| --- | --- | --- |
| Health | `/healthz` | 存活探针 |
| Catalog | `/api/providers|models|agents|tools|model-defaults` | Provider/MODEL/GROUP/Agent CRUD、工具候选、Provider 远端模型 ID（`providers/{name}/remote-models`）、新建模型的目录默认参数候选与手动刷新（`model-defaults`、`model-defaults/refresh`）；Model 的 PUT/DELETE 为 `/models/{*modelName}`，名称可含斜杠；变更使用 expectedVersion |
| Skill | `/api/skill-packages` | CRUD、check、exact commit update |
| MCP | `/api/mcp-servers` | name-keyed CRUD、显式 no-store config、同步 discover |
| Plugin | `/api/plugins` | 安全投影、普通配置 PUT、auth/prepare、auth/complete、删除 auth；`{pluginId}/connection-check` 按 expected 坐标执行一次显式只读连接检查 |
| Chat | `/api/chats` | CRUD 与归属 Session |
| Harness command | `/api/command-batches`、`/api/threads/{threadId}/command-batches` | 创建型 Chat 命令接受（owner-aware）与既有 Thread 的 owner-free 续写；都返回 202 |
| Session | `/api/sessions/{sessionId}` | threads、entries（含读取时 `usageCost`）、name、草稿/历史请求预览 |
| Thread | `/api/threads/{threadId}` | Snapshot、执行树、name、Debug、协议预览、流式追帧（`stream-catchup`）、compact、yolo、stop、tool approval |
| Agent task | `/api/agent-tasks`、`/{ticketId}`、`/{ticketId}/stop` | 提交独立执行的 Agent 任务（外部身份由 `ticketId`/`agent`/`prompt`/`maxTurns` 确定规范指纹，与 settings 无关；已存在票据校验指纹后直接重放而不重建物化配置，不同字段拒绝）、按票据观察状态与冻结结果、停止 |
| Interaction | `/api/interactions` | 等待分页（`total` 是同一过滤条件下真实可见的待处理计数）与 `/{interactionId}/input` 人工提交 |
| Canvas | `/api/canvases` | CRUD、Snapshot、commands、resource download/preview URL、节点 Function Run |
| Function catalog | `/api/canvas-functions` | 函数 schema、输出计划、可用性 |
| Storage | `/api/storage/uploads`、`/api/storage/blobs/{blobId}` | reserve/complete/delete 与短期 download/preview URL |
| Project | `/api/projects` | CRUD、workflow/yolo、archive/unarchive、Snapshot、Issue 创建 |
| Issue | `/api/issues/{issueId}` | CRUD、活动/证据、流转、阻塞/恢复、暂停/继续、stop、UNKNOWN 核查、额度重置、重开、归档 |
| Settings | `/api/settings`、`/api/settings/schema` | 聚合 GET/CAS PUT、编辑 schema |
| Configuration sync | `/api/settings/sync`、`/export`、`/import/check`、`/import` | GET 清单、POST 选择导出、导入检查与确认执行；响应禁止缓存 |
| Environment | `/api/environments`、`/{id}/runtime`、`/{id}/install`、`/{id}/update`、`/uninstall/{os}` | Card、当前运行状态快照、注册令牌轮换（明文只在创建与轮换响应中返回一次）、安装设置保存、安装命令与一次性安装码、卸载脚本、受管二进制在线更新（POST 发起并返回当前运行快照）、运维 events |
| Environment IDE | `/api/environments/{id}/ide/views`、`/environment-ide/{environmentId}/{viewId}/` | 打开/关闭视图、切换目录与终端；同源 IDE 固定入口、bootstrap 票据与 HTTP/WebSocket 数据面 |
| Environment data | `/api/harness/environment-data/v2/{environmentId}?ticket=...` | Daemon 复用数据 WebSocket（DATA_BIND）入口 |

`POST /api/chats` 省略 `yoloEnabled` 时为 `false`；创建请求的 `environmentName` 可省略或为 null（都表示无默认环境），
携带时必须为已存在的 Environment 名；响应中的 `environmentName` 是始终存在的 required-nullable 字段（未选择时为 null）。
`PUT /api/chats/{chatId}` 省略 `environmentName` 保留当前默认环境、显式 null 清空，
未知或非 canonical Environment 名 400 且当前值/version 不变。

Session 的 `GET /api/sessions/{sessionId}/threads` 返回全部 Thread 摘要，包含执行根和子 Thread，不按名称去重。每项 `parentThreadId` 必须存在：根为 null，子为执行父 Thread 的 canonical UUID string；根与子同名时仍是独立摘要。

命令 202 表示数据库已接受，执行由 dispatcher 异步推进。
`/api/command-batches` 只服务创建型 `CHAT` owner（`NEW_SESSION` / `NEW_THREAD` / `NEW_FORKED_SESSION`；
`ISSUE_AGENT` 由 Issue 工作流拥有，被该端点拒绝）；`/api/threads/{threadId}/command-batches`
服务既有 Thread 的 owner-free 续写（path `threadId`，body 不带 owner 与 target）：单条纯 `USER_MESSAGE` 批次不带 cursor（带了为 400），其余批次必须带精确 cursor。
Issue 输入与控制通过工作流用例，人工审批/问卷通过交互入口。
审批和问卷 actor 来自服务端认证主体；本地无认证模式使用固定 local-user。

Harness mapper 校验 canonical UUID、可放入 long 的规范十进字符串，
以及创建型 target 各自互斥字段（`NEW_SESSION` 携带 `rootSettings`；`NEW_THREAD` 携带 `threadName`；
`NEW_FORKED_SESSION` 必填 `sourceThreadId` 且禁止 `rootSettings`/`threadName`）与续写面的精确 cursor。
产品命令批为 SET_AGENT → SET_MODEL → SET_ENVIRONMENT 可选前缀和末尾 USER_MESSAGE/GOAL；
`CUSTOM_MESSAGE`、`NOTIFICATION` 与 `SET_CONTRIBUTOR_STATE` 不在产品 HTTP 面。
附件在接受事务转为 Resource，图片档位随消息冻结。
Session/Thread 命名是独立控制面；响应状态取 Runtime Snapshot 的分类结果。
完整执行语义见 [Harness Runtime](harness-runtime.md)。

Thread 控制面按认证、资源权限与请求格式收紧，不按 Thread 的产品归属拒绝。
`GET /api/threads/{threadId}` 的 Snapshot 携带 `thread.executionControl`（`RUNNABLE` / `STOPPED`）、
派生的 `thread.status`（`RUNNING` / `WAITING` / `IDLE`，只描述该 Thread 自身，不递归子树）、每条 Entry 的读取时 `usageCost` 与 `stopReceipts`；
`unconfirmedExecutions` 列出该 Thread 尚未退出的具体执行（`executionId`、`invocationId`、`attempt`、`kind`、`registeredAt`）；
`POST /api/threads/{threadId}/stop` 以 root `expectedVersion` + 稳定 `stopRequestId` 执行精确 CAS，
回执 `status` 为 `STOPPED` / `REPLAYED`、`thread` 是目标权威投影、`stoppedThreads` 是本次完整受影响集合的
逐 Thread 持久回执（`threadId`、`stopRequestId`、`stoppedTurnEndEntryId`、`cancelledCommandCount`、
仅含 `USER_MESSAGE` / `GOAL` 的 `cancelledInputs`）。
`POST /api/threads/{threadId}/executions/{executionId}/abandon` 接受 `{"expectedVersion":"<bigint>"}`，显式放弃一个具体执行代次并返回最新 Snapshot；
它与 Snapshot 使用相同的认证与资源读取边界，子 Thread 也可放弃，首次放弃要求 `STOPPED` 与精确 version，重复放弃或已确认真实退出是安全 replay。

### 请求诊断与预览

`GET /api/threads/{threadId}/tree` 返回该 Thread 所属执行树的节点列表。字段只有 `threadId`、显式可空的 `parentThreadId`、`name`、`agentName`、`model`（`modelName` / 可空 `variant`）、`status`（`RUNNING` / `WAITING` / `IDLE`）、`executionControl`（`RUNNABLE` / `STOPPED`）、`turnCount`、`toolCallCount` 和显式可空的 `outcome`（非 IDLE 状态一律为 null，严禁在等待子任务或活跃时宣称 COMPLETED）。它不返回 Entry、Command 或 Tool 参数与结果。非法 UUID 为 400，缺失 Thread 为 404。任意节点返回同一真实根的完整树，顺序为 `createdAt` 再 UUID。

`POST /api/threads/{threadId}/model-request-debug` 以 UI 当前草稿 `{model, environmentName}`（`model` 为必填的 `{modelName, variant?}` 逻辑选择，`environmentName` 可空，null 表示未选 Environment）在真实 Thread snapshot 上做一次只读规划，返回下一次结构化预览与可空的活动冻结请求；每个 subagent 另带当前配置声明 `tools` / `skills` / `subagents` / `configurationJson`。冻结请求始终按原始 snapshot 与原路径物化，与草稿选择无关；全程排除 credential 和 Base64，不写库、不发请求。

请求预览有三个只读入口：`POST /api/threads/{threadId}/provider-request-preview`（owner-free 续写面，
带精确 head/sequence）、`POST /api/sessions/{sessionId}/provider-request-preview`（本地分支草稿，
`{startEntryId,commands}`）与 `GET /api/sessions/{sessionId}/entries/{entryId}/provider-request-preview`
（历史模型输出，按该输出记录时间重建）。它们只读检查 READY 附件、复用正式规划/物化/编码器，
不消费上传、不推进游标、不调用 transport；历史入口接受普通 assistant 输出，并对父 COMPACTION 结果通过冻结 child join 的 finalAnswer 前缀重建压缩子最后一次调用，结果不等于原始发送字节。
`kind` 为 `DRAFT_REQUEST_PREVIEW` 或 `HISTORICAL_REQUEST_PREVIEW`，`bodyJson` 只代表点击时快照、
不等于原始发送字节。当前事实不允许精确预览时返回 409，客户端只按稳定的 `errors.reason` 恢复，不解析描述文本：

| `errors.reason` | 拒绝事实 |
| --- | --- |
| `PREVIEW_STALE_CURSOR` | head 或命令序号与当前快照不一致；重新读取快照后用新游标请求 |
| `PREVIEW_QUEUED_COMMANDS` | 尚有排队命令 |
| `PREVIEW_THREAD_BUSY` | 当前上下文不是空闲输入边界 |
| `PREVIEW_COMPACTION_REQUIRED` | 下一步必定为自动压缩 |
| `PREVIEW_ATTACHMENT_NOT_READY` | 附件上传未就绪或不可用 |
| `PREVIEW_PLANNING_FAILED` | 草稿无法规划 |
| `PREVIEW_PROVIDER_UNAVAILABLE` | 当前 Provider 无法解析，包括连接 generation 漂移 |
| `PREVIEW_UNSUPPORTED` | 冻结压缩子执行无法可靠定位，或 adapter 无请求体预览能力 |
| `PREVIEW_ENCODING_FAILED` | 请求体编码失败 |

规划详情、连接凭据与异常原因链不进入错误响应；请求形状或归属错误仍为 400，缺失 Thread 为 404。

Canvas commands 返回 patch，Function start 返回 202。
UNKNOWN resolve 要求 resolution 与非空 verification；Issue UNKNOWN 同样要求人工核查说明。
Evidence 接收 READY uploadId，在事务内转移引用并返回规范资源标识，展示名取权威上传行。

## 同源 IDE 网关与数据面

[`IdeGatewayFilter`](../../web/src/main/java/fun/fengwk/kkstudio/web/environment/ide/IdeGatewayFilter.java)
在 Studio 同源下固定 `/environment-ide/{environmentId}/{viewId}/` 路由：
按路径严格解析 canonical UUID 的 `environmentId` 与 `viewId`，剥除 view 基址后把原始 path/query 交给数据面。
缺少 viewId 的旧环境根路径只做规范跳转；非 IDE 路径原样放行。

- **Bootstrap 与会话隔离**：一次性票据请求由 `__ide/bootstrap?token=...` 消费，并在响应中通过 `Set-Cookie` 下发
  `Path` 限定在 `/environment-ide/{environmentId}/` 的 HttpOnly cookie；重定向严格保持在当前环境 route base 之下。
  Studio 与受管环境属于同一可信浏览器边界（非沙箱隔离），不需要独立 IDE 域名或主机模板。
- **会话探测**：`__ide/session` 只读、不触达 Daemon，返回 204（会话有效）、401（无会话）、409（代际已被另一个 Daemon 实例接管）或 503（暂无 READY 连接，同一实例重连后原 cookie 继续有效）。前端据此决定等待还是重新打开视图。
- **HTTP 与 WebSocket 数据面**：普通 HTTP 请求由 [`IdeGatewayDataPlane`](../../web/src/main/java/fun/fengwk/kkstudio/web/environment/ide/IdeGatewayDataPlane.java)
  映射为同一条 mux 逻辑流；WebSocket upgrade 请求由 [`IdeBrowserWebSocketGateway`](../../web/src/main/java/fun/fengwk/kkstudio/web/environment/ide/IdeBrowserWebSocketGateway.java)
  处理并保持 TEXT/BINARY/FIN 边界。每条流 OPEN 都带 `viewId` 与 `bindingEpoch`，路径保留浏览器 view 基址之后的原始
  path/query；内部挂载 `/__kk/ide/view/{viewId}/{bindingEpoch}/...` 由 Daemon 构造。上游 `Location` 中的内部挂载由 Web
  适配层映射回浏览器 view 基址。跨节点场景通过内部端点
  `/internal/environment-ide/{env}/data-http` 与 `/data-ws` 单跳转发至 owner，不使用粘性会话。
- **复用数据连接**：owner 首次需要逻辑流时经控制通道下发 `DATA_BIND`，Daemon 连接
  `/api/harness/environment-data/v2/{environmentId}?ticket=...`。票据一次性、30 秒有效并绑定 READY 控制身份与 generation；
  同一 Environment 只保留一条物理 WebSocket。非 owner 收到该物理连接时只做整条单跳桥接，owner 才是唯一票据消费者。
  物理写失败立即 fail-stop，不自动重连或重放请求。
- **开发代理**：本地开发时 Vite 代理 `/environment-ide` 配置 `changeOrigin: false, ws: true`，保留浏览器原始 Host 与 Origin 以对齐同源校验。

## JSON、错误与信任边界

[`StrictJacksonConfiguration`](../../web/src/main/java/fun/fengwk/kkstudio/web/StrictJacksonConfiguration.java)
定制 Boot4 装配的唯一 Jackson 3 `JsonMapper`（它与其父类型 `ObjectMapper` 是同一 bean）：
启用重复键检测、Long 字符串序列化和 DTO 声明顺序，默认省略 null。
只提供 Jackson 2 mapper 的 convention4j JSON 自动装配在组合根被排除
（[`WebApplication`](../../web/src/main/java/fun/fengwk/kkstudio/web/WebApplication.java) 与其测试入口一致），
自有代码只解析到该 Jackson 3 mapper；Share DTO 的严格字段规则直接由 Jackson 3 注解声明
（例如 `protocolOptionsJson` 与网络文本字段只接受 JSON 字符串 token）。
required-nullable 字段由 DTO 显式 ALWAYS。未知字段由 DTO 的 JsonAnySetter 控制，
领域 codec 另有严格语法校验；HTTP mapper 的 trailing-token 行为保持 Jackson 配置本身的边界。

advice 按 Controller 范围映射 validation 400、not found 404、version/duplicate/in-use 409，
以及设施不可用/不变量异常；Project 不变量异常为 500。
locale 支持 en-US/zh-CN，其他回退英文。字段契约见 [Share](share.md)。

用户认证、TLS 和 ingress 访问控制由部署边界提供。
配置同步导出包含所需凭据；三个端点的成功和错误响应均为 `no-store`，请求体与 YAML 不记日志。
Daemon registration token 在 HELLO 中绑定 Environment，通用 Card 只返回安全投影。
Plugin auth 请求 no-store，凭据只在数据库中保存，任何响应都不回显。
Storage/Canvas 签发有限期 URL，客户端使用受控存储入口。
Provider 上游错误正文沿其协议契约进入持久记录与客户端，可能包含上游回显的敏感信息；
处理边界见 [Harness Model](harness-model.md#上游错误原样透传)。

## 通知与浏览器事件

[`ApplicationEventConfiguration`](../../web/src/main/java/fun/fengwk/kkstudio/web/events/ApplicationEventConfiguration.java)
共享一个 PostgreSQL LISTEN 连接，路由调度器唤醒（Harness Work / Issue Work / Canvas Function）、
Thread/Canvas version、Project changed、Settings、realtime、Skill 同步、执行树、交互与环境失效。
建立/重连后通知每个 handler resync；单 handler 失败隔离，durable 恢复仍由回读与 poll/lease 完成。

`POST /api/threads/{threadId}/stream-catchup` 是浏览器追帧入口，body 为
`{invocationId: canonical UUID, attempt: 正整数}`。服务端先用目标 Thread 的 durable 快照确认
invocation 归属与 attempt，再让本地 active owner 立即提交 checkpoint，成功固定返回 202；
请求成功只表示已接受，不承诺仍有 owner 或已经落盘。终态、过期 attempt 与不匹配关联都是 202 no-op，
非法 UUID/请求形状为 400，Thread 不存在为 404。
没有本地 owner 时只有存在其它活跃集群节点才向专用 `harness_stream_catchup` channel 发布一次提示；
该 channel 与 `harness_realtime` 共用独立 realtime LISTEN 连接。接收节点只对本地 active owner 执行
attempt fence，不回广播、不回查 head；连接重连不重放追帧提示。数据库查询/发布失败直接向上传播为 5xx。

`/api/events/v2` 是唯一的浏览器事件通道，采用严格 version 2 帧。
连接建立后的第一帧是 `connected`，携带本次物理连接的 `eventNodeId` 与 `connectionId`；每次重连都是新身份，
浏览器据此把 IDE 视图绑定到当前连接，而不是沿用旧代际。
资源为 `{kind:thread|canvas|tree,id}` 或全局 `{kind:projects|interactions|environments}`；
Thread `version` 与 Canvas `revision` 携 durable cursor，Thread `realtime` 是唯一的真负载事件，
`projects` / `tree` / `interactions` / `environments` 只发失效提示，由客户端回读权威事实。
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
Environment 的连接状态不做推送：Card 的 `status` / `statusExpiresAt` 在读取时由
`min(leaseUntil, lastSeen + heartbeatTimeout)` 派生，浏览器只按 `environments` 失效提示回读。
非法帧、过载和关闭按稳定错误与 WebSocket close code 收尾。

IDE 使用证明走同一条通道：客户端发送 `ide-use`，携带 `documentId`、同一连接内单调递增的 `revision`
与 `views[]`（每项是 `environmentId`、`viewId`、`claimId`，已绑定视图再带 `bindingEpoch`）；
服务端回 `ide-use-accepted` 只确认 revision。帧字段集精确，多余或未知字段一律拒绝；
同一 revision 的相同内容幂等，不同内容是连接级冲突错误，更高 revision 原子替换整份列表。
缺 `bindingEpoch` 的项只是使用意图，不能用于续租；每个连接的 `documentId` 固定。

AsyncTextSender 每连接一个 in-flight frame，frame 数和 UTF-8 字节预算包含在途帧，
完成回调驱动下一帧。锁内转移队列，网络发送在锁外；失败关闭入队围栏并清理订阅。

Oversize realtime 事件不在通知里携带正文：owner 把完整 canonical event 放入短期有界内存，
`GET /internal/harness-realtime/{payloadId}` 供其它节点按 `cluster_node` 有效租约与内部 HMAC 认证回拉。
端点只接受 canonical UUID，认证先于读取；payload 缺失、过期或被驱逐时返回 404。回拉总超时约 2 秒，
接收方校验 Thread 后本地投递一次，失败只触发 RESYNC，不影响 durable 执行。

## Daemon WebSocket

`/api/harness/environment-daemon/v1` 的 handler 适配 Spring/native session，
委托 DaemonEndpoint 管理会话。DaemonOutboundSender 每连接一个发送线程，
非阻塞递交返回 ACCEPTED/BUSY/CLOSED，限制 frame、bytes 和 send timeout。
必须协商 permessage-deflate，缺失以 1010 关闭。
协议与会话裁决见 [Harness Environment](harness-environment.md)。

## 启动与静态分发

默认 profile 为 dev；prod datasource 全部来自部署变量，缺失直接启动失败。
连接池与 driver 超时约束数据库不可达时的等待。S3 配置和 bucket 可访问性在启动验证。
Flyway 加载 Schema 的 canonical baseline，各 profile 选择受控 seed，见 [Schema](schema.md)。

Web 的 distribution profile 在 prepare-package 执行 npm ci/Vite build，将产物写入 target
再复制到 classes/static，由 Boot repackage 嵌入 Fat JAR。
SPA fallback 仅适用于 GET、非 API/actuator 且末段无扩展名的路径；真实 asset/Controller 优先。

部署参数、secret 与启动操作见 [部署与运行](../operations/deployment.md)，
跨模块配置归属见 [Platform 配置](platform.md#配置)。

## 测试入口

[`web/src/test`](../../web/src/test/java/fun/fengwk/kkstudio/web) 覆盖组合与依赖方向、Flyway、
HTTP mapper/advice、owner 事务、Snapshot、ack-before-event、bounded sender、
通知重连、Daemon 帧与静态 SPA。Spring 集成基座使用真实 PostgreSQL。
同源 IDE 的测试按边界选择：[`IdeDataPlaneIntegrationTest`](../../web/src/test/java/fun/fengwk/kkstudio/web/environment/ide/IdeDataPlaneIntegrationTest.java)
用真实 Tomcat HTTP/WS 与 mux peer 覆盖本地与非 owner 单跳；
[`MuxCrossLanguageIntegrationTest`](../../web/src/test/java/fun/fengwk/kkstudio/web/environment/ide/MuxCrossLanguageIntegrationTest.java)
让 Java mux 引擎与 Node 24 Daemon 传输跨进程互通；
[`IdeProductionChainAcceptanceTest`](../../web/src/test/java/fun/fengwk/kkstudio/web/environment/ide/IdeProductionChainAcceptanceTest.java)
在显式提供 code-server 发行目录时启动真实 Spring/Tomcat、临时 PostgreSQL 和生产 Daemon，覆盖浏览器协议入口、目录规范化、恢复换代与崩溃失效；
另提供 Playwright 包目录时运行真实 Chromium 工作台、worker 注册、终端、dirty 拒绝与单视图 shell 退出验证。
Daemon 侧的 [`acceptance.test.ts`](../../daemon/test/ide/acceptance.test.ts) 直接验证受管视图；
Frontend 的 Playwright 回归使用路由夹具，不替代真实 code-server 链路。执行命令和 E2E 分类见 [开发与测试](../operations/development-and-testing.md)。

上级：[系统设计](../system-design.md)。相关文档：[Platform](platform.md)、[Share](share.md)、
[Harness Infra](harness-infra.md)、[Canvas](canvas.md)、[IDE](ide.md)、[Frontend](frontend.md)。
