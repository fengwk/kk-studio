# Harness Runtime

Harness 要回答的是：用户这一句话说完之后，系统究竟记住了什么、还欠什么、正在做什么、接下来该谁做。这四个问题分别由四份持久化事实回答——Session 与 append-only Entry Tree 记录「说过什么」，命令邮箱记录「还欠什么」，Invocation 记录「正在做什么」，[`Work`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/work/Work.java) 记录「该由谁在哪台机器上做」。`harness-runtime` 就是把用户输入、模型流式输出、工具副作用、停止与审批全部折叠进这四份事实、并在进程崩溃或消息丢失后沿同一条路径重新推进的地方。

本模块是纯 Java：生产依赖只有 `harness-common`、`harness-tool`、`harness-environment`、Jackson、SLF4J 与 JGit（仅用于权限路径匹配），不感知 Spring、JDBC、HTTP 或模型 SDK。持久化实现与调度分发在 [Harness Infra](harness-infra.md)，模型协议编码在 [Harness Provider](harness-provider.md)，外部执行装配在 [Platform](platform.md) 与 [Web](web.md)。

## 同步入口与事务边界

[`HarnessRuntime`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/HarnessRuntime.java) 是外部与运行时交互的唯一同步入口：

```text
acceptCommands / acceptCommandsAndJoin / findThreadCommand / getSession / getSessionEntries / listThreadsBySession
stop / decideToolApproval / setThreadYolo / renameThread / renameSession
manualCompactionAvailability / compactThread / getThreadSnapshot
findJoin / projectJoinReceipt / findAncestorChain
```

除 `manualCompactionAvailability`、`findThreadCommand`、`findJoin`、`projectJoinReceipt`、`findAncestorChain`、`getSession*`、`listThreadsBySession` 这类只读查询外，每个方法都在**一个** [`HarnessStore.Transaction`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/store/HarnessStore.java) 内完成全部写入并原子提交：状态变更要么整体可见，要么完全不发生。唯一跨越事务边界的是 Stop 的本地取消——`stop` 把取消登记在 [`HarnessStore#afterCommit`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/store/HarnessStore.java) 上：无外层事务时在该事务提交后执行，加入调用方外层事务时只在该物理事务真正提交后执行，**外层回滚即不取消**；取消失败不回滚已提交的事实。对称地，必须在事务外工作的入口（手动压缩的 `resolve`）先调用 `assertNoAmbientTransaction` 拒绝带环境事务的调用。

所有业务拒绝都是类型化的 [`HarnessRuntimeConflictException`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/HarnessRuntimeConflictException.java)（`STALE_VERSION`、`STALE_COMMAND_CURSOR`、`IDEMPOTENCY_KEY_REUSED`、`PARTIAL_COMMAND_REPLAY`、`COMMAND_REPLAY_ORDER_MISMATCH`、`THREAD_ID_REUSED`、`TERMINAL_APPLY_PENDING`、`STOP_REQUEST_ID_REUSED`、`APPROVAL_NOT_APPLICABLE`、`APPROVAL_DECISION_MISMATCH`、`MANUAL_COMPACTION_UNAVAILABLE`）或 `HarnessRuntimeNotFoundException`；被破坏的持久化不变量（所有权错误、sibling 混合挂接、`callIndex` 不连续）一律以 `IllegalStateException` fail closed，绝不降级成业务错误。

跨实体的多行事务必须按同一层级取锁，实现层负责在真正取锁前拒绝逆序：

```text
task 准入（全局事务级 advisory lock，仅 task 接受路径）
  -> tree（执行树的根 Thread，事务级 advisory lock）
  -> Session -> Thread（UUID 升序）-> Commands（sequence 升序）
  -> ModelInvocation -> ToolInvocation siblings（assistantEntryId + callIndex 升序）
  -> Work（type + UUID 升序）
```

task 接受先取全局准入锁，跨所有执行树统计非空闲子 Thread，保证全局额度判定与创建原子；根 Thread 不计入子代理额度。普通推进不取该准入锁。涉及执行树的事务用递归查询确定根 Thread，再取该树的 advisory lock；树的读写都在树锁内重读确认，跨树操作按根 UUID 升序依次取锁（细节见 [Harness Infra](harness-infra.md)）。

创建、请求或强制删除 Work 的业务事务必须先锁 owning Thread；Dispatcher 的 claim 与 heartbeat 是唯一允许只锁单条 Work 的调度事务，且它们不得制造新的业务 wake。

## Session、Entry Tree 与 Thread

Session 是一棵只追加的 Entry Tree。`Entry` 是不可变记录 `(id, sessionId, parentEntryId, payload, createdAt, providerReplayState)`，[`EntryType`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/history/EntryType.java) 是封闭的十元集合：

```text
ROOT                  TURN_START   MESSAGE      CUSTOM                MODEL_ATTEMPT_FAILURE
CUSTOM_MESSAGE        ASSISTANT_ERROR           ASSISTANT_ABORTED     COMPACTION           TURN_END
```

`ROOT` 是唯一根，只保存初始 [`BranchSettings`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/entry/BranchSettings.java)（`agentName`、`ModelSelection` 与可空 `environmentName`）；执行父子关系不物化进历史，而是 Thread 行上不可变的 `parent_thread_id`，因此历史始终是对话历史而不是运行树；`TURN_START` 冻结该回合完整 settings、`ownerThreadId`、启动原因（`INPUT` / `CONTINUATION` / `COMPACTION` / `STOP`）与解析出的上下文窗口和输出预算；`MODEL_ATTEMPT_FAILURE` 保存 provider-transparent 的重试审计；`CUSTOM` 是 Contributor 的分支透明状态，不参与 turn 文法也不默认投影；`ASSISTANT_ERROR` 与 `ASSISTANT_ABORTED` 是异常与中止屏障；`COMPACTION` 保存摘要；`TURN_END` 保存结果与 `continueModel` 延续义务。`providerReplayState` 只允许出现在 ASSISTANT `MESSAGE` 上。

分支冻结用户可见的 agent、model 与可空 Environment name：Environment 以全局唯一且不可变的 name 进入历史，每回合再解析为内部路由身份，目录只由每次工具调用自己的 arguments 提供，因此 Entry 里没有目录状态。

[`EntryPath`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/history/EntryPath.java) 是从 `ROOT` 到当前 `head` 的不可变连续路径：同 Session、父链严格相接、ID 不重复、`createdAt` 不早于父节点、首节点是唯一 `ROOT`。[`TurnPathValidator`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/history/TurnPathValidator.java) 在此之上校验 turn 文法：`CUSTOM` 透明（不打开也不关闭 turn）；非 `CUSTOM` 节点必须落在某个 open `TURN_START` 内，前一个 turn 未关闭时不得再开；`INPUT` 回合在 Assistant 结果前必须有至少一条 USER/CUSTOM 消息，`CONTINUATION` 偿还上一个 `continueModel=true` 且不消费用户消息，`COMPACTION` 消费零命令、绝不出现 USER/CUSTOM 且成功结果只能是 `COMPACTION`，`STOP` 是显式停止的持久屏障（不是模型工作轮）——只允许唯一一条 `ASSISTANT_ERROR(CANCELLED)` 取消屏障及其后 `continueModel=false`、`closeRequestId` 非 null 的 `STOPPED` `TURN_END`，绝不出现 USER/CUSTOM 消息、真实 Assistant 结果、Tool 结果、ModelAttemptFailure 或 `COMPACTION` 载荷；Assistant 结果（ASSISTANT MESSAGE / `ASSISTANT_ERROR` / `ASSISTANT_ABORTED` / `COMPACTION`）每个 turn 至多一次；TOOL 消息必须紧随带 `ToolCall` 的 ASSISTANT 消息，`callIndex` 从 0 起严格连续前缀且 `toolCallId`/`toolName`/`assistantEntryId` 精确匹配；`TURN_END` 只能关闭当前 open 的 `TURN_START`，并按 [`TurnEndOutcome`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/entry/TurnEndOutcome.java)（`COMPLETED` / `FAILED` / `STOPPED` / `CANCELLED`）校验前置条件。路径可以在任意前缀截断，用于恢复未闭合的回合。

