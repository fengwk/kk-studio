# Web 模块

## 定位

`web` 是 kk-studio 唯一的生产 Spring Boot composition root。它把 Platform application services、Canvas/Harness
infra、纯 Java `harness-runtime`、第一方内置 `BuiltinHarnessContributor` 和受信任 Contributor JAR 快照组合成一个可运行的 HTTP/WebSocket
进程，并提供浏览器 REST、静态 SPA、浏览器 Application Event WebSocket 与 Environment Daemon WebSocket。

生产入口只有 `web/src/main/java/fun/fengwk/kkstudio/web/WebApplication.java`：

```java
SpringApplication.run(WebApplication.class, args);
```

`web/src/test/java/fun/fengwk/kkstudio/web/WebTestApplication.java`仅用于 Spring integration tests，不是第二个生产
composition root。Web 不实现 Catalog、Canvas、Harness、Storage 或 Environment 的领域规则；Controller 只做 DTO
解析、调用 application service 和结果投影。

## Goals / Non-goals

### Goals

- 以一个 Spring Boot context 装配 Platform、Canvas infra、Harness infra/runtime 和 HarnessCatalog。
- 维护 Flyway 唯一 migration/seed 入口和 Spring Boot Fat JAR 的静态前端 distribution。
- 暴露严格、可定位、统一 `Result<T>` envelope 的 Controller/API families。
- 将 PostgreSQL notification、durable snapshot/version、realtime overlay 和 WebSocket resync 连接起来。
- 为浏览器和 Environment Daemon 提供有界、顺序一致、失败可恢复的异步发送链。
- 在 HTTP async boundary、trusted JAR、启动/关闭 lifecycle 和部署安全边界上保持清晰职责。

### Non-goals

- 不直接声明 `harness-tool` Maven dependency，也不使用 Tool execution、Daemon
  implementation 或 Platform 的 Environment gateway/registry implementation；
  DTO mapping 只使用架构守卫允许的 canonical Tool value/codec。
- 不在 Controller 内实现事务、owner authorization、Blob 引用计数、Provider admission 或 Harness reducer。
- 不创建第二份 schema、第二个 Flyway baseline 或第二套 durable realtime store。
- 不提供 trusted JAR 的运行时安装、刷新或不受控 classloader；贡献者只在启动阶段按显式目录加载。
- 不在浏览器事件 WebSocket 中搬运 command、snapshot、stop、approval 等 HTTP 能力；这些仍走 REST。

## 依赖边界

### Maven 依赖

`web/pom.xml`直接声明：

| 方向 | 依赖 |
| --- | --- |
| Application/domain | `kk-studio-platform`、`kk-studio-share`、`kk-studio-canvas-infra` |
| Harness composition | `kk-studio-harness-runtime`、`kk-studio-harness-infra`、`kk-studio-harness-contributor-api`、`kk-studio-harness-builtin` |
| Web transport | `convention4j-spring-boot-starter-web`、`spring-boot-starter-websocket` |
| Database bootstrap | `kk-studio-schema` runtime、`flyway-core`、`flyway-database-postgresql` |
| Integration tests | convention test starter、Testcontainers PostgreSQL/JUnit |

`WebModuleArchitectureTest`要求 web 直接声明 Canvas infra、Harness infra、Contributor API 和 Builtin 模块，同时禁止
直接声明 `kk-studio-harness-tool`、`kk-studio-harness-daemon`。生产源码只允许直接使用 Harness Runtime、Harness infra、
Contributor API、Builtin 模块，以及少数用于 DTO mapping 的 canonical Tool 类型；禁止直接消费 Platform Environment gateway/
registry implementation。

### 组合结构

```text
WebApplication
  -> Spring Boot / MVC / WebSocket / Flyway
  -> PlatformAutoConfiguration
       -> Platform application services and adapters
  -> Canvas infra -> canvas-core
  -> HarnessRuntimeConfiguration
       -> harness-infra -> harness-runtime -> harness-tool
  -> ContributorCatalogConfiguration
       -> Spring HarnessContributor beans + TrustedJarContributorLoader
       -> immutable HarnessCatalog
  -> HTTP Controllers / DTO mappers / error advice
  -> Application Event WebSocket + Environment Daemon WebSocket
```

### HarnessRuntime / Infra / Platform / Canvas / Contributor 装配

