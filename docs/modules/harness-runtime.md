# Harness Runtime

## 定位

`harness-runtime` 是纯 Java 实现的持久化 Agent 运行时核心，负责驱动会话树（Session Entry Tree）、线程命令邮箱（Thread command mailbox）、模型与工具调用（Invocation）、工作任务调度（Work mailbox）以及三大执行器（ThreadProcessor、ModelProcessor、ToolProcessor）。系统在运行时内统筹上下文压缩（compaction）、运行停止（Stop）、人工审批（approval）、供应商与工具抽象端口、权限判定、Prompt 缓存策略以及 Token 用量与成本核算。底层存储（PostgreSQL）、应用框架（Spring）、供应商 SDK、网络通信（HTTP/WebSocket）以及具体工具实现均在运行时外部适配接入。

外部与运行时进行同步命令分发、控制交互和状态查询的唯一入口是 [`HarnessRuntime`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/HarnessRuntime.java)。所有持久化写操作均在 [`HarnessStore`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/store/HarnessStore.java) 提供的强类型事务（typed transaction）内完成。每个外部交互方法在单个 Store 事务内提交状态变更；对于停止操作（Stop），在持久化事务成功提交后，才在当前 JVM 进程内执行最佳努力（best-effort）的任务取消。

## 职责

### 核心职责

- 以只追加（append-only）的 Entry Tree 保存完整且可恢复的会话语义，通过 Thread 的 head 游标记录当前分支位置。
- 由持久化的 Invocation 实体与独占的 Work 租约（lease）驱动模型与工具的长流程执行，支持进程退出或重启后安全重认领（re-claim）。
- 依托 Processor 的单动作归约器（single-action reducer）、CAS 版本比对、所有权围栏与严格的 Entry 物化机制，保障运行时各层状态一致性。
- 将模型供应商、工具执行、权限判定、资源历史物化、实时事件和线程变更感知收敛为职责明确的窄端口（narrow ports）。
- 将上下文压缩、运行停止、工具审批、失败重试、Prompt 缓存、用量核算以及子智能体会话（Subagent Session），统一复用在同一套持久化调度循环中。

### 协作边界

- 底层存储驱动、数据库事务基础设施、工作调度分发器（Dispatcher）、外部模型 SDK、执行环境网络连接与平台 Catalog 均由外部模块提供，由外部装配根统一注入运行时。
- Entry Tree 记录会话历史与当前事实，Invocation 保存长流程执行状态，Work 邮箱保存调度状态；恢复流程直接读取这些持久化快照。
- 具体的智能体编排规则、模型端点凭据以及具体工具定义在外部配置并解析，运行时通过标准端口（如 `TurnResolver`、`ModelGateway`、`ToolGateway`）直接消费已解析的规范数据。
- 系统的故障恢复与断点接续完全以 `HarnessStore` 中的持久化数据为唯一权威事实；实时通知（Realtime notification）、进程内注册表以及调度器内存仅用于实时呈现或调度加速，不作为恢复事实源。

## 依赖边界

```text
infra / platform / web composition root
        │         ┌───────────────┐
        ├────────▶│ HarnessRuntime│
        │         └───────┬───────┘
        │                 │
        │        ┌────────▼────────┐
        └───────▶│ HarnessStore    │
                 │ ModelGateway    │
                 │ ToolGateway     │
                 │ TurnResolver    │
                 │ RealtimeSink    │
                 └────────┬────────┘
                          ▼
          harness-common / harness-tool / harness-environment
```

