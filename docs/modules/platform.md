# Platform 模块

`platform` 组合领域端口与外部设施，为 Web 提供 Catalog、Chat、Interaction、Storage、
Environment、Settings 和 Harness 产品用例。它负责跨资源事务、实时配置解析和执行网关；
执行状态机见 [Harness Runtime](harness-runtime.md)，Canvas 事务见
[Canvas Infra](canvas-infra.md)，Issue 工作流见 [Project](project.md)。

[`PlatformAutoConfiguration`](../../platform/src/main/java/fun/fengwk/kkstudio/platform/PlatformAutoConfiguration.java)
通过 Boot 自动配置扫描 service 和 mapper。生产组合根是 [Web](web.md)。
PostgreSQL 保存业务事实，内存 registry、执行句柄和通知承担可重建的运行状态。

[`PostgresqlPersistenceConfiguration`](../../platform/src/main/java/fun/fengwk/kkstudio/platform/persistence/PostgresqlPersistenceConfiguration.java)
提供共享的 `SqlSessionTemplate`，在 MyBatis 默认模板条件判断前注册。异常翻译固定唯一数据库产品
`PostgreSQL`，保留 Spring 的 SQLState 分类，不在故障期间取连接读取元数据。
executor 优先采用 `mybatis.executor-type`，未配置时采用 factory 的 `defaultExecutorType`。

## 按用例定位源码

| 目录 | 入口与职责 |
| --- | --- |
| [catalog](../../platform/src/main/java/fun/fengwk/kkstudio/platform/catalog) | Provider/Model/Agent、Git Skill Package、MCP Server/Tool 的名称寻址与 CAS |
| [harness/model](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/model) | Provider 解析、资源物化、Model admission 与流式网关 |
| [catalog/tool](../../platform/src/main/java/fun/fengwk/kkstudio/platform/catalog/tool) | `RuntimeToolCatalog` 聚合静态与 MCP 工具，提供工具目录查询 |
| [harness/tool](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/tool) | 执行网关校验冻结绑定并终态化结果 |
| [harness/thread/command](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/thread/command) | `DatabaseTurnResolver` 按当前 branch 的 Agent、Model、Environment 规划 live turn，不反查 Thread 归属 |
| [harness/task](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/task) | Prompt 拼接与子 Agent 分支设置 |
| [harness/read](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/read) | Skill URI、Session 授权 Blob 文本与本地路径路由 |
| [orchestration](../../platform/src/main/java/fun/fengwk/kkstudio/platform/orchestration) | Chat、Issue+Agent owner 的命令接受、Session 查询与深删除 |
| [interaction](../../platform/src/main/java/fun/fengwk/kkstudio/platform/interaction) | 人工输入/审批与只读环境等待的统一投影、人工提交 |
| [project/adapter](../../platform/src/main/java/fun/fengwk/kkstudio/platform/project/adapter)、[project/tool](../../platform/src/main/java/fun/fengwk/kkstudio/platform/project/tool) | Project 宿主端口、Issue Agent 角色解析与 `issue_transition` |
| [storage](../../platform/src/main/java/fun/fengwk/kkstudio/platform/storage) | 上传、Blob owner 引用、S3 与耐久对象清理 |
| [environment](../../platform/src/main/java/fun/fengwk/kkstudio/platform/environment) | Card、注册令牌、路由租约、宿主信息和 Skill 同步 |
| [plugin](../../platform/src/main/java/fun/fengwk/kkstudio/platform/plugin) | 安装目录、安全管理面、凭据加密与资源端口 |
| [canvas](../../platform/src/main/java/fun/fengwk/kkstudio/platform/canvas) | Function 输出物化、Blob 访问与媒体处理适配 |
| [settings](../../platform/src/main/java/fun/fengwk/kkstudio/platform/settings) | 全局设置、严格 codec、编辑 schema、版本快照与启动期全局代理装配 |
| [configsync](../../platform/src/main/java/fun/fengwk/kkstudio/platform/configsync) | 七类配置的 YAML 读写、依赖闭包与原子导入 |

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
`openai`、`openai_response`、`anthropic`、`google`，按精确 wire value 解析。
Model 配置保存 limit、abilities、variants、pricing 和 default variant。
Agent 配置保存工具名、`SkillRef(packageName, name)`、subagents 与 `inheritParentEnvironment`
（默认 true）；工具候选和 Skill 引用在写入时校验。

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

[`RuntimeToolCatalogConfiguration`](../../platform/src/main/java/fun/fengwk/kkstudio/platform/catalog/tool/RuntimeToolCatalogConfiguration.java)
组合静态 `HarnessToolCatalogAdapter` 与现读数据库的 `McpToolCatalog`。
重复模型可见工具名使查询失败关闭。MCP 可选面要求 server enabled 且 AVAILABLE；
查找面按已持久化工具行返回定义，调用前再复验 server 状态与归属。
远端 MCP 工具使用 `EnvironmentSupport.NONE`、`NON_IDEMPOTENT`，deadline 覆盖初始化和调用。
客户端行为见 [Harness MCP](harness-mcp.md)。

