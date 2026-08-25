# Platform 模块

## 定位

`platform` 是 kk-studio 模块化单体中的 application layer。它把 `canvas-core`、`harness-runtime`、`harness-tool` 和
`harness-plugin-api` 的窄 port 组合成可执行的产品用例，并把 PostgreSQL、S3、Model Provider、Environment
Daemon、ComfyUI 和 OpenCLI Hub 等外部事实适配到这些 port。

Platform 不是 HTTP composition root，也不承载浏览器协议、Spring Boot 启动入口或 Harness 的状态机实现。生产启动由
[web 模块](web.md)完成；`harness-runtime` 负责 Session/Entry/Thread/Invocation/Work 的纯 Java 状态机与 processor；
`canvas-core` 负责 Canvas domain 和 repository port。Platform 负责它们之间的业务事务、实时资源解析、第三方调用边界和
应用级失败分类。

生产装配入口是 `platform/src/main/java/fun/fengwk/kkstudio/platform/PlatformAutoConfiguration.java`。它只做
`BaseMapperScan` 与 `ComponentScan`，并通过
`platform/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
作为 Spring Boot auto-configuration 被 web 引入。

## Goals / Non-goals

### Goals

- 为 Catalog、Chat、SystemSettings、Storage、Environment、Canvas 和 ComfyUI 提供稳定的 application service。
- 在 Model/Tool 执行进入第三方 transport 前完成确定性 admission、冻结事实校验、权限判定和容量控制。
- 把 Provider SDK、S3 SDK、ComfyUI client、OpenCLI Hub HTTP 和 Environment Daemon WebSocket 隔离在窄 adapter 内。
- 维护 Chat/Canvas/Harness/Blob 之间的 owner、引用计数、幂等键、CAS version 和深删除不变量。
- 将 Canvas Function 的外部执行、checkpoint、resource materialization 与通用 Canvas Resource lifecycle 接通。
- 让第三方结果在 durable state 形成前完成安全分类、尺寸校验和 Resource externalization。

### Non-goals

- 不提供 `@SpringBootApplication`、`SpringApplication.run`、HTTP Controller、WebSocket transport 或前端 DTO。
- 不实现 Harness reducer、processor、claim/lease 状态机；Harness 持久化只能经 Runtime/Store port 访问。
- 不在 Platform 内扫描插件目录、创建插件 classloader 或提供运行时插件安装入口。
- 不把 Provider SDK 类型、S3 bucket/object key、Daemon 连接句柄或第三方错误对象泄露到公共 application port。
- 不把 S3 对象删除、预览生成和过期上传回收放进持有数据库行锁的长事务。

## 依赖边界

### Maven 与模块边界

`platform/pom.xml` 的生产依赖是：

| 方向 | 直接依赖 | 用途 |
| --- | --- | --- |
| Domain port | `kk-studio-canvas-core`、`kk-studio-harness-runtime`、`kk-studio-harness-tool` | Canvas/Harness/Tool 的纯 Java contract |
| Plugin port | `kk-studio-harness-plugin-api` | `PluginCatalog`、`ToolContribution`、`BranchView` |
| HTTP share | `kk-studio-share` | Platform service 使用的 DTO 与 JSON wire 类型 |
| Persistence | MyBatis、PostgreSQL、`convention4j-spring-boot-starter` | Catalog、Chat、Settings、Storage 和 ComfyUI workflow API |
| Model/third-party | LangChain4j Provider modules、`convention4j-comfyui`、AWS SDK S3、JsonPath | 外部 Provider、ComfyUI、S3 和 selector |

`kk-studio-schema`、`kk-studio-canvas-infra`、Harness runtime `test-jar`、Goal plugin、Flyway 和
Testcontainers 都是 test scope；Platform main 不直接依赖 `harness-infra`、`web` 或 `harness-daemon`。这些边界由
`platform/src/test/java/fun/fengwk/kkstudio/platform/harness/PlatformArchitectureTest.java`、
`platform/src/test/java/fun/fengwk/kkstudio/platform/PlatformPackageArchitectureTest.java`、
`platform/src/test/java/fun/fengwk/kkstudio/platform/ProviderTypeArchitectureTest.java` 和
`platform/src/test/java/fun/fengwk/kkstudio/platform/harness/HarnessExecutionAdmissionArchitectureTest.java`守护。

### 运行时拓扑

```text
web composition root
  -> platform application services
       -> PostgreSQL repositories / Canvas ports / Harness Store ports
       -> harness-runtime / harness-tool / plugin-api
       -> S3 / Model Provider / Environment Daemon / ComfyUI / OpenCLI Hub