`web/src/main/java/fun/fengwk/kkstudio/web/runtime/HarnessRuntimeConfiguration.java`是 Harness 的 application
composition 子根：

- `PostgresqlHarnessStore`把 `HarnessStore`绑定到 Web datasource 和 transaction manager；
- `LocalFileResourceStore`使用 `environment-root/.kkstudio/resources/`作为 content-addressed resource root，
  单对象上限取 `SystemSettings.Advanced.resourceMaxBytes`；
- `PostgresqlRealtimeEventSink`和 `PostgresqlRealtimeEventSource`把 Model/Tool realtime 连接到 PostgreSQL；
- `ThreadProcessor`、`ModelProcessor`和 `ToolProcessor`共享 lease/heartbeat 配置；
- `HarnessWorkDispatcher`使用单线程 drain、bounded worker executor、poll scheduler 和 `harness_work` claim；
- `HarnessRuntimeLifecycle`只控制 dispatcher 是否启动，Runtime control/query beans 始终由 context 持有。

Platform 由 `PlatformAutoConfiguration`自动扫描，提供 Catalog、Chat、SystemSettings、Model/Tool gateway、Storage、
Environment 和 Canvas application services。Canvas infra 通过 web 的直接依赖提供 PostgreSQL/MyBatis repository、
Canvas query projection 和 Function dispatcher。Web 的业务适配主要面向
Platform/Core 接口；Canvas dispatcher 只在下述 composition seam 被直接引用。

`ContributorCatalogConfiguration`收集 Spring bean 形式的 `HarnessContributor`（包含 Platform 暴露的
`BuiltinHarnessContributor`）与 `TrustedJarContributorLoader` 加载的外部受信任贡献者，调用 `HarnessCatalog.from`
冻结为单一不可变 `HarnessCatalog`。外部 Contributor 是启动期静态扩展，不提供运行时安装或热刷新。

`web/src/main/java/fun/fengwk/kkstudio/web/events/ApplicationEventConfiguration.java`
是 composition seam：它直接 import
`CanvasFunctionDispatcher`，把 `canvas_function_work` notification 接到 Canvas
dispatcher；这不是 Platform 对 Canvas Infra implementation 的反向依赖。

数据库是唯一 durable database，Web 不在当前 context 内实现 HTTP 认证。HTTP
认证/TLS 的部署边界在应用外；
Environment Daemon 的连接身份由 `daemon-token`在 Platform gateway HELLO 协议中校验，trusted contributor directory 是另一个
显式部署信任边界。

## 核心子域 / API

### Spring/Flyway/static distribution

`web/src/main/resources/application.yml`提供 Spring application name `kk-studio`、默认 `dev` profile、
PostgreSQL datasource placeholder、8080 port、gzip compression，以及 `health,prometheus,offline,online`
actuator exposure。`StrictJacksonConfiguration`全局启用 `STRICT_DUPLICATE_DETECTION`；各 DTO/codec继续严格拒绝
unknown fields 和 trailing tokens。

Flyway 只有一个 baseline：

```text
schema/src/main/resources/db/migration/V1__schema.sql
```

Profile locations 是：

| profile | Flyway locations |
| --- | --- |
| dev | `classpath:db/migration` + `classpath:db/seed/dev` |
| e2e | `classpath:db/migration` + `classpath:db/seed/e2e` |
| canvas-test | migration + dev seed + `db/seed/canvas-test` |

`FlywayBootstrapArchitectureTest`还守护不存在 `spring.sql.init`、`schema-locations` 和
`docker-entrypoint-initdb.d`竞争入口；`web` runtime 依赖 schema，Platform/schema/infra 只在测试需要时使用 schema
资源。

`distribution` profile在 `prepare-package`阶段：

1. 在 `frontend/`执行 Node `v24.14.0`、npm `11.9.0`、`npm ci`和 Vite build；
2. 先清理 `web/target/classes/static/`，将输出写入 `web/target/frontend-dist`；
3. 将 dist copy 到 `${project.build.outputDirectory}/static`；
4. `spring-boot-maven-plugin:repackage`把它放入 Fat JAR。

该 profile 不写回 `frontend` source 或 Web 的静态资源源码目录；普通
`mvn test/package` 不触发 Node toolchain。
`SpaFallbackConfig`把 `classpath:/static/`接到 `/**`，真实 asset 优先；只有 GET、非 `/api/`、非 `/actuator/`、且
末段无扩展名的路径才 fallback 到 `static/index.html`。

