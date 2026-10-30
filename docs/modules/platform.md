# Platform 模块

`platform-runtime` 组合领域端口与外部设施，为 Web 提供 Catalog、Chat、Interaction、Storage、
Environment、Settings 和 Harness 产品用例。它负责跨资源事务、实时配置解析和执行网关；
执行状态机见 [Harness Runtime](harness-runtime.md)。
[Canvas](canvas.md)、[Project](project.md) 与 [IDE](ide.md) 是建立在平台之上的应用，平台从不依赖它们；
平台需要应用参与的地方由平台定义 SPI、应用实现（见[命令接受、产品归属与派发](#命令接受产品归属与派发)）。
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
| [catalog](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/catalog) | Provider/Model/Agent、Git Skill Package、MCP Server/Tool 的名称寻址与 CAS；每个实体一个包，只有 record、`JdbcClient` Repository（SQL、行锁与 CAS）和 Service（校验、引用保护与 DTO 投影）三件 |
| [harness/model](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/model) | Provider 解析、资源物化、Model admission 与流式网关 |
| [catalog/tool](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/catalog/tool) | `RuntimeToolCatalog` 聚合静态与 MCP 工具，提供工具目录查询 |
| [catalog/mcp/client](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/catalog/mcp/client) | MCP Streamable HTTP client：单一 deadline、调用级取消与结果映射 |
| [harness/tool](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/tool) | 执行网关校验冻结绑定并终态化结果 |
| [harness/thread/command](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/thread/command) | `DatabaseTurnResolver` 按当前 branch 的 Agent、Model、Environment 规划 live turn，不反查 Thread 归属 |
| [harness/realtime](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/realtime) | 流式追帧：durable 快照 + active owner attempt fence，本地优先、跨节点单次严格 envelope |
| [harness/task](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/task) | Prompt 拼接与子 Agent 分支设置 |
| [harness/read](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/read) | Skill URI、Session 授权 Blob 文本、本地路径路由与文本窗口 |
| [agenttask](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/agenttask) | 子代理与独立任务的票据接受、观察与停止；外部任务由外部字段规范计算指纹，settings 不参与重放判定，既有票据重放不触发物化 |
| [orchestration](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/orchestration) | Chat 与 Issue Agent owner 的命令接受、Session 查询与深删除；Issue Agent 归属经 `IssueAgentOwnership` SPI 由 Project 提供 |
| [interaction](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/interaction) | 人工输入/审批与只读环境等待的统一投影、人工提交 |
| [storage](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/storage) | 上传、Blob owner 引用、S3、媒体探针与预览、耐久对象清理 |
| [environment](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/environment) | Card、注册令牌、路由租约、宿主信息和 Skill 同步 |
| [plugin](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/plugin) | PluginProperties、安全管理面、凭据存储/刷新与资源传输实现；纯 SPI 契约见 [Platform Plugin API](platform-plugin-api.md) |
| [settings](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/settings) | 全局设置、严格 codec、编辑 schema、版本快照与运行时现读的全局代理装配 |
| [configsync](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/configsync) | 八类配置的 YAML 读写、依赖闭包与原子导入 |

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
同一根过滤条件下的完整可见待处理数，不是当前页长度。产品归属按根解析为 `CHAT`、`ISSUE_AGENT`，
或控制器/插件发起、没有产品页面的独立任务 `AGENT_TASK`（带 Agent 名与根 Thread 名，只在交互中心处理）；
其余无法解析归属的根不暴露。

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

Provider 与 Agent 以不可变 `name` 寻址。MODEL 与 MODEL GROUP 共用全局唯一的逻辑 `name`
命名空间：MODEL 另以 `providerName` 指向 Provider，并用独立的 `modelId` 保存发往上游的 wire
模型标识；GROUP 只保存有序成员、`fallbackStatusCodes` 与 `cooldownSeconds`，不得携带
`providerName`、`modelId` 或模型 `config`。两类资源都通过 `/api/models` 创建和查询，
PUT/DELETE 使用 `/api/models/{*modelName}`；名称可以包含斜杠，客户端必须把逻辑名作为
路径 tail 按 `/` 分段编码，保留层级分隔。变更携带 `expectedVersion`。PUT 通过请求体 `name` 支持改名，同一事务更新
引用，任何 CAS、类型变更或引用校验失败整体回滚。删除 MODEL 前校验 Agent 与 GROUP 入边，
删除 GROUP 前校验 Agent 入边，数据库外键提供并发兜底。

GROUP 的成员顺序就是 Fill First 顺序，成员只能是 MODEL 且其 Variant 必须已在该 MODEL 声明；
禁止重复成员和嵌套组。`fallbackStatusCodes` 是必需但可为空的 HTTP 400–599 集合，元素去重；
`cooldownSeconds` 是必需的非负整数，0 表示仅本次逻辑调用继续下一成员、不写跨调用冷却。
冻结的组版本隔离成员列表与冷却状态，调用开始后的组更新不混入本次调用。GROUP 的公开
`config` 只是成员能力的只读派生摘要：上下文/输出取最小值（输出再受上下文约束），工具与推理
能力取与、输入模态取交集；它不包含组级 pricing、variants 或 defaultVariant。
Agent 被其它 Agent 的 subagents、插件普通配置或未归档 Project 的工作流阶段引用时不能删除；Chat 只按名称软引用 Agent，
删除后其下一回合确定性失败，同名重建即恢复。

Provider credential 在显式写入口接收，常规响应给出安全投影；配置同步导出显式包含原值。运行时类型取自
[`ProviderType`](../../harness/model/src/main/java/fun/fengwk/kkstudio/harness/model/provider/ProviderType.java)：
`openai`、`openai_responses`、`anthropic`、`google`，按精确 wire value 解析。
`GET /api/providers/{name}/remote-models` 用已保存的 baseUrl 与 credential 读取 Provider 的
模型列表（[`ProviderModelDiscovery`](../../harness/model/src/main/java/fun/fengwk/kkstudio/harness/provider/ProviderModelDiscovery.java)），
返回去重排序的模型 ID，只用于添加模型时预填，不写入 Catalog；请求不在数据库事务内，缺少 baseUrl 为校验错误，
上游不可达或非 2xx 返回 502 `upstream_failed`，detail 带上游状态与截断正文。
MODEL 配置保存 limit、abilities、variants、pricing 和 default variant。
`pricing` 固定为 `{base, overrides, multipliers}`：所有价格与倍率在 wire 上都是十进制字符串，
单位固定为 USD / 每百万 token；普通基础价缺省归一为 0，倍率缺省为 1，`reasoning: null`
表示继承最终 output 价。`overrides` 与 `multipliers` 按数组顺序应用，规则 id 必须唯一；
条件对象同层字段为 AND，支持 `all` / `any` / `not`、context token 边界、service tier、UTC 日期
与时区时间段。覆盖规则只写入非 null 字段，显式 `reasoning: null` 恢复继承；倍率规则逐字段
缩放，未列出的字段不变。

models.dev v2 目录导入只填入 token 价格；`web_search` 等非 token 附加费用不计入并显示提示。
由于不感知上游请求地区，含 `region` 条件的倍率规则（包括嵌套组合）整条忽略，保留基础价、
TTL 缓存价和其他有效倍率，并提示统计费用可能低于实际账单。其他未知定价字段或条件、
非法价格与倍率仍拒绝整份候选价格；`region` 覆盖规则也不支持。价格不支持的候选仍可填入
其他模型信息，但保留已有价格，不用零价覆盖。

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
发布动态 `McpToolCatalog`，Web 组合根把它与 `PluginCatalogRuntime` 组合为唯一的 `RuntimeToolCatalogSource`。
每次租用钉住插件运行时的当前快照，同时一次读取 MCP 的 planning 与 selectable 事实，冻结为不可变目录；后续查找不再读库。
重复模型可见工具名使查询失败关闭。MCP 可选面要求 server enabled 且 AVAILABLE；
查找面按已持久化工具行返回定义，调用前再复验 server 状态与归属。
远端 MCP 工具使用 `EnvironmentSupport.NONE`、`NON_IDEMPOTENT`，deadline 覆盖初始化和调用。

执行网关从当前租用取回 binding 冻结的工具，逐项校验完整定义、贡献身份与 requirements，
不会按当前同名工具替换旧执行语义。工具所属插件已停用或移除时，调用以确定性失败返回模型：
说明工具已不可用且未执行，建议不用它继续或询问用户；定义已变化时说明工具在规划后发生变化，
提示重新调用（下一轮使用当前定义）。MCP 工具同样按现读定义校验。
准入后，目录租用与并发租约只在终态决议提交之后、工具执行体返回（或抛出）之后释放；
取消表现为对执行线程的 interrupt，不会提前回收仍被访问的插件实例。取消先于执行体开始时不会调用工具。

### MCP client

[`catalog/mcp/client`](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/catalog/mcp/client/) 把 LangChain4j 的 Streamable HTTP client
适配为平台自有的配置、工具定义与结果，供发现与执行共用，公共签名不出现 SDK 类型。
[`McpClientFactory`](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/catalog/mcp/client/McpClientFactory.java) 是唯一构造入口：
固定 client protocol version，关闭 SDK 的工具列表缓存、变更订阅与健康检查，每个 client 都不复用远端状态；
初始化在独立线程完成，超时、构建失败或交接竞态都不会留下半开的连接。

预算只有一个：[`McpDeadline`](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/catalog/mcp/client/McpDeadline.java)
保存绝对截止时刻，同一实例依次交给 factory 与 `listTools` / `callTool`，初始化花掉的时间从后续阶段扣除，没有无预算的形式。
[`McpCancellationToken`](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/catalog/mcp/client/McpCancellationToken.java)
只结束本次本地等待（取消该调用的 worker 并中断其线程），绝不关闭共享 client。每个 client 至多 64 个真实 worker，
交接不排队，池满或已关闭时立即抛 `McpException`，不挂起调用方。

失败分两类：工具自身以错误结束映射为可读的 `McpToolCallResult.errorText`；只有连接或协议失败才抛
`McpException`、`McpTimeoutException` 或 `McpCancelledException`，上层据此决定保留还是重建 client。
[`McpResultExtractor`](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/catalog/mcp/client/McpResultExtractor.java)
把 MCP content 映射为 Common 的内容单元，是唯一的 Jackson 2 边界（SDK 签名固定），文本、图片与扩展内容都按单条与单次响应预算有界写出；
入参要求严格 JSON object，重复键或尾随内容在本地拒绝，不交给服务端偶然实现。
[`RemoteMcpConfig`](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/catalog/mcp/client/RemoteMcpConfig.java)
的 `toString` 只输出 URL 长度与 header 数量，因为 URL 可能内嵌凭证。

## 命令接受、产品归属与派发

[`HarnessCommandAcceptanceOrchestrator`](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/orchestration/HarnessCommandAcceptanceOrchestrator.java)
在同一物理事务内完成命令接受：`accept` 只服务 `NEW_SESSION` / `NEW_THREAD` / `NEW_FORKED_SESSION` 创建型 owner batch（owner 授权 + Session 归属 + READY 附件引用转移；`NEW_FORKED_SESSION` 还要求 owner 持有来源 Thread 所属 Session，Issue agent 会话一律拒绝）；`acceptOnThread` 服务既有 Thread 的 owner-free 续写（path threadId + 精确 cursor），只做附件物化与目标 Session 归属校验，不按 owner 伪造或拒绝。精确重放仍执行创建型 owner 授权，但复用已接受事实。接受使用 owner KEY SHARE，深删除使用排他锁并按 Owner → Session → Thread 清理 Harness 与 Blob 引用。

平台对 Issue Agent 的全部感知收敛在 [`IssueAgentOwnership`](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/orchestration/IssueAgentOwnership.java) SPI：
Project 应用实现它，负责在调用方事务内锁定 Project → Issue 层级、读写 `(issueId, agentName) -> threadId` 绑定，并按执行根解析 Issue 归属。
显式的 Issue Agent 操作在没有实现时失败关闭；按执行根发现交互时视为没有 Issue 根。
Issue+Agent 的命令由 Issue 业务工作流拥有，公共 batch 端点拒绝 `ISSUE_AGENT` owner。Run 接受时显式提交 Agent/Model/Environment、`project`/`run` contributor state 与末尾任务输入；后续 turn 直接从冻结的 branch scope 取得 Run 身份，不再反查 Thread 归属。`issue_transition` 在业务锁内校验冻结 scope 的 Run/Issue/阶段/版本与调用 Thread 身份。问卷和审批复用 Runtime 的等待行，由 Interaction service 加入产品来源与人工操作者。

Runtime 在 Gateway 本地准入通过后，于自己的 lease 所有权短事务内持久 `READY -> RUNNING`，提交之后才执行 Provider / Tool 调用，不反查 owner、Issue 归属或阶段等产品事实，也不存在产品侧派发门禁。需要终止在途 Run 时由业务显式调用 Stop；工具权限、preflight / 审批 / YOLO 与 Gateway 容量准入仍在各自边界生效。

## Model 与 Tool 执行

`DatabaseTurnResolver` 每个 live turn 持有一次目录租用完成全部规划，只按该分支冻结/提交的 Agent、Model、Variant、
Environment、工具、Skill 和 subagents 规划；它不判断 Thread 属于哪个 Issue/Chat，也不据此覆写
Environment 或注入工具——Issue Run 的 Agent/Model/Environment、业务工具与身份上下文都在接受 Run 时
显式提交或冻结到 branch state。缺失引用返回规划失败，唯一例外是模型：branch 设置的 Model 已被删除或改名时，
本回合改用当前 Agent 的默认模型（variant 优先沿用原值，否则取 Agent 声明的 variant 或模型默认 variant），
并在 `TURN_START.fallbackModel` 记录实际型号供界面提示与计价；branch 设置本身不被改写，默认模型也不可用时拒绝本回合。
设置的 variant 已从模型中移除时，同一模型改用自身默认 variant。Agent 与 Environment 失效一律停止，不做回退。
`NONE`、`OPTIONAL` 工具保留，未选环境时过滤 `REQUIRED`；配置 Skill 时加入统一 `read`，
subagent allowlist 非空时加入内部 `task`。Prompt 的 Skill 三元组冻结在 system instruction 中。规划期同时按 live provider 配置冻结 `ProviderCacheControl`（retention 与会话 UUID key，压缩回合固定 `none()`），执行期不再按当前 capability 重新规范化。

Tool 网关先做资源解析与无等待 admission（纯本地：并发租约、目录租用与分支视图），返回
`Admitted(run)` / `Busy` / `Rejected`；Busy 与 Rejected 肯定未执行。Runtime 持久化 RUNNING 后在
独占的虚拟线程上阻塞执行 `run`，工具异常由网关分类为失败、取消或 UNKNOWN（远程发送不确定时绝不重放）。
Tool 网关按完整冻结定义、贡献归属和 requirements 复验目录；有效超时仅经 `Tool.resolveTimeout`
解析一次并原样传递。具体状态转换见 [Harness Runtime](harness-runtime.md)。

远端 Environment 工具每次实际 capability 调用都在发送前登记独立 UUID（不复用 ToolInvocation id），
`tryStart` 失败时零发送并收敛为取消；参数构造校验先于登记。只有 transport 明确保证未发送的
Busy / Unavailable 才确认未启动，send-uncertain、普通异常、中断与超时均保留未确认。远端
`onExitConfirmed` 直接确认该次执行，独立于可能已异常结束的结果 future；普通成功或失败终态不替代退出
ACK。远端结果的 call id 映射回原模型 call id。审批前 edit 预览与 Environment skill 同步是独立的
Environment 管理调用，不写 Thread Tool 执行账本。

媒体物化将模型模态、adapter 能力和权威 MIME 取交集。Blob 转换为 attempt-only Base64；
原始文件上限 100 MiB，请求累计 data URI 上限 160 MiB。图片按冻结的
`720P`、`1080P`、`ORIGINAL` 档位处理，方向取 EXIF，动画/多帧、超过 4000 万像素或
类型与字节不符直接失败。媒体模态在 TOOL 与 USER 位置都不支持时对全部模态显式失败；仅 USER 普通内容位置的非图片保留说明文本回退。
原生推理回放与协议请求上限见 [Harness Model](harness-model.md)。

统一 `read` 按 path 路由。受管 Blob 必须由本次 Thread 所属 Session 持有引用；权威 MIME 为 `image/*`/`audio/*`/`video/*`/`application/pdf` 时走媒体路径：在读取任何内容前，用本次调用冻结的模型模态声明、adapter 合法位置与 MIME 及内联预算校验，不支持则明确失败；通过后返回指向同一已授权 Blob 的真实媒体（不是上传成功文本），由 Provider 物化进模型。读取预算覆盖 S3 握手和响应体。本地文件与受管 Blob 共用 [`TextReadWindow`](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/read/TextReadWindow.java)：`offset` 与 `column_offset` 从 1 起，至多 2000 行、60000 个码点，扫描到 EOF 以给出准确的截断结论，但只保留窗口正文；NUL、畸形代理对与解码错误按看似二进制拒绝。本地路径交给 BoundEnvironment，URI 路径拒绝 workdir。

结构化 Debug 区分 `NEXT_REQUEST_PREVIEW` 与活动 `FROZEN_INVOCATION`，排除 credential
与 Base64 正文。请求预览有三个只读入口（既有 Thread 续写、本地分支草稿、历史模型输出），
都在同一条正式规划、物化与编码路径上生成点击瞬间的请求体（可能含内联媒体），
以只读方式检查附件，不写任何内部事实、不消费附件、不调用 transport；
历史入口按该输出记录的显式时间重建普通模型输出；父 COMPACTION 输出按冻结 child Thread / join 的 `finalAnswerEntryId` 定位压缩子最后一次调用并重建其前缀，结果仍是历史重建、不等于原始发送字节。
入口、`kind` 与冲突契约见 [Web](web.md)。

用量与费用只在读取时投影：durable 只保存 ASSISTANT `MESSAGE` 与 COMPACTION 结果的真实 `assistantMetadata`（`stopReason`、`usage`、可选 `decodeDurationMillis`，以及实际执行的 `model`、请求时刻 `requestedAt` 与 Provider 报告的 `responseServiceTier`），绝不持久化金额。`UsageCostProjectionService` 按 metadata 中实际执行的模型读取当前 catalog 价格，以请求时刻、上下文 token 与 service tier 依次应用覆盖与倍率规则，用纯 `BigDecimal` 现算并输出精确十进制字符串 `amount`；catalog 中已没有该单模型（已删除，或改名/删除后同名被模型组复用）则费用缺席，定义损坏则抛错，绝不伪装成 0。同一投影内每个出现过的模型至多查一次 catalog。

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

[`storage/media`](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/storage/media/) 在上传确认后用 ffprobe 补充权威媒体事实、
用 ffmpeg 生成 webp 预览；探针失败时退回 HEAD 元数据，非媒体内容的上传契约不受影响。可执行文件与临时目录由
`kk-studio.storage.media` 配置。

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
[Harness Environment](harness-environment.md) 拥有。
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
任一配置、贡献或目录失败时释放全部已建实例，错误只携带阶段与 pluginId。Web 从同一快照提供两域 Catalog。

### Plugin 配置存储

每个已安装 Plugin 在 `plugin_configuration` 中有一行当前普通配置（`revision` + `values_json`）。
普通值以受 64 KiB UTF-8 预算约束的严格 JSON 文本保存，数据库同时拒绝非对象与重复键；
不经 jsonb 数值展开，精度、scale 与指数表示保持可恢复。秘密不进入普通配置。
启动时为新安装的 Plugin 写入 Schema 默认值（revision 1），已有行保持不变；已卸载 Plugin 的残留行被忽略。

[`PluginConfigurationService`](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/plugin/service/PluginConfigurationService.java)
是普通配置与凭据的唯一写入口。保存以 expectedRevision 做 CAS：先按 Schema 归一普通值，
用候选配置与候选凭据 configure 一次目标插件（被拒绝即 400，不写入），再在同一事务内核验声明依赖的 Agent、
更新配置、写入或删除凭据行并发出 `plugin_changed` 提示。提交后唤醒本节点目录刷新，
凭据被改写时额外唤醒凭据刷新调度器；回滚不触发任何唤醒。

[`PluginCatalogRuntime`](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/plugin/catalog/PluginCatalogRuntime.java)
持有本节点当前快照。刷新回读全部配置，修订有变化时构建新快照并原子替换；构建失败保留旧快照，
并暴露固定的 `applicationError`。快照按引用计数退役：被替换的快照在最后一个租用释放后才关闭，
因此在途调用继续使用自己租到的实例完成。Web 合并通知、重连提示与每 5 秒的回读。
管理面投影把数据库中的 desired 修订与本节点快照的 applied 修订一起报告，二者不一致时附带应用说明；
数据库提交提示不代表所有节点已经应用。

Canvas 应用经自己的租用适配消费同一份快照里的 Function 目录，冻结绑定与定义变化的处理见 [Canvas](canvas.md#宿主适配)。

### 新增 Plugin

在 [`plugins`](../../plugins/pom.xml) 下创建独立模块，并在根
[`pom.xml`](../../pom.xml) 管理版本。实现安装定义与配置实例，Schema 是普通值和默认值的唯一来源；
用单一 Boot 自动配置入口注册定义，实例拥有自己的资源与生命周期，工具在 Runtime 提供的执行线程上阻塞执行；
网络访问与凭据读取在调用期执行，插件只能通过 scoped Context 读取本插件凭据。
工具按 [Contributor 契约](harness-runtime.md#contributor-与目录) 注册，
声明真实 side effect、timeout 和 EnvironmentSupport。最后在 Web 中选择 runtime 依赖，
验证 bean 装配、工具 schema、去敏、失败/取消/超时与资源预算。

管理协议为 `/api/plugins` 的列表/详情、配置写入和认证操作。
配置 Schema 与安全秘密投影由插件声明派生；秘密仅支持 KEEP、SET、CLEAR，不回读明文。
无认证插件不提供登录操作；STATIC_KEY 通过配置接口更新密钥。
DEEP_LINK 的 prepare 请求为 `{region}`，返回 loginUrl 与单次 authAttemptId；
complete 请求为 `{authAttemptId, callbackUrl}`，仅消费 prepare 之后配置修订未变、region 一致的未过期尝试。
认证响应 no-store。新增交互需同步 Share DTO、前端和测试；管理 UI 见
[Plugin 设置](frontend.md#plugin-设置)。

### Plugin credential

`plugin_credential` 每个已认证 Plugin 一行，载荷以明文 JSON 保存，数据库访问控制与备份加密是其保护边界；
载荷绝不进入 API 响应、日志、配置同步或模型上下文。所有写入以 `version` 做 CAS 并推进版本。
Plugin 只通过作用域 `PluginCredentialAccess` 读取本插件凭据：每次调用现读当前行，因此登录、轮换或撤销
立即对已配置实例生效，不需要重建实例。刷新在途、需要重新认证、刷新结果未知或已过期时以对应原因拒绝。
静态密钥的认证目标字段（`contextFields`）变化而本次没有重新录入密钥时，旧密钥被删除，避免把密钥发往新目标。
静态密钥没有 region、过期或刷新时刻，不参与刷新扫描。

刷新以短事务 claim lease（到期与租约有效性都以数据库时钟判定），请求前核验 token、version 与有效截止时刻，
事务外只执行一次 renewal，再按 token、version finalize。
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

## OpenCLI 插件

OpenCLI 作为独立构建期 Plugin，每次 HTTP send 前解析局部单 Bearer 凭据并冻结单调时钟 deadline，同一预算覆盖握手和完整响应体读取，
不会随 read 重置。资源响应要求正数 Content-Length 与 metadata size 一致，并在读流时
拒绝短读和超长。响应体 watchdog 到期主动关闭流，关闭在独立线程执行以免阻塞共享调度器；
EOF、失败和显式 close 均注销 watchdog。客户端拿到流后负责关闭；JDK 若在交付响应前
拒绝非法 header，此路径无客户端可关闭的流，只返回不回显 header 原值的安全错误。
非 2xx 响应、未知 execution/asset 状态与提交失败只暴露固定分类、HTTP 状态和阶段名；
远端 body、status、URL/header 以及底层 IO/Jackson/URI 异常的 message/cause/suppressed
都不进入公开异常与诊断日志，诊断只记录受控阶段与异常类型。

### 配置与凭据契约

OpenCLI 的配置完全归属于插件：受控 schema 声明字段、结构与默认值，配置模型校验取值区间和跨字段必填关系。
管理面与配置同步按同一份 schema 读写，未声明的字段一律被拒绝。使用者只需要关心三类输入：

- Hub 接入：`baseUrl` 给出 OpenCLI Hub 的 HTTP(S) origin，启用时必填；连接、请求、长轮询与响应体上限等预算参数有明确取值范围，默认值由 schema 给出。改动 `baseUrl` 而不重新录入 `apiKey` 会删除已保存的密钥，新旧实例都在出网前失败。
- 认证与私有身份：唯一认证密钥 `apiKey` 是静态密钥，通过统一 Plugin Secret API 的 KEEP/SET/CLEAR 管理，不读环境变量也不设外部优先级。可选私有身份 `instanceId` 是第二个受保护秘密字段，与 `apiKey` 同属一次 scoped snapshot，只在真正发送执行请求前取出并填入请求体，不进入普通值、配置历史、管理面投影或导出；未配置时不发送该字段，由 Hub 自由路由。
- 业务开关与任务预算：`enabled` 决定整个插件是否参与，`gptImage2.paidEnabled` 放开付费图片执行，`seedance.enabled`（配合 `seedance.workspaceId`）放开 Seedance 视频执行；两类任务的执行等待与重试预算也由插件配置模型统一校验。

管理面状态只表达凭据在本地是否可用：静态认证在必需认证字段已录入时才是 `CONNECTED`，只录入私有身份
（或撤销 `apiKey` 后只留私有身份）显示 `NOT_CONNECTED`，刷新失败等真实故障如实上报。
本地秘密已配置不等于 Hub 可达或浏览器侧账号已登录，这两件事只在真正发起请求时才会暴露。

## 配置

### 配置同步

`ConfigSyncService` 提供条目清单、选择导出、导入检查与 YAML 导入。清单和检查结果不含配置值；
导出在单一只读数据库快照中沿依赖边补齐选择，循环 Subagent 去重，不做反向扩展。
YAML 使用业务名称寻址，只保存可编辑字段，显式包含 Provider 凭据、Environment 注册令牌的摘要、Skill Package 私有仓库访问令牌与保存的安装设置，以及 MCP headers。
运行态、数据库身份、版本和时间戳不进入文件。

导入检查复用写入路径的字段与 codec 校验，在一次计划快照上分类新增、覆盖和跳过，不写数据库。
未知或不支持内容、依赖不满足和外部准备失败可明确跳过；必填缺失、结构/类型/有效值错误、
Environment 令牌摘要冲突、Skill 仓库身份变化和移除仍被引用的 Skill/MCP 工具整份拒绝。
Environment 的 `installConfig` 在未知字段跳过判断之前就用同一 codec 深度校验，已知非法设置不会被
条目级跳过；条目省略该键表示清空现有安装设置。
Agent Variant 按导入后将生效的 Model 配置检查，文件内同名 Model 优先；未知 Variant 整份拒绝。
Skill exact commit 获取、manifest 扫描和 MCP 发现先在写事务外完成。

执行重新生成计划，按名称新增或更新，不删除文件外配置；有跳过项而未显式 `allowPartial=true`
时拒绝写入。Environment 的注册令牌摘要与安装设置在同一次 CAS 更新中生效，同名更新保持其 UUID 身份。
预览不冻结状态，执行时同名配置以文件为准，不维护跨步骤检查状态。
可恢复配置在一个事务中写入，复用现有校验、引用保护和 CAS；循环 Agent 用同事务两阶段写入。
插件是第八类：条目只含 `pluginId` 与普通值文本，秘密与配置修订都不进入文件。
导出只覆盖已安装插件，并按插件在代码里声明的所需 Agent 补齐插件→Agent 闭包，缺失 Agent 时拒绝导出，
内置 Agent 只满足依赖、从不作为条目导出；导入时未安装的 id 按条目级跳过，已安装插件按本插件的
configurationSchema 归一化普通值，非法值整份拒绝。普通值可随文件往返，秘密与受保护身份不进入文件，
导入后仍需在 Plugin Secret API 或登录流程录入。
插件写入复用唯一写入口：计划期的各插件修订作为导入事务内的 CAS 坐标，
秘密补丁恒为 KEEP，因此导入既不创建也不恢复任何凭据；任一条目（含插件与其它类别）失败都整份回滚。
系统设置按当前完整六节契约直接校验，不合并局部字段；文件里的未知设置字段（含已退役的 `integrations` 段）
被移除并作为跳过项列出，不做别名或双读，其余六节按 `allowPartial` 语义导入；以执行准备时版本 CAS，
提交后沿现有通知刷新快照。
产品操作、恢复范围与保密要求见[配置导入与导出](../operations/development-and-testing.md#配置导入与导出)。

### 在线设置与启动配置

在线设置由单行 `system_setting` 保存，包含 tool、aiRuntime、environment、network、
storageMedia、advanced 六个 section。strict codec 与 record 校验完整聚合，
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
| `kk-studio.harness.runtime` | worker 开关 |
| `kk-studio.catalog.skill` | Git Skill cache 根（`cache-root`） |
| `kk-studio.storage` | S3 端点/凭据/bucket、maintenance 与对象清理 |
| `kk-studio.storage.media` | ffprobe/ffmpeg 可执行文件与临时目录 |
| `kk-studio.plugins` | 认证尝试有效期、refresh 与远端媒体预算 |
| `kk-studio.cluster` | 节点注册与节点间内部认证 |
| `kk-studio.harness.environment-gateway` | Daemon 帧、出站队列和发送预算，由 Web 拥有 |

应用模块各自拥有部署参数：`kk-studio.project.controller` 属于 [Project](project.md)，`kk-studio.canvas.function.runtime`
属于 [Canvas](canvas.md#部署参数)，`kk-studio.harness.ide-gateway` 属于 Web 的同源 IDE 网关。

默认值和完整字段以对应配置类及
[`SystemSettings`](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/settings/SystemSettings.java)
为准；部署输入与 secret 管理见 [部署与运行](../operations/deployment.md)。

## 测试入口

[`platform/runtime/src/test`](../../platform/runtime/src/test/java/fun/fengwk/kkstudio/platform) 按上述子域组织：
架构测试检查依赖方向，Catalog/Settings 检查 CAS 与 codec，
orchestration 检查 owner 事务与锁序，gateway 检查 admission、门控和终态，
storage 检查引用转移、对象清理与回滚，environment 检查路由围栏与同步。
真实设施测试基座、执行命令和覆盖率入口见 [开发与测试](../operations/development-and-testing.md)。

上级：[系统设计](../system-design.md)。相关文档：[Web](web.md)、[Share](share.md)、
[Schema](schema.md)、[Harness Runtime](harness-runtime.md)、[Canvas](canvas.md)、[Project](project.md)、[IDE](ide.md)。