```

Platform 只依赖 `canvas-core` 的 Canvas port；Canvas PostgreSQL implementation 位于
`canvas-infra`，只在 Platform 测试基座或 web composition 中装配。Platform 的 provider adapter 包
`fun.fengwk.kkstudio.platform.harness.model.provider`可以使用 LangChain4j 或其他 SDK，但其公共签名只能使用
`harness-runtime` 的 `model.provider` 类型；约束见
`platform/src/main/java/fun/fengwk/kkstudio/platform/harness/model/provider/package-info.java`。

## 核心子域 / API

### Catalog：Provider、Model、Agent Definition

Catalog 是名称寻址的全局资源集合，所有变更 service 都使用 `expectedVersion` CAS：

| 资源 | durable 身份 | Platform 运行时作用 |
| --- | --- | --- |
| Provider | `agent_provider.name` | 保存 `ProviderType`、base URL、credential 和内部 timeout config |
| Model | `(provider_name, name)` | 保存 context/output limit、abilities、variants、pricing 和 default variant |
| Agent | `agent_definition.name` | 保存 system prompt、Model 引用、variant 覆盖以及 tools/skills/subagents 配置 |

Provider、Model、Agent 的名称在记录存续期间不可修改；Model 对 Provider、Agent 对 Model 有数据库外键。删除由
`AgentProviderGuard`、`AgentModelReferenceResolver`、`AgentDefinitionReferenceResolver` 和对应 service
拒绝仍被引用的资源，并通过 PostgreSQL integrity classifier 把 FK 约束映射为领域错误。

配置 JSON 使用 strict codec：

- `AgentProviderConfigurationCodec`读取并合并 `modelCallTimeoutMillis` 与
  `modelCallIdleTimeoutMillis`，未声明字段使用 `ModelCallTimeoutPolicy.DEFAULT`，未知 Provider 扩展字段保留。
- `AgentModelRuntimeConfigParser`严格解析 `limit`、`abilities`、`variants`、`defaultVariant` 和 `pricing`；
  context/output、variant id、temperature、reasoning effort 等不满足约束时拒绝。
- `AgentDefinitionConfigCodec`严格解析去重的 tool/skill/subagent name，名称不允许首尾空白以及
  `:/@\`字符。

Provider type 的唯一 runtime enum 在
`harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderType.java`：

| enum | stable wire value | adapter |
| --- | --- | --- |
| `OPENAI` | `openai` | OpenAI Chat Completions 与 MiniMax endpoint adapter |
| `OPENAI_RESPONSES` | `openai_response` | OpenAI official Responses adapter |
| `ANTHROPIC` | `anthropic` | Anthropic adapter |
| `GOOGLE` | `google` | Google Gemini adapter |

`ProviderType.fromWireValue`不 trim、不折叠大小写；未支持的 wire value 直接失败。Catalog API 只暴露结构化
config、名称和版本，不暴露 credential。

### Chat 与 owner

`ChatServiceImpl`提供 Chat CRUD。Chat 保存 title、agentName、可空 Environment binding、workspace path、
`yoloEnabled`、version 和时间；`ChatMutationFactory`负责名称、标题和默认 YOLO 的规范化，`ChatGuard`负责存在性与
Agent 引用校验。更新和删除都需要 CAS version。

Chat 删除不是单行删除：`ChatServiceImpl.deleteChat`先排他锁定 Chat，再调用
`SessionDeletionOrchestrator.deleteSessionsByOwner(OwnerType.CHAT, chatId)`深删除全部 Session，最后删除 Chat。
`chat_session`只表达 owner relation，不绕过 Session 的 Harness/Blob 清理。

### SystemSettings

`system_setting`恰好一行（`id=1`），`config`是六个必填 section 的 canonical JSON：

```text
tool / aiRuntime / environment / integrations / storageMedia / advanced
```

`SystemSettings`的 canonical constructor 做字段范围、跨字段关系和启用前提校验；`SystemSettingsCodec`拒绝未知字段、
尾随 token、错误 JSON 类型和缺失 section。`SystemSettingsSchemaProvider`是 HTTP 编辑 schema 的唯一 metadata
来源，字段路径与 record component path 对齐。

`SystemSettingsServiceImpl.update`的顺序是：解析 expected version → 严格解码并校验完整聚合 → `system_setting` 行
CAS update → 事务提交后通知 `SystemSettingsChangeHandler`权威回读。`SystemSettingsSnapshot`以 version CAS 替换
内存快照，较旧回读不能覆盖较新值，事务回滚不改变快照；数据库 trigger 通过 `system_settings_changed` 通道广播。

### Storage、Blob 与 Resource

Storage 把内容身份、owner 引用和对象物理存储分开：

- `storage_blob`按 `(sha256, size_bytes)` 对 ACTIVE 内容去重，状态为 `ACTIVE` 或 `DELETING`，`ref_count`由
  `StorageBlobManager`维护。
- `session_blob_ref`是 Harness Session 对 Blob 的显式 owner edge；`canvas_resource`和 Tool history ingest
  通过各自 owner/service 维护引用。
- `storage_upload`记录 PENDING/READY 上传、candidate blob、过期时间和 cleanup lease。
- 对象 key 由 `StorageObjectKeys`集中生成：`uploads/{id}/original`、`blobs/{id}/original`、
  `blobs/{id}/preview.webp`；调用方不能选择 bucket。

`StorageBlobManager.retain/release`和 `SessionBlobRefManager`均要求 `PROPAGATION_MANDATORY`。引用减到零时，数据库
同一条条件 update 把 Blob 切为 `DELETING`；提交后只唤醒 maintenance，删除顺序是 preview → original → 条件删除
Blob 行。

上传协议由 `StorageUploadServiceImpl`执行：

1. `reserve`在短事务内按 hash/size 命中 ACTIVE 或创建 PENDING upload，未命中再生成 checksum PUT presign。
2. `complete`在数据库事务外 HEAD、校验 size/SHA-256、probe 媒体事实并复制 candidate object；随后短事务锁 upload
   行、做 ACTIVE dedup、设置 blobId。并发 complete 只有一个绑定成功。
3. `delete`在短事务记录 cleanup request；READY upload 同时 release upload owner。
4. `expireOnce`先用 `SKIP LOCKED` claim 有界批次，事务外幂等删除临时/candidate objects，再用 cleanup token
   做 fenced finalize；对象删除或 finalize 失败时保留 lease，下一次 maintenance 可重试。

`StorageMaintenance`是 `SmartLifecycle`，拥有单一 daemon scheduled executor，启动立即 wake、并合并并发 wake，
同时以 fixed-delay poll 驱动 upload expire 和 DELETING blob sweep。它的所有 S3 I/O都在事务外；数据库清理事实是唯一
可恢复依据。

Tool terminal 结果由 `ToolResultExternalizer`先做无副作用 plan，再按引用逐个写入 `ResourceStore`，返回引用必须与
plan 完全一致；`GlobalStorageToolResultHistoryMaterializer`在调用方 mandatory transaction 中把
Text/Json/Binary/Resource 物化为 Harness history，任一步失败使调用方事务回滚。

### Environment

Environment 是服务器内存中的 live registry，不是持久化资源。`LiveEnvironmentRegistry`以 canonical
`EnvironmentName`唯一寻址，状态只有 `CONNECTING`、`READY`；可用性要求连接打开、状态 READY 且
`lastSeenAt`未超过 `SystemSettings.environment.heartbeatTimeoutMillis`。

`EnvironmentDaemonGateway`同时实现：

- `EnvironmentDaemonEndpoint`：HELLO/WELCOME/READY/HEARTBEAT/close 协议；
- `EnvironmentSkillLoader`：按冻结 skill binding 读取正文；
- `RemoteToolTransport`：向 READY Daemon 发送 Environment tool；
- `EnvironmentDirectoryLister`：不占 Tool invocation slot 的 control-plane 目录查询。

同名连接的 bind 是原子三态：新连接 `Accepted`、新鲜持有者存在时 `Rejected`、持有者关闭或心跳过期时
`Replaced`。每个 Environment 只有一个 active remote invocation；并发 sibling 在 INVOKE 发送前返回
`RemoteToolBusyException`，不同 Environment 可以并行。发送不确定时连接和 active/pending 请求都按不确定结果收敛，
不重发可能已经产生副作用的请求。

### Provider adapters 与 PlatformModelGateway

`ModelExecutionConfiguration`为四个 `ProviderType`注册稳定命名的 `ProviderFactory`，Model I/O使用 Java 21
virtual-thread-per-task executor，Model admission 默认容量来自 `kk-studio.harness.execution-admission.model`
（默认 16）。

`DatabaseProviderResolutionService`在每次 Model attempt 按 frozen `providerName`读取当前 `agent_provider`：

1. 当前 Provider 必须存在，且 `providerType`必须等于 durable request 中冻结的 type。
2. 以当前 factory 读取 endpoint、credential、timeout policy 并创建 adapter。
3. 根据当前 Provider cache capability 规范化 durable cache control。
4. 通过 `ProviderResourceMaterializer`物化当前 attempt 的 Resource：图片在 30 MiB 内联为 data URI，audio/video
   使用 signed URL；Storage 不可用时 Resource 变为确定性文本回退。

四个 adapter 把 SDK 细节封装在 provider 包内。OpenAI adapter 支持 OpenAI-compatible endpoint 与 MiniMax
reasoning split；Responses adapter 使用 official client；Anthropic adapter 校验 breakpoint 并映射 thinking；
Google adapter 映射 Gemini thinking level。`LangChainModelProvider`统一 stream delta、thinking、tool call、
usage、stop reason 和 Provider error；错误消息截断并移除 Bearer/API key/token 等敏感值。

`PlatformModelGateway.start`的 admission 顺序和结果语义是：

| 阶段 | 结果 |
| --- | --- |
| Provider resolution 抛确定性 `IllegalArgumentException` | `Rejected(INVALID_REQUEST)`，不提交、不重试 |
| Model admission 无 permit | `Busy(busyRetryDelay)` |
| executor 明确 `RejectedExecutionException` | `Busy`，释放 lease |
| executor 抛其它提交异常 | `Indeterminate(TRANSIENT)`，因为无法证明 transport 是否启动 |
| 成功提交 | `Started(handle)`，但 callback gate 仍关闭 |

`Handle.activate`只在 Runtime attach handle 且 durable invocation 已标记 `RUNNING`后打开 gate。激活前 cancel 会
唤醒等待任务、释放 permit 且不触碰 Provider；Provider stream 延迟绑定后仍会收到一次 cancel。构造时拒绝 inline executor、
`CallerRunsPolicy`和静默丢弃 policy，避免 gate 死锁或出现无执行的 `Started`。

Provider callback 经 `BridgingHandler`进入单一 FIFO drainer，队列上限 256。第一个 terminal 胜出，terminal 后的
迟到/重复信号全部丢弃；队列溢出、未知 transport 异常或非 terminal listener 异常统一以一次 `UNKNOWN`收敛；
terminal listener 异常只记录日志，不发第二个 terminal。terminal、cancel 和提交失败共享幂等 permit release。

### PlatformToolGateway、ToolContributionCatalog 与 plugin source

`ToolContributionCatalog`把本地 `ToolFactory`和 `PluginCatalog`中的 Tool contribution 合并成一份不可变
Platform directory：

- 本地 factory 必须声明 `ToolType.PLATFORM`，identity 是 `core:name@version`。
- 插件 entry identity 是 `plugin:ContributionId`，保留 plugin provenance、priority 和 state access declaration。
- 排序先满足 plugin `requires` 的传递拓扑序，再按 priority 降序和 identity 字典序；重复 name 或依赖环直接失败。
- `find(name, version)`是 durable binding 恢复路径；本地重新 `factory.create()`后必须 descriptor equality。
- `toToolCatalog()`派生 selectable/internal catalog；Environment fixed catalog 不与 Platform factory 混淆。

`HarnessPluginSource`只是 `List<HarnessPlugin> plugins()`的启动快照 port。Platform 接收已冻结的
`PluginCatalog`，不读取目录、不创建 classloader、不提供 refresh。Web 组合根负责把 built-in plugin 与 trusted JAR
source 合并，详见 [web 模块](web.md)。

`PlatformToolGateway`的 `preflight`是纯权限判定：按 frozen binding、arguments、Environment workspace 或 server
workdir 生成 `ALLOW`、`ASK`或`DENY`，不改写 binding/arguments，也不感知 YOLO。`start`先获取 tool admission
（默认 `kk-studio.harness.execution-admission.tool=64`），再按 binding 路由：

| binding | admission / transport |
| --- | --- |
| `PLATFORM` local | 精确匹配 name/version/descriptor 后提交 virtual-thread executor |
| `PLATFORM` plugin | 精确匹配 `ContributionId`、descriptor、state access，执行声明式 intent |
| `ENVIRONMENT` | 精确匹配 fixed daemon descriptor，检查 READY/active slot 后经 `RemoteToolTransport`发送 |

local executor 明确拒绝返回 `Overloaded`，提交不确定返回 `Indeterminate(EXECUTION_FAILED)`。Environment 在发送前
不可用返回 `Rejected(UNAVAILABLE)`，同 Environment active 返回 `Busy`，发送不确定返回
`Indeterminate(REMOTE_UNCERTAIN)`；这些分类决定 Harness 是否重试或终止。

`GatedToolExecutionListener`与 Model gateway 同样是两阶段 activation、FIFO single drainer、256 signal bounded
buffer 和 terminal-once。partial 必须非空、toolCallId 精确匹配、不能携带 Binary/Resource，且 canonical JSON 不得
超过 256 KiB；terminal result 在 externalize 前校验，成功结果采用 all-or-nothing Resource externalization。第一个
terminal 后任何迟到信号、其余 Resource 写入和第二个 terminal 都被禁止。

Plugin Tool 的 `AppendCustomEntry` intent 必须属于自身 plugin、命中已注册 custom type 且存在声明的 WRITE access；
否则是 `PLUGIN_CONTRACT_VIOLATION`。冻结 binding 的 state access 顺序或 mode 与当前 contribution 不同则
`PLUGIN_BINDING_MISMATCH`。

### DatabaseTurnResolver、skill 与 task materialization

`DatabaseTurnResolver`只以 candidate `EntryPath`的最新 `BranchSettings`为输入，并在每个 live turn 解析：

1. 当前 Agent、Model、Provider、Variant 和 ProviderFactory；
2. 当前 Environment context；
3. Agent config 中的 selectable tools；
4. skills、subagents 和内部 `load_skill`/`task`；
5. plugin context projector、system prompt、cache control、context window 和 output budget。

Agent 配置有 skills 时追加 `LoadSkillTool`；subagents 非空且 Session depth 小于
`SubagentConfig.maxDepth`时追加 `TaskTool`。每个 tool 都精确恢复 descriptor；Environment tool 使用 branch 当前
完整 Environment binding。Skill 必须由当前选中的 live Environment 提供，且 Environment 必须 READY；缺失、未 READY、
能力或 Model 不支持时返回统一 `AssistantError.code=PLANNING_FAILED`。Repository/registry 基础设施异常向上抛出，由
ThreadProcessor 按 runtime policy reschedule。

system prompt 由 `AgentPromptComposer`拼接正文、当前 Environment、skill 和 subagent sections，并只替换
`${date}`、`${workspace}`、`${cwd}`等已知 placeholder。Plugin context projector 以 `BranchView`追加 preamble。
`DatabaseThreadSelectedSkillLookup`从冻结 ModelRequestSpec 读取 skill binding，`EnvironmentSkillBodyLoader`再经
Environment gateway 读取正文；不会用当前 Agent 配置扩张已冻结调用。

Compaction resolver 是窄路径：只解析 `CompactionPreparation.executionModel`，不查 Agent prompt、plugin、skill、
Environment availability 或 prompt cache，只返回零 tools/skills/subagents 的 ModelRequestSpec。

`AgentBranchSettingsMaterializer`为新建/恢复的 subagent 按最新 Agent/Model catalog 物化完整 BranchSettings，校验
variant、注入 `load_skill`和 depth 允许的 `task`。Task 的 agent name、description、parent/root/depth 和
task invocation 归属在 durable binding 中冻结，执行期间不依据运行时 Catalog 变更扩权。

### Canvas adapters 与 Resource lifecycle

`PlatformCanvasCommandService`实现 Canvas application command：

- `createCanvas/applyCommands/deleteCanvas`均在事务内；
- `applyCommands`先锁 `canvas_document`行，再以 `(canvasId, commandId, requestHash)`做精确 replay/conflict，
  以 `expectedVersion`推进 graph version；
- `CREATE_RESOURCE_NODE`在同一事务锁定 READY upload、retain Canvas Blob 引用、标记 upload cleanup 并创建
  `canvas_resource`；上传对象由提交后的 Storage Maintenance 清理；
- Function run、node、group、link、resource 的状态变化通过 Canvas core port 写入，并以 patch 返回；
- 删除节点或 Canvas 时由 `PlatformCanvasResourceLifecycle`同步处理 pins、owned resource 和 Blob ref。

`PlatformCanvasResourceLifecycle`的核心不变量是：Resource row 对其 Blob 贡献一个引用，Function pin 只保护无 owner
Resource，不增加 `ref_count`。run/node/canvas pin 释放后，只回收不再被 pin 且无 owner 的 Resource；Function success 用
target 替换 owner resource，失败/cancel/迟到结果只丢弃 unowned target。

`CanvasBlobResourceMaterializer`把 Function 输出 spool 到临时目录（上限 512 MiB），在事务外写
`blobs/{blobId}/original`并 probe 媒体事实，然后在事务内锁 Canvas、确认恰好一个 RUNNING output pin、做 Blob dedup 和
`resourceId`幂等 insert。并发落败方释放自身刚创建的 Blob 引用；预览生成在提交后 best-effort 执行。
`CanvasBlobPreviewService`通过不经 shell 的 `MediaProcessRunner`调用 ffmpeg 生成 webp，输入上限 512 MiB，超时和
缩略图参数来自 SystemSettings。

Function adapter 只实现 `CanvasFunctionAdapter`：

| adapter | 运行边界 |
| --- | --- |
| `FakeCanvasFunctionAdapter` | 读取 classpath 的 tiny image/video fixture，仍通过真实 materializer，受 `fake-enabled` property 控制 |
| `GptImage2CanvasFunctionAdapter` | OpenCLI Hub + `chatgpt-agent`，image reference 每项最多 20 MiB，checkpoint 覆盖 upload/submit/poll/materialize |
| `SeedanceCanvasFunctionAdapter` | OpenCLI Hub，冻结 reference policy、上传和有界 polling，checkpoint 恢复同一 execution |
| `MiniMaxH3CanvasFunctionAdapter` | Platform one-shot Harness prompt + Environment + ComfyUI，状态阶段覆盖 prompt、Comfy upload/submit/poll 和 Blob ingest |

`PlatformCanvasFunctionBlobAccess`是 Function runtime 读取 Blob facts、打开 original stream 和获取 presign 的唯一
Platform storage adapter。Canvas adapter 不直接拼 S3 key，也不直接管理 Canvas 引用计数。

### ComfyUI

`ComfyuiWorkflowApiService`管理 `comfyui_workflow_api`卡片；`ComfyuiWorkflowApiBindingsParser`严格验证 workflow JSON、
parameter/file binding、node/input 存在性、value type 和 S3 object key。只有 enabled workflow 才能进入运行服务。

`ComfyuiRuntimeService`是无状态 runtime：

1. 按 `apiName`读取 enabled binding 并复制 Workflow；
2. 校验 parameters/files，按 JsonPath selector 和 value type 写入参数；
3. 对 file binding 从 S3 受 `integrations.comfyui.maxInputFileBytes`限制地读取，再上传到 ComfyUI；
4. submit 后返回 `runId == prompt/job id`；
5. get/cancel/download 直接查询 ComfyUI job；输出文件按 node/media/index 精确解析，运行状态不落本地表。

ComfyUI client 是否装配由启动时的 `SystemSettings.integrations.comfyui.enabled`决定。base URL、timeout、
max input 等非敏感参数来自 SystemSettings；`kk-studio.comfyui.api-key`只在 deployment property 中，不能进入
SystemSettings 或 HTTP DTO。

## 关键流程 / 时序

### Model attempt

```text
ThreadProcessor
  -> DatabaseTurnResolver
       -> frozen ModelRequestSpec
  -> DatabaseProviderResolutionService
       -> current agent_provider row + ProviderType adapter
       -> attempt-local Resource materialization
  -> PlatformModelGateway.start
       -> admission lease
       -> Started(handle), gate closed
  -> Runtime markRunning + handle.activate
  -> virtual-thread Provider stream
  -> FIFO bridge: delta / thinking / tool call / terminal
  -> Runtime terminal CAS + Work wake