### Controller/API families

所有 JSON Controller 使用 convention4j `Result<T>`；`Results.created`、`Results.accepted`、`Results.ok`和
`Results.noContent`表达业务状态，response advice 负责使 HTTP status 与 result status 对齐。

| family | path | 当前职责 |
| --- | --- | --- |
| Health | `GET /healthz` | 进程存活探针，不读取业务外部资源 |
| Agent Provider | `/api/ai/catalog/providers` | page、create、update、expectedVersion delete |
| Agent Model | `/api/ai/catalog/models` | page、create、复合 `(providerName, modelName)` update/delete |
| Agent Definition | `/api/ai/catalog/agents` | page、create、update、expectedVersion delete |
| Tool catalog | `GET /api/ai/catalog/tools` | Platform/Environment 可选工具目录投影 |
| Chat | `/api/ai/chat` | Chat CRUD、Chat Session summary |
| Harness command | `POST /api/ai/runtime/command-batches` | Chat/Canvas 唯一用户 command write path |
| Harness Session | `/api/ai/runtime/sessions/{sessionId}/{threads,entries}` | Thread summary 和 Session Entry tree 查询 |
| Harness Thread | `/api/ai/runtime/threads/{threadId}` | snapshot、system-prompt、compact、yolo、stop、Tool approval |
| Harness resource | `/api/ai/runtime/resources/{sha256}` | content-addressed managed Resource 下载 |
| Canvas document | `/api/canvases` | document snapshot/list/create/delete、typed command batch |
| Canvas resource | `/api/canvases/{canvasId}/resources/{resourceId}/{download-url,preview-url}` | Blob original/preview presign |
| Canvas Function | `/api/canvas-function-models`、`/api/canvases/{canvasId}/nodes/{nodeId}/...` | model catalog、run、query、cancel |
| Storage | `/api/storage` | upload reserve/complete/delete、Blob original/preview presign |
| S3 presign | `/api/s3/presigned-{uploads,downloads}` | 仅 `comfyui-inputs/` namespace 的 PUT/GET presign |
| SystemSettings | `/api/settings`、`/api/settings/schema` | 全局设置 GET、schema GET、CAS PUT |
| Environment query | `GET /api/ai/environment` | live Environment registry read-only projection（capabilities/skills/MCP 摘要） |
| Environment directory | `GET /api/ai/environments/{name}/directories` | control-plane 单层目录 async query |
| ComfyUI workflow | `/api/comfyui/workflows` | persisted workflow API card CRUD |
| ComfyUI runtime | `/api/comfyui/workflows/{apiName}/runs`、`/api/comfyui/runs/{runId}` | stateless run/get/cancel/output download |

`StudioHarnessCommandBatchController`返回 `202 Accepted`只表示 durable acceptance 已提交；Provider/Tool 执行和
Thread progression 由 Work dispatcher 异步完成。Canvas command 返回带 `baseVersion/version`的 Patch，实时收敛另走
`/api/events/v1`。

### DTO mapping 与严格输入

`HarnessRuntimeRequestMapper`是 Harness HTTP 输入的单一 mapper：

- UUID 要求 `UUID.fromString`往返 canonical；
- version、cursor、sequence 使用 canonical decimal，分别拒绝符号、前导零和超出 long；
- owner discriminator 只允许 `CHAT`/`CANVAS`；
- target 只允许 `NEW_SESSION`、`ENTRY`、`THREAD`，每种 target 的字段集严格互斥；
- product HTTP command 只允许 `SET_ENVIRONMENT -> SET_AGENT -> SET_MODEL`前缀和一条最后的
  `USER_MESSAGE`；Agent 工具选择随最新 Agent definition 的 `config.toolIds` 解析，不通过 branch command
  发送；`CUSTOM_MESSAGE`不开放到 product HTTP surface；
- User content 只允许 `TEXT`、`ATTACHMENT`、`RESOURCE`，Attachment 由 Platform acceptance transaction 转成 durable
  Resource。

`HarnessRuntimeResponseMapper`把 Runtime snapshot 投影为严格 DTO：Entry/Command/AgentMessage/Model result/Model
error/Tool approval/Tool result 分别经对应 JSON codec 编码；Thread status 由 `ThreadContextClassifier`按同一 snapshot
计算，不从 HTTP 层复制状态机。

