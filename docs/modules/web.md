# Web 模块

## 组合根与唯一生产入口

`web` 是 kk-studio 唯一的生产 Spring Boot composition root：它把 Platform application
services、Canvas/Harness infra、纯 Java `harness-runtime`、第一方
`BuiltinHarnessContributor`、可选 classpath Plugin 和受信任 Contributor JAR 快照组合成一个可运行的
HTTP/WebSocket 进程，并把浏览器 REST、静态 SPA、浏览器 Application Event WebSocket
与 Environment Daemon WebSocket 一起暴露给外部；领域规则由被组合的模块持有，
Controller 只做 DTO 解析、调用 application service 和结果投影。

生产入口只有 [WebApplication](../../web/src/main/java/fun/fengwk/kkstudio/web/WebApplication.java)：
`SpringApplication.run(WebApplication.class, args)`。
[WebTestApplication](../../web/src/test/java/fun/fengwk/kkstudio/web/WebTestApplication.java)
只用于 Spring integration test，不是第二个生产 root。

## 依赖边界与组合结构

[`web/pom.xml`](../../web/pom.xml) 直接声明：

| 方向 | 依赖 |
| --- | --- |
| Application/domain | `kk-studio-platform`、`kk-studio-share`、`kk-studio-canvas-infra` |
| Harness composition | `kk-studio-harness-environment`、`kk-studio-harness-environment-server`、`kk-studio-harness-runtime`、`kk-studio-harness-infra`、`kk-studio-harness-contributor-api` |
| Web transport | `convention4j-spring-boot-starter-web`、`spring-boot-starter-websocket`、`spring-boot-starter-actuator` |
| Database bootstrap | `kk-studio-schema`（runtime）、`spring-boot-starter-flyway`、`flyway-database-postgresql` |
| Optional Plugins | 选中的 `kk-studio-plugin-*`（runtime）；没有 dependency 就不进入 Fat JAR |
| Integration tests | convention test starter、`spring-boot-starter-webmvc-test`、Testcontainers PostgreSQL/JUnit |

[WebModuleArchitectureTest](../../web/src/test/java/fun/fengwk/kkstudio/web/WebModuleArchitectureTest.java)
同时守护 import 与 POM：main source 只允许 `harness.runtime`、`harness.infra`、
`harness.contributor.api`、`harness.environment` 前缀（`harness.tool` 只放行
`codec.ToolResultJsonCodec`），禁止引用 Platform 的 `EnvironmentDaemonGateway`、
`EnvironmentRegistry`、`EnvironmentConnection`；POM 必须直接声明 Platform、Canvas Infra、
Harness Infra、Harness Environment、Contributor API，并禁止以任何 scope 声明
`kk-studio-harness-tool`、`kk-studio-harness-daemon`、`kk-studio-harness-builtin`、
`kk-studio-harness-common`。后两者的原因很具体：Builtin 与 Common 由 Platform 的
compile-scope 依赖传递进入组合根，web 一旦声明更近的 test-scope 依赖就会遮蔽它，使
Spring Boot repackage 把这些 jar 从 Fat JAR 的 `BOOT-INF/lib` 中剔除。测试还禁止
`web.controller` 包导入 `harness.runtime.processor`，并禁止 web main source 出现
`TrustedJarContributorLoader`、`URLClassLoader`、`ServiceLoader` 与
`kk-studio.harness.contributors.directory` 等动态加载残留。可选 `kk-studio-plugin-*` 只能是
runtime dependency，Web main/test source 都不得 import 其 implementation package。

```text
WebApplication
  -> Spring Boot / MVC / WebSocket / Flyway
  -> PlatformAutoConfiguration -> Platform application services and adapters
  -> selected Plugin AutoConfigurations -> StudioPlugin + HarnessContributor
  -> Canvas infra -> canvas-core
  -> HarnessRuntimeConfiguration -> harness-infra -> harness-runtime -> harness-tool / harness-environment
  -> ContributorCatalogConfiguration -> Spring HarnessContributor beans（Builtin + 已安装 Plugin）-> HarnessCatalog
  -> RuntimeToolCatalogConfiguration -> HarnessToolCatalogAdapter + DB-backed McpToolCatalog -> CompositeRuntimeToolCatalog
  -> IssueControllerRuntimeConfiguration -> bounded Issue dispatcher + worker + poll
  -> HTTP Controllers / DTO mappers / error advice
  -> Application Event WebSocket + Environment Daemon WebSocket
```

[HarnessRuntimeConfiguration](../../web/src/main/java/fun/fengwk/kkstudio/web/runtime/HarnessRuntimeConfiguration.java)
是 Harness 的 application composition 子根：

- `PostgresqlHarnessStore` 把 `HarnessStore` 绑定到 Web datasource 与 transaction manager；
- `LocalFileResourceStore` 以 `kk-studio.harness.runtime.resource-root` 为 content-addressed
  root，单对象上限取 `SystemSettings.Advanced.resourceMaxBytes`；
- `PostgresqlRealtimeEventSink` 与 `PostgresqlRealtimeEventSource` 把 Model/Tool realtime
  接到 PostgreSQL；
- `ThreadProcessor`、`ModelProcessor`、`ToolProcessor` 共享 lease/heartbeat 配置；
  `harnessProcessorScheduler` 只执行 timer 检查、合并与非阻塞分派，
  `harnessHeartbeatWorkerExecutor` 以独立命名虚拟线程执行续租事务与所有权丢失回调，
  Model checkpoint timer 只复用 scheduler 计时，实际批次 DB flush 与通知发布由
  `harnessModelFlushExecutor` 执行；
