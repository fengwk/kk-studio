# Platform 模块

## 应用层边界与装配入口

`platform` 是 kk-studio 的 application layer：它把 `canvas-core`、`harness-runtime`、
`harness-tool` 和 `harness-contributor-api` 的窄 port 组合成可执行的产品用例，并把
PostgreSQL、S3、Model Provider、Environment Daemon、MCP、ComfyUI 与 OpenCLI Hub 等
外部事实适配到这些 port，向上为 [web](web.md) 提供 Catalog、Chat、Project、Issue、
Canvas、Storage、Environment、Settings 与 Harness command 的 application service。

Platform 承载这些窄 port 之间的业务事务、实时资源解析、第三方调用边界和应用级失败
分类：Session/Entry/Thread/Invocation/Work 的纯 Java 状态机由
[harness-runtime](harness-runtime.md) 拥有，Canvas domain 与 repository port 由
[canvas-core](canvas-core.md) 拥有，生产 `@SpringBootApplication`、HTTP Controller 与
WebSocket transport 由 [web](web.md) 拥有。

生产装配入口是 [PlatformAutoConfiguration](../../platform/src/main/java/fun/fengwk/kkstudio/platform/PlatformAutoConfiguration.java)：
它只做 `BaseMapperScan` 与 `ComponentScan`，并通过
[AutoConfiguration.imports](../../platform/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports)
作为 Spring Boot auto-configuration 被 web 引入、装载全部 application service 与适配器。
Harness reducer、processor 与 claim/lease 状态机留在 harness-runtime，插件目录扫描与
classloader 由 web 的
[TrustedJarContributorLoader](../../web/src/main/java/fun/fengwk/kkstudio/web/runtime/contributor/TrustedJarContributorLoader.java)
承担。

## 子域地图

| 子域 | 主要入口 | 职责 |
| --- | --- | --- |
| [catalog](../../platform/src/main/java/fun/fengwk/kkstudio/platform/catalog) | `AgentProviderService`、`AgentModelService`、`AgentDefinitionService`、`SkillCatalogService`、`McpServerService` | 名称寻址的全局 Catalog（Provider/Model/Agent/Skill）与 MCP server/tool |
| [harness/model](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/model) | `PlatformModelGateway`、`ModelExecutionConfiguration`、`DatabaseProviderResolutionService`、`ProviderResourceMaterializer`、`ProviderInlineBlobReader` | Provider 解析、admission、资源物化与 Model I/O |
| [harness/tool](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/tool) | `RuntimeToolCatalog`、`CompositeRuntimeToolCatalog`、`ToolCatalogQueryService`、`ToolExecutionGateway`、`ToolResultFinalizer` | 静态 + 动态工具目录聚合与 Tool 执行 |
| [harness/thread/command](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/thread/command) | `DatabaseTurnResolver` | 每个 live turn 的 Agent/Model/Environment/skill/subagent 解析 |
| [harness/task](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/task) | `AgentPromptComposer`、`AgentBranchSettingsMaterializer` | system prompt 拼接与分支设置物化 |
| [harness/skill](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/skill) | `DatabaseThreadSelectedSkillLookup`、`DatabaseSkillContentLoader` | 冻结 Skill binding 解析与不可变正文加载 |
| [orchestration](../../platform/src/main/java/fun/fengwk/kkstudio/platform/orchestration) | `HarnessCommandAcceptanceOrchestrator`、`SessionDeletionOrchestrator`、`PlatformCanvasCommandService`、`OwnerType` | 跨 owner 的 Harness 命令接受、深删除与 Canvas command |
| [chat](../../platform/src/main/java/fun/fengwk/kkstudio/platform/chat) | `ChatServiceImpl` | Chat CRUD 与深删除 |
| [project](../../platform/src/main/java/fun/fengwk/kkstudio/platform/project) | `ProjectServiceImpl`、`IssueServiceImpl`、`IssueRunServiceImpl`、`IssueControllerDispatcher`、`IssueReconciler`、`ProjectHarnessContributor` | Project/Issue 生命周期与确定性 Coordinator |
| [settings](../../platform/src/main/java/fun/fengwk/kkstudio/platform/settings) | `SystemSettingsServiceImpl`、`SystemSettingsSnapshot`、`SystemSettingsSchemaProvider` | 数据库单行全局设置与其内存快照 |
| [storage](../../platform/src/main/java/fun/fengwk/kkstudio/platform/storage) | `StorageUploadServiceImpl`、`StorageBlobManager`、`S3StorageServiceImpl`、`StorageMaintenance`、`StorageObjectKeys` | Blob/upload 生命周期与对象存储 |
| [environment](../../platform/src/main/java/fun/fengwk/kkstudio/platform/environment) | `EnvironmentDaemonGateway`、`EnvironmentRegistry`、`EnvironmentServerConfiguration` | Environment Card、Daemon 会话装配与宿主元数据保留 |
| [canvas](../../platform/src/main/java/fun/fengwk/kkstudio/platform/canvas) | `PlatformCanvasResourceLifecycle`、`CanvasBlobResourceMaterializer`、Canvas Function adapters | Canvas Resource 生命周期与 Function adapter |
| [comfyui](../../platform/src/main/java/fun/fengwk/kkstudio/platform/comfyui) | `ComfyuiWorkflowApiServiceImpl`、`ComfyuiRuntimeService` | Workflow 卡片与无状态运行 |
| [error](../../platform/src/main/java/fun/fengwk/kkstudio/platform/error)、[persistence](../../platform/src/main/java/fun/fengwk/kkstudio/platform/persistence) | `DomainErrorCode`、`PostgresqlIntegrityViolationClassifier` | 领域错误分类与 FK/唯一约束到领域错误的映射 |

平台把 PostgreSQL 作为 Catalog、Chat、SystemSettings、Canvas graph、Harness durable
fact、Blob owner edge 和 cleanup claim 的唯一事实源；NOTIFY、内存 registry、gateway
handle 和 executor task 都只是可重建的 transport state。

## Catalog

Catalog 是名称寻址的全局资源集合，所有变更 service 都使用 `expectedVersion` CAS：

| 资源 | durable 身份 | 运行时作用 |
| --- | --- | --- |
| Provider | `agent_provider.name` | `ProviderType`、base URL、credential、内部 timeout config |
| Model | `(provider_name, name)` | context/output limit、abilities、variants、pricing、default variant |
| Agent | `agent_definition.name` | system prompt、Model 引用、variant 覆盖、tools/skills/subagents 与子会话 Environment 继承策略 |

三类资源的 `description` 都是无长度上限的自由文本（PostgreSQL `text`），与
`system_prompt` 同属展示事实，不参与名称、可见性、CAS 或路由判定，空值按 `trimToNull`
归一化为 null。