## 命令接受、产品归属与派发

[`HarnessCommandAcceptanceOrchestrator`](../../platform/src/main/java/fun/fengwk/kkstudio/platform/orchestration/HarnessCommandAcceptanceOrchestrator.java)
在同一物理事务内完成命令接受：`accept` 只服务 `NEW_SESSION` / `NEW_THREAD` 创建型 owner batch（owner 授权 + Session 归属 + READY 附件引用转移）；`acceptOnThread` 服务既有 Thread 的 owner-free 续写（path threadId + 精确 cursor），只做附件物化与目标 Session 归属校验，不按 owner 伪造或拒绝。精确重放仍执行创建型 owner 授权，但复用已接受事实。接受使用 owner KEY SHARE，深删除使用排他锁并按 Owner → Session → Thread 清理 Harness 与 Blob 引用。

Issue+Agent 的命令由 Issue 业务工作流拥有，公共 batch 端点拒绝 `ISSUE_AGENT` owner。Run 接受时显式提交 Agent/Model/Environment、`project`/`run` contributor state 与末尾任务输入；后续 turn 直接从冻结的 branch scope 取得 Run 身份，不再反查 Thread 归属。`issue_transition` 在业务锁内校验冻结 scope 的 Run/Issue/阶段/版本与调用 Thread 身份。问卷和审批复用 Runtime 的等待行，由 Interaction service 加入产品来源与人工操作者。

Runtime 在自己的 lease 所有权短事务内直接持久 `READY -> DISPATCHING`，提交之后才调用 Provider / Tool Gateway，不反查 owner、Issue 归属或阶段等产品事实，也不存在产品侧派发门禁。需要终止在途 Run 时由业务显式调用 Stop；工具权限、preflight / 审批 / YOLO 与 Gateway 容量准入仍在各自边界生效。

## Model 与 Tool 执行

`DatabaseTurnResolver` 每个 live turn 现读 Catalog，只按该分支冻结/提交的 Agent、Model、Variant、
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
类型与字节不符直接失败。图片送达失败明确报错；其他不支持媒体按契约投影为说明文本。
原生推理回放与协议请求上限见 [Harness Provider](harness-provider.md)。