- `HarnessWorkDispatcher` 使用单线程 drain、bounded worker executor、poll scheduler 与
  `harness_work` claim；
- `EnvironmentSkillSyncOrchestrator` 与它的 bounded `environmentSkillSyncExecutor`
  在这里装配：它依赖 Environment 会话核心提供的 `EnvironmentCapabilityTransport`，因此
  Platform 自动配置不持有这个组合，Platform-only 上下文不会因为缺少该传输而启动失败；
- `HarnessRuntimeLifecycle` 只控制 dispatcher 是否启动，Runtime control/query、store、
  processor 与 realtime bean 始终由 context 持有。

[ContributorCatalogConfiguration](../../web/src/main/java/fun/fengwk/kkstudio/web/runtime/contributor/ContributorCatalogConfiguration.java)
收集 Spring bean 形式的 `HarnessContributor`（含 Platform 暴露的
`BuiltinHarnessContributor` 和已安装 Plugin），调用 `HarnessCatalog.from` 冻结为单一不可变
`HarnessCatalog`；Contributor 是启动期静态扩展，不提供运行时安装或热刷新，也不存在外部
jar 目录或隔离 classloader 这条路径。
[RuntimeToolCatalogConfiguration](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/tool/RuntimeToolCatalogConfiguration.java)
进一步把静态目录与 DB-backed `McpToolCatalog` 聚合成 `@Primary` 的
`CompositeRuntimeToolCatalog`。

[ApplicationEventConfiguration](../../web/src/main/java/fun/fengwk/kkstudio/web/events/ApplicationEventConfiguration.java)
是 composition seam：它直接 import `CanvasFunctionDispatcher`，把
`canvas_function_work` notification 接到 Canvas dispatcher；这不是 Platform 对 Canvas
Infra implementation 的反向依赖。

数据库是唯一 durable database；HTTP 认证/TLS 由部署边界承担，Environment Daemon
的连接身份由 PostgreSQL 中的 Environment `registration_token` 在 gateway HELLO 协议中
校验，trusted contributor directory 是另一个显式部署信任边界。

## Controller、DTO 与错误边界

所有 JSON Controller 使用 convention4j `Result<T>`；`Results.created`、`Results.accepted`、
`Results.ok`、`Results.noContent` 表达业务状态，response advice 负责让 HTTP status 与
result status 对齐。

| family | path | 职责 |
| --- | --- | --- |
| Health | `GET /healthz` | 进程存活探针，不读取业务外部资源 |
| Agent Provider | `/api/ai/catalog/providers` | page、create、update、expectedVersion delete |
| Agent Model | `/api/ai/catalog/models` | page、create；路径参数复合 `(providerName, modelNameHead, *modelNameTail)` 承载可含 `/` 的 model name |
| Agent Definition | `/api/ai/catalog/agents` | page、create、update、expectedVersion delete |
| Tool catalog | `GET /api/ai/catalog/tools` | Platform/Environment 可选工具目录投影 |
| Plugin | `/api/plugins`、`/{pluginId}`、`/{pluginId}/auth/prepare|complete`、`DELETE /{pluginId}/auth` | 已安装 classpath Plugin、安全状态与固定 deep-link 认证动作；认证响应 `no-store` |
| MCP Server | `/api/ai/mcp-servers`、`/{name}`、`/{name}/config`、`/{name}/discover` | name-keyed 显式 HTTP CRUD（name 不可变、无改名端点）；显式配置查询附带 `Cache-Control: no-store`；发现同步返回 200 |
| Chat | `/api/ai/chats` | Chat CRUD 与 owner Session summary |
| Harness command | `POST /api/harness/command-batches` | Chat/Canvas 唯一用户 command write path（202 accepted） |
| Harness Session | `/api/harness/sessions/{sessionId}/threads`、`/entries`、`PUT /{sessionId}/name` | Thread summary、Entry tree 查询与 Session 改名 |
| Harness Thread | `/api/harness/threads/{threadId}`、`/name`、`/model-request-debug`、`/compact`、`/yolo`、`/stop`、`/tool-invocations/{id}/approval` | snapshot、模型请求诊断、命名、运行控制与人工审批；Issue Agent Branch 的公开 YOLO 与 stop 拒绝，YOLO 由 Project/Controller 管理 |
| Harness resource | `GET /api/harness/resources/{sha256}` | content-addressed managed Resource 下载 |
| Canvas document | `/api/canvases`、`/{canvasId}`、`/{canvasId}/sessions`、`POST /{canvasId}/commands` | document snapshot/list/create/delete、owner Session 与 typed command batch |
| Canvas resource | `/api/canvases/{canvasId}/resources/{resourceId}/download-url`、`/preview-url` | Blob original/preview presign |
| Canvas Function | `/api/canvas-function-models`、`/api/canvases/{canvasId}/nodes/{nodeId}/function-run`、`/cancel` | model catalog、run、query、cancel |
| Storage | `/api/storage`、`/api/storage/blobs/{blobId}/download-url|preview-url` | upload reserve/complete/delete 与 blob 签名 URL |
| Project | `/api/projects`、`/{projectId}`、`/{projectId}/archive|unarchive|snapshot` | Project CRUD/CAS、YOLO 启动策略与打回阈值配置、归档与权威聚合 Snapshot；Issue Agent 的命令仅经内部业务编排接受，不经公开 command-batches |
| Issue | `/api/projects/{projectId}/issues`、`/api/issues/{issueId}`、`/{issueId}/activities|status|block|recover|dependencies|evidence|review|cancel|retry|archive|unarchive` | Issue CRUD/CAS、七态迁移（含人工阻塞与恢复）、Activity 事实流与分页、依赖、人工上传转为公开证据、Run 人工动作与归档 |
| SystemSettings | `/api/settings`、`/api/settings/schema` | 全局设置 GET、schema GET、CAS PUT |
| Environment | `/api/harness/environments`、`/{id}`、`/{id}/registration-token`、`/{id}/token`、`/{id}/events` | Environment Card 创建/查询/删除与 token 轮换；`name` 是不可变身份（无改名端点），无目录浏览端点；最近一次 READY 宿主信息与最近一条 WARN/ERROR 运维事件直接随 Card 返回，`/{id}/events` 返回最近 200 条事件窗口 |
| ComfyUI workflow | `/api/comfyui/workflows` | persisted workflow API card CRUD |
| ComfyUI runtime | `POST /api/comfyui/workflows/{workflowId}/runs`、`/api/comfyui/runs/{runId}` | stateless 202 run、job/cancel/output download，文件输入使用 blobId |