Model 的 `name` 支持重命名，其 Provider 保持不可变；Provider 与 Agent 的名称在记录
存续期间不可修改。重命名由 `AgentModelRepository.rename` 在一个事务内完成：以
`expectedVersion` CAS 从旧行插入新行（沿用 `created_at`）、同步所有引用 Agent 的
Model 引用、再删除旧行；任一步不满足预期即抛错并整体回滚。删除由 `AgentProviderGuard`、
`AgentModelReferenceResolver`、`AgentDefinitionReferenceResolver` 拒绝仍被引用的资源，
并通过 `PostgresqlIntegrityViolationClassifier` 把外键约束映射为领域错误。

Agent 的严格 `config` 保存 `tools`、`skills`、`subagents` 与
`inheritParentEnvironment`；继承开关缺省为 `true`，显式 `null` 非法。委派图允许
自引用和环，不做静态拓扑限制；删除 Agent 时自身引用随目标行一起消失，只有其它 Agent
仍存在的入边会阻止删除。

配置 JSON 全部走 strict codec：

- [AgentProviderConfigurationCodec](../../platform/src/main/java/fun/fengwk/kkstudio/platform/catalog/provider/configuration/AgentProviderConfigurationCodec.java)
  合并 `modelCallTimeoutMillis` 与 `modelCallIdleTimeoutMillis`，未声明字段使用
  `ModelCallTimeoutPolicy.DEFAULT`，未知 Provider 扩展字段保留。
- [AgentModelRuntimeConfigParser](../../platform/src/main/java/fun/fengwk/kkstudio/platform/catalog/model/runtime/AgentModelRuntimeConfigParser.java)
  严格解析 `limit`、`abilities`、`variants`、`defaultVariant`、`pricing`；context/output、
  variant id、temperature、reasoning effort 不满足约束即拒绝。
- [AgentDefinitionConfigCodec](../../platform/src/main/java/fun/fengwk/kkstudio/platform/catalog/definition/configuration/AgentDefinitionConfigCodec.java)
  严格解析去重的 `tools`、skill 与 subagent 配置；`tools` 必须是合法模型可见
  tool name（旧 wire 字段 `toolIds` 被严格拒绝）且只能引用运行时目录中的 selectable
  entry；`skills` 是有序、唯一的 Platform 全局 canonical Skill name，并在保存事务中按
  name 升序锁定当前 `skill` 目录行，缺失名称确定性拒绝。

Provider type 的唯一 runtime enum 是
[ProviderType](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderType.java)，
wire value 分别是 `openai`、`openai_response`、`anthropic`、`google`；`fromWireValue`
不 trim、不折叠大小写，未支持的 wire value 直接失败。Catalog API 只暴露结构化 config、
名称和版本，不暴露 credential。

## MCP server 与运行时工具目录

MCP 配置与发现结果保存于 `mcp_server`、`mcp_tool`，唯一身份是 server 的不可变 `name`：
它同时是主键与 HTTP 路径身份，也是模型可见工具名 `mcp_<name>_<tool>` 的组成段。创建与
更新只接受显式 HTTP 字段（URL、headers、enabled、timeout），并严格拒绝任何未知字段，把
状态置为 `UNVERIFIED`；标准列表、详情与更新响应只返回安全投影（name、enabled、
timeoutMillis、discoveryStatus、toolCount、version、时间），URL 与 headers 只有显式
`GET /{name}/config`（强制 `Cache-Control: no-store`）才返回，且 headers 里的
`${VAR}` 占位符保持原样、不回显解析后的凭据。

显式发现在请求线程同步完成：事务外通过 `harness-mcp` Streamable HTTP client 完成握手、
`tools/list` 与 schema/name 校验，再在短事务中 `SELECT ... FOR UPDATE` 锁 server、校验
CAS version 并整体物理替换目录——先清空该 server 的全部工具行，再写入本次发现结果，没有
tombstone、修订号或 `available` 标记。任一步失败都完整保留旧工具行：

- 版本 CAS 不一致抛出 version conflict；
- 发现请求失败或工具名/schema/description 非法时拒绝整批结果，不写任何行；
- 被某个 Agent 引用的工具名将从目录中消失时 fail closed 并抛出 in-use 错误。

因此目录内容与 `AVAILABLE` 状态总是一次发现结果的原子快照。Server 删除同样先做引用校验，
再用 CAS 删除，工具行随 `fk_mcp_tool_server` 级联清理。

动态目录的贡献身份由模型可见工具名派生：`ContributionId` 的 contributor 是
`platform.mcp`，localName 由工具名把 `_` 换成 `-` 得到（模型可见名只含 `[a-z0-9_]`，
该转换一一对应，不引入隐藏 UUID 或修订号）。Agent 侧的唯一身份就是
`mcp_tool.name`；[`McpToolNameNormalizer`](../../platform/src/main/java/fun/fengwk/kkstudio/platform/catalog/mcp/McpToolNameNormalizer.java)
固定命名为 `mcp_<server>_<normalized_source>`，normalize 规则是 lowercase、非
`[a-z0-9]` 字符替换为 `_`、合并连续 `_`、去首尾 `_`，结果必须满足 `ToolDescriptor`
name 语法且 ≤64 字符，超长或同批发现内冲突都直接拒绝，绝不追加 hash 后缀。

[RuntimeToolCatalogConfiguration](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/tool/RuntimeToolCatalogConfiguration.java)
装配三个 bean：包装静态 `HarnessCatalog` 的 `HarnessToolCatalogAdapter`、读库的
动态 `McpToolCatalog`，以及唯一 `@Primary` 的 `CompositeRuntimeToolCatalog`。复合
目录不缓存动态工具，按静态在前、动态在后的固定顺序返回可选项，任何重复模型可见
name（无论 `ContributionId` 是否一致）都 fail closed；`ToolCatalogQueryService`、
`AgentDefinitionConfigValidator`、`DatabaseTurnResolver` 和 `ToolExecutionGateway`
只通过它列出或查找工具。

[McpToolCatalog](../../platform/src/main/java/fun/fengwk/kkstudio/platform/catalog/mcp/runtime/McpToolCatalog.java)
每次调用现读 DB，只选拔 `enabled=true` 且 `AVAILABLE` 的 server 行与它们的工具行，按
模型可见名升序返回。
[`RemoteMcpExecutableTool`](../../platform/src/main/java/fun/fengwk/kkstudio/platform/catalog/mcp/runtime/RemoteMcpExecutableTool.java)
在受管 `toolGatewayExecutor` 中以 per-call client 执行，`ToolRequirements` 为 none
（不绑定任何 Environment），side effect 是 `NON_IDEMPOTENT`。发送前按工具名重读当前行
并重新校验 server 可选拔状态与工具归属，一次绝对 deadline 覆盖 client 初始化和调用，
取消只作用于当前调用；超时、取消、配置漂移与执行失败只向 Tool result 暴露稳定通用文本，
不泄漏 URL、headers 或内部异常。