运行时主源码依赖 `harness-common`、`harness-tool`、`harness-environment`、Jackson 与 SLF4J；JGit 仅用于权限路径匹配实现（`PermissionPathMatcher`）。外层模块通过 Runtime ports 接入具体 Contributor、Infra、Daemon、Platform 与 Web 适配。具体依赖约束见 [`pom.xml`](../../harness/runtime/pom.xml)，并由 [`RuntimeModuleArchitectureTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/RuntimeModuleArchitectureTest.java) 自动化守卫。

## 包架构

Runtime 生产源码覆盖以下 26 个包（均位于 `fun.fengwk.kkstudio.harness.runtime` 之下）：

| 包名 | 核心职责 | 架构边界与不变量 |
| --- | --- | --- |
| `runtime` | 同步控制面与统一入口 facade（`HarnessRuntime`），编排命令接收、停止请求、人工审批与快照查询 | 统一管理单 Store 事务与全局规范锁序；执行状态流转委托给 `runtime.processor`，外部集成通过标准接口解耦 |
| `runtime.admission` | 进程内并发准入控制（`ConcurrencyAdmission`），提供线程安全且非阻塞的信号量准入 | 纯内存维护并发槽位，分发支持幂等关闭的 `Lease` 凭证；跨节点分布式调度与并发控制由基础设施层负责 |
| `runtime.cache` | Prompt Cache 稳定 affinity key 派生以及请求级 `cacheControl` 终结（`PromptCacheRequestFinalizer`） | 基于固定上下文规则纯内存派生稳定 cache key，在请求构造终结阶段确定性生成最终 cacheControl |
| `runtime.compaction` | 上下文压缩规划（`CompactionPlanner`）、Prompt 模板构建、压缩结果评估与最小化持久化标记 | 纯函数式规划与评估；压缩流程统一复用标准 `ModelInvocation` 与 MODEL 调度邮箱，由 `ModelProcessor` 执行 |
| `runtime.entry` | 共享的 Entry 基础值对象模型（`BranchSettings`、`ModelSelection`、`TurnStartReason`、`TurnEndOutcome`） | 定义轻量不可变值对象，承载分支配置（`agentName` 与 model）与 turn 状态标识；环境由 Agent definition 决定，目录只存在于具体工具 arguments 中，因此分支历史不保存目录状态；完整载荷结构与 JSON 编解码由 `runtime.history` 承载 |
| `runtime.history` | 不可变 Entry Tree 事实模型、`EntryPath` 根到 head 连续路径构建、turn 文法校验与历史 JSON 编解码 | 记录会话追加历史事实，校验同会话与 parent 连续性并严格守卫 turn 文法；执行调度状态由 `runtime.work` 承载 |
| `runtime.invocation.codec` | Model 与 Tool Invocation 持久化列的严格确定性 JSON 编解码 | 保证跨 JVM 与存储的线协议（wire format）一致性；序列化与反序列化时严格校验字段，检测到未知或非法字段立即拒绝 |
| `runtime.invocation.model` | ModelInvocation 持久化状态、冻结请求契约（`ModelRequestSpec`）、重试审计记录与请求物化 | 维护 Model 调用工作流的持久化状态、冻结请求契约与重试审计记录；调度租约由 `runtime.work` 独立管理，网络契约使用中立模型 |
| `runtime.invocation.tool` | ToolInvocation 持久化状态、冻结参数（`ToolBinding` / `ToolCall`）、人工审批状态与执行副作用（effects） | 维护 Tool 调用工作流的持久化状态、冻结参数、审批决策与副作用记录；调度租约由 `runtime.work` 独立管理，物理执行委托给 `ToolGateway` |
| `runtime.model` | 供应商中立的模型元数据描述符、Token 用量与成本统计（`ModelUsage`、`ModelCost`）及错误快照 | 提供纯领域值对象与错误分类；用量字段全部为非负数值，成本总额严格等于分项之和 |
| `runtime.model.cache` | Prompt Cache 策略（`PromptCachePolicy`）、供应商能力、缓存模式及保留期等配置值对象 | 承载调用方显式声明的运行时缓存控制指令，作为中立模型直接供请求物化阶段消费 |
| `runtime.model.codec` | Model 描述符（`ModelDescriptor`）与变体（`ModelVariant`）的确定性 JSON 编解码器 | 维护权威的模型描述符编解码规范；反序列化时严格过滤未知字段，保证平台内部标识与敏感密钥不发生外泄 |
| `runtime.model.provider` | 供应商中立的通信契约（请求、响应与流式事件）以及上下文窗口压力检测（`ContextPressureDetector`） | 抽象供应商交互契约与标准错误分类；核心契约与外部具体 SDK 保持独立 |
| `runtime.model.provider.codec` | ProviderRequest 与 ProviderResponse 确定性 JSON 编解码器 | 基于 Jackson 生成稳定的中立模型 wire 格式，供跨进程与网络适配使用 |
| `runtime.permission` | 有序 Tool 权限求值器（`PermissionEvaluator`）、权限配置模型与 Bash 静态表面分析器 | 按有序规则与单次调用 `arguments.workdir` 派生的相对 POSIX 路径求值生成 Allow/Ask/Deny 策略判定；不读取 Backend 本机 cwd/HOME，真实路径校验与能力执行由 Environment Daemon 负责 |
| `runtime.port` | 面向基础设施的反向依赖抽象端口（`TurnResolver`、`ModelGateway`、`ToolGateway`、`RealtimeEventSink`） | 定义面向基础设施的反向依赖抽象端口，严格隔离底层存储驱动、网络调用与外部执行实现 |
| `runtime.processor` | Target 级执行处理器（`ThreadProcessor`、`ModelProcessor`、`ToolProcessor`）与两阶段激活执行体 | 驱动 Target 级执行状态流转，执行所有权围栏校验，通过 Gateway 委托外部调用并通过 Store 完成短事务提交 |
| `runtime.realtime` | 实时事件传输模型（`RealtimeEvent`）及其 JSON 编解码器 | 定义向客户端分发的增量覆盖（live overlay）事件；事件传输采用有损设计，丢失时不影响数据库底层状态一致性 |
| `runtime.resource` | 宿主内容寻址资源存储抽象端口（`ResourceStore`）及引用规范 | 定义基于规范引用的二进制对象存取接口；具体的存储介质由基础设施层适配器实现 |
| `runtime.retry` | 确定性重试退避策略（`InvocationRetryPolicy`）与策略提供方端口 | 提供确定性的重试退避纯计算策略，依据失败原因与次数计算重试可行性与延迟时间；重试执行由 Processor 统一调度 |
| `runtime.session` | Session 实体定义与不可变消息内容块模型（Text、Image、ToolCall 等） | 定义会话根实体与不可变消息内容块模型；分支树状遍历与 turn 文法交由 `runtime.history` 处理 |
| `runtime.store` | 单一持久化存储抽象根（`HarnessStore`）与强类型事务契约（`Transaction`） | 领域模型与物理持久化的统一边界；严格定义并守卫 Session->Thread->Commands->Model->Tool->Work 规范锁序 |
| `runtime.thread` | Thread 持久化状态（`ThreadState`）、纯上下文分类器（`ThreadContextClassifier`）与状态投影 | 维护 Thread 当前分支 head 游标、YOLO 标志、命令序列号与版本号；命令批次由 `runtime.thread.command` 管理，树状分支事实由 Entry Tree 承载 |
| `runtime.thread.command` | Thread Command 邮箱实体、载荷定义、状态派生与命令收割规约器（harvest reducer） | 规范命令邮箱载荷格式与状态派生，提供确定性的命令收割规约逻辑；批次规约保证幂等性，事务控制由调度器统一处理 |
| `runtime.tool` | Tool 轻量错误描述类型（`ToolInvocationError`）及其严格 JSON 编解码器 | 定义 Tool 调用的错误描述值类型及严格 JSON 编解码规范；调用状态机由 `runtime.invocation.tool` 维护 |
| `runtime.work` | 统一任务调度邮箱（`Work`、`ClaimedWork`）与环境亲和性路由标记 | 维护独占调度租约（lease）与 `wakeVersion` 所有权围栏，提供状态跃迁纯函数；业务结果由 Invocation 独立记录 |

## 核心模型 / API

### Session、Entry Tree 与 Thread

每个 Session 组织一棵只追加（append-only）的 Entry Tree。`EntryType` 包含以下固定集合：

```text
ROOT
TURN_START
MESSAGE
CUSTOM
MODEL_ATTEMPT_FAILURE
CUSTOM_MESSAGE
ASSISTANT_ERROR
ASSISTANT_ABORTED
COMPACTION
TURN_END
```

各节点的定义与职责如下：
- `ROOT`：会话树的唯一根节点，没有父节点，保存初始的 `BranchSettings`，并可在派生子智能体时包含 `SubagentContext`。
- `TURN_START`：开启一个模型交互回合（turn），保存该轮完整的分支设置快照、所属的 Thread 标识、启动原因（`INPUT`、`CONTINUATION` 或 `COMPACTION`），以及解析后的上下文窗口与输出预算。
- `MESSAGE`：记录标准对话消息（USER、ASSISTANT、TOOL）。
- `CUSTOM`：供外部扩展插件（Contributor）保存分支透明状态，不参与 turn 文法校验。
- `CUSTOM_MESSAGE`：记录对用户可见的扩展消息（如 SYSTEM 或 USER 类型的自定义呈现内容）。
- `MODEL_ATTEMPT_FAILURE`：记录模型调用失败的重试审计信息。
- `ASSISTANT_ERROR` 与 `ASSISTANT_ABORTED`：模型执行发生异常或被主动中止时的屏障节点。
- `COMPACTION`：保存上下文压缩生成的摘要文本。
- `TURN_END`：闭合当前回合，保存最终结果与后续待执行的延续职责（continuation obligation）。

[`EntryPath`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/history/EntryPath.java) 表示从根节点 `ROOT` 到当前 `head` 的不可变连续路径。路径要求所有节点属于同一会话、父子关系严格相连、创建时间不早于父节点，且首节点为该路径唯一的 `ROOT`。
[`TurnPathValidator`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/history/TurnPathValidator.java) 对路径执行 turn 文法校验：`CUSTOM` 节点不改变 turn 的开启或关闭状态；`INPUT` 类型的回合起始后必须紧跟类用户消息；工具执行结果必须按照 `callIndex` 顺序前缀排列；已闭合的回合不允许追加新消息。

持久化链路回溯由 [`HarnessStore.Transaction`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/store/HarnessStore.java) 的 `loadEntryPath(headEntryId)` 完成。对于仅需要会话根配置的场景（如接收命令 `acceptCommands`），Store 提供轻量的根节点点查契约 `findRootEntry(sessionId)`；具体适配器负责选择高效的读取方式，PostgreSQL 实现使用 ROOT 部分唯一索引完成点查。
`loadContributorCustomEntriesOnPath(headEntryId, contributorId)` 用于在执行工具时构建特定作用域的视图（scoped BranchView）：它仅投影当前祖先链上特定 Contributor 的 `CUSTOM` 状态，排除兄弟分支并保持自根向叶的顺序，无需为工具物化全量历史消息。该接口返回的是窄投影；完整 `EntryPath` 的构造与 turn 文法校验仍由 `loadEntryPath` 路径负责。

`ThreadState` 维护轻量级的当前游标与控制状态：

```text
id / sessionId / headEntryId / creationRequestHash / name
yoloEnabled / nextCommandSequence / version
createdAt / updatedAt
```

执行环境（Environment）、分支配置、运行状态、未完回合标识与执行周期等，均由 Entry、Invocation 与 Work 联合动态投影得出。`ThreadState.validateTransition` 守卫 Thread 行的状态单调性：实体标识不可变更，命令序列号、版本号与更新时间不可回退，任何 Thread 行变更都使 `version` 严格递增 1；相同的重放请求保持状态幂等不变。`name` 是唯一可由控制面独立重命名的字段（`renameThread` 规范化替换名称，version 精确 +1）。Thread version 是结构与控制状态的 CAS / invalidation cursor，不是完整 Snapshot ETag；ModelInvocation 的流式 checkpoint 按有界批次在相同 version 下推进，并由完整 Snapshot 提供恢复事实。

### Thread command mailbox

`acceptCommands` 支持三种目标模式（sealed target）：

- `NEW_SESSION`：原子创建新的 Session、ROOT 节点、初始 Thread、命令批次以及首个 THREAD Work 调度任务；
- `NEW_THREAD`：在已有会话的指定 Entry 节点上派生出新 Thread，原分支历史完整保留，无需拷贝 Entry；
- `THREAD`：依据客户端传入的 `expectedHeadEntryId` 与 `expectedNextCommandSequence`，向现有线程追加命令批次。

命令类型包含 `USER_MESSAGE`、`CUSTOM_MESSAGE`、`SET_AGENT` 和 `SET_MODEL`。配置类命令的前缀顺序固定为：智能体变更（SET_AGENT）→ 模型变更（SET_MODEL）。Agent definition 决定环境，目录只由每次工具调用自己的 arguments 提供，因此命令邮箱不携带环境或目录变更。智能体可用的工具列表直接从外部最新的 Agent 声明 `config.toolIds` 中解析得出。初始化批次以一条末尾的类用户消息结束；日常追加批次允许包含一条用户消息或一条 SYSTEM 引导消息。YOLO 自动放行模式由独立的 `setThreadYolo` 接口直接修改线程状态，不排入命令邮箱。

命令的持久化状态根据其标记字段推导得出：
- 无附加标记时为待处理态 `QUEUED`；
- 关联了 `appliedTurnStartEntryId` 时为已应用态 `APPLIED`；
- `stopRequestId` 与 `cancelledAt` 成对存在且 `appliedTurnStartEntryId` 为空时为已取消态 `CANCELLED`。

当客户端请求的 `idempotencyKey` 与 `requestHash` 完全一致时，系统作为有序重放（ordered replay）安全返回；若哈希冲突、序号断号、部分重放或游标不匹配，则分别抛出明确的类型化冲突异常（`HarnessRuntimeConflictException`）。

### Invocation 与 Work

`ModelInvocation` 维护模型调用的完整持久化生命周期：包含 `requestHeadEntryId`、`turnStartEntryId`、冻结请求参数 `ModelRequestSpec`（持久化列 `request_spec`）、当前状态、当前 attempt 计数、流式检查点、终态结果或错误信息、生成的 `resultEntryId` 以及重试失败审计列表。状态流转如下：

```text
READY -> DISPATCHING -> RUNNING
  ├─> SUCCEEDED / FAILED / CANCELLED / UNKNOWN
  └─> READY（retry）
```

外部网关返回 `Started` 且本地事务成功写入 `RUNNING` 状态后，`attempt` 计数才递增 1；网关返回 `Busy` 或直接拒绝时，调用尚未发出，`attempt` 保持不变。状态由 `RUNNING` 转为 `READY` 触发重试时，系统追加一条重试失败审计记录并重置流式检查点，下次调度将基于同一冻结参数重新发起调用。

每个 `ModelExecution` 使用单 drain owner 顺序处理 delta、timer flush 与 terminal。`StreamFlushConfig` 默认按 `200ms`、`256` 个事件或 `64KiB` 增量载荷触发批次：delta 只做本地缓冲、不触达数据库，所有权围栏延后到提交边界——flush、terminal 与 retry 都在同一次短事务内重校验 `RUNNING` + attempt + claimed lease，再落地结果；有新 text/thinking 时一次 UPDATE 保存累计 checkpoint，纯工具批次不更新 Invocation。所有权丢失因此不在每个 token 上检测，而由下一个围栏边界或 heartbeat 触发收敛：任何边界都拒绝提交，缓冲事件整体丢弃，绝不发布未提交或失去所有权的数据。提交成功后才按原 sequence 发布 `MODEL_DELTA`，并按有界分块（事件数 + 增量载荷字节）批量投递，终态补齐的大量 gap delta 或单个超大事件都不会构造无界列表或单次无界调用。terminal 直接吸收未刷安全 partial，在一次 UPDATE 中提交状态与 checkpoint；retry 则在一次 `RUNNING -> READY` UPDATE 中将 partial 冻结到失败审计并清空活动 checkpoint。合法 `FILTERED` 终态清除 checkpoint 且不发布回退 delta。

`ModelRequestSpec` 作为调用的不可变持久化契约：

```text
providerType / model / variant / preambleMessages
toolBindings / skillBindings / subagentBindings
cacheControl
```

请求冻结供应商类型、模型与变体、引导消息、工具、Skill、Subagent 绑定以及缓存策略。完整历史、可由 `toolBindings` 派生的 Provider tools、Agent definition 决定的环境、YOLO、上下文窗口、凭证、端点和压缩临时元数据保留在各自事实源中；环境只以冻结的 `environmentId` 出现在 `toolBindings` 与 `skillBindings` 中。[`ModelRequestMaterializer`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/invocation/model/ModelRequestMaterializer.java) 在执行前根据不可变的 `EntryPath` 与冻结 Spec 纯内存物化标准 `ProviderRequest`。普通交互回合物化 MESSAGE、CUSTOM_MESSAGE、ASSISTANT_ABORTED 与完成的压缩摘要；压缩回合生成专用的摘要 SYSTEM 与 USER 提示词，工具列表为空。

`ToolInvocation` 维护工具调用的持久化事实：包含冻结的 `ToolCall` 调用参数、`ToolBinding` 规范、关联的 Assistant 节点 ID、`callIndex` 序号、审批状态、执行结果、副作用批次及错误描述。`ToolBinding` 封装了工具定义、插件归属（`ContributorBinding`）、环境依赖声明（`environmentRequired`）以及可空的冻结环境路由身份（`environmentId`）；当且仅当声明需要环境（`environmentRequired=true`）时，`environmentId` 必须非空。`ModelRequestSpec` 要求所有环境绑定工具与 skill source 共享同一 `EnvironmentId`。`CUSTOM` 节点载荷记录 `(contributorId, customType, schemaVersion, dataJson)`；`CUSTOM_MESSAGE` 节点载荷记录 `(contributorId, customType, rendererKey, message, detailsJson)`。状态流转如下：

```text
WAITING_APPROVAL -> READY -> DISPATCHING -> RUNNING
       └───────────────> FAILED / CANCELLED
                         └─> SUCCEEDED / FAILED / CANCELLED / UNKNOWN
```

终态的 Tool 记录表示执行结果已产出，但尚未物化为历史 Entry；同批次工具执行完毕后，ThreadProcessor 在单个事务内追加对应的 Tool Result Entry，并同时清理关联的兄弟工具记录与父级 ModelInvocation。`ToolEffectBatch` 最多包含 16 条自定义副作用（CUSTOM effect），且仅在 `SUCCEEDED` 状态下允许携带。

`Work` 邮箱是统筹调度任务的核心实体：每个 Work 唯一绑定一个目标 `(THREAD|MODEL|TOOL, targetId)`，包含调度就绪时间 `availableAt`、唤醒版本号 `wakeVersion`、租约持有令牌 `leaseToken`、租约过期时间 `leaseUntil` 以及可选的环境亲和性路由标记 `requiredEnvironmentId`。任务唤醒时合并最早就绪时间并递增 `wakeVersion`；租约令牌与唤醒版本号共同构成所有权围栏，防止调度唤醒丢失并拦截超期的过期工作进程。

`requiredEnvironmentId` 表达 Work 的环境亲和性路由约束（Environment affinity）：
- 严格类型约束：`requiredEnvironmentId` 仅当 Work target 为 `TOOL` 时允许非空，对 `THREAD` 与 `MODEL` 类型的 Work 必须为空；无需绑定特定执行主机的服务端工具（server-side TOOL）也可为空。若对非 `TOOL` 类型的 Work 传入非空环境 ID，领域对象构造时会直接抛出异常拒绝。
- 首次创建时冻结：环境要求在 Work 首次创建（`Work.initial` 或事务内 `requestWork`）时即完成冻结。在后续的 `request`、`claim`、`renew`、`complete` 与 `reschedule` 纯函数状态跃迁中，该环境标识完整保留；若后续调度请求传入冲突的环境 ID，系统立即抛出异常拒绝。
- 架构边界与路由围栏：Runtime 仅在 Work 持久化状态中标记该路由约束，具体的物理网络拓扑与外部连接由基础设施层管理。实际调度认领由 Infra 层的 PostgreSQL 与 Dispatcher 共同实施路由围栏：PostgreSQL 在 `claimNextWork` 查询中，强制要求当前工作节点的 `nodeInstanceId` 与 `environment_connection.owner_node_id` 匹配，且连接处于 `READY` 状态、租约 `lease_until` 尚未过期。
- 契约验证入口：该亲和性行为由 `WorkTest`、`ClaimedWorkTest`、`HarnessStoreWorkContract` 以及 Infra 模块的 `PostgresqlWorkTest` 提供自动化测试保障，外部路由调度详见 [Harness Infra](harness-infra.md)。

### HarnessRuntime 同步 API

[`HarnessRuntime`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/HarnessRuntime.java) 对外暴露以下方法：

```text
acceptCommands
findThreadCommand
stop
decideToolApproval
setThreadYolo
renameThread
renameSession
getSession
manualCompactionAvailability
compactThread
getThreadSnapshot
getSessionEntries
listThreadsBySession
```

`acceptCommands` 支持 `NEW_SESSION`、`NEW_THREAD` 和 `THREAD` 三种模式。会话初始化重放、有序重放、命令批次校验与游标准入，由包私有的 [`AcceptCommandsControl`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/AcceptCommandsControl.java) 在单个 Store 事务内协调完成，对外由 `HarnessRuntime` 统一暴露。Session / Thread 的显示名称由服务端在创建时派生，不属于创建请求：`NEW_SESSION` 的 Session 名取初始批次末尾类用户消息的首个非空文本（折叠为单行、前 40 个 Unicode 码点），缺省回退 `session-` + Session UUID 前 8 位；ROOT Thread 恒为 `main`；`NEW_THREAD` 分支 Thread 恒为 `branch-` + Thread UUID 前 8 位。名称不进入 creation request hash。`renameSession` / `renameThread` 在各自短事务内做 isolated metadata mutation：同名（规范化后）即 no-op，否则仅替换名称（Session 不动 id/createdAt；Thread 以 version 精确 +1、updatedAt 推进的方式复用 `updateThread` 行迁移，绝不产生 Command/Entry/Work 副作用），并发重命名为 last-commit-wins；`getSession` 返回 Session 当前投影，Session 不存在抛 `HarnessRuntimeNotFoundException`。手工压缩同样只通过根 Runtime 进入：`manualCompactionAvailability` 返回瞬时 advisory projection，`compactThread` 使用 expectedVersion、source head 与 command snapshot 做最终 CAS。ThreadProcessor 与手工压缩凡在锁定 Thread 后可能追加 Entry 的事务，均先对父 Session 获取 `KEY SHARE`，再获取 Thread `FOR UPDATE`；该顺序与 Session 深删除的 `Session -> Thread` 排他锁顺序一致，避免 Entry 外键隐式锁形成反向锁环。

`getThreadSnapshot` 在单个事务内获取目标线程锁、读取待处理命令、加载从根到当前 head 的 `EntryPath`，并通过 [`ThreadContextClassifier`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/thread/ThreadContextClassifier.java) 纯函数投影出当前最小适用状态。快照总是携带事务内最新、已批次提交的 Invocation checkpoint；该 checkpoint 可能在 Thread version 未变化时更新：

```text
IdleOrHistorical
ContinuationDue
ModelActive
ModelTerminalPending
ToolActive
ToolTerminalPending
```

在 `IdleOrHistorical` 与 `ContinuationDue` 状态下，仅暴露线程与基础历史；在 `ModelActive` 状态下暴露模型调用及未物化的重试失败记录；在 `ToolActive` 状态下暴露模型调用以及同批次的全部工具调用。分类器在检测到所有权归属异常、请求 head 不匹配、结果不一致、`callIndex` 断号或工具数量冲突等不变量破坏时，直接抛出 `IllegalStateException` 快速失败，拒绝将非法状态掩盖为正常业务枚举。

### Provider、Tool、permission 与 realtime ports

- [`TurnResolver`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/port/TurnResolver.java)：作为同步、无副作用的事务外解析端口。输入候选 `EntryPath`，输出冻结的调用参数、上下文窗口与输出预算，或输出确定性的拒绝结果 `Rejected`。发生未捕获异常时代表外部基础设施临时故障，由 ThreadProcessor 触发重调度（reschedule）。
- [`ModelGateway`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/port/ModelGateway.java)：准入结果分为 `Started`、`Busy`、`Rejected` 与 `Indeterminate`。对于 `Started`，采用严格的两阶段激活机制：先调用 `start` 获取句柄，待本地事务将 `RUNNING` 状态成功持久化后，再调用 `Handle.activate` 正式打开回调门控；过早到达的监听器回调由门控暂存，陈旧或重复的回调由运行时所有权围栏拦截。
- [`ToolGateway`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/port/ToolGateway.java)：执行分两阶段进行：先调用 `preflight` 获取权限预检结果（Allow、Ask、Deny），再调用 `start` 获取启动准入结果（Started、RetryLater、Rejected、Indeterminate）。YOLO 判定在 Thread 锁内完成并跳过 `preflight`，后续 `start` 流程保持一致。
- 供应商适配契约：外部模型适配器仅使用运行时定义的通用数据传输对象（[`ProviderRequest`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderRequest.java)、[`ProviderResponse`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderResponse.java) 与 `ProviderStreamEvent`），具体供应商 SDK 类型完全隔离在外部平台层。
- [`ToolResultHistoryMaterializer`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/port/ToolResultHistoryMaterializer.java)：在将工具执行结果写入 Entry 节点前，负责将工具输出中的临时资源引用外部化，并转换为 blob-backed 的持久化消息内容；运行时缺少该端口时，资源结果安全降级为只含名称、媒体类型与有界 preview 的文本，不自动序列化 `ResourceRef` 的瞬时 URI。
- [`RealtimeEventSink`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/port/RealtimeEventSink.java)：向外发布实时的有损增量事件（`MODEL_DELTA`、`TOOL_PARTIAL`）。Model delta 仅在对应批次完成持久化提交后，由单 drain owner 在事务与状态 monitor 外按 sequence 发布；单条使用 `append`，已提交有界批次使用 `appendAll` 批量投递（默认实现逐条委托 `append`，实现可覆写为有界分块的单次往返写入），两者同为 best-effort：调用方以调用为单位隔离失败，任何投影失败都不回滚 checkpoint 或终态，客户端通过 Snapshot/resync 恢复。

### usage、cost、cache 与 admission

`ModelUsage` 记录七个维度的非负 Token 指标（`inputTokens`、`outputTokens`、`cacheReadTokens`、`cacheWriteTokens`、`cacheWriteLongTokens`、`reasoningTokens`、`providerTotalTokens`）；`ModelCost` 包含币种代码、六类分项成本与总成本，总成本在领域构造时校验必须严格等于各分项之和。

[`PromptCacheAffinityKeyFactory`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/cache/PromptCacheAffinityKeyFactory.java) 基于会话标识、供应商与模型名称、连续的首部 SYSTEM 消息，以及按请求顺序排列的工具名称、描述与参数模式，生成带长度前缀的确定性数据帧，再计算以 `pc1-` 为前缀的 Base64URL 稳定亲和键；动态历史消息与采样参数不参与计算。`PromptCacheRequestFinalizer` 统筹请求中的缓存控制标记：未开启缓存或供应商不支持时输出 `none()`；AFFINITY 模式生成亲和键；BREAKPOINTS 模式取供应商缓存能力与请求实际结构断点的交集。

[`ConcurrencyAdmission`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/admission/ConcurrencyAdmission.java) 提供纯内存、无等待队列的信号量准入。当并发槽位已满时立即返回空（empty）；成功获取的 `Lease` 凭证支持幂等释放，且在关闭时严格且仅归还一个槽位许可。网关可通过该凭证约束调用生命周期的资源占用。

## 执行 / 状态 trace

```mermaid
flowchart TD
  A[Command / continuation / compaction] --> B[THREAD Work]
  B --> C[ThreadProcessor: classify one action]
  C -->|plan| D[TurnResolver outside transaction]
  D --> E[TURN_START + ModelInvocation]
  E --> F[MODEL Work]
  F --> G[ModelProcessor: materialize + gateway]
  G --> H[Provider terminal]
  H --> I[THREAD Work]
  I --> J[Model terminal apply]
  J -->|no tools| K[Assistant/Error + TURN_END + delete Model]
  J -->|tool calls| L[ToolInvocation siblings + TOOL Work]
  L --> M[ToolProcessor: permission + gateway]
  M --> N[Tool terminal]
  N --> I
  I --> O[Tool batch append + TURN_END(continueModel)]
  O --> B
```

### ThreadProcessor

[`ThreadProcessor`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ThreadProcessor.java) 在每次认领到有效 THREAD Work 时，严格执行单个持久化动作：

1. 验证 Work claim 的有效性，随后进入线程级准入守卫；
2. 获取 Thread 锁，构建自根向当前 head 的 `EntryPath`，执行纯函数上下文分类；
3. 若分类为 `MODEL_TERMINAL_PENDING`：原子写入 Assistant、Error 或 Compaction 终态结果节点；
4. 若分类为 `TOOL_TERMINAL_PENDING`：按 `callIndex` 顺序通过 [`ToolOutcomeAppender`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ToolOutcomeAppender.java) 写入执行副作用、Tool Result 节点并追加 `TURN_END(continueModel=true)`；
5. 若分类为 `MODEL_ACTIVE` 或 `TOOL_ACTIVE`：当前处于下游长执行中，直接完成当前调度并归还控制权；
6. 若分类为 `CONTINUATION_DUE`：优先处理未竟任务（HISTORY/TURN_PREFIX）或常规 continuation；
7. 若分类为 `IDLE_OR_HISTORICAL`：若存在待处理的用户指令则开启新的 INPUT 回合，否则直接完成调度。

需要外部解析器参与的 turn 采用投机规划机制（speculative plan）：
- 第一个短事务负责构建合法的候选路径与标记命令截断点（cutoff）；
- 规划过程在事务外异步调用 `TurnResolver.resolve`，期间通过后台心跳（`WorkHeartbeat`）持续对当前 Work 进行续租；
- 解析完成后发起第二事务，以源 head 节点、命令快照与 claim 归属权作为 CAS 条件进行提交；
- 若解析阶段发生异常、返回空或心跳丢失，系统仅安排重新调度；若提交时发生 CAS 冲突或丢失所有权，则安全回滚当前操作并返回 `LOST_OWNERSHIP`。

手工压缩流程由 Runtime 根包内的包私有 [`ManualCompactionControl`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/ManualCompactionControl.java) 协调，遵循“短事务规划 → 事务外解析 → 版本、source head 与 command snapshot CAS 提交”机制。`HarnessRuntime` 是唯一同步入口；`ThreadProcessor` 不暴露手工压缩 API，只负责已认领 THREAD Work 的单动作归约。自动压缩规划由 [`AutomaticCompactionPlanner`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/compaction/AutomaticCompactionPlanner.java) 承担，两条路径只共享 [`CompactionHistory`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/compaction/CompactionHistory.java) 的 closed-turn/history 投影与 [`ThreadContextProbe`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/thread/ThreadContextProbe.java) 的无锁上下文探测。

### ModelProcessor 与 ToolProcessor

[`ModelProcessor`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ModelProcessor.java) 负责处理模型调用生命周期：
- 认领到有效的 MODEL Work 后，从冻结的 Spec 物化中立的 ProviderRequest；
- 将持久化状态从 `READY` 跃迁为 `DISPATCHING`，启动租约心跳，随后调用外部 Gateway；
- 外部网关返回 `Started` 状态后，本地事务将状态更新为 `RUNNING`，随后调用 `activate` 打开回调门控；
- 流式增量由 `ModelExecution` 有界聚合，timer 只在共享 scheduler 上计时并将实际 flush 投递到独立 executor；在批次事务内重校验所有权，提交成功后再通过 RealtimeSink 按 sequence 有界分块批量广播；
- 终态结果或错误信息与未刷安全 partial 合并为一次 Invocation UPDATE，并在同一事务内触发 THREAD Work 调度请求；
- 对于租约已过期的 `DISPATCHING` 或 `RUNNING` 任务，状态统一收敛为 `UNKNOWN`，不执行重放以防止重复调用。

[`ToolProcessor`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ToolProcessor.java) 负责处理工具调用生命周期：
- 工具调用处于 `READY` 且无审批记录时，首先在事务锁内检查 Thread 的 YOLO 模式；
- 若已启用 YOLO 则直接判定为 Allow；未启用时在事务外调用 preflight 获取权限判定；
- 判定为 Allow 时记录免审批标记并进入 dispatch；判定为 Ask 时跃迁为 `WAITING_APPROVAL`；判定为 Deny 时标记为 `FAILED` 并唤醒 Thread；
- 处于超时租约的 DISPATCHING 或 RUNNING 任务同样安全收敛为 `UNKNOWN`，避免对非幂等工具造成重复调用；
- 工具增量更新仅支持文本与 JSON 格式；执行成功产出的结果与副作用记录在单次事务中更新，最终由 ThreadProcessor 负责将 Entry 写入历史。

三个 Processor 共用两层 `WorkHeartbeat` 执行模型：timer scheduler 只做固定周期检查、单在途合并和非阻塞分派；独立注入的 heartbeat worker 执行 `renewWork` 数据库事务与所有权丢失回调。重叠周期不会积压，`stop()` 返回前等待已开始的续租事务完成，排队任务在停止后不得再开始续租。

### 对话压缩机制

对话压缩复用现有的 `ModelInvocation`、`MODEL Work`、`ThreadProcessor` 与 `ModelProcessor`：

```text
TURN_START(reason=COMPACTION, CompactionStart)
  -> ModelInvocation（summary SYSTEM + USER，zero tools）
  -> COMPACTION(summaryText)
  -> TURN_END
```

[`CompactionConfig`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/compaction/CompactionConfig.java) 默认保留最近 Token 数量 `keepRecentTokens=20_000`，并支持指定备选降级模型；有效保留量为 `min(keepRecentTokens, contextWindow/2)`，安全预留预算为 `min(16384, maxOutputTokens)`，软触发阈值为 `max(effectiveKeep, contextWindow - effectiveReserve)`，手工压缩的最小阈值为 `min(keepRecentTokens*2, contextWindow/2)`；FULL 与 HISTORY 阶段使用预留预算的 80%，TURN_PREFIX 阶段使用 50%，并与待裁减前缀估算值取较小者。

[`CompactionPlanner`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/compaction/CompactionPlanner.java) 严格依据 `EntryPath` 历史事实与冻结的上下文窗口计算合法切分点；切分边界保证完整性，不会截断 ToolResult 或 COMPACTION 回合内部；规划阶段划分为 HISTORY 与 TURN_PREFIX 两个阶段，HISTORY 阶段完成后由持久化的延续职责（continuation obligation）驱动后续压缩。
`CompactionResultEvaluator` 评估压缩输出，内容截断、内容过滤、空摘要、非法工具调用或无 Token 增益均判定为失败；成功摘要在后续请求物化中作为包装后的 USER 消息参与上下文构建。失败、Stop 或未完成的压缩结束当前 attempt，不自行唤醒重试；hard overflow 最多触发一次恢复。切换备选模型时，下一次规划继承原阶段与切分锚点。

### Stop、approval 与 Subagent Session

主动停止机制（Stop）在 Thread 锁内校验所属线程、当前版本与客户端 `stopRequestId`；活跃回合闭合后，该幂等键写入 STOPPED `TURN_END` 的 `closeRequestId`，供后续精确重放。若当前处于 IDLE 状态，仅取消队列中排队的 Commands；若检测到活跃的模型或工具调用，系统写入已中止或错误屏障节点（aborted/error/cancelled barrier），闭合当前回合，清理关联的 Work 任务，并删除未完成的 Invocation 实体；在持久化事务成功提交后，系统才在当前 JVM 进程内调用 Processor 的本地取消，本地取消的成败不影响已持久化的终态。

人工审批控制（Approval）由 `decideToolApproval` 驱动：仅在当前线程处于 `ToolActive` 且工具调用处于 `WAITING_APPROVAL` 时接收决策指令；决策通过（`ALLOWED`）时状态转为 READY 并安排 TOOL Work，决策拒绝（`DENIED`）时标记为 FAILED 并唤醒 THREAD Work；相同的决策标识与载荷作为幂等重放安全处理，不递增线程版本号。
权限规则求值由 [`PermissionEvaluator`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/permission/PermissionEvaluator.java) 依据规则生成 Allow、Ask、Deny 候选结果。规则键支持全局通配符 `*` 或标准 `AgentToolId`，按全局规则到具体工具规则的顺序执行，数组声明顺序即为实际求值顺序；文件路径只按该次调用 `arguments.workdir` 词法解析为相对 POSIX 路径后再进行规则匹配，不读取 Backend 的 cwd 或 HOME，没有 `workdir` 语义的工具（如 `load_skill`、MCP）不会获得隐藏默认目录。真实文件系统边界、符号链接检查与进程隔离由 Environment Daemon 在执行入口落实。

子智能体会话（Subagent Session）由内部工具 [`TaskTool`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/TaskTool.java) 驱动，直接复用标准运行时协议与存储表。创建时校验父级 ModelInvocation 冻结的子智能体绑定参数，调用 `HarnessRuntime.acceptCommands(NEW_SESSION, SubagentContext)` 创建标准的持久化子线程；恢复已有会话时要求同时属于当前父级与同一根线程，且子线程已处于静止状态。父级通过 `HarnessThreadChangeSource` 接收可能发生版本变化的唤醒信号，配合 [`ChangeGate`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/ChangeGate.java) 合并并消费变化；并发槽位由 `SubagentRunRegistry` 动态管控，超出并发限制时直接拒绝发起。`maxTurns` 到期时追加软提醒，`idleTimeout` 到期时取消当前子执行并保留对应 Session，便于后续恢复。

## 不变量、failure / recovery

- **树结构与只追加保证**：Session 与 Entry 严格保持只追加（append-only）特性；Thread 的 head 游标必须严格指向属于同会话的有效 Entry 节点；EntryPath 校验连续性与 turn 文法规则，遇到破坏结构或插件所有权的不变式损坏时，系统直接采取快速失败（fail closed）。
- **全局规范加锁顺序**：所有跨实体的复杂事务严格遵守统一锁序：`Session -> Thread -> Commands -> ModelInvocation -> ToolInvocation siblings（按 callIndex 升序）-> Work（同层按 (type, id) 升序）`；所有权围栏检查置于事务提交的最后关卡，校验失败时整个事务回滚。统一锁序用于降低并发事务形成死锁的风险。
- **环境亲和性路由约束**：Work 的 `requiredEnvironmentId` 仅当 Work target 为 `TOOL` 时允许非空，对 `THREAD` 与 `MODEL` 类型的 Work 必须为空；环境要求在 Work 首次创建时即完成冻结，并在调度生命周期中完整保留，若传入冲突的环境 ID 将抛出异常拒绝；实际节点分发由底层存储与 Dispatcher 依据当前节点的有效 READY 环境租约实施物理路由围栏。
- **状态单调递增保证**：`ThreadState.version`、命令序列号、Entry 的创建时间戳、Invocation 的 attempt 次数以及 Work 的 `wakeVersion` 均单调递增，不可回退；Thread version 只在结构/控制状态变化时递增，RUNNING attempt 的 checkpoint 可在同一 version 内按序推进；更大的 checkpoint sequence 必须伴随 text 或 thinking 的严格前缀增长，相同 sequence 只允许精确重放；RUNNING 可在一次转换中引入或增长 terminal checkpoint，合法 `FILTERED` 可在终态清除它，已提交终态保持不可变。
- **并发与竞态安全收敛**：丢失的 claim、超期租约、重复监听器回调、重复命令与重复审批统一收敛为空操作（no-op）、`LOST_OWNERSHIP` 或幂等重放；只有持有当前所有权围栏的执行体可以提交状态。
- **外部准入分类与调度**：模型准入返回 `Busy` 或工具准入返回 `RetryLater` 时，表明外部尚未发起调用，系统安全安排重调度；返回 `Rejected` 判定为确定性失败；返回 `Indeterminate` 表明外部状态不确定，状态安全收敛为 `UNKNOWN`，严禁盲目重放以防产生未知副作用。
- **确定性重试机制**：重试调用仅基于初始冻结的请求契约重新物化参数；标记为非幂等（`NON_IDEMPOTENT`）的工具在执行失败后不进行自动重试。
- **恢复事实独立性**：实时事件流丢失或断开后重新拉取持久化快照；Entry、Invocation、Thread 与 Work 的恢复以底层持久化存储为准。
- **资源物化一致性**：工具产生的外部资源在物化为持久化消息时，任何一步失败均触发完整事务回滚，确保会话历史中仅包含有效持久化的规范资源引用。

## 配置 / 扩展

- **运行时调度策略**：`ThreadProcessorConfig` 提供租约时长、解析故障策略与压缩配置等运行时策略参数；压缩配置在每个决策点动态从提供方读取。
- **模型流聚合策略**：`ModelProcessorConfig` 持有 `StreamFlushConfig`，默认最大等待 `200ms`、最多 `256` 个事件、最多 `64KiB` 增量载荷；composition root 分别提供受管 executor 执行 DB flush，以及执行 lease 续租事务与所有权丢失回调。heartbeat scheduler 只负责 timer 唤醒、合并和非阻塞分派。
- **调用重试策略**：`InvocationRetryPolicyProvider` 在重试决策时提供最大重试次数、退避策略与延迟时长参数，时间参数严格使用毫秒精度的正数值。
- **外部执行接入**：模型与工具的具体执行能力通过 `ModelGateway`、`ToolGateway` 与 `ConcurrencyAdmission` 抽象端口注入，使运行时领域模型与外部执行实现保持独立。
- **局部能力端口**：`ToolResultHistoryMaterializer`、`RealtimeEventSink` 与 `HarnessThreadChangeSource` 作为按需装配的扩展端口；缺少资源物化器时，资源结果以 metadata-only 文本降级，Thread 仍可继续推进。
- **子智能体策略**：通过 `SubagentConfigProvider` 实时获取 `maxDepth`、单父级 `maxConcurrency`、全局 `maxTotalConcurrency`、`idleTimeout` 与 `maxTurns`；注册表中的内存预留用于本地准入，持久化执行状态仍以数据库事实为准。

## 测试与源码入口

### 源码入口

- [`HarnessRuntime.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/HarnessRuntime.java)、[`HarnessStore.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/store/HarnessStore.java)、[`AcceptCommandsControl.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/AcceptCommandsControl.java)、[`StopControl.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/StopControl.java)、[`ThreadContextLock.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/ThreadContextLock.java)
- [`EntryPath.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/history/EntryPath.java)、[`TurnPathValidator.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/history/TurnPathValidator.java)、[`ThreadState.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/thread/ThreadState.java)
- [`ModelInvocation.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/invocation/model/ModelInvocation.java)、[`ToolInvocation.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/invocation/tool/ToolInvocation.java)、[`Work.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/work/Work.java)、[`ClaimedWork.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/work/ClaimedWork.java)
- [`ManualCompactionControl.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/ManualCompactionControl.java)、[`ThreadProcessor.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ThreadProcessor.java)、[`ModelProcessor.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ModelProcessor.java)、[`ModelExecution.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ModelExecution.java)、[`StreamFlushConfig.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/StreamFlushConfig.java)、[`ToolProcessor.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ToolProcessor.java)
- [`ModelRequestMaterializer.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/invocation/model/ModelRequestMaterializer.java)、[`CompactionPlanner.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/compaction/CompactionPlanner.java)、[`ToolBinding.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/invocation/tool/ToolBinding.java)