[StudioHarnessCommandBatchController](../../web/src/main/java/fun/fengwk/kkstudio/web/controller/StudioHarnessCommandBatchController.java)
返回 `202 Accepted` 只表示 durable acceptance 已提交；Provider/Tool 执行和 Thread
progression 由 Work dispatcher 异步完成。Canvas
command 返回带 `baseVersion/version` 的 Patch，实时收敛另走 `/api/events/v1`。
公开 `command-batches` 仅接纳 Chat/Canvas owner；Issue Agent Session 的用户输入、分叉和停止不能绕过 Issue Activity 与 Run 权限校验。
`POST /api/issues/{issueId}/evidence` 只接收已 READY 的 `uploadId`（浏览器先经 `/api/storage/uploads`
reserve/PUT/complete），服务端在单个事务内转移引用并返回规范 `kkstudio:/resources/<blobId>`；请求与响应
都不接受/不暴露客户端声明的文件名、bucket 或对象 key，Issue 详情与 `issue_read` 暴露的是同一份有界证据窗口。
Session/Thread `name` 是独立控制面：`PUT .../name` 只更新命名元数据（可含规范化同名
no-op），不产生 Command、Entry 或 Work。

边界输入由单一 mapper 校验：

- [HarnessRuntimeRequestMapper](../../web/src/main/java/fun/fengwk/kkstudio/web/runtime/HarnessRuntimeRequestMapper.java)
  要求 UUID 经 `UUID.fromString` 往返 canonical，version/cursor/sequence 使用 canonical
  decimal（拒绝符号、前导零和超出 long）；owner discriminator 只允许 `CHAT`/`CANVAS`；
  target 只允许 `NEW_SESSION`、`NEW_THREAD`、`THREAD` 且每种 target 的字段集严格互斥
  （`NEW_SESSION` 携带 `{sessionId, threadId, rootSettings, yoloEnabled}`，`NEW_THREAD`
  携带 `{sessionId, startEntryId, threadId, yoloEnabled}`，二者都不接 name 输入；
  Session 名由服务端从首个非空白用户文本派生，root Thread 名固定 `main`，新 Thread 名
  固定 `branch-<threadId 前 8 位>`）；product HTTP command 只允许
  `SET_AGENT -> SET_MODEL -> SET_ENVIRONMENT` 前缀加一条位于末尾的 `USER_MESSAGE`，
  `CUSTOM_MESSAGE` 不开放
  到 product HTTP surface；`SET_ENVIRONMENT` 必须显式携带可空 `environmentName`（null 表示清除），
  其余命令禁止携带该字段；user content 只允许 `TEXT`、`ATTACHMENT`、`RESOURCE`，
  Attachment 由 Platform acceptance transaction 转成 durable Resource。
- [HarnessRuntimeResponseMapper](../../web/src/main/java/fun/fengwk/kkstudio/web/runtime/HarnessRuntimeResponseMapper.java)
  把 Runtime snapshot 投影为严格 DTO，Entry/Command/AgentMessage/Model result/Model
  error/Tool approval/Tool result 各经对应 JSON codec 编码，Thread status 由
  `ThreadContextClassifier` 按同一 snapshot 计算，不从 HTTP 层复制状态机。
- [WebDtoMapper](../../web/src/main/java/fun/fengwk/kkstudio/web/mapper/WebDtoMapper.java)
  负责 Canvas domain 到 share DTO 的映射；媒体 Resource 的 kind、mediaType、size、
  width、height、duration 来自 `StorageBlob` 权威行，TEXT Resource 没有 Blob，
  bucket/object key 不进入 DTO。
- [ProjectSnapshotAssembler](../../web/src/main/java/fun/fengwk/kkstudio/web/project/ProjectSnapshotAssembler.java)
  聚合未归档 Issues、依赖、blocked 状态与当前/最近 Run，
  并对每条跨表归属 fail closed。
- [StrictJacksonConfiguration](../../web/src/main/java/fun/fengwk/kkstudio/web/StrictJacksonConfiguration.java)
  全局启用 `STRICT_DUPLICATE_DETECTION`、省略 null 并保持 DTO 声明顺序，同时把 `Long`
  写为字符串；各 DTO/codec 继续严格拒绝 unknown fields 与 trailing tokens。