[`ThreadState`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/thread/ThreadState.java) 是 `harness_thread` 行的持久化当前状态，只保存 Thread 自己拥有的东西：

```text
id / sessionId / parentThreadId / headEntryId / creationRequestHash / name
yoloEnabled / status / nextCommandSequence / version / createdAt / updatedAt
```

`sessionId`、`parentThreadId`、`creationRequestHash`（64 位小写 SHA-256 身份键，不对产品 DTO 暴露）与 `createdAt` 创建后不可变；`headEntryId` 必须属于同一 Session；`validateTransition` 要求命令序号与 `updatedAt` 不回退，任何非精确重放的变更都让 `version` **严格 +1**，精确重放原样接受。`version` 是结构与控制状态的 CAS / invalidation cursor，不是完整快照的内容版本——ModelInvocation 的高频流式 checkpoint 在同一 version 内推进，`name` 是唯一可被控制面独立重命名的字段（`renameThread` 只替换名称并 +1，不产生 Command / Entry / Work）。

[`ThreadLifecycleStatus`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/thread/ThreadLifecycleStatus.java) 是持久化的**递归**生命周期，只存 `IDLE` / `ACTIVE` / `WAITING_CHILDREN`：`IDLE` 表示本地无 queued command、无 applicable invocation、无 continuation obligation，并且全部永久直接孩子都 `IDLE`；`ACTIVE` 表示本地仍有工作；`WAITING_CHILDREN` 表示本地已完成但至少一个直接孩子非 `IDLE`。是否存在未交付的 join 不影响判定——等待子结果不是本地工作。显式停止不是第四种状态，而是 head 上的 `STOPPED` `TURN_END` 事实。对外的细粒度状态由 [`ThreadSnapshot.runtimeStatus()`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/ThreadSnapshot.java) 派生：`WAITING_CHILDREN` / `IDLE` 直接透出；`ACTIVE` 根据本地上下文保留 `TOOL_*` / `MODEL_*`、终态待物化的 `APPLYING` 和续写义务的 `CONTINUATION_DUE`，无本地适用上下文时投影为 `QUEUED`。`IDLE` 之外的每个值都表示 `processing`。

[`ThreadContextClassifier`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/thread/ThreadContextClassifier.java) 是纯函数分类器，输入 ThreadState、root-to-head `EntryPath`、当前 open turn 的 ModelInvocation 与（仅当 Model 结果恰为当前 Assistant head 时加载的）Tool siblings，输出唯一的 [`ThreadContext`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/thread/ThreadContext.java)：`IdleOrHistorical`、`ContinuationDue`、`ModelActive`、`ModelTerminalPending`、`ToolActive`、`ToolTerminalPending`。检测到所有权归属异常、Model 身份与 Thread/open turn 不匹配、Model 未挂结果却有 Tool siblings、Model 响应 `toolCalls` 与 Assistant 消息 `ToolCall` 不一致、sibling 数量或 `callIndex` 不连续等破坏时直接抛 `IllegalStateException`。

## 命令邮箱

`acceptCommands` 接收一批有序命令加一个 sealed target：

- `NEW_SESSION`：调用方预分配 `sessionId`/`threadId`，同一事务插入 Session + ROOT + Thread（`version=0`、`nextCommandSequence=1`）+ Commands（sequence 从 1 起）+ THREAD Work；以 client `threadId` 为查找键做初始创建重放，同 `creationRequestHash` 精确重放，不同哈希冲突为 `THREAD_ID_REUSED`。
- `NEW_THREAD`：`KEY SHARE` 锁既有 Session（不串行化同 Session 的兄弟创建），校验 `startEntryId` 属于该 Session，插入 Thread + Commands + Work；不复制任何 Entry，新 Thread 的 head 直接指向该 Entry。STOP Turn 作为原子控制屏障，NEW_THREAD 绝不能 fork 到其未闭合 prefix（拒绝创建），但完整闭合的 `STOPPED` `TURN_END` 边界可正常 fork。
- `THREAD`：先按 immutable `sessionId` 做 `KEY SHARE`，再 `FOR UPDATE` 锁 Thread；exact ordered replay 必须**先于**任何 cursor / preflight 准入，全新批次要求 `expectedHeadEntryId` 与 `expectedNextCommandSequence` 精确匹配（否则 `STALE_COMMAND_CURSOR`），随后调用 preflight、预留连续 sequence、请求 THREAD Work。

命令类型只有 `USER_MESSAGE`、`CUSTOM_MESSAGE`、`SET_AGENT`、`SET_MODEL`、`SET_ENVIRONMENT`。配置命令固定位于消息之前且顺序为 `SET_AGENT -> SET_MODEL -> SET_ENVIRONMENT`，每种至多一次（`SET_ENVIRONMENT` 携带可空 `environmentName`，null 表示清除选择）；所有 target 的命令批次都要求**恰有一条末尾 USER 消息**（`USER_MESSAGE` 或 `CUSTOM_MESSAGE`，`CUSTOM_MESSAGE` 角色限定为 `USER`）；非法批次是请求校验错误（`IllegalArgumentException`）。`TurnPlanBuilder` 只在 `TURN_START.settings` 冻结已生效的配置，不向模型注入配置提醒；UI 对比 ROOT 与非压缩 TURN_START 的完整设置快照投影配置变化。需要模型执行的独立提醒仍可作为显式 `CUSTOM_MESSAGE` 输入。运行时注入的提醒（`SystemReminder`）同样是一条 USER `CUSTOM_MESSAGE`，因此也计入 user-like；其识别要求完整开闭定界符（`<system-reminder>` + 非空正文 + `</system-reminder>`，闭标签后无尾随内容），普通消息只要伪造开标签前缀不会被当作 steering。YOLO 不走邮箱，由 `setThreadYolo` 直接改 Thread 行。

状态由标记字段派生：无标记即 `QUEUED`；有 `appliedTurnStartEntryId` 即 `APPLIED`；`stopRequestId` 与 `cancelledAt` 成对存在且无 `appliedTurnStartEntryId` 即 `CANCELLED`。

会话显示名由服务端在创建时派生、不进入 creation request hash：Session 取初始批次末尾 user-like 消息的首个非空文本（折叠单行、前 40 个码点，无省略号），无文本回退 `session-` + Session UUID 前 8 位；ROOT Thread 恒为 `main`；分支 Thread 恒为 `branch-` + Thread UUID 前 8 位。手工名称统一经 [`Names.normalize`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/Names.java) 折叠空白并要求非空、至多 256 个码点（超长报错，绝不截断）。