```

### Tool attempt

```text
ToolProcessor
  -> PlatformToolGateway.preflight
       -> PermissionEvaluator + frozen workdir
  -> admission + exact catalog/binding route
  -> Started(handle), gate closed
  -> Runtime markRunning + handle.activate
  -> local Tool / plugin Tool / Environment Daemon
  -> bounded FIFO bridge
  -> partial realtime or terminal externalization
  -> Runtime terminal CAS + owning Thread Work
```

### Chat/Canvas first message with attachment

```text
HTTP command-batch
  -> HarnessCommandAcceptanceOrchestrator
       -> owner KEY SHARE authorization
       -> NEW_SESSION owner relation atomic insert
       -> lock READY upload
       -> retain SessionBlobRef
       -> mark upload cleanup / release upload owner
       -> HarnessRuntime.acceptCommands
  -> 202 durable acceptance
  -> Work dispatcher / ThreadProcessor
```

### Canvas Function output

```text
Function dispatcher claim + RUNNING lease
  -> adapter checkpoint / third-party execution
  -> CanvasBlobResourceMaterializer
       -> spool + S3 put + media probe outside DB transaction
       -> Canvas row lock + Blob dedup + resource insert in transaction
       -> commit
  -> preview generation best-effort
  -> Canvas version/patch notification