统一 `read` 按 path 路由。受管 Blob 必须由本次 Thread 所属 Session 持有引用；
读取预算覆盖 S3 握手和响应体，流式窗口见 [Harness Common](harness-common.md#字符流窗口)。
本地路径交给 BoundEnvironment，URI 路径拒绝 workdir。

结构化 Debug 区分 `NEXT_REQUEST_PREVIEW` 与活动 `FROZEN_INVOCATION`，排除 credential
与 Base64 正文。请求预览有三个只读入口（既有 Thread 续写、本地分支草稿、历史模型输出），
都在同一条正式规划、物化与编码路径上生成点击瞬间的请求体（可能含内联媒体），
以只读方式检查附件，不写任何内部事实、不消费附件、不调用 transport；
历史入口按该输出记录的显式时间与冻结的压缩执行模型重建，结果不等于原始发送字节。
入口、`kind` 与冲突契约见 [Web](web.md)。

用量与费用只在读取时投影：durable 只保存 ASSISTANT `MESSAGE` 与 COMPACTION 结果的真实 `assistantMetadata`（`stopReason` / `usage` / 可选 `decodeDurationMillis`），绝不持久化金额。`UsageCostProjectionService` 沿 Entry 父链取最近一次调用的 `ModelSelection`（压缩用 `TURN_START.compaction().executionModel`，否则该 Turn 的 branch settings，未进入 Turn 回落 ROOT），按当前 catalog pricing 用纯 `BigDecimal` 现算并输出精确十进制字符串 `amount`；模型已删除则费用缺席，定义损坏则抛错，绝不伪装成 0。同一投影内每个出现过的模型至多查一次 catalog。

## Storage、Blob 与 Resource

[`HarnessResourceConfiguration`](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/configuration/HarnessResourceConfiguration.java)
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
READY 更新宿主 OS、时区、进程用户、HOME 与 note，断线保留最后已接受的 metadata。
Skill 同步结果按 owner/lease fence 写回，运维窗口保存有界、去敏事件。

Platform 适配注册、租约和资源票据；会话代际、在途调用和重连裁决由
[Harness Environment Server](harness-environment-server.md) 拥有。
同实例重连依赖 Gateway 在途记录和 Daemon 进程内 journal；新实例接管使旧调用进入
结果不确定，恢复保证止于这两个进程内记录的生命周期。

## 构建期 Plugin

选中的 Plugin JAR 由 [`web/pom.xml`](../../web/pom.xml) 的 runtime dependency 进入发行物，
通过 `AutoConfiguration.imports` 创建 bean。`StudioPluginRegistry` 冻结唯一 pluginId；
`StudioPlugin` 提供管理面，`HarnessContributor` 提供模型工具，两种 SPI 可独立使用。
安装集合随发行物构建确定。

### 新增 Plugin

在 [`plugins`](../../plugins/pom.xml) 下创建独立模块，并在根
[`pom.xml`](../../pom.xml) 管理版本。实现静态 descriptor、认证处理器或 Contributor，
用单一 Boot 自动配置入口装配 client、executor 和 lifecycle；网络访问与凭据读取在调用期执行。
工具按 [Contributor API](harness-contributor-api.md#扩展一个-contributor) 注册，
声明真实 side effect、timeout 和 EnvironmentSupport。最后在 Web 中选择 runtime 依赖，
验证 bean 装配、工具 schema、去敏、失败/取消/超时与资源预算。

管理协议为 `/api/plugins` 的列表/详情与 `auth/prepare`、`auth/complete`、删除 auth。
当前认证交互是封闭的 `DEEP_LINK`，请求分别为 `{region}`、`{callbackUrl}`，
响应 no-store。新增交互需同步 Share DTO、静态前端和测试；管理 UI 见
[Plugin 设置](frontend.md#plugin-设置)。

### Plugin credential

`PluginCredentialStore` 用部署级 owner-only 32-byte key 做 AES-256-GCM 加密，
每次写入新 nonce，AAD 绑定 pluginId、region 和格式版本。主密钥留在部署文件，
Plugin 只获得本次调用快照。key 缺失或解密失败为 KEY_UNAVAILABLE，读写失败关闭。

刷新以短事务 claim lease，事务外一次 renewal，再按 token、version 和截止时刻 finalize。
扫描按最早 nextRefreshAt 安排，保存后唤醒，poll 是最慢兜底。
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

Platform 提供 fake/opencli adapter；本地 image.crop 和 ComfyUI/H3 由构建期 Plugin 提供。
Function Catalog 在启动时冻结，adapter 的 submit 记录外部任务身份，
execute 查询同一任务并物化预分配槽位。

OpenCLI 每次 HTTP send 前冻结单调时钟 deadline，同一预算覆盖握手和完整响应体读取，
不会随 read 重置。资源响应要求正数 Content-Length 与 metadata size 一致，并在读流时
拒绝短读和超长。响应体 watchdog 到期主动关闭流，关闭在独立线程执行以免阻塞共享调度器；
EOF、失败和显式 close 均注销 watchdog。客户端拿到流后负责关闭；JDK 若在交付响应前
拒绝非法 header，此路径无客户端可关闭的流，只返回不回显 header 原值的安全错误。

## 配置

### 配置同步

`ConfigSyncService` 提供条目清单、选择导出、导入检查与 YAML 导入。清单和检查结果不含配置值；
导出在单一只读数据库快照中沿依赖边补齐选择，循环 Subagent 去重，不做反向扩展。
YAML 使用业务名称寻址，只保存可编辑字段，显式包含 Provider 凭据、Environment 注册令牌与保存的安装设置，以及 MCP headers。
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
aiRuntime 包含重试策略、压缩保留量、可空 `compactionFallbackModel` 和 subagent 限额。

network 提供唯一的 Backend 全局 HTTP 代理：`proxyUrl` 为无认证的 `http://host:port`，
null 表示强制直连；`noProxyHosts` 为逗号分隔绕过规则，默认 `localhost,127.*,::1`，
支持主机、域名后缀、IP、可选端口、`*` 与 IPv4/IPv6 CIDR（CIDR 只按目标 URL 中的数值 IP
逐位匹配，不为域名解析 DNS）。
设置在启动时冻结，保存后重启各 Backend 节点生效；模型、Git、MCP、集成、媒体与 S3
统一使用该策略，无模块覆盖，也不回退宿主代理环境。媒体 CONNECT 仍固定到已校验的公网 IP，
Host/SNI 与证书校验保留原域名。Daemon 与浏览器的网络策略独立。

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
[`SystemSettings`](../../platform/src/main/java/fun/fengwk/kkstudio/platform/settings/SystemSettings.java)
为准；部署输入与 secret 管理见 [部署与运行](../operations/deployment.md)。

## 测试入口

[`platform/src/test`](../../platform/src/test/java/fun/fengwk/kkstudio/platform) 按上述子域组织：
架构测试检查依赖方向，Catalog/Settings 检查 CAS 与 codec，
orchestration/Project 检查 owner 事务与锁序，gateway 检查 admission、门控和终态，
storage 检查引用转移、对象清理与回滚，environment 检查路由围栏与同步。
真实设施测试基座、执行命令和覆盖率入口见 [开发与测试](../operations/development-and-testing.md)。

上级：[系统设计](../system-design.md)。相关文档：[Web](web.md)、[Share](share.md)、
[Schema](schema.md)、[Harness Runtime](harness-runtime.md)、[Canvas Core](canvas-core.md)。