`getThreadSnapshot` 在单事务内锁 Thread、读取待处理命令与 `EntryPath`，再用分类器投影出最小适用状态：`IdleOrHistorical`/`ContinuationDue` 只暴露 Thread 与历史；Model 上下文暴露 ModelInvocation 与尚未物化的失败 attempts；Tool 上下文额外暴露全部 Tool siblings。快照总是携带事务内最新的已提交 Invocation checkpoint，即使 Thread version 未变。

## Invocation 与 Work

[`ModelInvocation`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/invocation/model/ModelInvocation.java) 是 `harness_model_invocation` 行的当前状态：`id`、`threadId`、`turnStartEntryId`、`requestHeadEntryId`、冻结的 [`ModelRequestSpec`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/invocation/model/ModelRequestSpec.java)、状态、`attempt`、流式 checkpoint、终态 `result`/`error`、`resultEntryId` 与重试失败审计列表。

```text
READY -> DISPATCHING -> RUNNING
DISPATCHING -> READY（Busy）/ FAILED（Rejected）/ CANCELLED / UNKNOWN
RUNNING -> SUCCEEDED / FAILED / CANCELLED / UNKNOWN / READY（retry）
```

`attempt` 只在「Gateway 确认已启动」的转换上精确 +1：`DISPATCHING -> RUNNING`、以及 Stop 窗口内的 `DISPATCHING -> UNKNOWN` / `-> CANCELLED`；`Busy` 弹回与启动前拒绝都不增加它。`RUNNING -> READY` 重试时追加一条 `ModelAttemptFailure`（含 attempt、sequence、text、thinking、error、`failedAt`、`retryAt`）并清空活动 checkpoint，下次调度用同一冻结参数重新发起。checkpoint 只能在 `RUNNING -> RUNNING` 或 `RUNNING -> terminal` 中引入或增长，更大 sequence 必须保持 text/thinking 严格前缀增长，终态与重试只能原样保留或清除它；终态结果链接回 Entry 时同时清空 checkpoint 与失败审计。

[`ModelRequestSpec`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/invocation/model/ModelRequestSpec.java) 冻结一次调用不可变的请求契约：

```text
providerType / providerConnectionGenerationId / model / variant / outputTokens
systemInstruction / toolBindings / subagentBindings / cacheControl
```

工具与 Subagent 名各自唯一；携带环境的 Tool binding 共享 Branch 冻结的
`EnvironmentId` 与用户可见 `environmentName`，后者是历史投影判定 native 资格的
durable 事实。Skill 的 name、description 与稳定 path 已经完整写入
`systemInstruction`，Runtime 不再维护第二份 Skill binding。完整历史、可由 bindings
派生的 Provider tools、Branch settings 选择的环境、YOLO、上下文窗口、凭证与端点都留在
各自的事实源里；[`ModelRequestMaterializer`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/invocation/model/ModelRequestMaterializer.java)
每次 attempt 从不可变 `EntryPath` 与冻结 Spec 纯内存重建中立的 `ProviderRequest`
（压缩回合使用专用 summarization systemInstruction 与单个 USER 摘要提示词，工具列表
为空）。

[`ProviderMessageProjector`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/thread/ProviderMessageProjector.java) 将语义 Context 投影为与 Provider SDK 无关的 `ProviderMessage`：

- **会话角色**：`ProviderMessageRole` 仅有 `USER`、`ASSISTANT`、`TOOL`，系统指令由顶层 `systemInstruction` 单独承载，会话消息中绝不出现 SYSTEM 角色。
- **动态工具降级**：历史工具调用的 native 资格按次判定。调用名必须在本次请求的工具绑定中，且该调用冻结时的 Environment 名必须与当前同名工具的 Environment 名一致；任一条件不满足（工具未绑定、Environment 已切换、未知工具）即降级——调用不再出现在 ASSISTANT wire 消息中，与其后续 ToolResult 配对后合并为单条 USER `Previous context:` 上下文，绝不产生 TOOL 消息，也不输出 toolCallId 或 `Tool call` / `Tool result` 伪协议。降级只发生在本次调用投影结果中，durable Entry 与 `callIndex` 永不被改写，也不存在 `historyText` 重写。
- **降级动作来源**：每个降级项形如 `<action>:`（失败时 `<action> failed:`）加该结果的可读内容。`<action>` 来自调用成功时冻结在 `ProviderToolCall.historyAction`（进而落入 durable `ToolCallMessageContent.historyAction`）的 Tool 语义动作；没有该动作时使用确定性中性回退 `external operation:`，逐字围栏保留全部 arguments，因此投影既不猜测第三方工具语义，也不需要重新调用 Tool 代码。未被任何结果配对的降级调用以 `No result provided` 表达，不合成 TOOL 结果。
- **结果输出顺序**：同一 assistant 之后的 native TOOL 结果严格先于降级 USER 上下文输出，以保证 provider 要求的 tool-call adjacency，组内保持相对顺序。只有由降级调用组成的 assistant 消息整条不再输出（其语义已完整进入 USER 上下文）。
- **动态无标签围栏**：降级内容中的逐字 payload 使用动态围栏（反引号数取 `max(3, 内容中最长反引号连续段 + 1)`，不带语言标识），防止逐字 payload 中的反引号与空白被误读。
- **回放状态控制**：以上动态降级仅适用于没有 `ProviderReplayState` 的 assistant 消息。携带 replay 的消息若需降级，在改写前以 `INVALID_REQUEST` 拒绝；Runtime 不解析或丢弃不透明的 native payload。未被改写的消息保留 replay state。物化失败在 Gateway 启动前收敛为 FAILED；失去 claim 时不改写其他执行者的状态。恢复方式是还原原工具绑定/环境，或在新上下文中显式提供摘要。
- **未配对调用处理**：对当前连续 Tool chain 中未配对的 native ToolCall，在角色切换或 Context 结束前合成 error ToolResult `No result provided`（仅存在于本次 ProviderRequest，不写 Session Entry）；降级调用只在 USER 上下文中体现，不合成结果。

[`ToolInvocation`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/invocation/tool/ToolInvocation.java) 是 `harness_tool_invocation` 行的当前状态：冻结的 `ToolCall` 参数、[`ToolBinding`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/invocation/tool/ToolBinding.java)、`assistantEntryId`、`callIndex`、审批记录、结果、副作用批次与错误描述。

```text
READY -> WAITING_APPROVAL（Ask）/ DISPATCHING（Allow）/ FAILED（Deny）/ CANCELLED
WAITING_APPROVAL -> READY（Allowed）/ FAILED（Denied）/ CANCELLED
DISPATCHING -> RUNNING / READY（RetryLater）/ FAILED / UNKNOWN
RUNNING -> SUCCEEDED / FAILED / CANCELLED / UNKNOWN / READY（retry）
```

`ToolBinding` 把工具定义、Contributor 归属、`EnvironmentSupport` 与冻结的
`environmentId` / `environmentName` 绑在一起。`NONE` 不得携带环境，`OPTIONAL` 可以携带
当前选择，`REQUIRED` 的正常规划结果必须携带环境；无选择的 `REQUIRED` 工具在
ModelRequestSpec 构造前已被过滤。Gateway 对陈旧或伪造 binding 继续 fail closed。执行端
不写 Store：[`ToolEffectBatch`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/invocation/tool/ToolEffectBatch.java)
最多携带 16 条 CUSTOM 副作用，且只在 `SUCCEEDED` 状态允许非空；终态 Tool 行只表示
结果已产出，物化为历史 Entry 由 ThreadProcessor 在同一事务里完成，同时物理删除 Tool
行与父 ModelInvocation。