```

## 事务、并发、失败恢复不变量

1. PostgreSQL 是 Catalog、Chat、SystemSettings、Canvas graph、Harness durable facts、Blob owner edge 和 cleanup
   claim 的唯一事实源；NOTIFY、内存 registry、gateway handle 和 executor task 都是可重建的 transport state。
2. Chat/Canvas owner 对 Session 互斥。正常 acceptance 使用 owner `KEY SHARE`，删除使用 owner 排他锁；深删除统一按
   `Owner -> Session -> Thread`锁序，并对跨 Session 的 Thread 按 UUID 排序，避免锁序回退。
3. `HarnessCommandAcceptanceOrchestrator.accept`把 owner authorization、Session relation、attachment materialization、
   Session blob ref 和 Runtime command acceptance 放在同一物理事务；任一失败整体回滚。Runtime replay 不重复消费 upload，
   但仍执行 owner authorization。
4. `PlatformCanvasCommandService.applyCommands`的 command dedup、document version、graph mutation 和 Blob ref 转移同一事务
   成功或失败；相同 hash 精确 replay，不同 hash 是 idempotency conflict。
5. `ChatServiceImpl.deleteChat`和 `PlatformCanvasCommandService.deleteCanvas`先深删除 relation、Entry、Thread、Invocation、
   Work 和 Session Blob refs，再删 owner 行；不依赖数据库 cascade 绕过 Blob 引用计数。
6. `StorageBlobManager.release`在引用归零时只写 `DELETING`事实；S3 删除和 Blob row 删除由 maintenance 在事务外按
   preview → original → row 顺序执行，失败由下一次 wake/poll 恢复。
7. `StorageUploadServiceImpl`保证数据库不先引用缺失的 final object：complete 先在事务外复制 candidate，再在短事务绑定；
   expire 使用 cleanup token fencing，重复 complete/delete/cleanup 不重复 retain/release。
8. Provider/Tool gateway 的 admission lease 覆盖从 `start` 到 terminal/cancel/ambiguous submission，lease close 幂等；
   callback bridge 的第一个 terminal 胜出，overflow、迟到回调和 listener terminal exception 都不能产生第二个 terminal。
9. Model/Tool 两阶段 activation 防止 `start()`返回后在 durable `RUNNING`标记前触碰第三方；cancel-before-activate 不启动
   Provider/Tool，等待线程可被唤醒且不泄漏。
10. Tool terminal 成功先完成 descriptor、toolCallId、canonical size 和 externalization plan 校验，再写 ResourceStore；
    partial 不允许 Binary/Resource，外部化失败不会伪造 durable success。
11. Environment active remote invocation 每个 Environment 至多一个；发送 outcome 不确定时保守收敛 `UNKNOWN`，不自动重发
    非幂等副作用。
12. Canvas pin 不增加 Blob ref_count；Resource row、Session ref、upload owner 各自只维护一条明确引用边，任何 owner 删除
    都必须经过对应 manager。

## 配置

### SystemSettings（数据库单行，非敏感）

| section | 主要字段 / 默认值 | 应用时点 |
| --- | --- | --- |
| `tool` | permission 默认 write/edit/bash `* -> ask`，`defaultYolo=false`，Model Busy 5s、Tool Busy 1s、Tool Overload 5s、skill load 30s | admission/permission 读取点 live |
| `aiRuntime` | retry 3 次、EXPONENTIAL、base 2s、max 60s；compaction keep 20000；subagent depth 2、per-parent concurrency 10、maxTurns 50 | retry、resolver、subagent 配置读取点 |
| `environment` | resource 8 MiB、heartbeat 60s、directory list 10s | Environment gateway 查询/超时读取点 |
| `integrations.comfyui` | disabled；connect 10s、read 30s、WebSocket 1800s、input 50 MiB | client topology 由启动快照决定 |
| `integrations.openCliHub` | disabled、base URL 未配置；request 120s、long poll 130s、JSON 512 KiB、error 4 KiB | adapter 创建与执行参数 |
| `integrations.seedance/gptImage2/minimaxH3` | 各自 enabled/paid 开关、workspace、prompt/ComfyUI timeout 和 polling 约束 | adapter 的启动快照与执行读取点 |
| `storageMedia` | S3 disabled；upload 3600s；presign 600s/3600s；media process 30s；thumbnail 512/quality 80 | S3 bean topology 和媒体处理 |
| `advanced` | resource 16 MiB；processor lease/heartbeat 30s/10s；dispatcher worker 16、queue 64；event queue 512、2 MiB、10s、heartbeat 20s；notification poll/reconnect 20s/5s | 组合根装配的 restart-required 软策略 |

SystemSettings 永不承载数据库连接、filesystem root/workdir/temp、ffmpeg binary、Daemon token/identity、Provider
credential、ComfyUI API key、H3 bearer token 或 OpenCLI instance identity。启用 S3 或 ComfyUI 时，启动快照要求对应
endpoint 等 bootstrap property 完整，否则明确启动失败；禁用时对应 bean 不装配。

### 部署级 `@ConfigurationProperties`

| key | 边界 |
| --- | --- |
| `kk-studio.harness.execution-admission.{model,tool,subagent}` | 进程级容量，默认 `16/64/10`；不进数据库、DTO 或 frontend |
| `kk-studio.harness.runtime.{workers-enabled,environment-root,workdir}` | worker 开关与本地工作目录；`workdir`必须位于 root 内 |
| `kk-studio.harness.environment-gateway.{daemon-token,max-message-bytes,queue-capacity,max-bytes,send-timeout}` | Daemon 握手秘密与 WebSocket 安全边界；默认 `16MiB/256/16MiB/10s` |
| `kk-studio.storage.s3.{endpoint,public-endpoint,region,bucket,access-key,secret-key,public-base-url}` | S3/MinIO 服务端和 presign endpoint；bucket 只能由服务端配置 |
| `kk-studio.storage.maintenance.{poll-delay,cleanup-lease}` | maintenance 唤醒轮询与 cleanup lease，默认 `30s/5m` |
| `kk-studio.comfyui.api-key` | ComfyUI secret；非 SystemSettings |
| `kk-studio.opencli-hub.instance-id` | OpenCLI Hub 部署身份 |
| `kk-studio.canvas.resource.{ffprobe-binary,ffmpeg-binary,temp-dir}` | 媒体处理本地路径 |
| `kk-studio.canvas.function.minimax-h3.comfy-bearer-token` | H3 ComfyUI bearer secret；启用/路由/timeout 仍由 SystemSettings |

S3、Provider、ComfyUI、OpenCLI Hub 和 Environment Daemon 都是明确的 third-party/deployment boundary。Platform 对外只
传 domain DTO、稳定错误 kind、signed URL 和 frozen binding；不会把 bucket、对象 key、credential 或完整上游异常作为
产品协议的一部分。

## 测试与源码入口

### 组合与 schema

- `platform/src/main/java/fun/fengwk/kkstudio/platform/PlatformAutoConfiguration.java`
- `platform/pom.xml`
- `schema/pom.xml`
- `schema/src/main/resources/db/migration/V1__schema.sql`
- `schema/src/main/resources/db/seed/dev/V2__dev_seed.sql`
- `schema/src/main/resources/db/seed/e2e/V2__e2e_seed.sql`
- `schema/src/main/resources/db/seed/canvas-test/V3__canvas_test_system_settings.sql`
- Schema 重点表：`agent_provider`、`agent_model`、`agent_definition`、`comfyui_workflow_api`、`chat`、
  `chat_session`、`system_setting`、Canvas graph/function/resource 相关表、Harness 七张执行表、
  `canvas_session`、`storage_blob`、`storage_upload`、`session_blob_ref`。

### Architecture tests 与测试基座

- `platform/src/test/java/fun/fengwk/kkstudio/platform/PlatformPackageArchitectureTest.java`
- `platform/src/test/java/fun/fengwk/kkstudio/platform/harness/PlatformArchitectureTest.java`
- `platform/src/test/java/fun/fengwk/kkstudio/platform/ProviderTypeArchitectureTest.java`
- `platform/src/test/java/fun/fengwk/kkstudio/platform/harness/HarnessExecutionAdmissionArchitectureTest.java`
- `platform/src/test/java/fun/fengwk/kkstudio/platform/PlatformTestApplication.java`
- `platform/src/test/java/fun/fengwk/kkstudio/platform/persistence/test/PostgresSpringTestSupport.java`
- `platform/src/test/java/fun/fengwk/kkstudio/platform/harness/persistence/postgresql/PostgresSchemaSupport.java`
- `platform/src/test/java/fun/fengwk/kkstudio/platform/storage/S3PostgresSpringTestSupport.java`

### 领域与 integration tests

- Catalog：`CatalogParentLockIntegrationTest`及 definition/model/provider codec、mutation、service tests。
- Chat/事务：`ChatServiceIntegrationTest`、`HarnessCommandAcceptanceOrchestratorTest`、
  `SessionDeletionOrchestratorTest`、`ChatSessionRepositoryIntegrationTest`、`CanvasSessionRepositoryIntegrationTest`。
- Model/Provider：`PlatformModelGatewayTest`、`DatabaseProviderResolutionServiceIntegrationTest`、
  `ProviderAdapterContractTest`、Provider error/stop-reason/terminal normalization tests。
- Tool/plugin：`PlatformToolGatewayAdmissionTest`、`PlatformToolGatewayCallbackTest`、
  `PlatformToolGatewayPluginTest`、`ToolContributionCatalogTest`、`GlobalStorageToolResultHistoryMaterializerTest`。
- Resolver/materialization：`DatabaseTurnResolverTest`、`AgentBranchSettingsMaterializerTest`、
  `AgentPromptComposerTest`、`DatabaseThreadSelectedSkillLookupTest`、`EnvironmentSkillBodyLoaderTest`。
- Canvas/ComfyUI：`PlatformCanvasCommandServiceTest`、`PlatformCanvasResourceLifecycleTest`、
  `PlatformCanvasFunctionBlobAccessTest`、`OpenCliCanvasFunctionAdaptersTest`、
  `MiniMaxH3CanvasFunctionAdapterTest`、`ComfyuiRuntimeServiceTest`、`ComfyuiWorkflowApiBindingsParserTest`。
- Environment：`LiveEnvironmentRegistryTest`、`EnvironmentDaemonGatewayFinalTest`、
  `EnvironmentDaemonGatewaySettingsLiveTest`、`LiveEnvironmentQueryServiceImplTest`。
- Storage：`StorageBlobIngestServiceIntegrationTest`、`SessionBlobRefManagerIntegrationTest`、
  `StorageUploadServiceIntegrationTest`、`StorageUploadCleanupLeaseIntegrationTest`、
  `PostgresqlStorageBlobManagerTest`、`StorageMaintenanceTest`、S3 service/presign tests。
- Schema：`PostgresqlSchemaStructureTest`、`PostgresqlBusinessSchemaTest`、`PostgresqlSchemaSeedTest`、
  `PostgresqlStorageSchemaTest`。

这些测试覆盖的是当前 application layer 的可观察 contract：CAS、owner lock order、Blob ref 对账、S3 cleanup lease、
Provider/Tool admission、terminal-once、Environment bind、Canvas resource pin、strict codec 和 schema 约束。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Runtime](harness-runtime.md)、
[Canvas Core](canvas-core.md)、[Share](share.md)、[Web](web.md)。