五个 `@Order(HIGHEST_PRECEDENCE)`、按 `assignableTypes` 限定范围的 advice 保持稳定
error envelope：

| advice | controller 范围 | HTTP 映射 |
| --- | --- | --- |
| [StudioDomainErrorAdvice](../../web/src/main/java/fun/fengwk/kkstudio/web/advice/StudioDomainErrorAdvice.java) | Catalog、Chat、MCP、Environment | validation 400、not found 404、version conflict/duplicate/in-use 409 |
| [StudioResponseStatusErrorAdvice](../../web/src/main/java/fun/fengwk/kkstudio/web/advice/StudioResponseStatusErrorAdvice.java) | Canvas、Chat、ComfyUI、Harness | `ResponseStatusException` 按 status 输出；Runtime not found 404、conflict 409、非法输入 400 |
| [StudioStorageErrorAdvice](../../web/src/main/java/fun/fengwk/kkstudio/web/advice/StudioStorageErrorAdvice.java) | Storage | validation 400、not found 404、verification/conflict 409 |
| [StudioSystemSettingsErrorAdvice](../../web/src/main/java/fun/fengwk/kkstudio/web/advice/StudioSystemSettingsErrorAdvice.java) | SystemSettings | validation 400、row missing 404、CAS conflict 409，并带 expected/actual version |
| [StudioProjectErrorAdvice](../../web/src/main/java/fun/fengwk/kkstudio/web/project/StudioProjectErrorAdvice.java) | Project、Issue | validation 400、not found 404、version/runtime conflict 409、`IllegalStateException` 500；不回显 Issue Activity 正文、幂等键或认证数据 |

用户可见 message 由 [StudioMessageService](../../web/src/main/java/fun/fengwk/kkstudio/web/i18n/StudioMessageService.java)
按请求 locale 解析，支持 `en-US`、`zh-CN`，其它 locale fallback 到英文；Platform
domain error 不持有 locale 和 HTTP status。

## 启动、Flyway 与静态分发

[`application.yml`](../../web/src/main/resources/application.yml) 提供 application name
`kk-studio`、默认 `dev` profile、PostgreSQL datasource placeholder、8080 port、gzip
compression 与 `health,prometheus,offline,online` actuator exposure；
[`application-prod.yml`](../../web/src/main/resources/application-prod.yml) 追加
`server.forward-headers-strategy: framework`，数据源三项全部来自
`KK_STUDIO_DB_URL`/`KK_STUDIO_DB_USER`/`KK_STUDIO_DB_PASSWORD` 且无默认值，缺失时启动
失败而不是回退到开发数据库。数据源设置 Hikari `connection-timeout` 5 秒、JDBC
`connectTimeout` 5 秒与 `socketTimeout` 5 秒三个超时边界，使 PostgreSQL 不可达时在预算
内 fail closed 并映射 `ENVIRONMENT_UNAVAILABLE`，绝不回退到内存 route。

Flyway 只有一个完整声明当前结构的 canonical baseline：

```text
schema/src/main/resources/db/migration/V1__schema.sql
```

profile locations：

| profile | Flyway locations |
| --- | --- |
| dev | `classpath:db/migration` + `classpath:db/seed/dev` |
| e2e | `classpath:db/migration` + `classpath:db/seed/e2e` |
| canvas-test | migration + dev seed + `db/seed/canvas-test` |
| prod | 只用 `classpath:db/migration` |

[FlywayBootstrapArchitectureTest](../../web/src/test/java/fun/fengwk/kkstudio/web/FlywayBootstrapArchitectureTest.java)
守护不存在 `spring.sql.init`、`schema-locations` 和 `docker-entrypoint-initdb.d` 竞争
入口。

`distribution` profile 定义在 `web/pom.xml`，只在显式激活时于 `prepare-package` 阶段
执行 Node `v24.14.0` / npm `11.9.0` 的 `npm ci` 与 Vite build，先清理
`web/target/classes/static/`，把输出写入 `web/target/frontend-dist`，再复制到
`${project.build.outputDirectory}/static`，最后由 `spring-boot-maven-plugin:repackage`
放进 Fat JAR。该 profile 不写回 frontend source 或 Web 的静态资源源码目录，普通
`mvn test/package` 不触发 Node toolchain。

[SpaFallbackConfig](../../web/src/main/java/fun/fengwk/kkstudio/web/SpaFallbackConfig.java)
把 `classpath:/static/` 接到 `/**`，真实 asset 与已映射 Controller 优先；只有 `GET`、
非 `/api/`、非 `/actuator/`、且末段无扩展名的路径才 fallback 到 `static/index.html`。

## 通知、事件与 WebSocket

### PostgresqlNotificationLoop：单连接、多 channel

[ApplicationEventConfiguration](../../web/src/main/java/fun/fengwk/kkstudio/web/events/ApplicationEventConfiguration.java)
注册唯一 [PostgresqlNotificationLoop](../../web/src/main/java/fun/fengwk/kkstudio/web/events/postgresql/PostgresqlNotificationLoop.java)，
固定监听：

```text
harness_runtime_work
project_issue_work_due
canvas_function_work
harness_thread_version
canvas_version
project_issue_changed
system_settings_changed
harness_realtime
skill_package_changed
```

