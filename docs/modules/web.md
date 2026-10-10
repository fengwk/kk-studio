# Web 模块

[`WebApplication`](../../web/src/main/java/fun/fengwk/kkstudio/web/WebApplication.java)
是生产 Spring Boot 组合根：装配 Platform、Project、Canvas/Harness infra、Runtime、
Contributor 和构建期 Plugin，提供 REST、静态 SPA、浏览器事件与 Daemon WebSocket。
Controller 解析 DTO、调用用例、映射结果；领域行为与事务由相应模块持有。

## 组合与生命周期

[`web/pom.xml`](../../web/pom.xml) 声明应用模块、Web transport、Flyway 和 runtime Plugin。
Builtin/Common 经 Platform compile 依赖传递进入发行物。
Plugin JAR 通过自己的 AutoConfiguration.imports 创建 bean，
ContributorCatalogConfiguration 收集 HarnessContributor，冻结 HarnessCatalog；
Platform 的 RuntimeToolCatalogConfiguration 加入现读数据库的 MCP 目录。

[`HarnessRuntimeConfiguration`](../../web/src/main/java/fun/fengwk/kkstudio/web/runtime/HarnessRuntimeConfiguration.java)
将 datasource/transaction manager 接入 PostgresqlHarnessStore，
装配 ResourceStore、realtime source/sink、三个 Processor 与 Work dispatcher。
timer 只唤醒和分派，heartbeat worker 执行续租事务，flush executor 执行 checkpoint 提交。
该组合根还装配 Skill 同步和 PluginResourceGateway，因为它们需要 Harness 会话能力。

| 生命周期 owner | 启停职责 |
| --- | --- |
| HarnessRuntimeLifecycle、IssueControllerRuntimeLifecycle | workers-enabled 控制 dispatcher 启动，控制/查询 bean 仍可用 |
| CanvasFunctionDispatcher | 启动 wake，周期 poll，stop 关闭 drain；独立于 Harness worker 开关 |
| DefaultNotificationBus、NotificationSubscriptions | 总线 `start()` 先完成 LISTEN 再对账、每次订阅各自异步恢复、绑定 12 条领域静态订阅；关闭时逐条撤销订阅（首个失败不跳过其余）、释放连接与有界等待 |
| ApplicationEventHub、sender 与 heartbeat scheduler | 关闭订阅与发送围栏，释放连接和 timer |
| StorageMaintenance、PluginCredentialRefreshDispatcher | 各自持有调度器与在途任务的收尾责任 |

## HTTP 入口

JSON Controller 使用 ResultEnvelope，status 与业务结果对齐；
health、资源下载和 WebSocket 使用各自传输形式。公开路由如下：

| family | 路径 | 用途 |
| --- | --- | --- |
| Health | `/healthz` | 存活探针 |
| Catalog | `/api/ai/catalog/providers|models|agents|tools` | Provider/Model/Agent CRUD、工具候选；变更使用 expectedVersion |
| Skill | `/api/ai/catalog/skill-packages` | CRUD、check、exact commit update |
| MCP | `/api/ai/mcp-servers` | name-keyed CRUD、显式 no-store config、同步 discover |
| Plugin | `/api/plugins` | 安全投影、auth/prepare、auth/complete、删除 auth |
| Chat | `/api/ai/chats` | CRUD 与归属 Session |
| Harness command | `/api/harness/command-batches`、`/api/harness/threads/{threadId}/command-batches` | 创建型 Chat 命令接受（owner-aware）与既有 Thread 的 owner-free 续写；都返回 202 |
| Session | `/api/harness/sessions/{sessionId}` | threads、entries（含读取时 `usageCost`）、name、草稿/历史请求预览 |
| Thread | `/api/harness/threads/{threadId}` | Snapshot、执行树、name、Debug、协议预览、compact、yolo、stop、tool approval |
| Interaction | `/api/interactions` | 等待分页（`total` 是同一过滤条件下真实可见的待处理计数）与 `/{interactionId}/input` 人工提交 |
| Harness resource | `/api/harness/resources/{sha256}` | 内容寻址 Resource 下载 |
| Canvas | `/api/canvases` | CRUD、Snapshot、commands、resource download/preview URL、节点 Function Run |
| Function catalog | `/api/canvas-functions` | 函数 schema、输出计划、可用性 |
| Storage | `/api/storage/uploads`、`/api/storage/blobs/{blobId}` | reserve/complete/delete 与短期 download/preview URL |
| Project | `/api/projects` | CRUD、workflow/yolo、archive/unarchive、Snapshot、Issue 创建 |
| Issue | `/api/issues/{issueId}` | CRUD、活动/证据、流转、阻塞/恢复、暂停/继续、stop、UNKNOWN 核查、额度重置、归档 |
| Settings | `/api/settings`、`/api/settings/schema` | 聚合 GET/CAS PUT、编辑 schema |
| Configuration sync | `/api/settings/sync`、`/export`、`/import/check`、`/import` | GET 清单、POST 选择导出、导入检查与确认执行；响应禁止缓存 |
| Environment | `/api/harness/environments`、`/{id}/update` | Card、注册令牌查询/轮换、安装设置保存、受管二进制在线更新（POST 发起、GET 只读投影）、运维 events |