## Chat、Project 与 Issue

[ChatServiceImpl](../../platform/src/main/java/fun/fengwk/kkstudio/platform/chat/service/impl/ChatServiceImpl.java)
提供 Chat CRUD，保存 title、agentName、`yoloEnabled`、version 和时间；
Chat 本身不持有 Environment；具体 branch 的环境身份由该 branch 的 `BranchSettings.environmentName` 在每轮 turn 解析，Agent definition 的 `config` 不含任何 Environment 字段。
`deleteChat` 先排他锁定 Chat，再调用
`SessionDeletionOrchestrator.deleteSessionsByOwner(OwnerType.CHAT, chatId)` 深删除
全部 Session，最后删除 Chat 行；`session_owner` 只表达 owner relation，不绕过 Session
的 Harness/Blob 清理。

[ProjectServiceImpl](../../platform/src/main/java/fun/fengwk/kkstudio/platform/project/service/impl/ProjectServiceImpl.java)
与 [IssueServiceImpl](../../platform/src/main/java/fun/fengwk/kkstudio/platform/project/service/impl/IssueServiceImpl.java)
提供 Project/Issue 的事务边界。Project 持有必填 Coordinator Agent、项目内单调 Issue
编号、CAS `version` 与归档状态；Issue 持有六态生命周期、可空 assignee/reviewer
Agent、`specRevision`、`inputSequence` 与归档状态。状态迁移白名单由
[IssueStatusTransition](../../platform/src/main/java/fun/fengwk/kkstudio/platform/project/model/IssueStatusTransition.java)
单点维护，action 与状态的对应关系以该类为准：

```text
需求池 --READY--> 待处理 --START_EXECUTION--> 执行中 --SUBMIT--> 评审中 --APPROVE--> 完成
待处理 <--DEFER-- 需求池         评审中 --REQUEST_CHANGES--> 待处理
非终态 --CANCEL--> 已取消         完成/已取消 --REOPEN--> 待处理
```

依赖边只能连接同一 Project 的未归档 Issue，添加时在 Project 图锁下按 UUID 顺序锁两端
并通过递归 CTE 拒绝环；增删依赖推进目标 Issue 的 `specRevision/version`。
`issue_input` 是每 Issue 单调追加流，可用 `idempotencyKey` 精确重放，追加输入推进
`inputSequence/version` 并唤醒 Controller。

Project Coordinator 和每个 Agent IssueRun 分别以 `OwnerType.PROJECT` 与
`OwnerType.ISSUE_RUN` 拥有 Harness Session。
[ProjectHarnessSessionBootstrapService](../../platform/src/main/java/fun/fengwk/kkstudio/platform/project/session/ProjectHarnessSessionBootstrapService.java)
按 `Project -> Issue -> IssueRun` 锁序，在同一物理事务内原子创建 Session、ROOT、
Thread、首条 Command、Work 与 owner relation；数据库 `session_owner.session_id`
主键与排他弧 check 保证 Chat、Canvas、Project、IssueRun 四类 owner 全局互斥。

[IssueControllerDispatcher](../../platform/src/main/java/fun/fengwk/kkstudio/platform/project/controller/IssueControllerDispatcher.java)
只负责 `issue_controller_work` 的短事务 claim、bounded handoff、合并 wake 与 poll；
[IssueReconciler](../../platform/src/main/java/fun/fengwk/kkstudio/platform/project/controller/IssueReconciler.java)
在 `Project(FOR SHARE) -> Issue(FOR UPDATE) -> IssueRun(FOR UPDATE) -> work lease`
锁序与 fencing 下每次推进一个有界动作，处理依赖阻塞、Agent executor/reviewer Run、
Harness bootstrap/inspection、输入 continuation、人工等待、deadline、continuation
budget、retry/cancel 和 Coordinator attention。通知与 poll 都只是唤醒，数据库 work
行才是可恢复事实；claim/reconcile 由 lease token 与 claimed wake version 双重围栏，
worker 拒绝、处理失败、节点退出或通知丢失都由归还、延迟重试、lease 过期和 periodic
poll 收敛。

[ProjectHarnessContributor](../../platform/src/main/java/fun/fengwk/kkstudio/platform/project/tool/ProjectHarnessContributor.java)
注册 12 个 INTERNAL 角色工具，按 `ProjectRoleToolType` 划分归属：Coordinator 获得
Project/Issue 查询与编排工具，Executor 只获得 submit/request-input，Reviewer 只获得
review；`ProjectThreadOwnerResolver` 从 Thread 的唯一 owner relation 解析角色，不依赖
模型自报。Project 深删除先锁 Project、Issues、Runs 并拒绝活动或 UNKNOWN Run，再按
controller work -> Run Sessions -> reviewer/executor Runs -> inputs/dependencies/
Issues -> Coordinator Session -> Project 顺序清理，每个 CAS 删除都检查受影响行数。

## SystemSettings

`system_setting` 恰好一行（存在 `id = 1` 的 check 约束），`config` 是六个必填 section
的 canonical JSON：`tool`、`aiRuntime`、`environment`、`integrations`、`storageMedia`、
`advanced`。[SystemSettings](../../platform/src/main/java/fun/fengwk/kkstudio/platform/settings/SystemSettings.java)
的 canonical constructor 校验字段范围、跨字段关系与启用前提；
`SystemSettingsCodec` 拒绝未知字段、尾随 token、错误类型和缺失 section；
`SystemSettingsSchemaProvider` 是 HTTP 编辑 schema 的唯一 metadata 来源，字段路径与
record component path 对齐。

[SystemSettingsServiceImpl.update](../../platform/src/main/java/fun/fengwk/kkstudio/platform/settings/SystemSettingsServiceImpl.java)
的顺序是：解析 expected version → 严格解码并校验完整聚合 → 行 CAS update → 事务提交
后通知 `SystemSettingsChangeHandler` 权威回读。`SystemSettingsSnapshot` 以 version CAS
替换内存快照，较旧回读不能覆盖较新值，事务回滚不改变快照；数据库 trigger 通过
`system_settings_changed` 通道广播给其它节点。

## Storage、Blob 与 Resource

Storage 把内容身份、owner 引用和对象物理存储分开：

- `storage_blob` 按 `(sha256, size_bytes)` 对 ACTIVE 内容去重，状态为 `ACTIVE` 或
  `DELETING`，`ref_count` 由 `StorageBlobManager` 维护；
- `session_blob_ref` 是 Harness Session 对 Blob 的显式 owner edge，`canvas_resource`
  和 Tool history ingest 通过各自的 owner/service 维护引用；
