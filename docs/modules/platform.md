# Platform 模块

`platform-runtime` 组合领域端口与外部设施，为 Web 提供 Catalog、Chat、Interaction、Storage、
Environment、Settings 和 Harness 产品用例。它负责跨资源事务、实时配置解析和执行网关；
执行状态机见 [Harness Runtime](harness-runtime.md)，Canvas 事务见
[Canvas Infra](canvas-infra.md)，Issue 工作流见 [Project](project.md)。
构建期 Plugin 的纯 SPI、凭据与资源端口契约独立在 [Platform Plugin API](platform-plugin-api.md)。

[`PlatformAutoConfiguration`](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/PlatformAutoConfiguration.java)
扫描 service、repository 和 JdbcStore。生产组合根是 [Web](web.md)。
PostgreSQL 保存业务事实，内存 registry、执行句柄和通知承担可重建的运行状态。

[`PostgresqlPersistenceConfiguration`](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/persistence/PostgresqlPersistenceConfiguration.java)
提供 PostgreSQL 异常翻译器，Boot 自动配置的 `JdbcTemplate`、`NamedParameterJdbcTemplate` 和
`JdbcClient` 共用它。普通 SQL 使用 `JdbcClient` 命名参数与显式 `RowMapper`；异常翻译固定唯一数据库产品
`PostgreSQL`，保留 Spring 的 SQLState 分类，不在故障期间取连接读取元数据。

## 按用例定位源码

| 目录 | 入口与职责 |
| --- | --- |
| [catalog](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/catalog) | Provider/Model/Agent、Git Skill Package、MCP Server/Tool 的名称寻址与 CAS |
| [harness/model](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/model) | Provider 解析、资源物化、Model admission 与流式网关 |
| [catalog/tool](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/catalog/tool) | `RuntimeToolCatalog` 聚合静态与 MCP 工具，提供工具目录查询 |
| [harness/tool](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/tool) | 执行网关校验冻结绑定并终态化结果 |
| [harness/thread/command](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/thread/command) | `DatabaseTurnResolver` 按当前 branch 的 Agent、Model、Environment 规划 live turn，不反查 Thread 归属 |
| [harness/task](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/task) | Prompt 拼接与子 Agent 分支设置 |
| [harness/read](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/read) | Skill URI、Session 授权 Blob 文本与本地路径路由 |
| [orchestration](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/orchestration) | Chat、Issue+Agent owner 的命令接受、Session 查询与深删除 |
| [interaction](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/interaction) | 人工输入/审批与只读环境等待的统一投影、人工提交 |
| [project/adapter](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/project/adapter)、[project/tool](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/project/tool) | Project 宿主端口、Issue Agent 角色解析与 `issue_transition` |
| [storage](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/storage) | 上传、Blob owner 引用、S3 与耐久对象清理 |
| [environment](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/environment) | Card、注册令牌、路由租约、宿主信息和 Skill 同步 |
| [plugin](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/plugin) | PluginProperties、安全管理面、凭据存储/刷新与资源传输实现；纯 SPI 契约见 [Platform Plugin API](platform-plugin-api.md) |
| [canvas](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/canvas) | Function 输出物化、Blob 访问与媒体处理适配 |
| [settings](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/settings) | 全局设置、严格 codec、编辑 schema、版本快照与运行时现读的全局代理装配 |
| [configsync](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/configsync) | 七类配置的 YAML 读写、依赖闭包与原子导入 |

## 待处理交互

`GET /api/interactions` 合并三类待处理事项：`INPUT`、`APPROVAL` 与只读
`ENVIRONMENT_WAIT`。前两类携带原始 ToolInvocation、Thread 与冻结 ToolCall，
提交始终回写原始调用；环境等待没有调用主键，所有可操作字段显式为 null，不提供确认、
允许或拒绝入口。前端按 `type` 判别，环境等待使用 `(rootThreadId, environmentId)` 去重；
根面板只渲染可操作的人工等待，不重复渲染第二张环境提醒卡，也不伪造可回写的调用。

环境等待不是新的持久状态：只有 READY 调用存在已到领取时间、没有有效执行租约的
环境亲和 TOOL Work，且所需环境没有有效 READY 连接租约时才进入查询。
沿永久父链找到真实执行根，再按根与冻结环境聚合，`waitingCount` 是组内调用数。
分组用最早调用的 `(createTime, id)` 排序，与人工等待做 keyset 归并；`total` 是
同一根过滤条件下的完整可见待处理数，不是当前页长度。无法解析产品归属的根不暴露。