### 关键测试守卫

- [`RuntimeModuleArchitectureTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/RuntimeModuleArchitectureTest.java)：验证纯 Java 依赖边界、各包架构隔离以及模块依赖单向性。
- [`HarnessRuntimeAcceptInitialTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/HarnessRuntimeAcceptInitialTest.java)、[`HarnessRuntimeAcceptThreadTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/HarnessRuntimeAcceptThreadTest.java)：覆盖初始会话创建重放、有序重放与游标 CAS 校验。
- [`HarnessRuntimeStopReplayTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/HarnessRuntimeStopReplayTest.java)、[`HarnessRuntimeStopConcurrencyTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/HarnessRuntimeStopConcurrencyTest.java)、[`HarnessRuntimeApprovalTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/HarnessRuntimeApprovalTest.java)：覆盖 Stop 与审批决策的持久化幂等性与并发所有权围栏。
- [`ThreadContextClassifierTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/thread/ThreadContextClassifierTest.java)、[`ThreadProcessorPlanningTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/processor/ThreadProcessorPlanningTest.java)、[`ThreadProcessorToolBatchTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/processor/ThreadProcessorToolBatchTest.java)：覆盖上下文纯分类、投机规划流程与兄弟工具批次应用。
- [`ThreadProcessorCompactionTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/processor/ThreadProcessorCompactionTest.java)、[`HarnessRuntimeManualCompactionTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/HarnessRuntimeManualCompactionTest.java)、[`CompactionPlannerTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/compaction/CompactionPlannerTest.java)：覆盖自动压缩、手工压缩同步边界、CAS 提交、切分、降级与无收益校验。
- [`ModelProcessorTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/processor/ModelProcessorTest.java)、[`ModelExecutionStreamFlushTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/processor/ModelExecutionStreamFlushTest.java)、[`StreamFlushConfigTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/processor/StreamFlushConfigTest.java)、[`ToolProcessorRecoveryTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/processor/ToolProcessorRecoveryTest.java)、[`WorkHeartbeatTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/processor/WorkHeartbeatTest.java)：覆盖两阶段激活、checkpoint 聚合与终态吸收、timer/容量/并发仲裁、UNKNOWN 恢复、租约续期与所有权围栏；并以事务/行锁/UPDATE/批量派发次数断言 N 条缓冲 delta 的零数据库开销、单事务 flush 与单次批量投递，以及 lease 丢失、attempt 接管、本地取消在 timer / 容量 / terminal 边界上的零发布。
- [`WorkTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/work/WorkTest.java)、[`ClaimedWorkTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/work/ClaimedWorkTest.java)、[`InMemoryWorkTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/InMemoryWorkTest.java)：覆盖 Work 调度状态纯函数跃迁、环境亲和性不可变性约束与 Store 调度契约（外部真实 PostgreSQL/Dispatcher 路由围栏参见 Infra 模块 [`PostgresqlWorkTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlWorkTest.java) 与 [Harness Infra](harness-infra.md)）。
- [`ToolBindingTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/invocation/tool/ToolBindingTest.java)、[`ToolBindingJsonCodecTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/invocation/codec/ToolBindingJsonCodecTest.java)、[`ConcurrencyAdmissionTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/admission/ConcurrencyAdmissionTest.java)：覆盖 ToolBinding 参数校验、JSON 序列化、并发准入槽位预留与租约释放。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Common](harness-common.md)、[Harness Infra](harness-infra.md)、
[Harness Tool](harness-tool.md)、[Harness Environment](harness-environment.md)、[Harness Builtin](harness-builtin.md)、[Harness Contributor API](harness-contributor-api.md)、
[Platform](platform.md)。