- `storage_upload` 记录 PENDING/READY 上传、candidate blob、过期时间和 cleanup lease；
- 对象 key 由 [StorageObjectKeys](../../platform/src/main/java/fun/fengwk/kkstudio/platform/storage/StorageObjectKeys.java)
  集中生成：`uploads/{id}/original`、`blobs/{id}/original`、`blobs/{id}/preview.webp`，
  调用方不能选择 bucket。

`StorageBlobManager.retain/release` 与 `SessionBlobRefManager` 都要求
`PROPAGATION_MANDATORY`，因此引用变更必须属于调用方已有事务。引用减到零时，数据库
同一条条件 update 把 Blob 切为 `DELETING`；提交后只唤醒 maintenance，删除顺序是
preview → original → 条件删除 Blob 行。

[StorageUploadServiceImpl](../../platform/src/main/java/fun/fengwk/kkstudio/platform/storage/service/impl/StorageUploadServiceImpl.java)
的上传协议是：

1. `reserve` 在短事务内按 hash/size 命中 ACTIVE 或创建 PENDING upload，未命中再生成
   checksum PUT presign；
2. `complete` 在数据库事务外 HEAD、校验 size/SHA-256、probe 媒体事实并复制 candidate
   object，随后短事务锁 upload 行、做 ACTIVE dedup、设置 blobId；并发 complete 只有
   一个绑定成功；
3. `delete` 在短事务记录 cleanup request，READY upload 同时 release upload owner；
4. `expireOnce` 先用 `SKIP LOCKED` claim 有界批次，事务外幂等删除临时/candidate
   object，再用 cleanup token 做 fenced finalize；删除或 finalize 失败时保留 lease，
   下一次 maintenance 重试。

[StorageMaintenance](../../platform/src/main/java/fun/fengwk/kkstudio/platform/storage/StorageMaintenance.java)
是 `SmartLifecycle`，拥有单一 daemon scheduled executor，启动立即 wake 并合并并发
wake，同时以 fixed-delay poll 驱动 upload expire 与 DELETING blob sweep；所有 S3 I/O
都在事务外，数据库清理事实是唯一可恢复依据。

Tool 终态结果由
[ToolResultFinalizer](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/tool/gateway/ToolResultFinalizer.java)
先对完整投影做无副作用 plan 与 hard-limit 校验。普通 Backend Tool 的 Binary/Resource
按 plan 写入 `ResourceStore`，返回引用必须与 plan 完全一致，已有 Resource 也必须经同一
Store 读取并复核 size/SHA-256。Daemon Binary 则在终态前通过 invocation-scoped
`RESOURCE_UPLOAD_REQUEST/TICKET/COMMIT` 取得预签名 PUT 并由 Daemon 直传 S3，WebSocket
只携带 `uploadId/mediaType/name/size/sha256/preview`，绝不携带 Base64、二进制帧、
预签名 URL 或 Daemon 本地 URI。

[GlobalStorageToolResultHistoryMaterializer](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/tool/gateway/GlobalStorageToolResultHistoryMaterializer.java)
在调用方 mandatory transaction 中分两条路径收敛：普通 managed Resource 从
`ResourceStore` 读取并复核完整性后摄入 `storage_blob`；`blob-upload:<uploadId>` 瞬时
引用先 `lockReady`，以权威 `storage_blob` 复核媒体类型/大小/SHA-256，再
`retain session_blob_ref -> delete upload owner` 原子转移引用，durable 名称取上传行。
两条路径最终都只把 `blobId`、权威名称与有界 preview 写入 Harness history；任何一步
失败都使调用方事务回滚。

## Environment

Environment 的持久化 Card 保存于 `environment` 表（UUID `id` 为路由主键，全局唯一且
不可变的 `name` 为对外身份）；跨节点 route ownership 由 `environment_connection` 租约保存。每个 JVM 共享
一个 `nodeInstanceId`，[EnvironmentRegistry](../../platform/src/main/java/fun/fengwk/kkstudio/platform/environment/registry/EnvironmentRegistry.java)
以 `(environmentId, ownerNodeId, leaseToken)` 围栏读写数据库权威路由，并实现会话核心
的 `DaemonLeaseStore`。

[Harness Environment Server](harness-environment-server.md) 的
[EnvironmentDaemonServer](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServer.java)
唯一拥有本节点 daemon 会话
状态：连接代际、HELLO/WELCOME/READY/HEARTBEAT 握手推进、在途 invocation 与终态所有权。
[EnvironmentServerConfiguration](../../platform/src/main/java/fun/fengwk/kkstudio/platform/environment/server/EnvironmentServerConfiguration.java)
装配它并提供窄端口实现：`EnvironmentRegistry`（租约围栏）、`EnvironmentRepository`
解析的注册凭据、`EnvironmentSessionListener`、`StorageDaemonResourceTicketService`
（把会话核心的票据端口映射到 `StorageUploadService.reserve/complete/delete`）与
`SystemSettingsSnapshot`（每次判定现读的心跳超时与资源上限）。

Platform 侧的产品适配器只做映射，不持有会话状态：[EnvironmentDaemonGateway](../../platform/src/main/java/fun/fengwk/kkstudio/platform/environment/gateway/EnvironmentDaemonGateway.java)
暴露会话核心与租约实现，供 Web 层 WebSocket transport 与产品查询复用；
[EnvironmentServiceImpl](../../platform/src/main/java/fun/fengwk/kkstudio/platform/environment/service/EnvironmentServiceImpl.java)
在 Product CRUD 上执行 CAS 与引用校验，唯一的可变状态是 `registrationToken` 轮换
（`name` 是不可变身份，不存在改名入口）。每次 READY 在同一条 owner/lease 围栏 SQL 中
推进连接状态并覆盖连接行保留的宿主 metadata（`environment_connection.runtime_info`）；
断开或重新 CONNECTING 只回退状态、绝不清空该保留事实，因此 Daemon 离线后系统提示词
和管理页面仍可读取最后一次已接受的 OS、时区、备注与 root path。
Skill 正文不经过 Daemon：内部工具 `load_skill` 经 `DatabaseSkillContentLoader` 按冻结的
`(packageName, packageVersion, name)` 从不可变 `skill` 行精确读取正文。

每个 Environment 的调用只按 `invocationId` 关联：同一 Environment 允许任意数量
capability 并发在途，不同能力之间没有共享槽位，也不存在环境级容量或排队，唯一拒绝
重复的规则是同一 Environment 内重用相同的活动 `invocationId`。发送前按该连接 READY
中冻结的目标 Daemon OS 对 `arguments.workdir` 做纯词法校验，真实存在性、目录类型与
可访问性由 Daemon 判定。物理连接失效不终结在途 invocation：同一 `daemonInstanceId`
重连时以相同 `invocationId` 重放在途 INVOKE；只有身份不同的 Daemon 进程接管或调用方
deadline 才收敛为 uncertain，绝不重发可能已产生副作用的请求。资源上传控制消息同样
绑定 `invocationId`，并以 `transferId` 在调用内幂等关联。