owner 附带 Chat/Issue 标题、根 Thread 名称和 Agent 名；查询内缓存产品与根读取，
显示名称不参与路由或身份判断。工具快照还独立批量读取当前 Work 的
`requiredEnvironmentId`、`requiredEnvironmentName`、`waitingForEnvironment` 和
`environmentWaitFreshnessAt`，不从聚合卡片反推调用。名称沿 Work 冻结身份读取环境目录，
与 Thread 当前环境选择无关；时效边界只取该 READY 环境调用未来的有效 READY 连接租约、
Work 可领取时间和执行租约的最小值，不使用全局交互截止点。非 READY、server-side
或无未来边界时不定时。这四项是读取时 sidecar，不属于 Thread version 所保证的
durable 快照，到期后同 version 重新 GET 会自然变化，无额外持久写入。

数据库 `environment_changed` 同时失效环境与交互查询。租约自然过期没有数据库写事件，
因此交互页附带 `freshnessAt`：未来 READY 环境租约、可领取时间或 Work 执行租约
可能改变等待投影的最早时刻。前端在此时刻加 250ms 宽限后仅安排一次交互查询失效，
续租会取消旧截止点；同一已消费截止点不重新排任务，不使用浏览器轮询或新增通知通道。

## Catalog

Provider 与 Agent 以不可变 `name` 寻址；Model 以 `(providerName, name)` 寻址，允许改名。
变更携带 `expectedVersion`。Model 重命名在同一事务内插入新行、更新 Agent 引用、删除旧行，
任何 CAS 或引用校验失败整体回滚。删除前校验入边，数据库外键提供并发兜底。

Provider credential 在显式写入口接收，常规响应给出安全投影；配置同步导出显式包含原值。运行时类型取自
[`ProviderType`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderType.java)：
`openai`、`openai_responses`、`anthropic`、`google`，按精确 wire value 解析。
Model 配置保存 limit、abilities、variants、pricing 和 default variant。
Agent 配置保存工具名、`SkillRef(packageName, name)`、subagents 与 `inheritParentEnvironment`
（默认 true）；工具候选和 Skill 引用在写入时校验。

Agent 定义另有系统拥有的不可变 `type`（`USER` / `BUILTIN`），只出现在读 DTO，可编辑的创建/PUT 请求不接受它；普通用户不能创建或提升内置定义。首个内置定义是普通目录中可见的 `Compaction`：名称与存在由系统拥有，用户可编辑其 prompt、model、tools、skills 与普通执行配置，但不能删除或改名；内置定义允许显式未配置模型（独立选择，不继承调用 Thread 的模型，也不静默 fallback），用户创建的 Agent 仍要求模型。启动初始化按 add-if-missing 只创建一次（默认 summarization prompt、无 tools/skills/subagents），配置导入导出排除内置定义，既不覆盖用户编辑也不降级。

### Git Skill Package

每个 Package 保存不可变 `packageName`、repository URL，以及 branch、`currentCommit`、
`observedHeadCommit`、检查结果和从当前 commit 派生的 Skill 列表。
根目录 `<name>/SKILL.md` 的 frontmatter 必须满足名称与目录一致、description 非空且有界；
路径和符号链接保持在仓库树内。正文和附属文件由 Git 保存。

创建发布当时 branch 的 exact HEAD；Check 只更新观察事实。Update 要求
`targetCommit + expectedVersion`，target 必须等于该 Card 快照的 observed commit，
校验全部 Skill 与 Agent 引用后原子替换 current commit 和 Skill 列表。
检查失败保留已发布内容。状态由观察事实派生为 `UNCHECKED`、`UP_TO_DATE`、
`UPDATE_AVAILABLE` 或 `CHECK_FAILED`。

稳定路径为 `kkstudio:/skills/<package>/<skill>/...`，每次读取 Package 当前 commit。
Git cache 由 Catalog 拥有，根目录通过 `kk-studio.catalog.skill.cache-root` 配置，
默认是进程当前目录下的 `.kkstudio/skills`。
发布后的通知唤醒各节点回读、补齐 exact commit cache，并同步本节点在线 Daemon；
listener 建连/重连全量对账。Daemon installed commit 与 current commit 一致时，Prompt
使用其本地稳定路径，否则使用 Platform URI。

### MCP server 与运行时工具目录

Server 以不可变 name 寻址。常规 Card 返回 enabled、timeout、发现状态、工具数和版本；
URL 与 headers 仅在显式配置查询中以 `no-store` 返回，变量占位符保持原样。
发现先在事务外完成 HTTP 握手、`tools/list` 与 schema 校验，再锁 server、校验版本、
原子替换该 server 的工具行；失败保留整批旧目录，Agent 引用保护仍生效。

[`RuntimeToolCatalogConfiguration`](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/catalog/tool/RuntimeToolCatalogConfiguration.java)
发布 `RuntimeToolCatalogSource` 与动态 `McpToolCatalog`。每次 current 或历史 epoch 租用
钉住 `PluginCatalogRuntime` 的同版本快照，同时一次读取 MCP 的 planning 与 selectable 事实，
冻结为不可变目录；后续查找不再读库。Harness 插件来源来自该快照的 owner map、配置及凭据身份。
重复模型可见工具名使查询失败关闭。MCP 可选面要求 server enabled 且 AVAILABLE；
查找面按已持久化工具行返回定义，调用前再复验 server 状态与归属。
远端 MCP 工具使用 `EnvironmentSupport.NONE`、`NON_IDEMPOTENT`，deadline 覆盖初始化和调用。
客户端行为见 [Harness MCP](harness-mcp.md)。