Loop 只拥有一个专用 JDBC connection 和一个 daemon platform thread：连接建立后一次性
`LISTEN` 全部 channel，再对每个 handler 调 `onResync`，随后用
`PGConnection.getNotifications(pollMillis)` 拉取通知。驱动的 `getNotifications` 在拉取时
临时以 poll timeout 覆盖并恢复底层 SO_TIMEOUT，因此 driver 的 5 秒 `socketTimeout` 不会
破坏 LISTEN 循环，空闲无通知时不会触发意外断连与额外 resync。某个 handler 抛错只隔离
该 handler，不终止 loop；连接断开按 backoff 重连，重连成功再次 resync。关闭顺序是
`connection.abort` → interrupt loop thread → 最多 5s join，`SmartLifecycle.close` 幂等。
前三个 channel 只做 dispatcher wake，version、realtime、settings、Project 与 Skill Package
handler 负责各自的 snapshot/resync 逻辑，NOTIFY 本身不是 durable event log；
`skill_package_changed` 的 payload 是 package 名，resync 对本节点全部 READY Environment
做全量对账。

### Application Event WebSocket

[ApplicationEventWebSocketConfiguration](../../web/src/main/java/fun/fengwk/kkstudio/web/events/ApplicationEventWebSocketConfiguration.java)
注册端点 `/api/events/v1`，客户端帧是严格 version 1：

```json
{"version":1,"type":"subscribe","resource":{"kind":"thread","id":"<canonical UUID>"}}
{"version":1,"type":"unsubscribe","resource":{"kind":"canvas","id":"<canonical UUID>"}}
{"version":1,"type":"subscribe","resource":{"kind":"projects"}}
```

[EventFrameCodec](../../web/src/main/java/fun/fengwk/kkstudio/web/events/EventFrameCodec.java)
严格校验字段集与组合：Thread/Canvas resource 精确包含 `kind,id`，全局 `projects`
resource 精确只包含 `kind`；`subscribed.cursor` 是建立订阅瞬间的 durable Thread/Canvas
version，全局资源使用 `0` 作为连接代际 ack cursor；version event 的 cursor 与
`data.version` 必须相等；Thread realtime event 携带 Runtime realtime JSON 且不带
cursor；Projects 的 `changed` event 携带单一 canonical `projectId` 且不带 cursor；
`resync` 只表示客户端重新读取对应 REST snapshot；heartbeat 不携带 resource/cursor/data。

[ApplicationEventHub](../../web/src/main/java/fun/fengwk/kkstudio/web/events/ApplicationEventHub.java)
按 resource key 维护共享上游：Thread 使用 version source + realtime source，Canvas 只用
version source，Projects 连接数据库失效 Hub。建立上游时先注册 consumer 再读取 cursor；
fan-out 与 cursor/consumer registration 在 resource lock 内互斥；WebSocket handler 先把
`subscribed` ack 入 `AsyncTextSender`，再 `subscription.activate()`，因此事件帧不会早于
ack；`version <= ack cursor` 的陈旧信号被过滤。

上游建立期与 ack 前缓冲都有界，溢出清空并折叠为一个 `resync`。资源不存在只发送资源级
`RESOURCE_NOT_FOUND` 并保持连接；非法帧发送 `INVALID_FRAME` 后以 1002 关闭；队列过载
发送 `BACKPRESSURE` 后以 1013 关闭；应用关闭发送 `SEND_FAILED` 后以 1012 关闭。

[AsyncTextSender](../../web/src/main/java/fun/fengwk/kkstudio/web/events/AsyncTextSender.java)
使用 jakarta `AsyncRemote.sendText(SendHandler)`，同一 session 只有一个 in-flight
frame，完成回调驱动下一帧；frame count 与 UTF-8 bytes 双限都包含 in-flight frame。
锁内只做计数与队列转移，网络 I/O 在锁外；发送失败清空队列并关闭 session。所有浏览器
连接共享一个 heartbeat scheduler，heartbeat 只入既有发送队列。

### Environment Daemon WebSocket：有界发送

[EnvironmentDaemonWebSocketConfiguration](../../web/src/main/java/fun/fengwk/kkstudio/web/environment/EnvironmentDaemonWebSocketConfiguration.java)
注册端点 `/api/harness/environment-daemon/v1`。
[EnvironmentDaemonWebSocketHandler](../../web/src/main/java/fun/fengwk/kkstudio/web/environment/EnvironmentDaemonWebSocketHandler.java)
只做 transport adapter：在 Spring WebSocket 与 native JSR-356 session 两侧设置
`kk-studio.harness.environment-gateway.max-message-bytes`；创建
`SpringWebSocketConnection` 与每连接 [DaemonOutboundSender](../../web/src/main/java/fun/fengwk/kkstudio/web/environment/DaemonOutboundSender.java)；
把 open/receive/close 委托给会话核心的 `DaemonEndpoint`；断开时先由核心解绑 registry、
在途 invocation 与 pending request，再关闭 sender。web 层不解释协议，也不保存任何会话
状态。

`DaemonOutboundSender` 使用每连接一个 virtual-thread sender，独占帧串行化、frame count
与 UTF-8 bytes 双限（均含 in-flight frame）以及 send timeout，不叠加 Spring 并发装饰器。
递交非阻塞并返回确定性结果：容量/字节预算拒绝为 `BUSY`（帧未发送、连接保持可用），
围栏已关闭为 `CLOSED`；按入队顺序调用 `sendMessage`。超时或异常会关闭入队围栏、通知
会话核心做 uncertain/unavailable cleanup，再关闭 transport；慢连接不会占住 receive、
会话核心锁或其它连接。