`Work` 是调度邮箱的持久化当前状态，每个 target 一行：`target = (THREAD|MODEL|TOOL, targetId)`、`availableAt`、`wakeVersion`、`leaseToken`、`leaseUntil` 与可选的环境路由标记 `requiredEnvironmentId`。所有跃迁都是返回新状态的纯函数（`initial` / `request` / `claim` / `renew` / `complete` / `reschedule`）：`request` 把 `availableAt` 提前到最早值并把 `wakeVersion` +1，`claim` 写入租约，`complete` 在 `wakeVersion` 未变时删除该行、被新 wake 推进时只清空租约保留行，`reschedule` 清空租约并重设 `availableAt`。`leaseToken` + `wakeVersion` 共同构成所有权围栏，让迟到的旧执行体无法提交或删除更新的 wake。`claim` / `renew` 用租约时长（`Duration`）描述新 deadline，`reschedule` 用非负整毫秒 `Duration` 描述相对当前的重试延迟（零表示立即），`request` 则只表达「立即唤醒」的一次调度原语；有效性判定、deadline 与 due 全部在**单一权威时间域**内完成：生产实现是数据库时钟（`statement_timestamp()`），内存实现是传入的 `now`。因此节点 JVM 时钟与数据库的偏差既不会让刚落地的租约在自己的时间域内瞬间失效、把已过期租约延展，也不会把「立即 Work」延迟或改变重试相对量。

**`requiredEnvironmentId` 仅当 Work target 为 `TOOL` 时允许非空；对 THREAD 与 MODEL 类型的 Work 必须为空，不需要绑定执行主机的服务端 TOOL 也可以为空。** 对非 TOOL 传入非空环境 ID 时领域构造器直接抛 `IllegalArgumentException`。环境要求在 Work 首次创建时冻结，并在后续 `request`、`claim`、`renew`、`complete`、`reschedule` 中完整保留；后续 `request` 传入冲突的非空环境 ID 同样抛异常拒绝。Runtime 只负责在 Work 行上携带这条路由要求，真正的节点分发由 Infra 按当前节点是否持有该 Environment 的 READY 连接租约实施围栏（见 [Harness Infra](harness-infra.md)）。

## 三个 Processor

Dispatcher 认领 Work 后把 `ClaimedWork` 交给对应 Processor；Processor 从不在内部循环，每个 claim 只做一个持久化动作，下一个动作一律由同事务的 `requestWork` 驱动。

[`ThreadProcessor`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ThreadProcessor.java) 先校验 claim 所有权与 per-thread 准入，再锁 Session `KEY SHARE` + Thread、构建 `EntryPath` 并分类：

- `MODEL_TERMINAL_PENDING`：原子写入 Assistant / Error / Compaction 终态结果并物理删除 ModelInvocation；
- `TOOL_TERMINAL_PENDING`：按 `callIndex` 写入副作用与 Tool Result，追加 `TURN_END(continueModel=true)`，删除 children 与父 ModelInvocation；
- `MODEL_ACTIVE` / `TOOL_ACTIVE`：下游长执行仍在进行，完成本次 claim 即归还；
- `CONTINUATION_DUE`：启动 continuation 投机规划（HISTORY 段缺口则以 `continueModel` 义务机械启动 TURN_PREFIX）；
- `IDLE_OR_HISTORICAL`：有待处理用户指令就启动 INPUT 回合，否则完成 claim。

需要外部解析器的回合走投机规划：第一短事务锁 Session `KEY SHARE` -> Thread、捕获命令快照与 cutoff、校验并对临近过期租约补齐余量、分配 candidate Entry ID 构造完整合法的 candidate `EntryPath`（不写任何持久化状态）；事务外调用 `TurnResolver`，期间由本地 `WorkHeartbeat` 续租；第二短事务以 source head、cutoff 内命令精确快照与 claim ownership 作 CAS，一次性提交 `TURN_START` + Message + 命令标记 + Thread 更新 + ModelInvocation/MODEL Work。Resolved 请求在提交前还要经机械一致性校验（route / model / variant / tools / compaction 必须与 candidate 分支事实一致，不一致即抛错且零写入）；任何 CAS 或 claim 损失都会整体回滚并映射为 `LOST_OWNERSHIP`，Resolver 异常、返回 null 或心跳调度失败则按统一的失败延迟 reschedule，绝不静默丢失 Work。重复或陈旧的 THREAD claim 是 no-op。

[`ModelProcessor`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ModelProcessor.java) 驱动一次 Model 调用：prepare 事务把 `READY` 转为 `DISPATCHING` 并推进 Thread version（旧租约恢复直接收敛为 `UNKNOWN`）→ 事务外启动 heartbeat 并调用 `ModelGateway.start` → `Started` 时先安全 attach handle、持久化 `RUNNING`，最后才调用 `handle.activate()` 打开回调门控 → 流式增量交给 [`ModelExecution`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ModelExecution.java) → 终态合并未刷增量，在一次 UPDATE 中写终态并请求 THREAD Work。超过租约仍在 `DISPATCHING`/`RUNNING` 的调用统一收敛为 `UNKNOWN`，绝不重放。

`ModelExecution` 用单 drain owner 顺序处理 delta、flush 与 terminal：delta 只做本地缓冲不触库，[`StreamFlushConfig`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/StreamFlushConfig.java) DEFAULT 按 `200ms`、`256` 个事件或 `64KiB` 增量载荷任一到达触发批次（`IMMEDIATE` 为 1ms / 1 / 1B，用于单事件场景）。所有权围栏只落在提交边界——flush、terminal 与 retry 都在同一次短事务内重校验 `RUNNING` + attempt + claimed lease，再落地结果；有新 text/thinking 时一次 UPDATE 保存累计 checkpoint，纯工具批次只推进 sequence 水位。围栏失败即拒绝提交、整批丢弃，且提交成功后才按原 sequence 在事务与锁之外以有界分块发布 `MODEL_DELTA`，因此既不会发布未提交数据，也不会在 token 级别检测所有权。

成功的 terminal 响应在成为 durable 事实之前先冻结 Tool 历史语义：`ModelExecution` 用本次调用的冻结 `toolBindings` 与注入的 [`ToolHistoryActionResolver`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/port/ToolHistoryActionResolver.java)（Platform 实现按冻结贡献身份与完整定义查不可变 `HarnessCatalog`，再调用 Tool 的 `historyRenderer()`）算出每个 READY 调用的自然语言动作，写入 `ProviderToolCall.historyAction` 后再持久化。解析与渲染在 execution monitor 和状态事务之外完成，动态 MCP Tool 不触发数据库查询；任何缺失、blank、异常或定义变化都退化为不冻结 action——历史语义渲染绝不阻断模型调用，也绝不改写 durable arguments。