`WebDtoMapper`负责 Canvas domain 到 share DTO 的映射。媒体 Resource 的 kind、mediaType、size、width、height、
duration 来自 `StorageBlob`权威行；TEXT Resource 没有 Blob。Canvas id、command id、resource id 和 graph version
在这里做 canonical 解析和 wire 投影，不把 bucket/object key写入 DTO。

### DTO error advice 与 i18n

四个 `@Order(HIGHEST_PRECEDENCE)`、按 `assignableTypes`限定范围的 advice 保持稳定 error envelope：

| advice | controller 范围 | HTTP 映射 |
| --- | --- | --- |
| `StudioDomainErrorAdvice` | Catalog + Chat | validation 400、not found 404、version conflict/duplicate/in-use 409 |
| `StudioResponseStatusErrorAdvice` | Canvas、Chat、ComfyUI、Harness | `ResponseStatusException`按 status 输出；Runtime not found 404、conflict 409、非法输入 400 |
| `StudioStorageErrorAdvice` | Storage | validation 400、not found 404、verification/conflict 409 |
| `StudioSystemSettingsErrorAdvice` | SystemSettings | validation 400、row missing 404、CAS conflict 409，并带 expected/actual version |

所有 advice 返回 convention `Result`和 stable machine-readable code；用户可见 message 由
`StudioMessageService`按请求 locale 解析，支持 `en-US`、`zh-CN`，其它 locale fallback 到英文。Platform domain
error 不持有 locale 和 HTTP 状态。

## 关键流程 / 时序

### 启动与组合

```text
SpringApplication.run(WebApplication)
  -> profile datasource + Flyway migration/seed
  -> Spring context creates PlatformAutoConfiguration beans
       -> SystemSettingsSnapshot reads system_setting id=1
       -> Platform gateways/resolver/storage/environment services
  -> HarnessRuntimeConfiguration
       -> PostgresqlHarnessStore + LocalFileResourceStore
       -> Thread/Model/Tool processors
       -> HarnessWorkDispatcher + realtime source/sink
  -> ContributorCatalogConfiguration
       -> Spring contributors + TrustedJarContributorLoader
       -> immutable HarnessCatalog
  -> ApplicationEventConfiguration
       -> notification loop + version hubs + ApplicationEventHub
  -> WebSocket handlers and Controllers become available
  -> SmartLifecycle starts Harness workers and notification loop
```

`HarnessRuntimeLifecycle` phase 是 `Integer.MAX_VALUE - 1`，`workers-enabled=false`时只停用 dispatcher，不影响
Runtime control/query、store、processor 和 realtime bean。`PostgresqlNotificationLoop` phase 是
`Integer.MAX_VALUE`，独立于 worker 开关。

### Harness REST acceptance

```text
POST /api/ai/runtime/command-batches
  -> HarnessRuntimeRequestMapper
  -> HarnessCommandAcceptanceOrchestrator
       -> owner authorization
       -> attachment/upload/blob relation transaction
       -> HarnessRuntime.acceptCommands
  -> runtime.getThreadSnapshot
  -> HarnessRuntimeResponseMapper
  -> Result<HarnessAcceptedCommandsDTO> / 202
```

Controller 不创建 Thread、Session 或 Work；owner lock、Chat/Canvas relation、attachment materialization 和 Runtime
command acceptance 都由 Platform application transaction 完成。

### PostgresqlNotificationLoop：单连接、多 channel

`ApplicationEventConfiguration`注册一个 `PostgresqlNotificationLoop`，固定监听：

```text
harness_runtime_work
canvas_function_work
harness_thread_version
canvas_version
system_settings_changed
harness_realtime
```

Loop 只拥有一个专用 JDBC connection 和一个 daemon platform thread。连接建立后一次性 `LISTEN`全部 channel，再对
每个 handler 调 `onResync`；再用 `PGConnection.getNotifications(pollMillis)`拉取通知。某个 handler 抛错只隔离该
handler，不终止 loop。连接断开按 backoff 重连，重连成功再次 resync。

关闭顺序是 `connection.abort` → interrupt loop thread → 最多 5s join；`SmartLifecycle.close`幂等。`harness_runtime_work`
和 `canvas_function_work`只做 dispatcher wake；version/realtime/settings handler 负责各自 snapshot/resync 逻辑，
NOTIFY 本身不成为 durable event log。