## Model 与 Tool 执行

### Provider 解析与 Model I/O

[ModelExecutionConfiguration](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/model/ModelExecutionConfiguration.java)
为四个 `ProviderType` 注册稳定命名的 `ProviderFactory`，Model I/O 使用 Java 21
virtual-thread-per-task executor。

[DatabaseProviderResolutionService](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/model/DatabaseProviderResolutionService.java)
在每次 Model attempt 按 frozen `providerName` 读取当前 `agent_provider`：

1. 当前 Provider 必须存在，且 `providerType` 等于 durable request 中冻结的 type；
2. 以当前 factory 读取 endpoint、credential、timeout policy 并创建 adapter；
3. 根据当前 Provider cache capability 规范化 durable cache control；
4. 通过 `ProviderResourceMaterializer` 物化当前 attempt 的 Resource：模型输入模态、
   adapter 用户/工具结果能力与 Blob MIME 同时匹配时，图片、音频、视频和 PDF 统一转成
   Base64 data URI；非 PDF 文档、缺失或非 ACTIVE Blob、能力不匹配及 ASSISTANT
   资源使用确定性文本回退，不读取内容、不生成预签名 URL。

持久化只保存自己的 `ResourceMessageContent` / `ProviderResourceBlock`（Blob ID、名称、
有界预览与外部化事实），Base64 只存在于 attempt 的有效请求，durable codec 拒绝
Image/Audio/Video/Document 媒体块。物化保留 assistant 的原生 `replayState`，不改写
签名、加密回放数据、affinity 或源前缀 hash。

[ProviderInlineBlobReader](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/model/ProviderInlineBlobReader.java)
经 `StorageBlobContentService` 读取权威 Blob：短事务 retain/release，事务外通过内部 S3
endpoint 下载原始字节，不使用面向浏览器的 public endpoint。应用侧安全上限为单文件
原始 **100 MiB**、每请求累计 data URI **160 MiB** ASCII 字符，重复引用与嵌套工具结果
均逐次计入预算，超限明确失败而不截断。不可变 Blob 内容的 Base64 使用 Caffeine 缓存：
TTL **5 分钟**、总记账上限 **64 MiB**、单条超过 **8 MiB** 不缓存，所有下载共享
**2 个并发许可**；缓存命中前仍逐次检查 ACTIVE 状态，失败不缓存。以上是应用侧资源
保护，不代表具体模型或供应商允许的附件大小。

四个 Provider（OpenAI Chat、OpenAI Responses、Anthropic、Google）都使用
[`harness-provider`](harness-provider.md) 的原生协议适配器，共享基于 JDK 21
`HttpClient` 的 `modelExecutionTransport`。

### Gateway admission 与两阶段激活

[PlatformModelGateway](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/model/PlatformModelGateway.java)
的 `start` 顺序与结果语义是：

| 阶段 | 结果 |
| --- | --- |
| Provider resolution 抛确定性 `IllegalArgumentException` | `Rejected(INVALID_REQUEST)`，不提交、不重试 |
| Model admission 无 permit | `Busy(busyRetryDelay)` |
| executor 明确 `RejectedExecutionException` | `Busy`，释放 lease |
| executor 抛其它提交异常 | `Indeterminate(TRANSIENT)`，无法证明 transport 是否启动 |
| 成功提交 | `Started(handle)`，callback gate 仍关闭 |

`Handle.activate` 只在 Runtime attach handle 且 durable invocation 已标记 `RUNNING`
后打开 gate；激活前 cancel 会唤醒等待任务、释放 permit 且不触碰 Provider，构造时拒绝
inline executor、`CallerRunsPolicy` 和静默丢弃 policy。Model 与 Tool gateway 共用
[GatewayExecutorSafety](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/GatewayExecutorSafety.java)
作为该构造约束的唯一实现。Provider callback 经单一 FIFO drainer 进入，队列上限
`256`：第一个 terminal 胜出，terminal 后的迟到/重复信号全部丢弃，队列溢出、未知
transport 异常或非 terminal listener 异常统一以一次 `UNKNOWN` 收敛。

[ToolExecutionGateway](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/tool/gateway/ToolExecutionGateway.java)
的 `preflight` 先用 frozen definition 的模型可见 `name` 从 `RuntimeToolCatalog` 恢复
ToolContribution，再要求目录中的完整 definition、contributor provenance 与
requirements 相等；贡献缺失或 definition/requirements 漂移直接生成确定性的
`TOOL_NOT_FOUND` / `TOOL_DEFINITION_MISMATCH` Deny，不进入 permission evaluator。
正常路径按模型可见 tool name、arguments 与单次调用的 `arguments.workdir` 生成 `ALLOW`、
`ASK` 或 `DENY`，不改写 binding/arguments，也不感知 YOLO。`start` 先获取 tool
admission（默认 `kk-studio.harness.execution-admission.tool=64`），再经单一执行路径
校验冻结定义与 requirements、构建隔离所属 Contributor 的只读 `BranchView` 与可选
`BoundEnvironment`、提交异步执行并返回两阶段门控 Handle，最后在门控桥校验 effects
归属与声明、完成 managed Resource 外部化并一次性投递。

Tool admission 无 permit 或 executor 明确拒绝时返回 `RetryLater`，其它无法证明是否
提交的异常返回 `Indeterminate(EXECUTION_FAILED)`。`Started` 后的 partial 必须非空、
toolCallId 精确匹配、不能携带 Binary/Resource，且 canonical JSON 不超过 `256 KiB`；
terminal result 在 externalize 前校验，成功结果采用 all-or-nothing Resource
externalization，第一个 terminal 后任何迟到信号、其余 Resource 写入和第二个 terminal
都被禁止。`AppendCustomEntry` intent 必须属于自身 Contributor、命中已注册 custom type
且存在声明的 WRITE access。

### turn 解析与 prompt 物化

[DatabaseTurnResolver](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/thread/command/DatabaseTurnResolver.java)
只以 candidate `EntryPath` 的最新 `BranchSettings` 为输入，在每个 live turn 解析：

1. 当前 Agent、Model、Provider、Variant 与 ProviderFactory，并把 Variant 未显式声明的
   输出上限补齐为 Model 全局 `limit.output`；
2. 当前 Environment context：只按 `BranchSettings.environmentName()` 查全局唯一且不可变的
   name 得到路由身份，name 无法解析时确定性返回 `PLANNING_FAILED`；