Session 的 `GET /api/harness/sessions/{sessionId}/threads` 返回全部 Thread 摘要，包含执行根和子 Thread，不按名称去重。每项 `parentThreadId` 必须存在：根为 null，子为执行父 Thread 的 canonical UUID string；根与子同名时仍是独立摘要。

命令 202 表示数据库已接受，执行由 dispatcher 异步推进。
`/api/harness/command-batches` 只服务创建型 `CHAT` owner（`NEW_SESSION` / `NEW_THREAD` / `NEW_FORKED_SESSION`；
`ISSUE_AGENT` 由 Issue 工作流拥有，被该端点拒绝）；`/api/harness/threads/{threadId}/command-batches`
服务既有 Thread 的 owner-free 续写（path `threadId` + 精确 cursor，body 不带 owner 与 target）。
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

Thread 控制面按认证、资源权限与请求格式收紧，不再按 Thread 的产品归属拒绝。
`GET /api/harness/threads/{threadId}` 的 Snapshot 携带 `thread.executionControl`（`RUNNABLE` / `STOPPED`）、
派生的 `thread.status` / `processing`（只描述该 Thread 自身，不递归子树）、每条 Entry 的读取时 `usageCost` 与 `stopReceipts`；
`POST /api/harness/threads/{threadId}/stop` 以 root `expectedVersion` + 稳定 `stopRequestId` 执行精确 CAS，
回执 `status` 为 `STOPPED` / `REPLAYED`、`thread` 是目标权威投影、`stoppedThreads` 是本次完整受影响集合的
逐 Thread 持久回执（`threadId`、`stopRequestId`、`stoppedTurnEndEntryId`、`cancelledCommandCount`、
仅含 `USER_MESSAGE` / `GOAL` 的 `cancelledInputs`）。

### 请求诊断与预览

`GET /api/harness/threads/{threadId}/tree` 返回该 Thread 所属执行树的节点列表。字段只有 `threadId`、显式可空的 `parentThreadId`、`name`、`agentName`、`model`（`providerName` / `modelName` / `variant`）、`status`、`processing`、`turnCount`、`toolCallCount` 和显式可空的 `outcome`。它不返回 Entry、Command 或 Tool 参数与结果。非法 UUID 为 400，缺失 Thread 为 404。任意节点返回同一真实根的完整树，顺序为 `createdAt` 再 UUID。

GET model-request-debug 返回下一次结构化预览与可空的活动冻结请求，排除 credential 和 Base64。

请求预览有三个只读入口：`POST /api/harness/threads/{threadId}/provider-request-preview`（owner-free 续写面，
带精确 head/sequence）、`POST /api/harness/sessions/{sessionId}/provider-request-preview`（本地分支草稿，
`{startEntryId,commands}`）与 `GET /api/harness/sessions/{sessionId}/entries/{entryId}/provider-request-preview`
（历史模型输出，按该输出记录时间重建）。它们只读检查 READY 附件、复用正式规划/物化/编码器，
不消费上传、不推进游标、不调用 transport；历史入口只接受普通 assistant 输出，压缩结果以 `PREVIEW_UNSUPPORTED` 拒绝（父回合没有 ModelInvocation）。
`kind` 为 `DRAFT_REQUEST_PREVIEW` 或 `HISTORICAL_REQUEST_PREVIEW`，`bodyJson` 只代表点击时快照、
不等于原始发送字节。`PREVIEW_*` reason 区分 stale cursor、queued、busy、compaction、attachment、
planning、provider、unsupported（父压缩回合无 ModelInvocation，或 adapter 无预览能力）与 encoding 拒绝；客户端按 reason 恢复。