端点强制本次握手协商 `permessage-deflate`：扩展缺失或不含该 token 时以 RFC 6455
close code `1010` 关闭，且不向会话核心暴露通道。非 servlet Spring context（MockMvc、
`WebEnvironment.MOCK`）中 `EnvironmentDaemonWebSocketContainerFactoryBean` 检测不到
`ServerContainer` 时 no-op，因此测试 context 不需要真实 JSR-356 container。

[HarnessRuntimeConfiguration](../../web/src/main/java/fun/fengwk/kkstudio/web/runtime/HarnessRuntimeConfiguration.java)
声明组合 `EnvironmentSessionListener`，通过 `ObjectProvider` 弱引用唤醒可选的
`HarnessWorkDispatcher`，解除与 `EnvironmentDaemonServer` 的循环依赖并隔离异常。

### Plugin 组合边界

Contributor 只有一条来源：Spring 容器中的 bean。`ContributorCatalogConfiguration` 用
`ObjectProvider<HarnessContributor>` 收集它们并冻结为单一 `HarnessCatalog`，重复
contributor id 在启动期失败。构建期 Plugin 由自己的 `AutoConfiguration.imports` 随应用
启动，可使用 Platform 的凭据、Storage、调度和管理端口；它是否进入发行物只由
`web/pom.xml` 的 runtime dependency 决定，因此删除该 dependency 后应用不残留任何 Plugin
语义，web 也不需要任何插件目录、classloader 或热刷新代码。

Plugin 自己的管理面由 [StudioPluginController](../../web/src/main/java/fun/fengwk/kkstudio/web/plugin/StudioPluginController.java)
暴露，装配入口是 Platform 的 `PluginConfiguration`；同一入口也以 `@ConditionalOnMissingBean`
装配 Platform 自带的 `PluginResourceGateway` 生产实现（会话资源受控下载与远端媒体暂存），
它只依赖 Platform 自身的 Harness/Storage bean，与具体 Plugin 无关。

## 生命周期与配置

| 生命周期 | phase / owner | 关闭语义 |
| --- | --- | --- |
| Harness workers | `HarnessRuntimeLifecycle`，`MAX_VALUE - 1` | `workers-enabled=false` 不启动 dispatcher；store、processor 与 realtime bean 仍可用；start 失败回滚 dispatcher，stop 幂等 |
| Issue Controller | `IssueControllerRuntimeLifecycle`，`MAX_VALUE - 1` | 与 Harness workers 共用 `workers-enabled`；不启动时仍保留 REST 与 notification loop |
| Browser heartbeat | `applicationEventHeartbeatScheduler` | destroy `shutdown`；handler `@PreDestroy` 先发 1012 `SEND_FAILED` |
| Application Event Hub | `ApplicationEventHub`，destroy `close` | 关闭 resource upstream、标记 subscriptions closed |
| PostgreSQL notifications | `PostgresqlNotificationLoop`，`MAX_VALUE` | abort connection、interrupt、bounded 5s join |
| Harness processors / RT source | `modelProcessor`、`toolProcessor`、`realtimeEventSource` 等 | Spring destroy `close`；heartbeat timer 用 `shutdown`，heartbeat worker 与 model flush executor 用 `close` |
| Plugin credential refresh | `PluginCredentialRefreshDispatcher`（phase `MAX_VALUE`） | 启动立即 scan，随后默认每小时 scan；stop 取消调度并等待在途 scan，claim 后的 finalize 仍由 lease token 与 version 围栏 |
| Plugin credential refresh | `PluginCredentialRefreshLifecycle` | 启动立即 scan，随后默认每小时 scan；stop 停止领取新 lease，in-flight finalize 仍由 lease/version 围栏 |

Web context 自身持有的部署配置：

| 配置 | 职责 |
| --- | --- |
| `spring.application.name` / `spring.profiles.active` | 应用名 `kk-studio`，默认 `dev` |
| `spring.datasource` | 唯一 durable database；Hikari `connection-timeout: 5000`、driver `connectTimeout: "5"`、`socketTimeout: "5"` |
| `spring.flyway.locations` | 按 profile 选择 canonical baseline 与 seed |
| `server.port` / `server.compression.enabled` / `server.forward-headers-strategy` | 默认 `8080`、gzip；prod 用 `framework` 按 `X-Forwarded-*` 还原外部 scheme/host |
| `management.endpoints.web.exposure.include` | `health,prometheus,offline,online` |
| `spring.mvc.converters.preferred-json-mapper` | `jackson`，并排除 convention4j 的 `WebErrorAutoConfiguration` |
| `kk-studio.harness.runtime.workers-enabled` | 是否启动 Work dispatcher 与 Issue Controller；可由 `KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED` 配置，默认 true，测试与本机 preview 显式关闭 |
| `kk-studio.harness.environment-gateway.*` | Daemon WebSocket 入站 frame 与出站 queue/bytes/send timeout |
| `kk-studio.plugins.credential-key-file` | Plugin 凭据主密钥的 owner-only 绝对路径；空值表示本部署不提供凭据能力并 fail closed，多节点必须挂载同一内容 |
| `kk-studio.plugins.refresh.poll-delay` / `lease-duration` | 凭据刷新扫描间隔与跨节点互斥 lease；由 `KK_STUDIO_PLUGINS_REFRESH_POLL_DELAY` / `..._LEASE_DURATION` 提供，默认 `1h` / `2m` |
| `kk-studio.plugins.resource.*` | Plugin 资源端口边界：`connect-timeout`、`request-timeout`、`upload-timeout`、`max-bytes`（默认 `256MiB`，硬上限 `1GiB`）与 `temp-directory`（留空即 `java.io.tmpdir`）；由 `KK_STUDIO_PLUGINS_RESOURCE_*` 提供 |