[`ToolProcessor`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ToolProcessor.java) 驱动一次工具调用：`READY` 且无审批记录时先看 Thread 的 YOLO 快照——为 true 则跳过 preflight 直接 Allow；否则在事务外调用 `ToolGateway.preflight`（期间 heartbeat 续租），`Allow` 记录免审批并进入 dispatch，`Ask` 转为 `WAITING_APPROVAL` 并完成 TOOL claim（不唤醒 THREAD），`Deny` 标记 `FAILED` 并唤醒 THREAD。preflight 抛异常时保持 `READY` / 无审批 / version 不变，按失败延迟 reschedule。两阶段激活与回调门控协议与 Model 一致；超期租约同样收敛为 `UNKNOWN`，绝不对非幂等工具重放。工具增量只支持文本与 JSON 格式。

三个 Processor 共用 [`WorkHeartbeat`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/WorkHeartbeat.java) 的两层模型：注入的 scheduler 只做固定周期唤醒、单在途合并与非阻塞分派，绝不阻塞在数据库上；独立的 heartbeat worker 执行 `renewWork` 短事务与所有权丢失回调。续租事务异常、调度器启动拒绝或分派执行器拒绝都视为所有权无法维系，统一触发一次 `onLostOwnership`；`stop()` 返回前保证不会再有排队的续租事务开始。

## 压缩、停止、审批与子智能体

压缩复用普通的持久化 ModelInvocation、MODEL Work 与两个 Processor，只是 `TURN_START(reason=COMPACTION)` 上冻结了 `CompactionStart`：

```text
TURN_START(COMPACTION) -> ModelInvocation（summarization systemInstruction + USER，zero tools）-> COMPACTION(summaryText) -> TURN_END
```

[`CompactionConfig`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/compaction/CompactionConfig.java) 默认 `keepRecentTokens=20_000`、可选 `fallbackModel`，并派生全部阈值：`effectiveKeep = min(keepRecentTokens, C/2)`、`effectiveReserve = min(16384, maxOutputTokens)`、`softThreshold = max(effectiveKeep, C - effectiveReserve)`、`manualMinimum = min(keepRecentTokens*2, C/2)`；压缩输出预算取 `min(maxOutputTokens, floor(reserve * 0.8), removedPrefixTokens)`，TURN_PREFIX 阶段把 0.8 换成 0.5。有效保留量按 `reserve` 与 `maxOutputTokens` 取小，因此窗口很小时不会预留超过可用输出。

[`CompactionPlanner`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/compaction/CompactionPlanner.java) 是纯函数：只读 `EntryPath` 与配置，从尾部累计估算长度（`ceil(chars/4)`，媒体占位 4800 字符）越过 `effectiveKeep` 后取下一个合法切分点，绝不切在 ToolResult 或 COMPACTION 回合内部；切分落在 Agent 轮次中间时先出 HISTORY 中间摘要，再由 continuation 义务驱动 TURN_PREFIX 生成最终摘要。[`CompactionResultEvaluator`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/compaction/CompactionResultEvaluator.java) 把 LENGTH 截断、FILTERED 过滤、非法 stop reason、返回工具调用、空摘要、保留段解析失败与无 token 收益判定为失败（`COMPACTION_OUTPUT_TRUNCATED`、`COMPACTION_CONTENT_FILTERED`、`COMPACTION_INVALID_RESPONSE`、`COMPACTION_EMPTY_SUMMARY`、`COMPACTION_NO_GAIN`）；失败、停止或未完成的压缩结束当前 attempt 且不自行重试，hard overflow 最多触发一次恢复，切换 fallback model 时下一次规划继承原阶段与切分锚点。

手工压缩走 `compactThread`：第一短事务按 version / availability / boundary 规划，事务外 resolve，第二短事务以 source head、命令快照与 claim 作 CAS 提交；[`ManualCompactionAvailability`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/ManualCompactionAvailability.java) 是瞬时投影，给出 `THREAD_BUSY`、`OWNERSHIP_BARRIER`、`NO_RESOLVED_CONTEXT`、`MODEL_CHANGED`、`BELOW_MINIMUM`、`NOTHING_TO_COMPACT` 等禁用原因，但最终仍由 `expectedVersion` CAS 决定。

Stop 在 Thread 锁内校验归属、version 与客户端 `stopRequestId`：活跃回合闭合后把该幂等键写入 `STOPPED` `TURN_END` 的 `closeRequestId`，因此重放返回同一 `StopResult`。检测到活跃 Model/Tool 调用时写入 ASSISTANT_ABORTED / ASSISTANT_ERROR 屏障、闭合回合、清理关联 Work 并物理删除未完成的 Invocation 行。**空闲（无 live Invocation）Stop 留下 durable 事实**：path 无 open Turn 时写入完整的 `STOP` barrier Turn（`TURN_START(STOP)` → `ASSISTANT_ERROR(CANCELLED)` → `STOPPED` `TURN_END`）；path 上本线程的 open Turn 已不可能再被模型推进时，若它已有完整 assistant 结果就直接用于 `STOPPED` `TURN_END`（不重复 assistant 结果），尚无 assistant 结果且允许屏障则在 Turn 内部追加取消屏障；两者都不成立（尚无输入的空 `INPUT` Turn，或 assistant 结果仍缺 Tool 结果）时先按 history normalization 补齐 synthetic `UNKNOWN`/`HISTORY_CUT` ToolResult 并以 `CANCELLED`/`HISTORY_CUT` 收尾，再写 `STOP` barrier Turn——绝不嵌套第二个 `TURN_START`、不伪造模型完成，也不留模糊错误。STOP Turn 是原子控制屏障：新分支（`NEW_THREAD`）绝不能 fork 到其未闭合 prefix，但完整闭合的 `STOPPED` `TURN_END` 边界可正常 fork。所有分支都在同一事务把 head 推进到该 `TURN_END` 并恰好递增一次 version；open Turn 属于其它线程的共享历史时，该 Turn 与其停止边界的所有权都归其 owner 线程，本线程只取消排队 Command 而不写停止边界（`stoppedTurnEndEntryId` 为 null）。在物理提交之后（`afterCommit`）才在当前 JVM 内 best-effort 本地取消，本地取消成败不影响已持久化的终态；重放与并发由 `StopResult` 与所有权围栏收敛。

审批由 `decideToolApproval` 驱动，只在 Thread 处于 `ToolActive` 且目标工具处于 `WAITING_APPROVAL` 时接受：`ALLOWED` 转 `READY` 并安排 TOOL Work，`DENIED` 标记 `FAILED` 并唤醒 THREAD Work，两者都推进一次 Thread version；相同决策幂等重放，不同决策抛 `APPROVAL_DECISION_MISMATCH`。

[`PermissionEvaluator`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/permission/PermissionEvaluator.java)
按有序规则产出 Allow / Ask / Deny 候选：规则键是全局 `*` 或合法模型可见 tool name，
按全局到具体工具的顺序求值，数组声明顺序即求值顺序；文件路径只按该次调用显式
`arguments.workdir` 词法解析为相对 POSIX 路径，再交给 JGit gitignore 语义匹配，不读取
Backend 的 cwd 或 HOME。没有 `workdir` 语义的 Platform/MCP 工具不会获得隐藏默认目录。
这里只生成策略候选，真实文件边界、符号链接检查与进程隔离由 Environment Daemon 在
执行入口落实。

### 子智能体委派与 Join