Canvas commands 返回 patch，Function start 返回 202。
UNKNOWN resolve 要求 resolution 与非空 verification；Issue UNKNOWN 同样要求人工核查说明。
Evidence 接收 READY uploadId，在事务内转移引用并返回规范资源标识，展示名取权威上传行。

## JSON、错误与信任边界

[`StrictJacksonConfiguration`](../../web/src/main/java/fun/fengwk/kkstudio/web/StrictJacksonConfiguration.java)
启用重复键检测、Long 字符串序列化和 DTO 声明顺序，默认省略 null；
required-nullable 字段由 DTO 显式 ALWAYS。未知字段由 DTO 的 JsonAnySetter 控制，
领域 codec 另有严格语法校验；HTTP mapper 的 trailing-token 行为保持 Jackson 配置本身的边界。

advice 按 Controller 范围映射 validation 400、not found 404、version/duplicate/in-use 409，
以及设施不可用/不变量异常；Project 不变量异常为 500，Plugin 主密钥不可用为 503。
locale 支持 en-US/zh-CN，其他回退英文。字段契约见 [Share](share.md)。

用户认证、TLS 和 ingress 访问控制由部署边界提供。
配置同步导出包含所需凭据；三个端点的成功和错误响应均为 `no-store`，请求体与 YAML 不记日志。
Daemon registration token 在 HELLO 中绑定 Environment，通用 Card 只返回安全投影。
Plugin auth 请求 no-store，数据库保存认证密文，主密钥缺失时凭据读写失败关闭。
Storage/Canvas 签发有限期 URL，客户端使用受控存储入口。
Provider 上游错误正文沿其协议契约进入持久记录与客户端，可能包含上游回显的敏感信息；
处理边界见 [Harness Provider](harness-provider.md#上游错误原样透传)。

## 通知与浏览器事件

[`NotificationConfiguration`](../../web/src/main/java/fun/fengwk/kkstudio/web/events/NotificationConfiguration.java)
登记全部 12 个领域固定 topic，以 `SystemSettings.Advanced` 的 `notificationPollMillis` 与 `notificationReconnectBackoffMillis`
装配 [`DefaultNotificationBus`](../../notification/src/main/java/fun/fengwk/kkstudio/notification/DefaultNotificationBus.java)（`initMethod=start`、`destroyMethod=close`）与 [`NotificationLimits.defaults()`](../../share/src/main/java/fun/fengwk/kkstudio/share/notification/NotificationLimits.java) 资源预算。该配置同时声明全应用唯一的 `PlatformTransactionManager`（[`NotificationTransactionManager`](../../notification/src/main/java/fun/fengwk/kkstudio/notification/NotificationTransactionManager.java)）：沿用 Spring Boot 默认 `JdbcTransactionManager` 的提交/回滚异常语义，并在每个物理事务起始登记最高优先级阶段标记，使 afterCommit 线程上的发布被拒绝，而不是注册一个永不提交、会被静默丢弃的批次；业务 claim/CAS 与隔离级别语义不变。
[`NotificationSubscriptions`](../../web/src/main/java/fun/fengwk/kkstudio/web/events/NotificationSubscriptions.java) 是该组合根唯一的静态订阅绑定处（12 条），集中分发调度器唤醒（Harness Work / Issue Work / Canvas Function）、Thread/Canvas version、Project changed、Settings、realtime、Skill 同步、执行树、交互与环境失效。总线装配期需读取启动快照节奏，而快照依赖 settings 仓库、仓库依赖 `SystemSettingsChangeNotifier`，因此该 notifier 对总线的注入使用 `@Lazy` 打破装配环；该惰性注入只是装配期 provider，不产生初始通知读写。健康状态由 [`NotificationBusHealthIndicator`](../../web/src/main/java/fun/fengwk/kkstudio/web/health/NotificationBusHealthIndicator.java) 暴露。
`start()` 先完成 LISTEN 再安排对账，且每次 `subscribe` 自身异步排入一次权威恢复标记，因此订阅早于或晚于建连都被覆盖；建立/重连或总线请求对账同样触发各订阅者 resync。关闭该绑定会逐一尝试关闭每一条已建立的订阅，首个失败不跳过其余资源，并上报第一个失败（其余作为 suppressed）。单订阅失败进入终态并使总线健康检查置为 DOWN，durable 恢复仍由权威回读与 poll/lease 完成。

`/api/events/v1` 是唯一的浏览器事件通道，采用严格 version 2 帧（拒绝 version 1）。
每条连接由一个固定 topic `app.events.v2` 的 `NotificationPeerLink` 物理承载，
全部逻辑帧（含 `count=1`）都经共享 carrier 分片，不存在 raw JSON 旁路。
资源为 `{kind:thread|canvas|tree,id}` 或全局 `{kind:projects|interactions|environments}`；
Thread `version` 与 Canvas `revision` 携 durable cursor，Thread `realtime` 是唯一的真负载事件，
`projects` / `tree` / `interactions` / `environments` 只发失效提示，由客户端回读权威事实。
订阅先注册上游再读 cursor，sender 先排 subscribed ack 后 activate，
过滤陈旧版本；缓冲溢出折叠为 resync。
Environment 的连接状态不做推送：Card 的 `status` / `statusExpiresAt` 在读取时由
`min(leaseUntil, lastSeen + heartbeatTimeout)` 派生，浏览器只按 `environments` 失效提示回读。
浏览器 shell 控制以 `shell.command` 帧（嵌套 `TerminalCommand`）交给 `ShellGateway`：
网关只经 `NotificationBus` 的 `shell.command` / `shell.event` 两个固定 topic 收发，
先按窄 `EnvironmentTerminalRouteSource` 读取当前权威 READY owner/lease，再把 `TerminalDispatch`
投递到 owner 节点；owner 侧消费调用唯一的 `EnvironmentDaemonServer.sendShell`。
每条真实连接按 `connectionId + environment + viewer` 有界保存观察 scope：回执必须同时匹配权威 owner 与 lease，
只有匹配本次 OPEN/ATTACH 请求（ATTACH 还须匹配声明 identity）的 ATTACHED 才建立显示流；
发起新请求、断线或总线 resync 都递增代次并作废旧绑定，旧 owner/lease/daemon/terminal/stream 回执一律丢弃。
READY 路由读取失败或容量拒绝只在同 topic 回确定未执行的固定错误，命令一旦 publish 后的未知失败
绝不伪报未执行；关闭连接只对仍持有绑定的 scope 发 best-effort DETACH，绝不关闭 PTY。
非法 carrier（peer/topic/target/UTF-8/缺片/超时）、binary、超限与非法协议帧一律清理并关闭，
绝不回显原 payload。

`AsyncTextSender` 不再自管队列：每连接借用组合根 executor 驱动共享
`NotificationPeerLink` / `NotificationOutbox` 的有界公平批次（逻辑包数与 pending 字节双预算），
每连接同时只有一个物理帧在途，只有成功的 native 回调才推进下一帧、批内最后一帧成功才释放整包预算，
native 调用在锁外。失败或过载不丢弃已排队逻辑包：把 error 作为最后一包排在同一预算之后，
出队并完成回调后按稳定 close code 关闭；error 帧本身无法入队时直接放弃关闭。
总线 resync 以 1012 `SERVICE_RESTART` 关闭真实连接并清理观察/订阅，浏览器唯一连接重连后显式重新 ATTACH，
绝不自动重放 OPEN/INPUT 等副作用。

## Daemon WebSocket

`/api/harness/environment-daemon/v1` 的 handler 适配 Spring/native session，
委托 DaemonEndpoint 管理会话。DaemonOutboundSender 每连接一个发送线程，
非阻塞递交返回 ACCEPTED/BUSY/CLOSED，限制 frame、bytes 和 send timeout。
必须协商 permessage-deflate，缺失以 1010 关闭。
协议与会话裁决分别见 [Harness Environment](harness-environment.md)、
[Harness Environment Server](harness-environment-server.md)。

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
通知重连、Daemon 帧与静态 SPA。Spring 集成基座使用真实 PostgreSQL；
执行命令和 E2E 分类见 [开发与测试](../operations/development-and-testing.md)。

上级：[系统设计](../system-design.md)。相关文档：[Platform](platform.md)、[Share](share.md)、
[Harness Infra](harness-infra.md)、[Canvas Infra](canvas-infra.md)、[Frontend](frontend.md)。