### Application Event WebSocket：snapshot / version / realtime / resync

端点由 `ApplicationEventWebSocketConfiguration`注册为：

```text
/api/events/v1
```

客户端帧是严格 version 1：

```json
{"version":1,"type":"subscribe","resource":{"kind":"thread","id":"<canonical UUID>"}}
{"version":1,"type":"unsubscribe","resource":{"kind":"canvas","id":"<canonical UUID>"}}
```

服务端帧有 `subscribed`、`event(version)`、`event(realtime)`、`resync`、`heartbeat`和`error`：

- `subscribed.cursor`是建立订阅瞬间的 durable Thread/Canvas version；
- version event 的 cursor 与 `data.version`必须相等；
- Thread realtime event 携带 Runtime realtime JSON，不携带 cursor；
- `resync`只表示客户端重新读取对应 REST snapshot；
- heartbeat 不携带 resource/cursor/data。

`ApplicationEventHub`按 `(kind, id)`维护共享上游：Thread 使用 version source + realtime source，Canvas 只使用
version source。建立上游时先注册 consumer 再读取 cursor；fan-out 与 cursor/consumer registration 在 resource lock
内互斥。WebSocket handler 先把 `subscribed` ack 入 `AsyncTextSender`，再 `subscription.activate()`，因此事件帧不会
早于 ack。version <= ack cursor 的陈旧信号被过滤。

上游建立期和 ack 前 pending buffer 都有界；溢出清空并折叠为一个 `resync`。资源不存在只发送资源级
`RESOURCE_NOT_FOUND`并保持连接；非法帧发送 `INVALID_FRAME`后以 1002 关闭；队列过载发送 `BACKPRESSURE`后以
1013 关闭；应用关闭发送 `SEND_FAILED`后以 1012 关闭。

`AsyncTextSender`使用 jakarta `AsyncRemote.sendText(SendHandler)`，同一 session 只有一个 in-flight frame，完成回调
驱动下一帧；frame count 和 UTF-8 bytes 双限均包含 in-flight frame。锁内只做计数和队列转移，网络 I/O在锁外；
发送失败清空队列并关闭 session。所有浏览器连接共享一个 heartbeat scheduler，heartbeat 只入既有发送队列。

### Environment Daemon WebSocket：有界发送

端点由 `EnvironmentDaemonWebSocketConfiguration`注册为：

```text
/api/ai/environment/daemon/v2
```

`EnvironmentDaemonWebSocketHandler`只做 transport adapter：

1. 在 Spring WebSocket 和 native JSR-356 session 两侧设置 `max-message-bytes`；
2. 创建 `SpringWebSocketConnection`和每连接 `DaemonOutboundSender`；
3. 把 open/receive/close 委托给 Platform `EnvironmentDaemonEndpoint`；
4. Gateway 只接受 v4 HELLO 及严格的 `capabilityCatalogVersion`，并负责校验 capability INVOKE payload；
5. Gateway 先解绑 registry、active invocation 和 pending request，再关闭 sender。

`DaemonOutboundSender`使用 `ConcurrentWebSocketSessionDecorator`和每连接一个 virtual-thread sender。入队是非阻塞的，
同时受 frame count 与 UTF-8 bytes 限制（均含 in-flight frame）；sender 按入队顺序调用 `sendMessage`。每帧有
send timeout，超时/异常会关闭入队围栏、通知 Gateway 进行 uncertain/unavailable cleanup，再关闭 transport。慢连接
不会占住 receive、Gateway monitor 或其它连接。

非 servlet Spring context（MockMvc、`WebEnvironment.MOCK`）中
`EnvironmentDaemonWebSocketContainerFactoryBean`检测不到 `ServerContainer`时 no-op，因此测试 context 不需要
真实 JSR-356 container；真实 servlet container 才提升默认文本/二进制 buffer。

### HTTP async boundary：Environment directory

`GET /api/ai/environments/{name}/directories?path=.`是 control-plane read-only 查询，不经过 Tool permission、不会
创建 ToolInvocation、不会占用 Environment active tool slot，也不把绝对路径返回给浏览器。

Controller 直接返回 `CompletionStage<ResponseEntity<Result<?>>>`：