内部 `task` 工具只做一次持久接受：Platform 的 `SubagentTaskRunner` 把本轮调用归一化为 `ThreadJoinRequest`，用 `acceptCommandsAndJoin` 在同一事务接受源 prompt、建立或沿用子 Thread、写入 join 凭据并请求 Work；`TaskTool` 立即用一次成功 tool_result 回执唯一 JSON `{"thread_id":"…","status":"accepted"}`，不阻塞、没有第二个 tool_result。子结果不经过工具返回，而是由运行时在子执行首次 Idle 时匹配 join，并作为父 Thread 的一条独立消息交付。

join 的持久事实是 `harness_thread_join` 的一行，只记凭据、不复制任何对话内容：

```text
invocationId / requestHash / parentThreadId / childThreadId
sourceCommandSequence / afterVersion / agent / maxTurns / reminderTurn
matchedIdleVersion / resultHeadEntryId / deliveryCommandSequence / createdAt / updatedAt
```

- `ThreadJoinRequest` 携带 `invocationId`、可空 `parentThreadId`、父非空时的 `expectedParentHeadEntryId`、`requestHash`、`agent`、`maxTurns`，以及本次接纳的限额（`maxDepth`、`maxConcurrentChildren`、`maxConcurrentThreads`）。
- `parentThreadId` 非空表示 task：同一事务校验父仍可接受本次调用、`expectedParentHeadEntryId` 与父 head 匹配、子关系与深度/并发额度。只有新建子才设置父关系，resume 不能改父；父停在 `STOPPED` 边界时拒绝发起新委派。resume 不要求子已 Idle——向仍在执行的子追加源 prompt 后等待它真正的下一次 Idle，绝不同步等待。
- `parentThreadId` 为空表示内部 root one-shot 的 completion ticket：同样与源 prompt 原子接受，但不投递父消息。
- `sourceCommandSequence` 与 `afterVersion` 记录源命令身份与接受完成后的 Thread version（不是事务前版本），`afterVersion` 之后的首次 Idle 才允许匹配。
- `matchedIdleVersion` 与 `resultHeadEntryId` 同空或同非空，只写一次且必须大于 `afterVersion`；`deliveryCommandSequence` 非空必须已匹配。

结果固定在源命令 `applied_turn_start_entry_id` 到 `resultHeadEntryId` 的历史范围：子树后续继续推进也绝不改写旧回执；源命令在执行前就被取消时单独投影为取消，不会误拾取更早的回答；同一 turn 的多条输入可以共享同一个结果。只读入口是 `findJoin`（固定 join 事实）、`projectJoinReceipt`（按 `invocationId` 投影回执）与 `findAncestorChain`（head-to-root 祖先链）。

首次「非空闲 -> Idle」的版本推进、join 匹配、父 `CUSTOM_MESSAGE` 入队与父重新标为非空闲在同一事务内完成，不存在假 Idle 窗口；多个 join 可以同时命中首个 Idle。完成消息是 `SystemReminder` 形态的 USER 消息（`<system-reminder>` 包裹 `<subagent_result thread_id agent state>`，内含本次任务原文 `<task>` 与 `<result>` / `<error>` / `<partial_result>`），渲染是 `runtime.join` 的纯函数，绝不读取子线程的当前 head。

显式停止的父不会被自动唤醒：先保存已匹配的 receipt，父真实接受新输入时再在同一事务原子交付旧结果，重放不产生第二次交付。停止传播覆盖全部执行后代，迟到的模型/工具结果由既有 ownership fence 拒绝。`maxTurns` 是软预算，按源命令实际 turn 计数，在已确定继续运行的边界用 durable reminder 进度（`reminderTurn`）入队提醒，不为已经 Idle 的已完成子线程人为开启新 turn。

推进完全由 durable Work 驱动：PG NOTIFY 只作唤醒提示，通知丢失或进程重启后由 dispatcher 的周期恢复重新 claim（见 [Harness Infra](harness-infra.md)）。并发额度、`maxDepth` 与 `maxTurns` 配置属于 [Harness Builtin](harness-builtin.md) 与 [Platform](platform.md)。

## 端口、数据与配置

[`port`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/port/) 包定义运行时对基础设施的反向依赖，全部不泄漏 Spring、JDBC 或 HTTP 类型：

| 端口 | 契约 |
| --- | --- |
| [`TurnResolver`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/port/TurnResolver.java) | 同步、无副作用、事务外的 turn 解析；输入 candidate `EntryPath`，输出冻结 spec 与 contextWindow / maxOutputTokens，或确定性 `Rejected`；异常表示临时基础设施故障，由 Processor reschedule |
| [`ModelGateway`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/port/ModelGateway.java) | 准入 `Started` / `Busy` / `Rejected` / `Indeterminate` + 两阶段激活 `start -> activate` |
| [`ToolGateway`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/port/ToolGateway.java) | `preflight` 返回 `Allow` / `Ask` / `Deny`，`start` 返回 `Started` / `RetryLater` / `Rejected` / `Indeterminate` |
| [`ToolResultHistoryMaterializer`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/port/ToolResultHistoryMaterializer.java) | 在 Tool outcome Entry 插入前、同一事务内把瞬时 Resource 外部化为 durable 内容；缺失时资源结果降级为 metadata-only 文本 |
| [`WorkDispatchAdmission`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/port/WorkDispatchAdmission.java) | 宿主派发准入：`executeIfAdmitted(request, intent)` 在同一物理事务内以宿主自己的行锁复验产品事实，只有通过才执行 `READY -> DISPATCHING` 意图；返回空表示宿主拒绝（`ALLOW_ALL` 为无宿主策略部署的默认实现） |
| [`RealtimeEventSink`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/port/RealtimeEventSink.java) | 有损 `MODEL_DELTA` / `TOOL_PARTIAL` 投影写入，`append` 与 `appendAll` 同为 best-effort，失败不回滚 checkpoint 或终态 |
| [`ResourceStore`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/resource/ResourceStore.java) | 内容寻址二进制对象存取；不是运行时正确性的一部分 |

`ModelProcessor` / `ToolProcessor` 只在「会写下 `READY -> DISPATCHING`」的短事务里询问 [`WorkDispatchAdmission`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/port/WorkDispatchAdmission.java)：请求在任何 Harness 行锁之前发出，宿主先取自己的行锁再在同一物理事务内执行状态转换意图，外部 Provider / Tool Gateway 调用仍在提交之后；宿主拒绝时 invocation 事实一概不变，只按 `admissionDeferral` durable 重排 Work。在途、等待态、终态与 THREAD claim 结构性不询问，因此暂停不会阻断执行收敛。

三种准入结论各自对应唯一的 durable 后续：`Busy` / `RetryLater` 表示肯定未开始，安全 reschedule；`Rejected` 是确定性失败；`Indeterminate` 表示可能已开始，收敛为 `UNKNOWN`，严禁盲目重放。`Started` 必须先落 `RUNNING` 再 `activate`，因此 Gateway 打开回调门控必然晚于持久化事实，过早回调只会被缓冲，陈旧或重复回调由所有权围栏拦截。

[`ConcurrencyAdmission`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/admission/ConcurrencyAdmission.java) 提供纯内存、无等待队列的进程内并发槽位，网关用它约束外部执行压力；槽位满立即返回空，`Lease` 幂等释放且恰好归还一个许可。[`InvocationRetryPolicy`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/retry/InvocationRetryPolicy.java) 提供 `FIXED` / `EXPONENTIAL` 确定性退避，只计算是否允许下一次重试与延迟，不读时钟、不写状态；重试只能基于初始冻结的请求契约重新物化，`NON_IDEMPOTENT` 工具永不自动重试。