3. Agent config 中的每个工具 ID 都通过 `RuntimeToolCatalog.findTool(id)` 查找并校验
   `visibility() == ToolVisibility.SELECTABLE`；`environmentRequired()` 为 true 但该
   branch 未选择 Environment 时仍绑定为 null 并保留完整工具声明（调用时才以
   `ENVIRONMENT_NOT_SELECTED` 失败，绝不阻止规划）；`requiredEnvironmentId` 与已选环境
   冲突时在 planning 阶段确定性返回 `AssistantError.code=PLANNING_FAILED`；
4. skills、subagents 和内部 `load_skill` / `task`；
5. Contributor context projector、system instruction、cache control、context window 与
   output budget。

每个 tool 都从 `RuntimeToolCatalog` 精确恢复 descriptor；Skill 严格按全局 name 从
Platform 当前目录解析并冻结 `(packageName, packageVersion, name, description)`，与
Branch Environment 无关；名称缺失返回 `PLANNING_FAILED`。
[AgentPromptComposer](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/task/AgentPromptComposer.java)
拼接正文、当前 Environment、skill 和 subagent sections，并只替换已知的 `${date}`
placeholder，其余 `${...}` 与未闭合形式保持原文。Platform 将其与 Project 角色上下文、
Contributor context projector 片段用空行确定性拼接为单条非空 `systemInstruction` 冻结进
`ModelRequestSpec`，会话历史中绝不作为系统消息注入。
[DatabaseThreadSelectedSkillLookup](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/skill/DatabaseThreadSelectedSkillLookup.java)
从冻结的 ModelRequestSpec 读取 Skill binding，正文由 `load_skill` 经
`DatabaseSkillContentLoader` 按精确的三元组读取，不会用当前 Agent
配置或当前 Skill 目录扩张已冻结调用。

[AgentBranchSettingsMaterializer](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/task/AgentBranchSettingsMaterializer.java)
为普通 root 按最新 Agent/Model catalog 物化 `agentName` 与 model，并固定
`environmentName=null`；为新建或恢复的 subagent 额外读取被调用 Agent 的
`inheritParentEnvironment`，决定是否采用父 Model invocation 已冻结的
`environmentName`。恢复会话以 `SET_AGENT -> SET_MODEL -> SET_ENVIRONMENT -> USER`
命令前缀收敛完整目标设置。工具、skill 和 subagent binding 在每个 live turn 重新解析；
非空 `subagents` 始终声明 `task` 并冻结 allowlist，递归深度上限只在实际调用时返回稳定
Tool error，不动态裁剪工具面。Task 的 agent name、description、parent/root/depth 和
invocation 归属在 durable binding 中冻结，执行期间不因 Catalog 变更扩权。Compaction
resolver 是窄路径：只解析 `CompactionPreparation.executionModel`，返回零
tools/skills/subagents 的 ModelRequestSpec。

## Canvas 与 ComfyUI 适配

[PlatformCanvasCommandService](../../platform/src/main/java/fun/fengwk/kkstudio/platform/orchestration/PlatformCanvasCommandService.java)
实现 Canvas application command：`createCanvas/applyCommands/deleteCanvas` 都在事务内；
`applyCommands` 先锁 `canvas_document` 行，再以 `(canvasId, idempotencyKey, requestHash)`
做精确 replay/conflict 并以 `expectedVersion` 推进 graph version；
`CREATE_RESOURCE_NODE` 在同一事务锁定 READY upload、retain Canvas Blob 引用、标记 upload
cleanup 并创建 `canvas_resource`，上传对象由提交后的 Storage Maintenance 清理；Function
run、node、group、link、resource 的状态变化通过 Canvas core port 写入并以 patch 返回。

[PlatformCanvasResourceLifecycle](../../platform/src/main/java/fun/fengwk/kkstudio/platform/canvas/resource/PlatformCanvasResourceLifecycle.java)
的核心不变量是：Resource row 对其 Blob 贡献一个引用，Function pin 只保护无 owner
Resource 而不增加 `ref_count`；run/node/canvas pin 释放后只回收不再被 pin 且无 owner 的
Resource，Function success 用 target 替换 owner resource，失败/cancel/迟到结果只丢弃
unowned target。这使 Resource row、Session ref 和 upload owner 各自只维护一条明确引用
边，任何 owner 删除都必须经过对应 manager。

[CanvasBlobResourceMaterializer](../../platform/src/main/java/fun/fengwk/kkstudio/platform/canvas/resource/CanvasBlobResourceMaterializer.java)
把 Function 输出 spool 到临时目录（上限 512 MiB），在事务外写 `blobs/{blobId}/original`
并 probe 媒体事实，然后在事务内锁 Canvas、确认恰好一个 RUNNING output pin、做 Blob
dedup 和 `resourceId` 幂等 insert；并发落败方释放自身刚创建的 Blob 引用，预览生成在
提交后 best-effort 执行。
[CanvasBlobPreviewService](../../platform/src/main/java/fun/fengwk/kkstudio/platform/canvas/resource/CanvasBlobPreviewService.java)
通过不经 shell 的 `MediaProcessRunner` 调用 ffmpeg 生成 webp，输入上限 512 MiB，超时与
缩略图参数来自 SystemSettings。

Function adapter 只实现 `CanvasFunctionAdapter`：

| adapter | 运行边界 |
| --- | --- |
| [FakeCanvasFunctionAdapter](../../platform/src/main/java/fun/fengwk/kkstudio/platform/canvas/function/fake/FakeCanvasFunctionAdapter.java) | 读取 classpath 的 tiny image/video fixture，仍通过真实 materializer，受 `fake-enabled` property 控制 |
| [GptImage2CanvasFunctionAdapter](../../platform/src/main/java/fun/fengwk/kkstudio/platform/canvas/function/opencli/GptImage2CanvasFunctionAdapter.java) | OpenCLI Hub + `chatgpt-agent`，image reference 每项最多 20 MiB，checkpoint 覆盖 upload/submit/poll/materialize |
| [SeedanceCanvasFunctionAdapter](../../platform/src/main/java/fun/fengwk/kkstudio/platform/canvas/function/opencli/SeedanceCanvasFunctionAdapter.java) | OpenCLI Hub，冻结 reference policy、上传和有界 polling，checkpoint 恢复同一 execution |
| [MiniMaxH3CanvasFunctionAdapter](../../platform/src/main/java/fun/fengwk/kkstudio/platform/canvas/function/h3/MiniMaxH3CanvasFunctionAdapter.java) | Platform one-shot Harness prompt + Environment + ComfyUI，状态阶段覆盖 prompt、Comfy upload/submit/poll 和 Blob ingest |

[PlatformCanvasFunctionBlobAccess](../../platform/src/main/java/fun/fengwk/kkstudio/platform/canvas/function/PlatformCanvasFunctionBlobAccess.java)
是 Function runtime 读取 Blob facts、打开 original stream 和获取 presign 的唯一 Platform
storage adapter；Canvas adapter 不直接拼 S3 key，也不直接管理 Canvas 引用计数。当前
Function 扩展点是 `CanvasFunctionAdapter` + `CanvasFunctionCatalog`，Catalog 在启动装配
时冻结能力快照。