```text
Controller
  -> EnvironmentDirectoryLister.listDirectory(..., timeout)
  -> CompletionStage.handle(mapResult/mapAsyncFailure)
  -> Spring MVC async dispatch
```

Servlet request thread 不 `join`或等待 future。typed failure 映射为
`INVALID_PATH/NOT_DIRECTORY -> 400`、`ENVIRONMENT_NOT_FOUND/NOT_FOUND -> 404`、
`ENVIRONMENT_UNAVAILABLE -> 409`、`TIMEOUT -> 504`、`IO_ERROR -> 502`。timeout 从
`SystemSettings.environment.directoryListTimeoutMillis`在请求时读取。

### Trusted JAR 启动加载

`TrustedJarContributorLoader`只接受 `kk-studio.harness.contributors.directory`：

1. 空值返回空 snapshot；
2. 非空目录必须 canonical、真实存在且为 directory；
3. 只收集不跟随 symlink 的 `.jar`普通文件，并按 filename 排序；
4. 创建 parent-first `URLClassLoader`，parent 是 `HarnessContributor`所在 classloader；
5. 用 `ServiceLoader<HarnessContributor>`加载，并要求实现类确实来自该 child classloader；
6. 构造完成后保存 immutable contributor list，不提供 refresh/install；
7. 加载失败立即关闭 child classloader；Spring destroy 时 `close()`幂等关闭。

`ContributorCatalogConfiguration`把 Spring 容器内建 `HarnessContributor`与 `TrustedJarContributorLoader`的列表合并后调用
`HarnessCatalog.from` 创建单一不可变 `HarnessCatalog`。Trusted loader 本身不引用
Spring、Flyway、HttpClient 或 WebClient，classloader 只存在于 composition root。

## 事务、并发、失败恢复不变量

1. `WebApplication`是唯一生产 Spring Boot root；Controller 只调用 Platform/Runtime/Core port，不复制领域事务或
   状态机。
2. `V1__schema.sql`是全仓唯一 baseline，profile seed 只由 Flyway locations 选择；没有第二份 SQL bootstrap。
3. REST snapshot 是 durable truth 的读取入口；Application Event WebSocket 只发送 version/realtime invalidation 和
   resync signal，不保存事件记录、不替代 REST command/query。
4. Event subscription 的上游注册先于 cursor 读取，ack 先于 activation，陈旧 version 按 cursor 过滤；buffer 溢出必然
   fold 成一个 resync，内存有界且可恢复。
5. WebSocket 每连接发送链都有 frame count + UTF-8 bytes 上限、单一 in-flight 顺序和有限 send timeout；失败先停止入队，
   再清理订阅/registry，最后关闭 transport。
6. PostgreSQL notification loop 的一个 handler 失败不影响其它 channel；连接丢失由 reconnect + resync 恢复，通知丢失
   不改变 PostgreSQL durable truth。
7. Harness worker dispatcher、processor、event loop、heartbeat scheduler 和 sender 都有明确 owner；worker 关闭不会
   释放 notification loop，事件 channel 关闭也不会留下 Runtime subscription。
8. HTTP async endpoint 只返回 completion stage，不在 servlet thread 执行阻塞 Daemon round trip；future timeout 和 transport
   error 都映射为稳定应用 error code。
9. Trusted JAR list 在 catalog 创建后不可变，jar scan 只从显式目录发生；任何加载异常在 context 可用前关闭已创建
   classloader。
10. strict JSON field、canonical UUID、canonical decimal、DTO discriminator 和 duplicate detection 在 Web boundary
    拒绝不确定输入；error advice 不改变 domain fact，只映射 status、stable code、context 和 locale message。
11. Storage/S3 未启用时 Controller 仍注册，但依赖 bean 缺失明确返回 503；ComfyUI disabled 返回 503，enabled workflow
    不存在返回 404，参数/selector/binding 错误返回 400。

## 配置与安全边界

### Web application 配置

| 配置 | 当前职责 |
| --- | --- |
| `spring.application.name` / `spring.profiles.active` | 应用名 `kk-studio`，默认 `dev` |
| `spring.datasource` 或 `spring.datasource.multi.primary` | PostgreSQL 唯一 durable database |
| `spring.flyway.locations` | dev/e2e/canvas-test 的 migration + seed 组合 |
| `server.port` / `server.compression.enabled` | 默认 `8080`与 gzip |
| `management.endpoints.web.exposure.include` | `health,prometheus,offline,online` |
| `kk-studio.harness.runtime.workers-enabled` | 是否启动 Work dispatcher；测试默认 false |
| `kk-studio.harness.contributors.directory` | trusted JAR 目录；空值不加载外部贡献者 |
| `kk-studio.harness.environment-gateway.*` | Daemon token、入站 frame 和出站 queue/bytes/send timeout |