`ModelUsage` 记录七个非负维度（`inputTokens`、`outputTokens`、`cacheReadTokens`、`cacheWriteTokens`、`cacheWriteLongTokens`、`reasoningTokens`、`providerTotalTokens`），前六类是互斥计费类别；`ModelCost` 以 scale 12 HALF_UP 逐项计价，并在构造时校验总额严格等于六项之和。[`PromptCacheAffinityKeyFactory`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/cache/PromptCacheAffinityKeyFactory.java) 从 session、provider 连接代际与 wire model、唯一的 `systemInstruction` 全文、按序工具名/描述/schema 派生 `pc2-` 前缀的长度前缀数据帧亲和键（动态历史与采样参数不参与），[`PromptCacheRequestFinalizer`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/cache/PromptCacheRequestFinalizer.java) 把调用方显式声明的策略收敛为请求上的不可变缓存控制指令。

运行时策略全部通过构造注入的配置对象给出，不在模块内缓存或自建线程：[`ThreadProcessorConfig`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ThreadProcessorConfig.java)（lease 配置、统一的 resolve 失败延迟、现读的压缩配置）、[`ModelProcessorConfig`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ModelProcessorConfig.java)（lease、现读重试策略、dispatch 失败延迟、宿主拒绝后的 `admissionDeferral`、`StreamFlushConfig`、可选的历史 action resolver）、[`ToolProcessorConfig`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ToolProcessorConfig.java)（另加 preflight 失败延迟与 `admissionDeferral`）。[`ProcessorLeaseConfig`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ProcessorLeaseConfig.java) 要求 heartbeat 间隔严格小于租约时长，保证两次续租之间租约不会自然过期。

## 包架构

生产源码全部位于 `fun.fengwk.kkstudio.harness.runtime` 之下；命名空间 `runtime.invocation` 本身不含源码，类型都在其三个子包中：

| 包名 | 职责 | 边界 |
| --- | --- | --- |
| `runtime` | 同步控制面 facade `HarnessRuntime` 与包私有控制类 `AcceptCommandsControl`、`StopControl`、`ManualCompactionControl`、`ToolInputControl`、`ThreadContextLock`、`ThreadLifecycleCoordinator`、`ThreadTreeLocks`、`ChangeGate`、`Names` | 独占单 Store 事务与类型化冲突；递归生命周期收敛与 `admission -> tree -> session -> thread -> command -> invocation -> work` 锁序由 `ThreadLifecycleCoordinator` 与 `ThreadTreeLocks` 统一守卫；执行状态流转委托给 `runtime.processor` |
| `runtime.admission` | `ConcurrencyAdmission` 进程内非阻塞并发槽位 | 纯内存，不持久化、不跨进程协调 |
| `runtime.cache` | `PromptCacheAffinityKeyFactory` 与 `PromptCacheRequestFinalizer` | 纯内存派生稳定亲和键，只在请求终结阶段生成缓存控制指令 |
| `runtime.compaction` | `CompactionPlanner`、`AutomaticCompactionPlanner`、`CompactionConfig`、`CompactionHistory`、`CompactionResultEvaluator`、摘要装配与提示词 | 规划与评估是纯函数；压缩复用标准 ModelInvocation 与 MODEL 邮箱 |
| `runtime.entry` | `BranchSettings`、`ModelSelection`、`TurnStartReason`、`TurnEndOutcome` | 只含分支配置与 turn 生命周期值对象；环境与目录不进入分支历史 |
| `runtime.history` | `Entry`、`EntryPath`、`EntryType`、turn 文法校验与历史 JSON 编解码 | 只追加事实与路径不变量；调度状态归 `runtime.work` |
| `runtime.input` | `ask_user` 冻结问卷的值契约（`HumanInputQuestionnaire`、`HumanInputAnswers`）、答案规范化与 [HumanInputTool](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/input/HumanInputTool.java) | 纯值对象与校验：不访问 Store、不做 I/O；等待冻结与派发门禁归 `runtime.processor` |
| `runtime.interaction` | `PendingInteraction` 与 `PendingInteractionPage`：WAITING_INPUT / WAITING_APPROVAL 的只读投影与稳定分页 | 不新增持久化事实，也不持有产品归属；owner 与 Pane 跳转由上层按 Session/Thread 解析 |
| `runtime.invocation.codec` | Model/Tool 持久化列的严格确定性 JSON 编解码 | 未知、缺失、重复或尾随字段直接拒绝 |
| `runtime.invocation.model` | `ModelInvocation` 状态机、`ModelRequestSpec`、重试审计与 `ModelRequestMaterializer` | 调度租约归 `runtime.work`；请求使用供应商中立模型 |
| `runtime.invocation.tool` | `ToolInvocation` 状态机、`ToolBinding`、审批状态与 `ToolEffectBatch` | 不自行执行工具，物理执行委托 `ToolGateway` |
| `runtime.join` | `ThreadJoinRequest`、`ThreadJoin`、`ThreadJoinReceipt`、`ThreadJoinOutcome`、`ThreadJoinProjector`、`ThreadJoinCompletion`、`ThreadJoinCompletionRenderer` | join 凭据与回执投影的纯函数；匹配、交付与递归 Idle 收敛由 `runtime` 与 `runtime.processor` 在事务内驱动 |
| `runtime.model` | `ModelDescriptor`、`ModelVariant`、`ModelPricing`、`ModelUsage`、`ModelCost`、`ModelInvocationError` | 纯领域值对象；用量非负、成本总额等于分项之和 |
| `runtime.model.cache` | `PromptCachePolicy`、`PromptCacheCapability`、`PromptCacheMode`、`PromptCacheRetention`、`ProviderCacheControl` | 静态策略与指令契约，不维护缓存存储或命中事实 |
| `runtime.model.codec` | `ModelDescriptor` 与 `ModelVariant` 的权威 JSON 编解码 | 拒绝未知字段，不允许数据库 resource id 或密钥进入该边界 |
| `runtime.model.provider` | 供应商中立的请求/响应/流事件、`ProviderAdapter`、`ProviderReplayState`、`ContextPressureDetector` | 与具体 SDK 解耦；SDK 类型只在 Platform 适配层出现 |
| `runtime.model.provider.codec` | `ProviderRequest`、`ProviderResponse`、replay state 与工具诊断的编解码 | 只依赖 Jackson 与纯 model 类型 |
| `runtime.permission` | `PermissionEvaluator`、规则模型与 `BashSurfaceAnalyzer` | 只产出策略候选；真实路径、符号链接与沙箱由 Environment Daemon 负责 |
| `runtime.port` | `TurnResolver`、`ModelGateway`、`ToolGateway`、`ToolResultHistoryMaterializer`、`ToolHistoryActionResolver`、`ToolSuccess`、`RealtimeEventSink` | 窄端口，不泄漏 Spring、JDBC、HTTP 类型 |
| `runtime.processor` | `ThreadProcessor`、`ModelProcessor`、`ToolProcessor`、`ModelExecution`、`ToolExecution`、`WorkHeartbeat`、`ClaimAdmissionGuard` 与各 ProcessorConfig | Target 级单动作归约、两阶段激活、租约心跳；所有写入走短事务与所有权围栏 |
| `runtime.realtime` | `RealtimeEvent`、`RealtimeEventType` 与 JSON 编解码；Tool partial 携带逐条生成、重投递复用的 canonical `eventId` identity | 有损 live overlay，不持久化、不承担恢复或审计 |
| `runtime.resource` | `ResourceStore` 内容寻址资源存取端口 | 基础设施能力，不参与 Agent Loop 正确性判定 |
| `runtime.retry` | `InvocationRetryPolicy`、`InvocationRetryPolicyProvider`、退避策略 | 只计算重试可行性与延迟，不读时钟、不写状态 |
| `runtime.session` | `Session` 实体与不可变消息内容块（Text、Image、Audio、Video、Resource、Thinking、ToolCall 等） | 与 `harness_session` 对齐；Entry 节点与 payload 在 `runtime.history` |
| `runtime.store` | `HarnessStore` 根与 `HarnessStore.Transaction` 原语、`HarnessStoreTime`、`UuidOrder` | 领域模型与物理持久化的唯一边界，守卫锁序与毫秒精度 |
| `runtime.thread` | `ThreadState`、`ThreadLifecycleStatus`、`ThreadRuntimeStatus`、`ThreadContextClassifier`、`ThreadContext`、`ThreadContextProbe`、`SystemReminder`、`ResolvedRequestValidator`、`ProviderMessageProjector` | 只保存 Thread 自身状态；环境、open turn 与 lease 由 Entry/Invocation/Work 投影 |
| `runtime.thread.command` | 命令邮箱实体、`ThreadCommandState` 派生与 `CommandHarvestReducer` | 只做 typed 字段归约；持久化与 CAS 由调度路径负责 |
| `runtime.tool` | `ToolInvocationError` 与严格 JSON 编解码 | 只含错误值类型，不定义执行状态机 |
| `runtime.work` | `Work`、`ClaimedWork`、`WorkTarget`、`WorkTargetType` 与环境亲和性标记 | 独占 lease 与所有权围栏；不存放业务状态、attempt 或审批 |