[ComfyuiWorkflowApiServiceImpl](../../platform/src/main/java/fun/fengwk/kkstudio/platform/comfyui/workflow_api/service/impl/ComfyuiWorkflowApiServiceImpl.java)
管理 `comfyui_workflow_api` 卡片；bindings parser 严格验证 workflow JSON、
parameter/file binding、node/input 存在性、value type 和 blobId，只有 enabled workflow
才能进入运行服务。[ComfyuiRuntimeService](../../platform/src/main/java/fun/fengwk/kkstudio/platform/comfyui/ComfyuiRuntimeService.java)
是无状态 runtime：读取 enabled binding 并复制 Workflow、校验并按 JsonPath selector 写入
参数、对 file binding 从全局 Storage 按 blobId 受
`integrations.comfyui.maxInputFileBytes` 限制地读取后上传 ComfyUI、submit 后以 202 返回
`runId == prompt/job id`、get/cancel/download 直接查询 ComfyUI job 且运行状态不落本地表。
ComfyUI client 是否装配由启动时的 `SystemSettings.integrations.comfyui.enabled` 决定。

## 关键流程

### Model attempt

```text
ThreadProcessor
  -> DatabaseTurnResolver -> frozen ModelRequestSpec
  -> DatabaseProviderResolutionService
       -> current agent_provider row + ProviderType adapter
       -> attempt-local Resource materialization
  -> PlatformModelGateway.start -> admission lease -> Started(handle), gate closed
  -> Runtime markRunning + handle.activate
  -> virtual-thread Provider stream
  -> FIFO bridge: delta / thinking / tool call / terminal
  -> Runtime terminal CAS + Work wake
```

### Tool attempt

```text
ToolProcessor
  -> ToolExecutionGateway.preflight -> PermissionEvaluator + arguments.workdir
  -> admission + exact catalog/binding route
  -> Started(handle), gate closed
  -> Runtime markRunning + handle.activate
  -> unified Tool.execute (with BranchView / BoundEnvironment)
  -> bounded FIFO bridge
  -> partial realtime or terminal externalization
  -> Runtime terminal CAS + owning Thread Work
```

### Chat/Canvas 首条带附件消息

```text
HTTP command-batch
  -> HarnessCommandAcceptanceOrchestrator.accept (single physical transaction)
       -> owner KEY SHARE authorization
       -> NEW_SESSION owner relation atomic insert
       -> lock READY upload + retain SessionBlobRef + mark upload cleanup
       -> HarnessRuntime.acceptCommands
  -> 202 durable acceptance -> Work dispatcher / ThreadProcessor
```

owner authorization、Session relation、attachment materialization、Session blob ref 和
Runtime command acceptance 属于同一物理事务，任一失败整体回滚；Runtime replay 不重复
消费 upload，但仍执行 owner authorization。正常接受使用 owner `KEY SHARE`，删除使用
owner 排他锁，深删除统一按 `Owner -> Session -> Thread` 锁序并对跨 Session 的 Thread
按 UUID 排序，避免锁序回退。

### Project Issue 调谐

```text
Project/Issue mutation or controller poll
  -> issue_controller_work request / PostgreSQL wake hint
  -> IssueControllerDispatcher claim (short transaction)
  -> bounded worker handoff
  -> IssueReconciler
       -> fenced Project/Issue/Run/work locks
       -> one bounded transition
       -> optional atomic IssueRun Session bootstrap or Harness command
       -> complete/reschedule work
```

### Canvas Function 输出

```text
Function dispatcher claim + RUNNING lease
  -> adapter checkpoint / third-party execution
  -> CanvasBlobResourceMaterializer
       -> spool + S3 put + media probe outside DB transaction
       -> Canvas row lock + Blob dedup + resource insert in transaction
  -> commit -> preview generation best-effort -> Canvas version/patch notification
```

## 配置

配置分三层，边界不可混用：**SystemSettings（数据库单行，可在线修改）**、**部署级
`@ConfigurationProperties`（进程启动边界，不进数据库/DTO/前端）**、**部署 secret
（只在 deployment property）**。

### SystemSettings section

| section | 主要字段与默认值 | 应用时点 |
| --- | --- | --- |
| `tool` | permission 默认 `write`/`edit`/`bash` 各 `* -> ask`，`defaultYolo=false`，Model Busy retry 5s、Tool Busy retry 1s、Tool overload retry 5s、skill load 30s | admission/permission 读取点 live |
| `aiRuntime` | retry 3 次、EXPONENTIAL、base 2s、max 60s、compaction keep 20000 tokens、subagent depth 2 / per-parent concurrency 10 / maxTurns 50 | retry、resolver、subagent 配置读取点 |
| `environment` | resource 16 MiB、heartbeat 60s | Environment 单项/聚合上传资源上限与心跳超时读取点 |
| `integrations.comfyui` | disabled；connect 10s、read 30s、WebSocket 1800s、input 50 MiB | client topology 由启动快照决定 |
| `integrations.openCliHub` | disabled、base URL 未配置；connect 5s、request 120s、long poll 130s、JSON 512 KiB、error 4 KiB | adapter 创建与执行参数 |
| `integrations.seedance` / `gptImage2` / `minimaxH3` | 各自 enabled/paid 开关、workspace、prompt 与 ComfyUI timeout、polling 约束 | adapter 的启动快照与执行读取点 |
| `storageMedia` | upload 3600s、presign 默认 600s / 上限 3600s、media process 30s、thumbnail 512 / quality 80 | 上传与预签名生命周期、媒体处理预算 |
| `advanced` | resource 16 MiB；processor lease/heartbeat 30s/10s、失败与回退各 1s；event queue 512 / 2 MiB / 10s、heartbeat 20s；notification poll 5s、reconnect 1s | 组合根装配的 restart-required 软策略 |

SystemSettings 永不承载 Dispatcher 容量与调度节奏、数据库连接、filesystem root、
ffmpeg binary、Daemon token/identity、Provider credential、ComfyUI API key、H3 bearer
token 或 OpenCLI instance identity。

### 部署级 `@ConfigurationProperties`