`application-dev.yml`默认数据库为 `127.0.0.1:5432/kk_studio`并加载 dev seed；
`application-e2e.yml`使用 `kk_studio_e2e`和 e2e seed，配置测试 daemon token；
`application-canvas-test.yml`在 dev seed 上追加 canvas-test SystemSettings。

### 安全与第三方边界

- 代码没有 `spring-boot-starter-security`、`SecurityFilterChain`或 Spring Security auth filter；HTTP TLS、用户认证和
  ingress policy 属部署边界，不由当前 Web context伪造。
- Daemon WebSocket 的共享 `daemon-token`是 deployment secret，不进 SystemSettings、DTO 或日志；Platform gateway
  在 HELLO 认证时做常量时间比较，连接失败会清理 live state。
- S3 presign Controller 只允许 ComfyUI input namespace 或 server 生成的 Blob key，响应丢弃 bucket/object key；
  浏览器拿到的是有限 expiry 的 signed URL。
- `TrustedJarContributorLoader`把本地 JAR 作为 trusted code，只从显式 canonical directory 加载，不提供远程下载或热加载。
- `StrictJacksonConfiguration`和各 domain codec 拒绝 duplicate/unknown/trailing fields；Controller 不把上游异常、credential、
  bucket 或本地 Environment root 作为公开字段。

### 启动 / 关闭顺序

| 生命周期 | phase / owner | 关闭语义 |
| --- | --- | --- |
| Harness workers | `HarnessRuntimeLifecycle`，`MAX_VALUE - 1` | `workers-enabled=false`不启动；start 失败回滚 dispatcher；stop 幂等 |
| Browser heartbeat | `applicationEventHeartbeatScheduler` | Spring destroy `shutdown`，handler `@PreDestroy`先发 1012 `SEND_FAILED` |
| Application Event Hub | `ApplicationEventHub`，destroy `close` | 关闭 resource upstream、标记 subscriptions closed |
| PostgreSQL notifications | `PostgresqlNotificationLoop`，`MAX_VALUE` | abort connection、interrupt、bounded 5s join |
| Harness processors/RT source | `modelProcessor`、`toolProcessor`、`realtimeEventSource` | Spring destroy `close`，executors 使用 `shutdown` |
| Trusted contributor | `TrustedJarContributorLoader` | destroy `close` child classloader，列表不可再使用 |

## 测试与源码入口

### Composition、架构和 bootstrap tests

- `web/src/main/java/fun/fengwk/kkstudio/web/WebApplication.java`
- `web/src/main/java/fun/fengwk/kkstudio/web/runtime/HarnessRuntimeConfiguration.java`
- `web/src/main/java/fun/fengwk/kkstudio/web/runtime/HarnessRuntimeLifecycle.java`
- `web/src/main/java/fun/fengwk/kkstudio/web/runtime/contributor/ContributorCatalogConfiguration.java`
- `web/src/main/java/fun/fengwk/kkstudio/web/runtime/contributor/TrustedJarContributorLoader.java`
- `web/src/test/java/fun/fengwk/kkstudio/web/WebModuleArchitectureTest.java`
- `web/src/test/java/fun/fengwk/kkstudio/web/FlywayBootstrapArchitectureTest.java`
- `web/src/test/java/fun/fengwk/kkstudio/web/SpaFallbackTest.java`
- `web/src/test/java/fun/fengwk/kkstudio/web/WebPostgresTestSupport.java`

### Controller、DTO 和 error boundary tests

- `web/src/main/java/fun/fengwk/kkstudio/web/controller/`
- `web/src/main/java/fun/fengwk/kkstudio/web/runtime/HarnessRuntimeRequestMapper.java`
- `web/src/main/java/fun/fengwk/kkstudio/web/runtime/HarnessRuntimeResponseMapper.java`
- `web/src/main/java/fun/fengwk/kkstudio/web/mapper/WebDtoMapper.java`
- `web/src/main/java/fun/fengwk/kkstudio/web/advice/`
- `web/src/main/java/fun/fengwk/kkstudio/web/i18n/StudioMessageService.java`
- `web/src/test/java/fun/fengwk/kkstudio/web/controller/`
- `web/src/test/java/fun/fengwk/kkstudio/web/advice/`
- `web/src/test/java/fun/fengwk/kkstudio/web/StudioI18nIntegrationTest.java`
- `web/src/test/java/fun/fengwk/kkstudio/web/FlywayAutoConfigurationIntegrationTest.java`