## 源码与测试

按维护任务分组：每组先给包目录，再给少数代表文件。

- 同步入口与事务边界：[`runtime`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/) 与 [`runtime.store`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/store/) 包，代表文件 [`HarnessRuntime.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/HarnessRuntime.java)、[`HarnessStore.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/store/HarnessStore.java)。测试入口 [`RuntimeModuleArchitectureTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/RuntimeModuleArchitectureTest.java)、[`HarnessRuntimeAcceptInitialTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/HarnessRuntimeAcceptInitialTest.java)、[`HarnessRuntimeStopReplayTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/HarnessRuntimeStopReplayTest.java)、[`HarnessStoreContractTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/HarnessStoreContractTest.java)：依赖方向、命令批次形状与创建重放、停止幂等，以及同一份 Store 契约在内存与 PostgreSQL 上的并行验证。
- Entry Tree、Thread 与命令邮箱：[`runtime.history`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/history/)、[`runtime.thread`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/thread/)、[`runtime.thread.command`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/thread/command/) 包；测试 [`EntryPathTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/history/EntryPathTest.java)、[`ThreadContextClassifierTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/thread/ThreadContextClassifierTest.java)、[`ThreadRuntimeStatusTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/thread/ThreadRuntimeStatusTest.java)、[`CommandHarvestReducerTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/thread/command/CommandHarvestReducerTest.java) 覆盖路径不变量、turn 文法、上下文分类、递归生命周期投影与 typed 字段归约。
- 子智能体委派与 Join：[`runtime.join`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/join/) 包；测试 [`HarnessRuntimeJoinAcceptanceTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/HarnessRuntimeJoinAcceptanceTest.java) 覆盖原子接受与回滚、幂等重放、深度/并发额度与边界拒绝，[`ThreadProcessorIdleJoinDeliveryTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/processor/ThreadProcessorIdleJoinDeliveryTest.java) 与 [`ThreadProcessorSoftBudgetTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/processor/ThreadProcessorSoftBudgetTest.java) 覆盖首次 Idle 匹配、父停止挂起、递归 Idle 传播与软预算提醒，[`ThreadJoinProjectorTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/join/ThreadJoinProjectorTest.java)、[`ThreadJoinCompletionRendererTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/join/ThreadJoinCompletionRendererTest.java)、[`ThreadJoinTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/join/ThreadJoinTest.java) 锁定回执投影、完成消息转义与 join 不变量的纯函数语义；递归停止由 [`HarnessRuntimeStopRecursivePropagationTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/HarnessRuntimeStopRecursivePropagationTest.java) 覆盖。
- Invocation、Work 与端口：[`runtime.invocation.model`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/invocation/model/)、[`runtime.invocation.tool`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/invocation/tool/)、[`runtime.work`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/work/)、[`runtime.port`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/port/)、[`runtime.admission`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/admission/) 包；测试 [`ModelInvocationTransitionTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/invocation/model/ModelInvocationTransitionTest.java)、[`ToolInvocationTransitionTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/invocation/tool/ToolInvocationTransitionTest.java)、[`WorkTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/work/WorkTest.java)、[`ConcurrencyAdmissionTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/admission/ConcurrencyAdmissionTest.java) 覆盖状态机跃迁、调度纯函数、环境亲和性不可变性与槽位归还。
- Processor 与执行：[`runtime.processor`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/) 包；测试集中在 [`processor` 测试目录](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/processor/)，其中 [`ModelProcessorTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/processor/ModelProcessorTest.java)、[`ThreadProcessorPlanningTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/processor/ThreadProcessorPlanningTest.java)、[`ToolProcessorRecoveryTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/processor/ToolProcessorRecoveryTest.java)、[`WorkHeartbeatTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/processor/WorkHeartbeatTest.java) 覆盖两阶段激活、checkpoint 聚合与终态吸收、投机规划与批次物化、UNKNOWN 恢复与租约续期。
- 压缩、权限、重试与缓存：[`runtime.compaction`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/compaction/)、[`runtime.permission`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/permission/)、[`runtime.retry`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/retry/)、[`runtime.cache`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/cache/) 包；测试 [`CompactionPlannerTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/compaction/CompactionPlannerTest.java)、[`CompactionResultEvaluatorTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/compaction/CompactionResultEvaluatorTest.java)、[`PermissionEvaluatorTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/permission/PermissionEvaluatorTest.java)、[`InvocationRetryPolicyTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/retry/InvocationRetryPolicyTest.java) 锁定阈值公式与切分、失败分类、规则求值与退避判定。

覆盖率契约：本模块 POM 绑定 JaCoCo `check` 到 `verify`，对控制面、Invocation 模型与 codec、Processor 执行、压缩规划的关键类要求行覆盖率不低于 0.90，被检查类清单以 [`harness/runtime/pom.xml`](../../harness/runtime/pom.xml) 为准；运行命令与门禁含义见 [开发与测试](../operations/development-and-testing.md)。真实 PostgreSQL 侧的 claim / lease / notify 契约由 [`harness/infra` 测试](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/) 守卫（见 [Harness Infra](harness-infra.md)）。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Infra](harness-infra.md)、[Harness Provider](harness-provider.md)、[Harness Tool](harness-tool.md)、[Harness Environment](harness-environment.md)、[Harness Contributor API](harness-contributor-api.md)、[Harness Builtin](harness-builtin.md)、[Platform](platform.md)。