| key | owner | 边界 |
| --- | --- | --- |
| `kk-studio.harness.dispatcher.*` | platform | Work claim/handoff 租约、轮询、拒绝退避与 bounded worker 容量；默认 `64/30s/1s/1s/16/64`（maxDispatchTasks/lease/poll/rejection/worker/queue） |
| `kk-studio.harness.execution-admission.{model,tool,subagent}` | platform | 进程级容量，默认 `16/64/10` |
| `kk-studio.harness.runtime.{workers-enabled,resource-root}` | platform | worker 开关与内容寻址 Resource 存储根（默认 `<cwd>/.kkstudio/resources`） |
| `kk-studio.project.controller.*` | platform | Issue Controller lease 30s、poll 1s、retry 5s、blocked 60s、run 30m、continuation 10、worker `8 + queue 64` |
| `kk-studio.storage.s3.{endpoint,public-endpoint,region,bucket,access-key,secret-key}` | platform | S3/MinIO 服务端与 presign endpoint；bucket 只能由服务端配置 |
| `kk-studio.storage.maintenance.{poll-delay,cleanup-lease}` | platform | maintenance 轮询与 cleanup lease，默认 `30s/5m` |
| `kk-studio.canvas.resource.{ffprobe-binary,ffmpeg-binary,temp-dir}` | platform | 媒体处理本地路径 |
| `kk-studio.comfyui.api-key` | platform | ComfyUI secret，非 SystemSettings |
| `kk-studio.opencli-hub.instance-id` | platform | OpenCLI Hub 部署身份 |
| `kk-studio.canvas.function.minimax-h3.comfy-bearer-token` | platform | H3 ComfyUI bearer secret；启用/路由/timeout 仍由 SystemSettings |
| `kk-studio.canvas.function.runtime.*` | canvas-infra | Canvas Function 调度容量、lease、heartbeat 与 poll |
| `kk-studio.harness.environment-gateway.{max-message-bytes,queue-capacity,max-bytes,send-timeout}` | web | Daemon WebSocket 传输安全边界，默认 `16MiB/256/16MiB/10s`；不是 Environment 并发配额 |

S3、Provider、ComfyUI、OpenCLI Hub 和 Environment Daemon 都是明确的
third-party/deployment boundary。Platform 对外只传 domain DTO、稳定错误 kind、签名 URL
和 frozen binding，不会把 bucket、对象 key、credential 或完整上游异常作为产品协议的
一部分。

## 测试入口

先看架构守卫，再按子域定位 integration test：

- [`PlatformPackageArchitectureTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/PlatformPackageArchitectureTest.java)、
  [`PlatformArchitectureTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/PlatformArchitectureTest.java)：
  包依赖方向，Platform 不得引用 `canvas-infra`、`harness-infra`、`web`、`harness-daemon`
  的生产实现。
- [`ProviderTypeArchitectureTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/ProviderTypeArchitectureTest.java)、
  [`HarnessExecutionAdmissionArchitectureTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/HarnessExecutionAdmissionArchitectureTest.java)：
  ProviderType 唯一来源与 admission 构造约束。
- 测试基座：[`PlatformTestApplication.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/PlatformTestApplication.java)、
  [`PostgresSpringTestSupport.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/persistence/test/PostgresSpringTestSupport.java)、
  [`PostgresSchemaSupport.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/persistence/postgresql/PostgresSchemaSupport.java)、
  [`StorageS3TestConfiguration.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/storage/StorageS3TestConfiguration.java)。

按子域定位测试，每个目录都按上面的子域地图组织：

| 子域 | 测试目录 | 代表测试 |
| --- | --- | --- |
| Catalog、MCP | [catalog/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/catalog/)、[catalog/mcp/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/catalog/mcp/) | [`McpServerServiceTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/catalog/mcp/service/McpServerServiceTest.java)、[`AgentDefinitionConfigCodecTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/catalog/definition/configuration/AgentDefinitionConfigCodecTest.java) |
| Chat、跨 owner 事务 | [chat/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/chat/)、[orchestration/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/orchestration/) | [`ChatServiceIntegrationTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/chat/ChatServiceIntegrationTest.java)、[`HarnessCommandAcceptanceOrchestratorTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/orchestration/HarnessCommandAcceptanceOrchestratorTest.java) |
| Project、Issue | [project/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/project/)、[project/controller/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/project/controller/)、[project/session/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/project/session/) | [`IssueReconcilerTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/project/controller/IssueReconcilerTest.java)、[`ProjectHarnessSessionBootstrapServiceTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/project/session/ProjectHarnessSessionBootstrapServiceTest.java) |
| Model、Tool 执行 | [harness/model/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/model/)、[harness/tool/gateway/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/tool/gateway/) | [`PlatformModelGatewayTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/model/PlatformModelGatewayTest.java)、[`ToolExecutionGatewayPreflightTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/tool/gateway/ToolExecutionGatewayPreflightTest.java) |
| turn 解析与 prompt 物化 | [harness/thread/command/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/thread/command/)、[harness/task/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/task/)、[harness/skill/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/skill/) | [`DatabaseTurnResolverTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/thread/command/DatabaseTurnResolverTest.java)、[`AgentPromptComposerTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/task/AgentPromptComposerTest.java) |
| Environment | [environment/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/environment/)、[environment/registry/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/environment/registry/) | [`EnvironmentRegistryTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/environment/registry/EnvironmentRegistryTest.java)、[`EnvironmentServiceImplTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/environment/service/EnvironmentServiceImplTest.java) |
| Canvas 与 ComfyUI 适配 | [canvas/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/canvas/)、[canvas/function/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/canvas/function/)、[comfyui/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/comfyui/) | [`PlatformCanvasResourceLifecycleTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/canvas/resource/PlatformCanvasResourceLifecycleTest.java)、[`ComfyuiRuntimeServiceTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/comfyui/ComfyuiRuntimeServiceTest.java) |
| Storage、Blob | [storage/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/storage/)、[storage/service/impl/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/storage/service/impl/) | [`StorageUploadServiceIntegrationTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/storage/StorageUploadServiceIntegrationTest.java)、[`StorageUploadCleanupLeaseIntegrationTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/storage/StorageUploadCleanupLeaseIntegrationTest.java) |
| Settings | [settings/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/settings/) | [`SystemSettingsServiceImplTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/settings/SystemSettingsServiceImplTest.java)、[`SystemSettingsServiceIntegrationTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/settings/SystemSettingsServiceIntegrationTest.java) |
| PostgreSQL schema | [harness/persistence/postgresql/](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/persistence/postgresql/) | [`PostgresqlSchemaStructureTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/persistence/postgresql/PostgresqlSchemaStructureTest.java)、[`PostgresqlBusinessSchemaTest.java`](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/persistence/postgresql/PostgresqlBusinessSchemaTest.java) |

这些测试覆盖的是当前 application layer 的可观察 contract：CAS、owner lock order、
Project/Issue 调谐、Blob ref 对账、S3 cleanup lease、Provider/Tool admission、
terminal-once、Environment 路由冻结、Canvas resource pin 与 strict codec。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Common](harness-common.md)、
[Harness Runtime](harness-runtime.md)、[Canvas Core](canvas-core.md)、
[Share](share.md)、[Web](web.md)。
