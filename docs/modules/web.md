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
| PostgresqlNotificationLoop | 专用连接 LISTEN，断线重连/resync，关闭 abort、interrupt、有界等待 |
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
`/api/harness/command-batches` 只服务创建型 `CHAT` owner（`NEW_SESSION` / `NEW_THREAD`；
`ISSUE_AGENT` 由 Issue 工作流拥有，被该端点拒绝）；`/api/harness/threads/{threadId}/command-batches`
服务既有 Thread 的 owner-free 续写（path `threadId` + 精确 cursor，body 不带 owner 与 target）。
Issue 输入与控制通过工作流用例，人工审批/问卷通过交互入口。
审批和问卷 actor 来自服务端认证主体；本地无认证模式使用固定 local-user。

Harness mapper 校验 canonical UUID、可放入 long 的规范十进字符串，
以及创建型 `NEW_SESSION`/`NEW_THREAD` 各自互斥字段与续写面的精确 cursor。
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
不消费上传、不推进游标、不调用 transport；历史入口接受普通 assistant 输出与带真实 metadata 的压缩结果。
`kind` 为 `DRAFT_REQUEST_PREVIEW` 或 `HISTORICAL_REQUEST_PREVIEW`，`bodyJson` 只代表点击时快照、
不等于原始发送字节。`PREVIEW_*` reason 区分 stale cursor、queued、busy、compaction、attachment、
planning、provider、unsupported（仅 adapter 无预览能力）与 encoding 拒绝；客户端按 reason 恢复。

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

[`ApplicationEventConfiguration`](../../web/src/main/java/fun/fengwk/kkstudio/web/events/ApplicationEventConfiguration.java)
共享一个 PostgreSQL LISTEN 连接，路由调度器唤醒（Harness Work / Issue Work / Canvas Function）、
Thread/Canvas version、Project changed、Settings、realtime、Skill 同步、执行树、交互与环境失效。
建立/重连后通知每个 handler resync；单 handler 失败隔离，durable 恢复仍由回读与 poll/lease 完成。

`/api/events/v1` 是唯一的浏览器事件通道，采用严格 version 1 帧。
资源为 `{kind:thread|canvas|tree,id}` 或全局 `{kind:projects|interactions|environments}`；
Thread `version` 与 Canvas `revision` 携 durable cursor，Thread `realtime` 是唯一的真负载事件，
`projects` / `tree` / `interactions` / `environments` 只发失效提示，由客户端回读权威事实。
订阅先注册上游再读 cursor，sender 先排 subscribed ack 后 activate，
过滤陈旧版本；缓冲溢出折叠为 resync。
Environment 的连接状态不做推送：Card 的 `status` / `statusExpiresAt` 在读取时由
`min(leaseUntil, lastSeen + heartbeatTimeout)` 派生，浏览器只按 `environments` 失效提示回读。
非法帧、过载和关闭按稳定错误与 WebSocket close code 收尾。

AsyncTextSender 每连接一个 in-flight frame，frame 数和 UTF-8 字节预算包含在途帧，
完成回调驱动下一帧。锁内转移队列，网络发送在锁外；失败关闭入队围栏并清理订阅。

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