其余 `kk-studio.*` 键（dispatcher、execution-admission、runtime resource root、project
controller、storage、Plugin credential、canvas、comfyui、opencli-hub）由 Platform 与 Canvas Infra 的
`@ConfigurationProperties` 定义，完整清单见 [Platform 模块配置](platform.md#配置)。

### 安全边界

- 代码没有 `spring-boot-starter-security`、`SecurityFilterChain` 或 Spring Security auth
  filter；HTTP TLS、用户认证和 ingress policy 属部署边界，不由当前 Web context 伪造。
- Daemon WebSocket 的 registration token 是 deployment secret，保存在
  `environment.registration_token`，不进 SystemSettings、DTO 或日志；gateway 在 HELLO
  认证时基于该 token 映射对应 Environment Card，连接失败会清理 live state。
- 系统不开放公开 S3 预签名端点；Blob 原始与预览访问统一由 Storage/Canvas 签发有限
  expiry 的 presigned URL，ComfyUI 文件输入直接使用 blobId。
- Trusted JAR 只从显式 canonical directory 加载，不提供远程下载或热加载。
- Plugin auth callback、明文 credential 与 renewal 参数只在 `no-store` 请求内短暂存在；
  PostgreSQL 只存认证密文。未配置部署级主密钥时认证写入与已存在凭据读取 fail closed，
  但未连接的可选 Plugin 不阻止应用启动。
- `StrictJacksonConfiguration` 与各 domain codec 拒绝 duplicate/unknown/trailing
  fields；Controller 不把上游异常、credential、bucket 或任意宿主执行路径作为
  公开字段；明确声明的 Daemon 用户与 HOME 宿主事实除外。

## 事务、并发与失败恢复不变量

1. `WebApplication` 是唯一生产 Spring Boot root；Controller 只调用 Platform/Runtime/Core
   port，不复制领域事务或状态机。
2. Plugin 是否安装只由 `web` 的 runtime dependency 决定；数据库行或前端开关不能加载
   一个 classpath 中不存在的 Plugin。
3. `V1__schema.sql` 是全仓唯一 baseline，profile seed 只由 Flyway locations 选择；没有
   第二份 SQL bootstrap。
4. REST snapshot 是 durable truth 的读取入口；Application Event WebSocket 只发送
   Thread/Canvas version、Thread realtime、Project changed 与全局 resync signal，
   不保存事件记录、不替代 REST command/query。
5. Event subscription 的上游注册先于 cursor 读取，ack 先于 activation，陈旧 version 按
   cursor 过滤；buffer 溢出必然折叠成一个 resync，内存有界且可恢复。
6. WebSocket 每连接只保留单一 in-flight frame，并有 frame count + UTF-8 bytes 双限与
   有限 send timeout；失败先停止入队，再清理订阅/registry，最后关闭 transport。
7. PostgreSQL notification loop 的一个 handler 失败不影响其它 channel；连接丢失由
   reconnect + resync 恢复，通知丢失不改变 PostgreSQL durable truth。Project 的跨节点/
   浏览器 invalidation 只来自数据库 trigger 提交事实，不在 Controller 额外广播。
8. Harness dispatcher、processor、event loop、heartbeat scheduler 和 sender 都有明确
   owner；Environment WebSocket 的等待链路在 DB 连接与 send timeout 预算内 fail closed，
   control-plane 不存在目录 round trip。
9. Trusted JAR list 在 catalog 创建后不可变，jar scan 只从显式目录发生；任何加载异常在
   context 可用前关闭已创建的 classloader。
10. strict JSON field、canonical UUID、canonical decimal、DTO discriminator 与 duplicate
   detection 在 Web boundary 拒绝不确定输入；error advice 不改领域事实，只映射 status、
   stable code、context 与 locale message。
11. S3 是应用必配基础设施，启动时严格验证 properties 与 bucket 可访问性；ComfyUI
    disabled 返回 503，enabled workflow 不存在返回 404，参数/selector/binding 错误返回
    400。

## 测试入口

组合、架构与 bootstrap：

- [`WebModuleArchitectureTest.java`](../../web/src/test/java/fun/fengwk/kkstudio/web/WebModuleArchitectureTest.java)、
  [`FlywayBootstrapArchitectureTest.java`](../../web/src/test/java/fun/fengwk/kkstudio/web/FlywayBootstrapArchitectureTest.java)、
  [`NoRedisArchitectureTest.java`](../../web/src/test/java/fun/fengwk/kkstudio/web/NoRedisArchitectureTest.java)、
  [`StudioApiRouteTopologyTest.java`](../../web/src/test/java/fun/fengwk/kkstudio/web/StudioApiRouteTopologyTest.java)。
- [`WebTestApplication.java`](../../web/src/test/java/fun/fengwk/kkstudio/web/WebTestApplication.java)、
  [`WebPostgresTestSupport.java`](../../web/src/test/java/fun/fengwk/kkstudio/web/WebPostgresTestSupport.java)、
  [`PostgresqlDataSourceConfigurationTest.java`](../../web/src/test/java/fun/fengwk/kkstudio/web/PostgresqlDataSourceConfigurationTest.java)、
  [`FlywayAutoConfigurationIntegrationTest.java`](../../web/src/test/java/fun/fengwk/kkstudio/web/FlywayAutoConfigurationIntegrationTest.java)、
  [`SpaFallbackTest.java`](../../web/src/test/java/fun/fengwk/kkstudio/web/SpaFallbackTest.java)、
  [`StrictJacksonConfigurationTest.java`](../../web/src/test/java/fun/fengwk/kkstudio/web/StrictJacksonConfigurationTest.java)。

HTTP boundary 按目录定位：

| 范围 | 测试目录 | 代表测试 |
| --- | --- | --- |
| Controller 与 DTO 解析 | [controller/](../../web/src/test/java/fun/fengwk/kkstudio/web/controller/) | [`StudioHarnessCommandBatchControllerTest.java`](../../web/src/test/java/fun/fengwk/kkstudio/web/controller/StudioHarnessCommandBatchControllerTest.java)、[`StudioMcpRuntimeToolIntegrationTest.java`](../../web/src/test/java/fun/fengwk/kkstudio/web/controller/StudioMcpRuntimeToolIntegrationTest.java) |
| Error advice 与 i18n | [advice/](../../web/src/test/java/fun/fengwk/kkstudio/web/advice/)、[i18n/](../../web/src/test/java/fun/fengwk/kkstudio/web/i18n/) | [`StudioProjectErrorAdviceTest.java`](../../web/src/test/java/fun/fengwk/kkstudio/web/project/StudioProjectErrorAdviceTest.java)、[`StudioMessageServiceTest.java`](../../web/src/test/java/fun/fengwk/kkstudio/web/i18n/StudioMessageServiceTest.java) |
| Project snapshot 与 invalidation | [project/](../../web/src/test/java/fun/fengwk/kkstudio/web/project/) | [`ProjectSnapshotAssemblerTest.java`](../../web/src/test/java/fun/fengwk/kkstudio/web/project/ProjectSnapshotAssemblerTest.java)、[`ProjectInvalidationHubTest.java`](../../web/src/test/java/fun/fengwk/kkstudio/web/project/ProjectInvalidationHubTest.java) |
| Harness/Issue 组合与 mapper | [runtime/](../../web/src/test/java/fun/fengwk/kkstudio/web/runtime/) | [`HarnessRuntimeConfigurationTest.java`](../../web/src/test/java/fun/fengwk/kkstudio/web/runtime/HarnessRuntimeConfigurationTest.java)、[`HarnessRuntimeRequestMapperTest.java`](../../web/src/test/java/fun/fengwk/kkstudio/web/runtime/HarnessRuntimeRequestMapperTest.java)、[`IssueAcceptanceChainRuntimeIntegrationTest.java`](../../web/src/test/java/fun/fengwk/kkstudio/web/runtime/IssueAcceptanceChainRuntimeIntegrationTest.java) |
| Contributor 装配与 Plugin 管理面 | [runtime/contributor/](../../web/src/test/java/fun/fengwk/kkstudio/web/runtime/contributor/)、[plugin/](../../web/src/test/java/fun/fengwk/kkstudio/web/plugin/) | [`ContributorCatalogWiringTest.java`](../../web/src/test/java/fun/fengwk/kkstudio/web/runtime/contributor/ContributorCatalogWiringTest.java)、[`StudioPluginControllerTest.java`](../../web/src/test/java/fun/fengwk/kkstudio/web/plugin/StudioPluginControllerTest.java) |
| 通知、Application Event 与 Daemon WebSocket | [events/](../../web/src/test/java/fun/fengwk/kkstudio/web/events/)、[events/postgresql/](../../web/src/test/java/fun/fengwk/kkstudio/web/events/postgresql/)、[environment/](../../web/src/test/java/fun/fengwk/kkstudio/web/environment/) | [`PostgresqlNotificationLoopTest.java`](../../web/src/test/java/fun/fengwk/kkstudio/web/events/postgresql/PostgresqlNotificationLoopTest.java)、[`ApplicationEventHubTest.java`](../../web/src/test/java/fun/fengwk/kkstudio/web/events/ApplicationEventHubTest.java)、[`DaemonOutboundSenderTest.java`](../../web/src/test/java/fun/fengwk/kkstudio/web/environment/DaemonOutboundSenderTest.java) |
| 跨 owner 事务 orchestration | [orchestration/](../../web/src/test/java/fun/fengwk/kkstudio/web/orchestration/) | [`HarnessCommandAcceptanceOrchestratorIntegrationTest.java`](../../web/src/test/java/fun/fengwk/kkstudio/web/orchestration/HarnessCommandAcceptanceOrchestratorIntegrationTest.java)、[`SessionDeletionOrchestratorIntegrationTest.java`](../../web/src/test/java/fun/fengwk/kkstudio/web/orchestration/SessionDeletionOrchestratorIntegrationTest.java) |

这些测试覆盖 Web composition 的真实风险：唯一 root 与依赖方向、Flyway single
baseline、PostgreSQL notification reconnect/resync、Harness/Issue worker NOTIFY+poll
唤醒、Project trigger invalidation、ack-before-event、bounded sender、Environment large
READY frame、synchronous MCP discovery/execution、strict DTO、Plugin 管理面 no-store 与
去敏、locale fallback 与静态 SPA fallback。

---

上级：[系统设计](../system-design.md)。相关文档：[Platform](platform.md)、
[Canvas Infra](canvas-infra.md)、[Harness Infra](harness-infra.md)、
[Harness Builtin](harness-builtin.md)、[Harness Contributor API](harness-contributor-api.md)、
[部署与运行](../operations/deployment.md)。