执行网关按 binding 的 epoch 取历史租用，逐项校验完整定义、贡献身份、requirements 与插件来源；
不会按当前同名工具替换旧执行语义。MCP 没有独立历史快照，旧 binding 与现读定义不一致时拒绝执行。
成功启动后，目录租用持续到终态或取消决议、执行体退出及句柄静止同时成立；
取消先于启动时不进入工具执行体。违反静止契约的句柄产生 UNKNOWN，无法确认静止时保留资源租用。

## 命令接受、产品归属与派发

[`HarnessCommandAcceptanceOrchestrator`](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/orchestration/HarnessCommandAcceptanceOrchestrator.java)
在同一物理事务内完成命令接受：`accept` 只服务 `NEW_SESSION` / `NEW_THREAD` / `NEW_FORKED_SESSION` 创建型 owner batch（owner 授权 + Session 归属 + READY 附件引用转移；`NEW_FORKED_SESSION` 还要求 owner 持有来源 Thread 所属 Session，Issue agent 会话一律拒绝）；`acceptOnThread` 服务既有 Thread 的 owner-free 续写（path threadId + 精确 cursor），只做附件物化与目标 Session 归属校验，不按 owner 伪造或拒绝。精确重放仍执行创建型 owner 授权，但复用已接受事实。接受使用 owner KEY SHARE，深删除使用排他锁并按 Owner → Session → Thread 清理 Harness 与 Blob 引用。

Issue+Agent 的命令由 Issue 业务工作流拥有，公共 batch 端点拒绝 `ISSUE_AGENT` owner。Run 接受时显式提交 Agent/Model/Environment、`project`/`run` contributor state 与末尾任务输入；后续 turn 直接从冻结的 branch scope 取得 Run 身份，不再反查 Thread 归属。`issue_transition` 在业务锁内校验冻结 scope 的 Run/Issue/阶段/版本与调用 Thread 身份。问卷和审批复用 Runtime 的等待行，由 Interaction service 加入产品来源与人工操作者。

Runtime 在自己的 lease 所有权短事务内直接持久 `READY -> DISPATCHING`，提交之后才调用 Provider / Tool Gateway，不反查 owner、Issue 归属或阶段等产品事实，也不存在产品侧派发门禁。需要终止在途 Run 时由业务显式调用 Stop；工具权限、preflight / 审批 / YOLO 与 Gateway 容量准入仍在各自边界生效。

## Model 与 Tool 执行

`DatabaseTurnResolver` 每个 live turn 持有一次 current epoch 租用完成全部规划，只按该分支冻结/提交的 Agent、Model、Variant、
Environment、工具、Skill 和 subagents 规划；它不判断 Thread 属于哪个 Issue/Chat，也不据此覆写
Environment 或注入工具——Issue Run 的 Agent/Model/Environment、业务工具与身份上下文都在接受 Run 时
显式提交或冻结到 branch state。缺失引用返回规划失败。
`NONE`、`OPTIONAL` 工具保留，未选环境时过滤 `REQUIRED`；配置 Skill 时加入统一 `read`，
subagent allowlist 非空时加入内部 `task`。Prompt 的 Skill 三元组冻结在 system instruction 中。规划期同时按 live provider 配置冻结 `ProviderCacheControl`（retention 与会话 UUID key，压缩回合固定 `none()`），执行期不再按当前 capability 重新规范化。

网关先做资源解析与无等待 admission，再返回门控 `Started(handle)`。
Runtime 持久化 RUNNING 后调用 activate；Busy/RetryLater 表示确定未启动，允许重排，
Indeterminate 表示结果不确定，进入 UNKNOWN。Tool 网关按完整冻结定义、贡献归属和
requirements 复验目录；有效超时仅经 `Tool.resolveTimeout` 解析一次并原样传递。
回调 FIFO、有界队列与终态围栏保证迟到信号被丢弃，具体状态转换见
[Harness Runtime](harness-runtime.md)。

媒体物化将模型模态、adapter 能力和权威 MIME 取交集。Blob 转换为 attempt-only Base64；
原始文件上限 100 MiB，请求累计 data URI 上限 160 MiB。图片按冻结的
`720P`、`1080P`、`ORIGINAL` 档位处理，方向取 EXIF，动画/多帧、超过 4000 万像素或
类型与字节不符直接失败。媒体模态在 TOOL 与 USER 位置都不支持时对全部模态显式失败；仅 USER 普通内容位置的非图片保留说明文本回退。
原生推理回放与协议请求上限见 [Harness Provider](harness-provider.md)。