### Runtime、contributor 和 notification integration tests

- `web/src/test/java/fun/fengwk/kkstudio/web/runtime/HarnessRuntimeConfigurationTest.java`
- `web/src/test/java/fun/fengwk/kkstudio/web/runtime/HarnessRuntimeLifecycleTest.java`
- `web/src/test/java/fun/fengwk/kkstudio/web/runtime/HarnessRuntimePostgresqlLifecycleIntegrationTest.java`
- `web/src/test/java/fun/fengwk/kkstudio/web/runtime/HarnessRuntimeRequestMapperTest.java`
- `web/src/test/java/fun/fengwk/kkstudio/web/runtime/HarnessRuntimeResponseMapperTest.java`
- `web/src/test/java/fun/fengwk/kkstudio/web/runtime/contributor/ContributorCatalogWiringTest.java`
- `web/src/test/java/fun/fengwk/kkstudio/web/runtime/contributor/TrustedJarContributorLoaderTest.java`
- `web/src/main/java/fun/fengwk/kkstudio/web/events/postgresql/PostgresqlNotificationLoop.java`
- `web/src/test/java/fun/fengwk/kkstudio/web/events/postgresql/PostgresqlNotificationLoopTest.java`
- `web/src/test/java/fun/fengwk/kkstudio/web/events/postgresql/PostgresqlNotificationLoopPostgresqlIntegrationTest.java`
- `web/src/test/java/fun/fengwk/kkstudio/web/events/ApplicationEventHubTest.java`
- `web/src/test/java/fun/fengwk/kkstudio/web/events/ApplicationEventWebSocketEndpointIntegrationTest.java`
- `web/src/test/java/fun/fengwk/kkstudio/web/events/EventFrameCodecTest.java`

### WebSocket、async 和静态资源 tests

- `web/src/main/java/fun/fengwk/kkstudio/web/events/ApplicationEventWebSocketHandler.java`
- `web/src/main/java/fun/fengwk/kkstudio/web/events/AsyncTextSender.java`
- `web/src/main/java/fun/fengwk/kkstudio/web/environment/EnvironmentDaemonWebSocketHandler.java`
- `web/src/main/java/fun/fengwk/kkstudio/web/environment/DaemonOutboundSender.java`
- `web/src/test/java/fun/fengwk/kkstudio/web/events/ApplicationEventWebSocketHandlerTest.java`
- `web/src/test/java/fun/fengwk/kkstudio/web/events/AsyncTextSenderTest.java`
- `web/src/test/java/fun/fengwk/kkstudio/web/environment/EnvironmentDaemonWebSocketEndpointTest.java`
- `web/src/test/java/fun/fengwk/kkstudio/web/environment/EnvironmentDaemonWebSocketHandlerTest.java`
- `web/src/test/java/fun/fengwk/kkstudio/web/environment/EnvironmentDaemonWebSocketLargeCapabilitiesIntegrationTest.java`
- `web/src/test/java/fun/fengwk/kkstudio/web/controller/StudioEnvironmentDirectoryControllerTest.java`
- `web/src/test/java/fun/fengwk/kkstudio/web/SpaFallbackTest.java`

这些测试覆盖 Web composition 的真实风险：唯一 root 与依赖方向、Flyway single baseline、PostgreSQL notification
reconnect/resync、worker NOTIFY/poll 两条唤醒路径、ack-before-event、bounded sender、Environment large READY frame、
HTTP async error mapping、strict DTO、trusted JAR classloader lifecycle、locale fallback 和静态 SPA fallback。

---

上级：[系统设计](../system-design.md)。相关文档：[Platform](platform.md)、
[Canvas Infra](canvas-infra.md)、[Harness Infra](harness-infra.md)、
[Harness Builtin](harness-builtin.md)、[Harness Contributor API](harness-contributor-api.md)、
[部署与运行](../operations/deployment.md)。