统一 `read` 按 path 路由。受管 Blob 必须由本次 Thread 所属 Session 持有引用；权威 MIME 为 `image/*`/`audio/*`/`video/*`/`application/pdf` 时走媒体路径：在读取任何内容前，用本次调用冻结的模型模态声明、adapter 合法位置与 MIME 及内联预算校验，不支持则明确失败；通过后返回指向同一已授权 Blob 的真实媒体（不是上传成功文本），由 Provider 物化进模型。读取预算覆盖 S3 握手和响应体，流式窗口见 [Harness Common](harness-common.md#字符流窗口)。本地路径交给 BoundEnvironment，URI 路径拒绝 workdir。

结构化 Debug 区分 `NEXT_REQUEST_PREVIEW` 与活动 `FROZEN_INVOCATION`，排除 credential
与 Base64 正文。请求预览有三个只读入口（既有 Thread 续写、本地分支草稿、历史模型输出），
都在同一条正式规划、物化与编码路径上生成点击瞬间的请求体（可能含内联媒体），
以只读方式检查附件，不写任何内部事实、不消费附件、不调用 transport；
历史入口按该输出记录的显式时间重建普通模型输出；父 COMPACTION 输出以 `PREVIEW_UNSUPPORTED` 拒绝（其真实请求属于压缩子 Thread），结果不等于原始发送字节。
入口、`kind` 与冲突契约见 [Web](web.md)。

用量与费用只在读取时投影：durable 只保存 ASSISTANT `MESSAGE` 与 COMPACTION 结果的真实 `assistantMetadata`（`stopReason` / `usage` / 可选 `decodeDurationMillis`），绝不持久化金额。`UsageCostProjectionService` 沿 Entry 父链取最近一次调用的 `ModelSelection`（压缩用 `TURN_START.compaction().executionModel`，否则该 Turn 的 branch settings，未进入 Turn 回落 ROOT），按当前 catalog pricing 用纯 `BigDecimal` 现算并输出精确十进制字符串 `amount`；模型已删除则费用缺席，定义损坏则抛错，绝不伪装成 0。同一投影内每个出现过的模型至多查一次 catalog。

## Storage、Blob 与 Resource

[`HarnessResourceConfiguration`](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/configuration/HarnessResourceConfiguration.java)
在 Harness 边界装配 Provider 与 Tool history 的资源物化器，消费全局 Storage 服务；
Storage 不 import Harness，存储设施与执行资源边界各自装配。

`storage_blob` 按 ACTIVE 内容的 `(sha256, size_bytes)` 去重；
upload、Session、Canvas 和 Issue Evidence 通过各自 owner 边持有引用。
retain/release 加入调用方事务；引用减至零时切为 DELETING，后台清理 preview、original 和行。

上传使用 reserve → 浏览器 PUT → complete。complete 在专用会话级 upload advisory lock
内做事务外 checksum HEAD、媒体 probe 与 copy，再短事务绑定 Blob。
服务端 `stage` 有界落盘、计算摘要，复用上传生命周期。清理先取得同一操作锁、
登记耐久对象 key，再在事务外删除，避免与 complete/stage 的对象写入交错。

`storage_object_cleanup` 永久保存删除记录，按数据库时间领取、提前安排下一次尝试，
失败短重试，成功低频再次删除。它提供迟到写入后的最终清理；对象存储写入本身仍可能发生，
该保证不是存储侧写入围栏。

Tool 结果先经过无副作用 plan 与硬限校验，再外部化 Binary/Resource。
普通文本内联预算 50 KiB / 2000 行，可信内置 read 为 320 KiB / 2020 行。
history materializer 在 mandatory transaction 中锁 READY upload、复验权威媒体事实、
retain Session 引用并删除 upload owner，整体回滚保留可回收上传。
Daemon 字节走对象存储直传，控制协议见 [Harness Environment](harness-environment.md)。

## Environment

Card 的 name 不可变，UUID 用于路由；`environment_connection` 以
`environmentId + ownerNodeId + leaseToken` 围栏保存当前路由和租约。
READY 更新宿主 OS、时区、进程用户、HOME、note 与**实际 daemon 构建版本**（`daemonVersion`，取自运行中
JAR manifest，未打包时为 `development`），断线保留最后已接受的 metadata；`daemonVersion`
与 Card 的 CAS 配置版本是两个不同事实。
Skill 同步结果按 owner/lease fence 写回，运维窗口保存有界、去敏事件。

Platform 适配注册、租约和资源票据；会话代际、在途调用和重连裁决由
[Harness Environment Server](harness-environment-server.md) 拥有。
同实例重连依赖 Gateway 在途记录和 Daemon 进程内 journal；新实例接管使旧调用进入
结果不确定，恢复保证止于这两个进程内记录的生命周期。

## 构建期 Plugin

选中的 Plugin JAR 由 [`web/pom.xml`](../../web/pom.xml) 的 runtime dependency 进入发行物，
通过 `AutoConfiguration.imports` 创建 bean。`StudioPluginRegistry` 冻结唯一 pluginId；
`StudioPlugin` 声明身份、配置 Schema 与认证协议，`configure` 产出持有资源的
`PluginInstance`，按需提供独立的 Harness Contributor / Canvas Adapter，两域可为空。
安装集合随发行物构建确定；`StudioPlugin`、安装目录以及凭据与资源端口的纯契约见
[Platform Plugin API](platform-plugin-api.md)。

`PluginCatalogSnapshot` 逐个配置实例，聚合内置贡献，并在返回前验证两域完整目录；
任一配置、贡献或目录失败时释放全部已建实例。Web 从同一快照提供两域 Catalog，
配置由权威 manifest 指定，每个 epoch 内目录冻结，运行期通过完整快照原子切换。

### Plugin 配置存储

[`PluginCatalogBootstrap`](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/plugin/catalog/PluginCatalogBootstrap.java)
从单行 `plugin_catalog_state` 读取当前 epoch 指针，再按该 epoch 读取不可变完整 manifest
（`plugin_catalog_revision`）与其中 revision 指定的 `plugin_configuration_revision`。
全新库的 epoch 0 只表示未初始化：
宿主以所有安装定义的 Schema 默认值构建完整候选，通过 CAS 写入 epoch 1 与配置历史；
首次启动的竞争败方释放候选、回读赢家，不重复初始化。

配置 revision 取该插件最后一次写入的全局 epoch，不另设插件计数器。
[`PluginConfigurationRepository`](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/plugin/persistence/PluginConfigurationRepository.java)
在同一短事务中核验权威基线、用一条语句同时推进 epoch 指针并新建该 epoch 的 manifest 历史、
插入配置历史、复核完整 manifest 引用，
最后发出 `plugin_catalog_changed` 提示。未修改条目必须与基线相同，不能丢失已登记插件或回退到旧 revision；
任何失败都回滚 epoch、历史与通知，CAS 败方不留下 manifest 或配置历史。

普通值以受 64 KiB UTF-8 预算约束的严格 JSON 文本保存，数据库同时拒绝非对象与重复键；
不经 jsonb 数值展开，精度、scale 与指数表示保持可恢复。秘密不进入普通配置历史。
manifest 保存代码版本、Schema 与实际能力指纹，全局指纹覆盖完整两域目录及内置贡献；
指纹只由冻结定义派生，不序列化运行实例。

重启必须按持久版本恢复：安装集合、历史引用、代码/Schema 身份及两域能力指纹均须一致，
缺失或漂移直接启动失败，不采用新 defaults 替换旧配置。

[`PluginCatalogRuntime`](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/plugin/catalog/PluginCatalogRuntime.java)
持有当前已发布快照，并把最近一次权威回读的 `desiredEpoch`、已发布 `appliedEpoch` 与固定受控错误码报给节点。
已持久接受但尚未执行完的任务用 `acquire(epoch)` 取该 epoch 的完整两域目录：已加载的当前视图或仍被租用钉住的
历史视图直接复用；未加载时按该 epoch 的不可变完整 manifest 与配置在刷新锁内严格重建一次，
因此绝不把当前其它插件拼进旧任务，也不修改 desired/applied、不发布旧 epoch。历史视图只由租约持有，
最后一个租约释放即移除并关闭，重启后旧 epoch 仍按持久历史重建。
配置写入接口以 expectedEpoch 和 expectedRevision 做 CAS，普通值、秘密变更及 manifest
在同一事务中提交。唯一 afterCommit 同步始终唤醒本节点目录对账，仅秘密写入或删除时
额外唤醒凭据刷新调度器；KEEP 不唤醒凭据调度器，回滚不触发任何唤醒。
Web 合并通知、重连提示与每 5 秒的权威回读，应用失败时保留整个旧视图。
管理面一次投影先取得当前 applied 租用，持有到完整 DTO 构造结束，再将权威 desired
与该租用的 applied 坐标一起报告；仅当运行期错误状态的两端 epoch 都匹配时暴露
`authority-unavailable` 或 `apply-failed`，不匹配或尚未应用时报告 pending。
数据库提交提示不代表所有节点已经应用。

`PluginCanvasFunctionCatalogSource` 将 current/history 租用直接绑定到该运行期。
函数绑定的 epoch、完整定义与插件来源必须逐项匹配，内置函数来源为 null；
来源中的能力指纹、配置修订及认证引用只取自同一冻结快照，不读取凭据内容。
失败即释放租用，关闭幂等且关闭后不可访问，不按同名函数回退到当前版本。

### 新增 Plugin

在 [`plugins`](../../plugins/pom.xml) 下创建独立模块，并在根
[`pom.xml`](../../pom.xml) 管理版本。实现安装定义与配置实例，Schema 是普通值和默认值的唯一来源；
用单一 Boot 自动配置入口注册定义，实例拥有自己的执行器与生命周期；
网络访问与凭据读取在调用期执行，插件只能通过 scoped Context 读取本插件凭据。
工具按 [Contributor API](harness-contributor-api.md#扩展一个-contributor) 注册，
声明真实 side effect、timeout 和 EnvironmentSupport。最后在 Web 中选择 runtime 依赖，
验证 bean 装配、工具 schema、去敏、失败/取消/超时与资源预算。

管理协议为 `/api/plugins` 的列表/详情、配置写入和认证操作。
配置 Schema 与安全秘密投影由插件声明派生；秘密仅支持 KEEP、SET、CLEAR，不回读明文。
无认证插件不提供登录操作；STATIC_KEY 通过配置接口更新密钥。
DEEP_LINK 的 prepare 请求为 `{region}`，返回 loginUrl 与单次 authAttemptId；
complete 请求为 `{authAttemptId, callbackUrl}`，仅消费仍匹配当前配置及认证身份的未过期尝试。
认证响应 no-store。新增交互需同步 Share DTO、前端和测试；管理 UI 见
[Plugin 设置](frontend.md#plugin-设置)。

### Plugin credential

`PluginCredentialStore` 用部署级 owner-only 32-byte key 做 AES-256-GCM 加密，
每次写入新 nonce，AAD 绑定 pluginId、认证代际、上下文指纹、region 的存在性和值及格式版本（当前 fmt2）。
登录、秘密 SET/CLEAR 和认证目标变更会换发认证代际；普通配置 KEEP 不改凭据行，
刷新只沿用当前行代际。静态密钥没有 region、过期或刷新时刻，不参与刷新扫描。
代际随之写入快照、加密 AAD 与所有 CAS 条件，因此断连删除后按 version 0 重建的行
不会被旧调用或迟到 finalize 误伤。主密钥留在部署文件，
Plugin 只通过绑定认证引用的 `PluginCredentialAccess` 获得本次请求快照，不持有完整 Store。
每次解析都回读权威 manifest 并核对凭据行身份，旧代际或旧上下文不能取得新秘密。
key 缺失或解密失败为 KEY_UNAVAILABLE，读路径不返回未认证载荷；主密钥不可用时禁止写入。
Plugin fmt1 密文不再兼容读取，已有凭据须在备份并批准迁移后离线转换。
Skill 令牌共用 AES-GCM 与 AAD 编码原语，但独立保持既有 fmt1 与 Package 身份绑定，不引入插件认证代际。

刷新以短事务 claim lease，请求前核验代际、token、version 与有效截止时刻，
事务外只执行一次 renewal，再按代际、token、version finalize。
只扫描已声明刷新器的插件，按最早 nextRefreshAt 安排，保存后唤醒，poll 是最慢兜底。
确定认证拒绝为 REAUTH_REQUIRED，确定未发或确定无效响应为 REFRESH_FAILED；
已发而无确定响应、过期在途 lease 为 REFRESH_UNCERTAIN，要求重新认证。
lease 只保证互斥，无法证明外部 exactly-once；不确定 renewal 保留停止自动重放的边界。
调用期作废仅按本次快照 CAS，保护并发重新登录；普通 403 权限错误保持独立分类。

### Plugin 资源端口

`PluginResourceGateway.resolveSessionResource` 从 invocation threadId 解析 Session 引用，
授权成功后签发短期 HTTPS 下载地址，缺上下文或引用直接失败。
`stageRemoteMedia` 只接纳 HTTPS 公网地址，检查所有 DNS 结果并钉扎连接地址，
保持原 Host/SNI/证书校验；拒绝重定向和自动重试。有界流式落盘计算摘要并嗅探类型，
校验媒体族后上传为 READY ResourceRef，失败删除临时文件并回收上传。

MiniMax Mavis 使用固定 CN/EN origin，以 capability catalog 验证 callback token，
未验签 JWT claim 仅约束本地期限。静态工具 schema 保持稳定，调用期解析当前 endpoint。
生成类为 NON_IDEMPOTENT，不确定结果保留失败而停止自动重放；部分批量结果逐项表达。

## Canvas 媒体与 Function 适配

Platform 提供 Blob facts、原件流、presign、输出物化和 Blob 释放适配。
媒体输出在 `stage` 前后分别执行短事务，按 document → run 加锁，复验
`canvasId/nodeId/requestId/leaseToken` 对应当前 RUNNING owner，且租约在取得行锁后
按数据库 `clock_timestamp()` 判定未过期。读流与 S3 stage 不持有数据库锁；
幂等查询也必须先通过 owner 校验。后置事务插入无 owner Resource、写 OUTPUT pin、
转移 upload 引用；旧 attempt 或事务失败会 best-effort 清理未消费 staged upload，
上传过期回收作为兜底。TEXT 输出内联且有长度上限。整组成功挂接、pin 生命周期和 UNKNOWN
决议见 [Canvas Infra](canvas-infra.md)。

Platform 仅提供内置 fake adapter；本地 image.crop、ComfyUI/H3 以及 OpenCLI（`gpt-image-2` 与 Seedance 四函数）均由构建期 Plugin 提供。
Function Catalog 在启动时冻结，adapter 的 submit 记录外部任务身份，
execute 查询同一任务并物化预分配槽位。

OpenCLI 作为独立构建期 Plugin，每次 HTTP send 前解析局部单 Bearer 凭据并冻结单调时钟 deadline，同一预算覆盖握手和完整响应体读取，
不会随 read 重置。资源响应要求正数 Content-Length 与 metadata size 一致，并在读流时
拒绝短读和超长。响应体 watchdog 到期主动关闭流，关闭在独立线程执行以免阻塞共享调度器；
EOF、失败和显式 close 均注销 watchdog。客户端拿到流后负责关闭；JDK 若在交付响应前
拒绝非法 header，此路径无客户端可关闭的流，只返回不回显 header 原值的安全错误。
非 2xx 响应、未知 execution/asset 状态与提交失败只暴露固定分类、HTTP 状态和阶段名；
远端 body、status、URL/header 以及底层 IO/Jackson/URI 异常的 message/cause/suppressed
都不进入公开异常与诊断日志，诊断只记录受控阶段与异常类型。

### OpenCLI 插件配置与凭据契约

OpenCLI 作为构建期独立 Plugin，其配置完全归属于自身受控 schema，已彻底删除旧 SystemSettings 的
`openCliHub`、`gptImage2` 与 `seedance` 三段配置及部署环境变量属性，无双读与别名兼容；写入或导入旧字段均被明确拒绝。

- 顶层配置字段与默认值：`enabled`(false)、`baseUrl`(可省略)、`connectTimeoutMillis`(5000)、`requestTimeoutMillis`(120000)、`longPollTimeoutMillis`(130000)、`streamBufferBytes`(16384)、`maxJsonResponseBytes`(524288)、`maxErrorResponseBytes`(4096)、`maxOutputChars`(65535)。
- `gptImage2` 对象：`paidEnabled`(false)、`askTimeoutSeconds`(900)、`hubExecutionTimeoutMillis`(960000)、`maxWaitMillis`(1200000)。
- `seedance` 对象：`enabled`(false)、`workspaceId`(可省略)、`retry`(0)、`hubExecutionTimeoutMillis`(600000)、`statusPollIntervalMillis`(30000)、`maxWaitMillis`(1800000)。
- 字段校验沿用合法 HTTP origin、开启状态下的必填项校验以及私有身份的 1..36 字符约束；未配置文本通过省略字段表示，不引入 nullable 或以空字符串伪造合法 URL。
- schema 是默认值的唯一来源：插件不再自带第二份默认值常量。
- 唯一认证密钥 `apiKey`，通过 `PluginAuthSpec.StaticKey("apiKey", Set.of("baseUrl"))` 声明，走统一 Plugin Secret API（KEEP/SET/CLEAR）管理，不读环境变量或设置外部优先级。每次 HTTP 发送时动态解析并仅在局部构造单 Bearer 请求头，不落盘明文且不泄露在日志与异常中。
- 可选私有身份 `instanceId` 是第二个受保护秘密字段：它与 `apiKey` 同属一次 scoped snapshot，只在真正发送执行请求前取出并填入请求体，不出现于普通值、配置历史、管理面投影或导出；未配置时不发送该字段，由 Hub 自由路由。
- 静态认证的 `CONNECTED` 只由必需认证字段是否已录入决定：仅录入私有身份（或撤销 `apiKey` 后只留私有身份）时管理面显示 `NOT_CONNECTED`，`KEY_UNAVAILABLE` 等真实故障仍如实上报。

## 配置

### 配置同步

`ConfigSyncService` 提供条目清单、选择导出、导入检查与 YAML 导入。清单和检查结果不含配置值；
导出在单一只读数据库快照中沿依赖边补齐选择，循环 Subagent 去重，不做反向扩展。
YAML 使用业务名称寻址，只保存可编辑字段，显式包含 Provider 凭据、Environment 注册令牌、Skill Package 私有仓库访问令牌与保存的安装设置，以及 MCP headers。
运行态、数据库身份、版本和时间戳不进入文件。

导入检查复用写入路径的字段与 codec 校验，在一次计划快照上分类新增、覆盖和跳过，不写数据库。
未知或不支持内容、依赖不满足和外部准备失败可明确跳过；必填缺失、结构/类型/有效值错误、
Environment 令牌身份冲突、Skill 仓库身份变化和移除仍被引用的 Skill/MCP 工具整份拒绝。
Environment 的 `installConfig` 在未知字段跳过判断之前就用同一 codec 深度校验，已知非法设置不会被
条目级跳过；条目省略该键表示清空现有安装设置。
Agent Variant 按导入后将生效的 Model 配置检查，文件内同名 Model 优先；未知 Variant 整份拒绝。
Skill exact commit 获取、manifest 扫描和 MCP 发现先在写事务外完成。

执行重新生成计划，按名称新增或更新，不删除文件外配置；有跳过项而未显式 `allowPartial=true`
时拒绝写入。Environment 的注册令牌与安装设置在同一次 CAS 更新中生效，同名更新保持其 UUID 身份。
预览不冻结状态，执行时同名配置以文件为准，不维护跨步骤检查状态。
可恢复配置在一个事务中写入，复用现有校验、引用保护和 CAS；循环 Agent 用同事务两阶段写入。
系统设置按当前完整七节契约直接校验，不合并局部字段；以执行准备时版本 CAS，提交后沿现有通知刷新快照。
产品操作、恢复范围与保密要求见[配置导入与导出](../operations/development-and-testing.md#配置导入与导出)。

### 在线设置与启动配置

在线设置由单行 `system_setting` 保存，包含 tool、aiRuntime、environment、network、
integrations、storageMedia、advanced 七个 section。strict codec 与 record 校验完整聚合，
`expectedVersion` CAS 后提交通知驱动权威回读，内存快照按 version 替换。
`SystemSettingsSchemaProvider` 提供 UI 编辑 metadata。
aiRuntime 包含 `modelHttpRetryStatusCodes`（唯一可重试 HTTP 状态名单，默认 `[408,429,500,502,503,504]`，仅接受 400–599）、调用重试次数与退避、压缩保留量与 subagent 限额；Provider 可用同名字段覆盖该名单（null 继承、数组完全替代、空数组禁用 HTTP 重试），压缩执行使用普通 Agent 目录中的 `Compaction` 定义，不在 settings 里选择。
storageMedia 持有临时资源保留期与扫描间隔（默认 3 天 / 30 分钟，热更经心跳通道作用于尚未回收的 Daemon workspace）、上传有效期与下载签名默认值。

network 提供唯一的 Backend 全局 HTTP 代理：`proxyUrl` 为无认证的 `http://host:port`，
null 表示强制直连；`noProxyHosts` 为逗号分隔绕过规则，默认 `localhost,127.*,::1`，
支持主机、域名后缀、IP、可选端口、`*` 与 IPv4/IPv6 CIDR（CIDR 只按目标 URL 中的数值 IP
逐位匹配，不为域名解析 DNS）。
代理选择在每次请求时现读内存快照：保存后新发起的 HTTP 请求立即使用新路由，正在执行的请求
由其自身连接与响应流继续完成，不受影响；模型、Git、MCP、集成、媒体与 S3 统一使用该策略，
无模块覆盖，也不回退宿主代理环境。媒体 CONNECT 仍固定到已校验的公网 IP，Host/SNI 与证书
校验保留原域名。Daemon 与浏览器的网络策略独立。

部署级设置由各 `@ConfigurationProperties` 定义，进程启动时装配：

| 前缀 | 用途 |
| --- | --- |
| `kk-studio.harness.dispatcher` | claim/handoff 容量、lease、poll 与拒绝 |
| `kk-studio.harness.execution-admission` | model/tool/skill-sync 本机并发 |
| `kk-studio.harness.runtime` | worker 开关与 Resource 根 |
| `kk-studio.catalog.skill` | Git Skill cache 根（`cache-root`） |
| `kk-studio.project.controller` | Issue dispatcher、worker、预算和调谐节奏 |
| `kk-studio.storage` | S3 端点/凭据/bucket、maintenance 与对象清理 |
| `kk-studio.plugins` | 主密钥文件、refresh 与远端媒体预算 |
| `kk-studio.canvas` | ffmpeg/ffprobe、本地路径和 Function runtime |
| `kk-studio.harness.environment-gateway` | Daemon 帧、出站队列和发送预算，由 Web 拥有 |

默认值和完整字段以对应配置类及
[`SystemSettings`](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/settings/SystemSettings.java)
为准；部署输入与 secret 管理见 [部署与运行](../operations/deployment.md)。

## 测试入口

[`platform/runtime/src/test`](../../platform/runtime/src/test/java/fun/fengwk/kkstudio/platform) 按上述子域组织：
架构测试检查依赖方向，Catalog/Settings 检查 CAS 与 codec，
orchestration/Project 检查 owner 事务与锁序，gateway 检查 admission、门控和终态，
storage 检查引用转移、对象清理与回滚，environment 检查路由围栏与同步。
真实设施测试基座、执行命令和覆盖率入口见 [开发与测试](../operations/development-and-testing.md)。

上级：[系统设计](../system-design.md)。相关文档：[Web](web.md)、[Share](share.md)、
[Schema](schema.md)、[Harness Runtime](harness-runtime.md)、[Canvas Core](canvas-core.md)。
