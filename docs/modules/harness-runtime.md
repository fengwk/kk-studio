# Harness Runtime

`harness-runtime` 是 Agent 执行内核的公共层。它定义与具体执行后端无关的会话协议：Session 的只追加 Entry Tree 保存历史，Thread 的命令邮箱保存已接受的输入，ToolInvocation 保存工具在途调用，Work 保存调度义务；用户输入、工具副作用与控制操作都沿这些持久事实推进，崩溃恢复复用同一组状态转换。同一模块还承载三类面向扩展的契约：Tool 值契约、Contributor SPI 与第一方内置工具，以及 prompt 模板。

具体模型生成由 Builtin Driver 实现：组合根直接装配，Runtime 通过 [`runtime.driver`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/driver/) 的窄契约调用它，不动态加载、不读取 Driver 私有状态。Builtin 的模型状态、请求物化、MODEL Work 与自动压缩策略见 [Harness Agent Builtin](harness-agent-builtin.md)。

模块是纯 Java：生产依赖只有 [Common](common.md)、[Harness Environment](harness-environment.md)、[Harness Model](harness-model.md)、Jackson 3、SLF4J 与 JGit（仅用于权限路径匹配），不感知 Spring、JDBC、HTTP 或模型 SDK。持久化与调度分发在 [Harness Infra](harness-infra.md)，模型契约与协议编码在 [Harness Model](harness-model.md)，网关、目录与产品用例在 [Platform](platform.md)，组合根在 [Web](web.md)。

## 持久模型

```text
Session ─ Entry Tree        只追加的对话历史；分支 = 从 ROOT 到某个 head 的路径
Thread  ─ head + 邮箱        指向自己的 head，持有执行控制、输入水位与已接受的 Command
ToolInvocation              工具在途调用、审批记录、结果与副作用批次
ModelInvocation（Builtin Driver 私有）  当前回合在途模型生成的状态机行，终态物化进历史后删除
Work                         每个 target（THREAD / MODEL / TOOL）至多一行的调度义务与租约
Join                         一次子执行委派的票据，只记凭据与冻结的终态坐标
```

推进没有内存中的 Agent Loop：每一步都是一个短事务，读取这些事实、写入下一组事实，并在同事务里请求后续 Work。Dispatcher 认领 Work 后交给对应 Processor；通知丢失或进程重启后，周期恢复会重新认领，执行到哪一步完全由持久状态决定。

## 同步入口与事务边界

[`HarnessRuntime`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/HarnessRuntime.java) 是外部与内核交互的唯一同步入口，覆盖命令接受与 spawn、Stop、审批与人工输入、YOLO 与重命名、手工压缩，以及快照、执行树与 Join 查询。

每个控制操作在**一个** [`HarnessStore.Transaction`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/store/HarnessStore.java) 内完成全部写入并原子提交；手工压缩也只用一个短事务（锁内复验、同步 resolve、提交）。查询同样走 Store 事务，快照查询会加锁。Stop 的本地取消登记在 `afterCommit` 上，只在物理事务真正提交后执行，外层回滚即不取消，取消失败也不回滚已提交的事实。

业务拒绝使用类型化的 [`HarnessRuntimeConflictException`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/HarnessRuntimeConflictException.java) 与 `HarnessRuntimeNotFoundException`（游标冲突、幂等身份复用、审批或输入不适用、压缩不可用等）；被破坏的持久化不变量（所有权错误、sibling 混合挂接、`callIndex` 不连续）以 `IllegalStateException` 拒绝继续，绝不降级为业务错误。

多行事务按同一层级取锁，实现层在真正取锁前拒绝逆序：

```text
前置 advisory 围栏：task 准入（仅接受路径）-> tree（根 UUID 升序）
行锁阶梯：Session -> Thread -> Command -> Model -> Tool -> Work
```

tree 锁只用于结构或跨 Thread 的写操作（spawn、Stop、删除、fork、Join 结算、压缩 spawn）与整树查询，以 Thread 行上不可变的 `rootThreadId` 定位并在锁内重读确认；单 Thread 控制与 Model/Tool 状态提交只锁自己的 Thread 及其 Model/Tool/Work，与持 tree 锁的写操作在同一 Thread 行上线性化。task 接受另取全局准入锁，使跨树的子代理额度判定与接受原子。创建、请求或删除 Work 的业务事务必须先锁 owning Thread；Dispatcher 的 claim 与 heartbeat 是唯一只锁单条 Work 的事务，且不得制造新的业务唤醒。同级排序与树内交付的细节见 [Harness Infra](harness-infra.md#事务边界与锁序)。

## Entry Tree 与回合文法

`Entry` 是不可变记录 `(id, sessionId, parentEntryId, payload, createdAt, providerReplayState)`，类型是封闭集合 [`EntryType`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/history/EntryType.java)：

| 类型 | 语义 |
| --- | --- |
| `ROOT` | 唯一根，保存初始 [`BranchSettings`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/entry/BranchSettings.java)：agent、`ModelSelection`（全局 `modelName` + 可空 `variant`）、可空的 Environment name 与用户 [`GoalSetting`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/entry/GoalSetting.java) |
| `TURN_START` | 打开回合，冻结完整 settings、模型回退时的实际型号 `fallbackModel`、owner Thread、启动原因（`INPUT` / `CONTINUATION` / `COMPACTION` / `STOP`）与解析出的上下文窗口和输出预算 |
| `MESSAGE` | USER / ASSISTANT / TOOL 消息；只有 ASSISTANT 消息可以携带 `providerReplayState` |
| `CUSTOM_MESSAGE` | 可信调用方注入的消息 |
| `MODEL_ATTEMPT_FAILURE` | 与 Provider 无关的重试审计 |
| `ASSISTANT_ERROR` / `ASSISTANT_ABORTED` | 异常与中止屏障 |
| `COMPACTION` | 摘要结果：非空 `summaryText` 与真实模型输出的元数据 |
| `TURN_END` | 关闭回合，保存 [`TurnEndOutcome`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/entry/TurnEndOutcome.java)（`COMPLETED` / `FAILED` / `STOPPED` / `CANCELLED`）与 `continueModel` 延续义务 |
| `NOTIFICATION` | 系统通知（子结果交付、任务预算提醒），对模型投影为上下文 USER，但不提升权限，也不是产品可提交的输入 |
| `CUSTOM` | Contributor 的分支状态，不参与回合文法，默认不投影 |
| `SETTINGS` | 回合间安全边界上的 settings 应用快照，不调度模型 |
| `FORK` | 分支事实节点 `{mode, sourceEntryId, sourceThreadId}`，模型可见文本是固定英文通知 |

执行父子关系不进历史，而是 Thread 行上不可变的 `parentThreadId`，因此 Entry Tree 始终是对话历史而不是运行树。Environment 以全局唯一、不可变的 name 进入 settings，每回合再解析为内部路由身份；目录只出现在每次工具调用自己的 arguments 里，历史中没有目录状态。

[`EntryPath`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/history/EntryPath.java) 是从 `ROOT` 到 head 的不可变连续路径（同 Session、父链相接、ID 不重复、首节点是唯一的 `ROOT`）。[`EntrySlice`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/history/EntrySlice.java) 是同一条链的读取视图：`baseEntryId` 为空时就是这条完整路径；非空时只含该祖先之后、不含游标本身的连续子链，空列表表示游标就是 head。增量不满足 `EntryPath` 的 ROOT 约束，不能当作完整路径使用。[`TurnPathValidator`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/history/TurnPathValidator.java) 在其上校验回合文法：

- `CUSTOM`、`SETTINGS`、`FORK` 是透明节点，不打开也不关闭回合，后两者只能位于回合之间；其余节点必须落在唯一 open 的 `TURN_START` 内，前一回合未关闭时不得再开。
- 每回合至多一个 Assistant 结果（ASSISTANT 消息、`ASSISTANT_ERROR`、`ASSISTANT_ABORTED` 或 `COMPACTION`）；TOOL 消息紧随带 `ToolCall` 的 ASSISTANT 消息，`callIndex` 从 0 起严格连续，调用 ID、工具名与所属 Assistant 精确匹配。
- `INPUT` 回合在 Assistant 结果前至少有一条 USER/CUSTOM 消息；`CONTINUATION` 偿还上一回合的 `continueModel=true`，不消费用户消息；`COMPACTION` 不出现 USER/CUSTOM，成功结果只能是 `COMPACTION`；`STOP` 是显式停止的持久屏障，只含一条 `ASSISTANT_ERROR(CANCELLED)` 与随后的 `STOPPED` `TURN_END`。
- `TURN_END` 只能关闭当前 open 的回合，并按结局校验前置条件。路径可以在任意前缀截断，用于恢复未闭合的回合。

## Thread

[`ThreadState`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/thread/ThreadState.java) 只保存 Thread 自己拥有的事实：创建后不可变的身份（Session、父 Thread、执行根 `rootThreadId`、创建请求哈希、创建时间），可变的 head、名称、执行控制、输入水位、命令序号与 `version`。

- `version` 是结构与控制状态的 CAS 游标，不是快照内容版本：任何非精确重放的变更让它严格 +1，精确重放原样接受；Model 的流式 checkpoint 在同一 version 内推进。名称与 YOLO 由控制面直接更新，同值 no-op，不产生 Command、Entry 或 Work。
- `rootThreadId` 是执行根：根指向自身，子代理继承父的执行根。YOLO 只属于执行根，子代理下一次权限决定现读根上的开关。
- [`ThreadExecutionControl`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/thread/ThreadExecutionControl.java) 只有 `RUNNABLE` / `STOPPED`：Stop 把目标与全部后代置为 `STOPPED`，跨重启保留；显式的新用户输入或新任务只恢复目标本身。`inputThroughSequence` 由 `INPUT` 回合推进；Stop 物化已排队通知时也同步覆盖这些通知的序号，保证三态与 Join 判据对“已消费通知”一致。

对外状态都从 Thread 自身事实派生，不递归子树。[`ThreadSnapshot`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/ThreadSnapshot.java) 包含当前生效的 [`EntryCurrentState`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/history/EntryCurrentState.java) 与返回片段 [`EntrySlice`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/history/EntrySlice.java)，不无条件持有全量 Entry 历史路径。快照的 `runtimeStatus()` 统一为严格三态：`RUNNING`、`WAITING` 与 `IDLE`，与 `executionControl`（`RUNNABLE` / `STOPPED`）完全正交。由 [`ThreadActivityEvaluator`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/thread/ThreadActivityEvaluator.java) 共享最小客观事实判定：本地活跃执行、自身 open owner compaction 或仍有未完成的直接 `SETTLE_OWNER_TURN` 子 Join 始终为 `RUNNING`；待启动输入需求（排队命令或未消费的已应用通知水位）仅在 `RUNNABLE` 下体现为 `RUNNING`，在 `STOPPED` 下保留通知但不自启（明确抑制本地回合启动，不冒充 RUNNING）；本地已让出执行但存在未完成直接 `NOTIFY_PARENT` 子 Join（`incompleteChildJoins > 0`）体现为 `WAITING`；其余完全静止为空闲 `IDLE`。非 `IDLE` 状态下 `outcome()` 强制抑制为 null，严禁在等待子任务或活跃时宣称 `COMPLETED`。需要注意：未确认执行（`UNCONFIRMED`）会让 Thread 保持非空闲；显式 `ABANDONED` 或已确认真实退出不再阻塞，但退出 ACK 只证明本地 body 返回与资源释放完成，不扩张为对远端进程或副作用的普遍事实，因此三态仍不能单独证明外部进程已退出。`executionFacts()` 另给三个独立事实：`hasWork`、`waitingForHuman` 与 `hasUnknownToolOutcome`。

[`ThreadContextClassifier`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/thread/ThreadContextClassifier.java) 是纯函数：输入 ThreadState、`EntryCurrentState`、Driver 按 open 回合锁定的中立状态与 Tool siblings，输出唯一的 [`ThreadContext`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/thread/ThreadContext.java)（`IdleOrHistorical`、`ContinuationDue`、`ModelActive`、`ModelTerminalPending`、`ToolActive`、`ToolTerminalPending`）；任何所有权或配对不一致都直接抛 `IllegalStateException`。快照与 Processor 共用这一分类。

## 命令邮箱

`acceptCommands` 接收一批有序命令与一个 sealed target：

- `NEW_SESSION`：调用方预分配 Session/Thread ID，同一事务创建 Session、ROOT、Thread、Commands 与 THREAD Work；同一 `threadId` 携带相同创建哈希为精确重放，不同哈希为 `THREAD_ID_REUSED`。
- `NEW_THREAD`：在同 Session 的合法切点（ROOT 或已闭合的 `TURN_END`）追加一个 `FORK` Entry 作为新 Thread 的 head，不复制前缀、不移动源 Thread；未闭合的 STOP 回合不可作为切点。
- `NEW_FORKED_SESSION`：把执行根在合法切点处的有效上下文复制到新 Session 并创建独立执行根；根 settings 从切点处的生效快照推导，不复制 Join、命令或调用。
- `THREAD`：续写既有 Thread。精确的有序重放先于一切游标与 preflight 准入；单条纯 `USER_MESSAGE` 不带游标、按锁内邮箱尾部入队，其它批次必须携带 `expectedHeadEntryId` 与 `expectedNextCommandSequence` 并精确匹配（不匹配为 `STALE_COMMAND_CURSOR`）。

命令类型分三类：人工输入 `USER_MESSAGE` 与 `GOAL`，可信调用方输入 `CUSTOM_MESSAGE`（task、自动业务、运行时注入的 `SystemReminder`），系统通知 `NOTIFICATION`。配置命令 `SET_AGENT -> SET_MODEL -> SET_ENVIRONMENT -> SET_CONTRIBUTOR_STATE` 按此顺序位于消息之前，每种至多一次；`TurnPlanBuilder` 只把生效配置冻结进 `TURN_START.settings`，不向模型注入配置提醒。约束如下：

- 创建型 target 与统一 `spawn` 批次必须以恰好一条 user-like 任务输入结尾。
- 既有 Thread 接受纯设置批次：在回合间的安全边界上立即归约为一条 `SETTINGS` Entry，不打开回合、不调度模型、不恢复 `STOPPED`。`SET_CONTRIBUTOR_STATE` 是分支配置，只随普通输入回合生效。
- typed `GOAL` 每次由运行时分配新的 `id` 并追加一条冻结的 USER 消息，旧 `id` 上的进度声明因此不会命中新目标；`text` 为 null 表示显式清除。
- 产品 HTTP 面只接受 `USER_MESSAGE`、`GOAL` 与三个 `SET_*` 前缀，`GOAL` 不能与普通消息同批；`SystemReminder` 必须有完整的开闭定界符，普通消息伪造前缀不会被当作 steering。YOLO 不走邮箱。

命令状态由标记字段派生：无标记为 `QUEUED`，有 `appliedEntryId` 为 `APPLIED`，成对的 `stopRequestId` / `cancelledAt` 为 `CANCELLED`。Session 显示名在创建时从首批末尾 user-like 消息派生（前 40 个码点，无文本时回退 `session-` 加 UUID 前缀），ROOT Thread 固定为 `main`，分支名由调用方给出；手工名称经 [`Names.normalize`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/Names.java) 折叠空白，超过 256 个码点报错，绝不截断。

`getThreadSnapshot` 支持可选的 `afterEntryId` 增量游标（祖先匹配时返回轻量增量 `EntrySlice`，不匹配时回退全量快照），只锁目标 Thread 及其当前适用的 Model/Tool，返回事务内最新已提交的 checkpoint。`getThreadTree` 持执行根的 tree 锁读取同根全部 Thread（同 Session 的其它执行根不在其中），返回轻量只读节点 [`ThreadTreeNode`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/ThreadTreeNode.java)，节点上的 `turnCount`、`toolCallCount` 与 `outcome` 只按各自 root-to-head 路径计算，不构建完整快照，查询不写任何事实。

## ToolInvocation

[`ToolInvocation`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/invocation/tool/ToolInvocation.java) 记录冻结的 `ToolCall`、[`ToolBinding`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/invocation/tool/ToolBinding.java)、所属 Assistant 与 `callIndex`、审批记录、结果与副作用批次：

```text
READY            -> WAITING_INPUT（ask_user）/ WAITING_APPROVAL（Ask）/ RUNNING（Allow 且准入通过）/ FAILED / CANCELLED
WAITING_APPROVAL -> READY（Allowed）/ FAILED（Denied）/ CANCELLED
WAITING_INPUT    -> SUCCEEDED（已作答或明确拒答）/ CANCELLED
RUNNING          -> SUCCEEDED / FAILED / CANCELLED / UNKNOWN / READY（重试）
```

`attempt` 同样在副作用之前持久化；RUNNING 的旧租约恢复一律收敛为 `UNKNOWN`，绝不重放。`WAITING_INPUT` 与 `WAITING_APPROVAL` 刻意分离：问卷不能授权工具，审批也不能代替回答，两者都只占 durable 行，不占 Worker。`ToolBinding` 冻结工具定义、Contributor 归属、环境支持级别与 Environment id/name；无环境选择的 `REQUIRED` 工具在规划时已被过滤，网关对已移除或定义已变化的 binding 返回确定性失败。执行端不写 Store：[`ToolEffectBatch`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/invocation/tool/ToolEffectBatch.java) 只在成功时携带至多 16 条 CUSTOM 副作用，由 ThreadProcessor 在同一事务把结果与副作用物化为 Entry 并删除临时行；真实工具的所有终态都把原 binding 写入结果元数据，删除临时行或 fork 不会丢失原定义。

[`ToolOutcomeQuery`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/ToolOutcomeQuery.java) 按 Thread 和 invocation 身份查询稳定终态：调用行仍在时读取 terminal Invocation，物化后从当前路径的 ToolResult Entry 读取，并校验所属 Thread 与 Turn。返回的 [`ToolOutcomeView`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/ToolOutcomeView.java) 保留调用身份、终态与稳定内容；`resultError` 是模型可见的业务错误标志，与生命周期 `status` 正交，执行 `SUCCEEDED` 也可能携带业务错误。

## Work

[`Work`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/work/Work.java) 是每个 target 一行的调度邮箱：`availableAt`、`wakeVersion`、租约与可选的 `requiredEnvironmentId`。所有跃迁都是纯函数：`request` 把 `availableAt` 提前并让 `wakeVersion` +1，`claim` 写入租约，`complete` 在 `wakeVersion` 未变时删除行、被新唤醒推进时只清租约，`reschedule` 清租约且不让旧 claim 推迟新唤醒。租约 token、有效期与认领时的 `wakeVersion` 共同拦截迟到的旧执行体。

时间只有一个权威域：生产是数据库时钟，内存实现是测试时钟；持久化时间戳都取自事务的 `tx.now()`。时间只记录事实发生的时刻，不承担排序——Entry 顺序由父链表达，推进由 version 与状态机表达。`requiredEnvironmentId` 只在 TOOL Work 上允许非空，THREAD 与 MODEL Work 必须为空；它在首次创建时冻结、之后不可改变；按当前节点是否持有该 Environment 的 READY 连接租约分发由 Infra 实施。

## Processor

Thread claim 只归约一个持久化动作；Model / Tool claim 驱动一次外部调用，期间续租、提交 checkpoint 与终态。后续调度由同事务的 `requestWork` 驱动。

[`ThreadProcessor`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ThreadProcessor.java) 校验 claim 与准入后取执行树锁，按分类结果行动：

- `MODEL_TERMINAL_PENDING`：写入 Assistant / Error / Compaction 结果；关闭回合或进入 Tool 阶段。
- `TOOL_TERMINAL_PENDING`：按 `callIndex` 写入副作用与 Tool 结果，追加 `TURN_END(continueModel=true)`，删除调用行。
- `MODEL_ACTIVE` / `TOOL_ACTIVE`：外部执行仍在进行，归还 claim。
- `CONTINUATION_DUE` / `IDLE_OR_HISTORICAL`：先处理适用的自动压缩；有排队输入或通知需求时启动 `INPUT` 回合并一次性消费冻结 cutoff 内的完整队列，否则偿还续写义务或直接完成。

需要规划的回合在单个事务内完成：锁 Thread、捕获命令快照、构造只存在于内存的 candidate 路径，同步调用 Driver 解析，机械校验其结果与 candidate 一致后提交 `TURN_START`、消息、命令标记与 Driver 私有 generation。只有显式的 `TurnResolveTransientException` 在回滚后按失败延迟重排，其它解析失败落 durable `FAILED` 终态，绝不静默丢失 Work。

[`ToolProcessor`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ToolProcessor.java) 的短事务只锁 own Thread -> Model -> Tool -> Work。执行根开启 YOLO 时直接放行，否则在事务外调用 `ToolGateway.preflight`：`Allow` 进入执行，`Ask` 转 `WAITING_APPROVAL`（可附带编辑审查），`Deny` 转 `FAILED`。放行后同样经 `admit` 准入、先持久化 RUNNING，再由 [`ToolExecution`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ToolExecution.java) 在独占虚拟线程上执行；终态提交之后才释放并发租约与目录租用，因此取消只需 interrupt。只有 `retryable` 且工具为 `READ_ONLY` / `IDEMPOTENT` 的失败才重试；`UNKNOWN` 是终态，与其它终态一样物化为失败结果交给下一轮模型，不阻塞 Thread。工具增量只支持文本与 JSON，作为有损 `TOOL_PARTIAL` 发布。

Model / Tool Processor 以组合方式共用公开的已认领执行骨架 [`ClaimedExecutionSupport`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ClaimedExecutionSupport.java)（Driver SPI）：所有权校验、按 invocation 的准入与 execution 登记、派发前的租约 margin、本地取消 / 关闭与活跃执行查询只实现一次，Processor 自己只负责请求物化、Gateway 准入与终态提交。具体准入由同一类级强类型 `ClaimAdmissionGuard` 按 invocation 原子协调，事务内最终所有权围栏失败一律抛出唯一的 [`ClaimLostSignal`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ClaimLostSignal.java)。准入操作在原子计算中复核数据库 claim 所有权，不同有效 token 可替换旧 execution，捕获后在锁外执行 abandon；相同 token 去重，失权 claim 不得取消当前执行。关闭守卫确保 close 期间迟到准入不创建 slot 执行，未 attach 的 ticket 在 close 或 cancel 后拒绝 attach；准备与外部调用均在准入锁外进行，最终持久化仍须通过 durable ownership fence。

每个具体执行代次在进入 body 或发出远端调用前登记为独立的退出事实，不依赖逻辑 Invocation 行是否仍存在。admission 取得的每个 run 在失权、取消或离场时都恰 close 一次；body 返回且本地 admission close 成功后，执行线程才确认 `STUDIO_EXITED`；远端子执行在完整 identity 匹配后确认 `REMOTE_ACKNOWLEDGED`。已登记但确定未进入 body 的代次，只在 close 成功后确认 `NOT_STARTED`；close 失败保留 `UNCONFIRMED`，不得偷偷二次 close 或确认。取消、lease 丢失与逻辑终态本身都不等于退出：body 开始后仍须等待真实返回并完成资源释放，未进入 body 的已排队任务也由统一 finally 收敛。最后一次真正改变退出事实的 ACK 在同一事务递增 Thread version；若普通终态已物化则直接补结算 Join，否则只有存在源命令已应用的未完成 Join 才请求 THREAD Work。重复 ACK 不重复 version、wake 或通知；STOPPED Thread 在 ACK 事务内递归结算已收敛祖先。

### 退出确认与放弃

真实退出证据只有 `STUDIO_EXITED`、`REMOTE_ACKNOWLEDGED` 与 `NOT_STARTED` 三种。确认入口在读取或提交数据库之前先把完整执行身份与证据时刻写入本机 write-ahead journal；只有确认与结算事务提交成功后才删除记录。同一 execution 的首次 canonical proof 在进程内按 identity、target 与观测时刻冻结，并发回调与内存重试复用该时刻，不因后一次重试刷新 `confirmedAt`。worker 每 1 秒调度一次待重放扫描，只尝试已到重试时刻的记录；读取 journal 时健康记录继续重放，无法解码、身份不符或类型异常的文件只进入逐文件失败快照，原文件保留且按文件身份去重告警；目录无法枚举、journal 已关闭或目录锁占用仍整体失败。journal 目录由单个 Studio 实例独占并持久化，文件锁在进程生命周期内持有；journal 写入只证明正向证据已落盘，不证明远端进程已经退出。

同一 execution 的确认在 64 个稳定 stripe 上以 `ReentrantLock` 串行化，journal 的全局锁也使用 `ReentrantLock`，避免这两处 monitor 在阻塞时固定载体线程；文件 I/O 本身仍可能阻塞载体，不代表消除了全部阻塞来源。数据库或文件瞬态失败按 1/2/4/8/16/30 秒上限退避，持续故障只首轮告警，成功后重置；目录枚举失败也按该退避重试，但不阻断内存 proof。事实缺失以及 canonical proof 与 journal/数据库的真实 identity/target 冲突保留证明并按每 30 秒低频核对，同一原因只告警一次，不阻断其它记录；人工修复后可恢复确认。通过 journal 持久化与身份校验的证明，也能在观察到合法深删且核对事务提交后清理。被拒绝的新输入不改变原 proof 的退避状态，显式再次确认仍会立即重新核对。journal 删除失败不回滚已提交的确认事实，保留 proof 并按退避重试。首次 journal 写入失败时 proof 仅保存在本进程内等待重试，不能据此宣称跨进程 durable。

`ABANDONED` 是用户显式放弃继续等待确认的独立事实，不是物理退出，也不能伪造 ACK。只有 `UNCONFIRMED` 阻止收敛；首次放弃要求 Thread 已 `STOPPED`、执行属于该 Thread 且 `expectedVersion` 精确匹配，重复放弃或已真实确认是安全 replay，容忍旧 version。真实证据到达后可以把 `ABANDONED` 升级为已确认状态并保留原 `abandonedAt`；两种时刻都不得早于 `registeredAt`，但 `confirmedAt` 可以早于 `abandonedAt`，绝不改写观测时间。退出事实行（包括放弃行）保留到 Thread 深删除，没有 TTL，也不以 lease 到期、`cluster_node` 路由记录或 journal 存在推断宿主已死亡。

Thread 由轻量 `ThreadAdmissionGuard` 独立防重，旧 action 未结束时同 token 或异 token 均返回 LOST，不抢占。

preflight 创建的 execution 在返回 `STARTED` 前由 Processor 负责清理：Allow、Ask、Deny 的回写失败或启动失败均停止 heartbeat 并释放本地登记；成功移交后由执行线程负责释放，不能再由 preflight 清理。两类 Processor 共用 [`WorkHeartbeat`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/WorkHeartbeat.java)：调度器只做周期唤醒与非阻塞分派，独立 worker 执行续租事务；续租失败、调度拒绝或分派拒绝都视为失去所有权，统一触发一次 `onLostOwnership`。

### 审批与受管文件变更

`decideToolApproval` 只在工具处于 `WAITING_APPROVAL` 且 Thread 处于 `ToolActive` 时接受：`ALLOW` 转 READY 并安排 TOOL Work，`DENY` 转 FAILED 并唤醒 THREAD；相同决策幂等，不同决策为 `APPROVAL_DECISION_MISMATCH`。需要人工审批的环境 `fs.edit` 必须携带有效的编辑审查（canonical 路径、全量 diff 与原始字节 SHA-256），否则网关在调用 transport 前以 `EDIT_REVIEW_REQUIRED` 拒绝；审查中的 `expectedFile` 作为内部元数据下发，Daemon 在文件锁内核对哈希，审查后文件变化即拒绝写入。成功后权威 diff 保存在结果的 `details.editReview`。YOLO 与自动 Allow 不要求审查快照。

## 压缩

压缩回合自身不调用模型：父 Thread 打开 `TURN_START(COMPACTION)`，在同一事务里经统一 [`AgentSpawner`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/AgentSpawner.java) 创建新 Session 上的子 Thread、首条摘要命令与 `SETTLE_OWNER_TURN` 票据，然后在 durable wait 中等待结算：

```text
父 TURN_START(COMPACTION) -> COMPACTION(summaryText) -> TURN_END
子 TURN_START(INPUT)      -> Driver generation（Compaction Agent）-> ASSISTANT MESSAGE(summaryText) -> TURN_END
```

自动压缩的阈值判定由各 Driver 通过 `AgentDriver.prepareAutomaticCompaction` 提供；Builtin 的 owned `HISTORY` -> hard overflow -> eligible threshold 策略见 [Harness Agent Builtin](harness-agent-builtin.md#自动压缩)。Runtime 只负责压缩回合结构与子执行结算。

子执行使用目录中的 `Compaction` 内置 Agent，约束为父回合的输出预算、禁用 prompt cache、禁止自动压缩；[`CompactionStart`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/compaction/CompactionStart.java) 冻结阶段、触发原因、执行模型、输出预算、切分锚点与子身份，后来排队的输入不会污染它。

[`CompactionConfig`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/compaction/CompactionConfig.java) 只有 `keepRecentTokens` 一个参数，其余阈值都由它与上下文窗口 `C`、输出上限派生：

```text
effectiveKeep    = min(keepRecentTokens, C/2)
effectiveReserve = min(16384, maxOutputTokens)
softThreshold    = max(effectiveKeep, C - effectiveReserve)
manualMinimum    = min(keepRecentTokens*2, C/2)
```

[`CompactionPlanner`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/compaction/CompactionPlanner.java) 是纯函数：从尾部估算长度，越过 `effectiveKeep` 后取下一个合法切点，绝不切在 Tool 结果或压缩回合内部；切点落在 Agent 轮次中间时先出 HISTORY 摘要，再由续写义务驱动 TURN_PREFIX 摘要。最新一批尚未被后续 assistant 消费的工具调用不会被提前摘要。[`CompactionResultEvaluator`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/compaction/CompactionResultEvaluator.java) 把截断、过滤、返回工具调用、空摘要、保留段解析失败与无 token 收益判为失败；可重试的失败走普通调用重试预算，耗尽则子执行失败、父回合失败且不交付部分结果。

手工压缩走 `compactThread`：单事务复验 expected version 与可用性，成功时原子提交压缩回合、子 Thread 与票据。[`ManualCompactionAvailability`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/ManualCompactionAvailability.java) 是给界面的瞬时投影（Thread 已停止、忙碌、模型已变化、低于最小量、无可压缩内容等），不能替代提交时的复验。`STOPPED` 是持久执行事实：即使历史仍满足压缩阈值，`manualDecision` 也在 resolve 与任何写入之前直接返回 `THREAD_STOPPED`；先由显式新输入恢复为 `RUNNABLE` 才允许再次压缩。

## 停止

Stop 在 tree 锁内校验目标 version 与 `stopRequestId`，按 `parentThreadId` 停止目标及全部后代（目标已空闲或已停止时仍覆盖后代）。事务先物化已提交的 Model/Tool 结果，再闭合在途回合、清理调用与 Work；只有退出事实与直接子 Join 已收敛时才冻结 Join 结果，否则保留未完成 Join，由后续退出 ACK 推进。无 open 回合时追加 `TURN_START(STOP) → ASSISTANT_ERROR(CANCELLED) → TURN_END(STOPPED)` 屏障；已有 open 回合时按真实结果闭合，必要时补齐未知工具结果，绝不嵌套回合或伪造成功。

[`StopResult`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/StopResult.java) 返回完整受影响集合；每个 [`StoppedThreadReceipt`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/StoppedThreadReceipt.java) 记录停止边界、取消数量与被退回的人工输入（`USER_MESSAGE` / `GOAL`）。`CUSTOM_MESSAGE` 与设置命令被取消但不退回；`NOTIFICATION` 不取消，而是物化进历史、同步推进 `inputThroughSequence` 且不唤醒模型。整次 Stop 按 `(root_thread_id, root_stop_request_id)` 持久化：精确重放返回原集合、不重停新工作，后代的派生身份被当作新请求复用时为 `STOP_REQUEST_ID_REUSED`。

显式新输入恢复 STOPPED 目标前，必须已无 `UNCONFIRMED` 执行，且没有未完成的直接 `NOTIFY_PARENT` 或 `SETTLE_OWNER_TURN` 子 Join；`ABANDONED` 或已确认执行不阻塞恢复。设置类维护输入不触发恢复，因此不受此限制。已收敛后代的退出 ACK 可递归完成祖先的 Join 结算，不依赖祖先重新启动。

## 人工输入与权限

`ask_user` 不经过 preflight 或网关：ToolProcessor 在锁内把 READY 冻结为 `WAITING_INPUT`，问卷由调用 arguments 冻结。`submitToolInput` 在单事务内校验调用仍处于当前 `TOOL_ACTIVE` 上下文，按问卷校验并规范化答案，答案与提交回执同事务落盘（答复或明确拒答都收敛为 `SUCCEEDED`），随后唤醒 THREAD 物化结果；答案非法、目标不适用与提交身份冲突分别为 `INPUT_SUBMISSION_INVALID`、`INPUT_SUBMISSION_NOT_APPLICABLE` 与 `INPUT_SUBMISSION_MISMATCH`。`listPendingInteractions` 按 `(createdAt, id)` keyset 分页读取两种等待态，产品归属由上层解析。

[`PermissionEvaluator`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/permission/PermissionEvaluator.java) 按有序规则产出 Allow / Ask / Deny 候选：规则键是 `*` 或模型可见工具名，从全局到具体工具求值，数组顺序即求值顺序；文件路径取调用显式的绝对 `path`，按 gitignore 语义匹配，非绝对路径（如 `kkstudio:` URI）只命中通配规则。工具不携带 `workdir`，不存在隐藏默认目录。真实文件边界、符号链接与进程隔离由 Environment Daemon 在执行入口落实。

## 子智能体委派与 Join

内置 `task` 工具只做一次持久接受：平台经 `AgentTaskService` 调用统一 `spawn`，在同一事务接受源 prompt、创建或续用子 Thread、写入 Join 票据并请求 Work，然后立即回执 `Task accepted. thread_id: <uuid>.`。子结果不经过工具返回，而是在子执行收敛后作为父 Thread 的一条系统通知交付。

`harness_thread_join` 的一行只记凭据：调用身份与请求指纹、父子 Thread、源命令序号、agent 与 `maxTurns`、交付方式，以及一次冻结的 `terminalEntryId` / `finalAnswerEntryId` 与交付命令序号（并发额度由 `delivery == NOTIFY_PARENT` 直接推导，不设独立字段）。[`JoinDelivery`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/join/JoinDelivery.java) 有三种：

- `NOTIFY_PARENT`：task 子执行，计入额度；同一事务校验父可接受本次调用、父 head 匹配与深度/并发额度，父已 `STOPPED` 时拒绝新委派。续用只能向既有子追加 prompt，不能改父关系。
- `RECEIPT_ONLY`：没有父的内部 root 任务，不计额度，只供票据观察。
- `SETTLE_OWNER_TURN`：压缩等运行时子执行，不计额度，结算后只唤醒父 Thread 的 open 回合；父已 `STOPPED` 时只推进一次 owner `version` 使结算后的派生状态可见，不请求 Work、不自动恢复执行。

同一父子对至多一个有效未完成 Join；子 Thread 无未完成的 `SETTLE_OWNER_TURN` 时，新续接可取代旧等待，否则须完成 owner 结算后才可继续。精确重放仍返回原票据。

普通结算要求：源命令已应用；没有尚未冻结结果的直接子 Join（含 owner 结算义务）、本 Thread 没有待处理输入且没有 `UNCONFIRMED` 执行——`ABANDONED` 或已确认执行不再阻塞，因此多级委派自底向上自然收敛，既不等于首次本地 Idle，也不等待整棵子树永久静止。Stop 结算允许纳入尚未应用的源命令，但仍要求同一收敛事实。普通终态已物化但 Join 仍被未确认执行阻挡时，最后一次退出 ACK 在同一事务内补结算，不依赖后续 Work 恰好再次到达。普通终态物化与这次迟到确认共用同一最终回答规则：只有 `COMPLETED`、无 `continueModel` 义务且紧邻 `TURN_END` 之前是 ASSISTANT 消息时才冻结 `finalAnswerEntryId`；失败、续作与非 ASSISTANT 结尾一律为空。结果固定在源命令应用位置到终态边界的历史范围内，子树后续推进不改写旧回执。`maxTurns` 是软预算，超出时在规划中物化一条 `TASK_BUDGET` 通知，每个 Join 只提醒一次。

结算与父通知入队在同一事务完成：`NOTIFY_PARENT` 结算原子冻结终态结果、写入父 `NOTIFICATION` 命令、推进父序列并请求 Work，通过唯一次 `updateJoin` 直接落盘为 delivered 状态，不持久化已匹配但未送达的中间态。通知的消息由 `runtime.join` 纯函数渲染（`<subagent_result>` 包裹任务原文与结果），不读取子线程当前 head。显式停止的父不会被唤醒：通知直接物化进历史，待父接受新输入后由 `INPUT` 回合消费。

## 端口与策略

[`port`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/port/) 包定义内核对外部设施的反向依赖，不泄漏 Spring、JDBC 或 HTTP 类型：

| 端口 | 契约 |
| --- | --- |
| [`ToolGateway`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/port/ToolGateway.java) | `preflight` 返回 `Allow` / `Ask` / `Deny`；`admit` 接收携带审批快照的执行请求，返回 `Admitted(run)` / `Busy` / `Rejected`；`Run.close` 释放本地资源 |
| [`ToolResultHistoryMaterializer`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/port/ToolResultHistoryMaterializer.java) | 在 Tool 结果 Entry 插入前、同一事务内把瞬时 Resource 外部化为 durable 内容 |
| [`ToolHistoryActionResolver`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/port/ToolHistoryActionResolver.java) | 按冻结 binding 找到 Tool 并渲染历史动作文本 |
| [`RealtimeEventSink`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/port/RealtimeEventSink.java) | best-effort 写入有损的 delta / checkpoint / partial 事件，失败不回滚任何事实 |

三种准入结论各对应唯一的 durable 后续：`Busy` 确定未开始，安全重排；`Rejected` 是确定性失败；`Admitted` 先落 RUNNING 再执行，此后请求或副作用都可能已发生，Model 记一次失败 attempt、Tool 收敛为 `UNKNOWN`，严禁盲目重放。内核不反查 owner、Issue 或阶段等产品门禁，需要终止在途执行时由业务显式调用 Stop。

[`ConcurrencyAdmission`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/admission/ConcurrencyAdmission.java) 提供无等待队列的进程内并发槽位；`InvocationRetryPolicy` 只计算是否允许下一次重试及其延迟（`FIXED` / `EXPONENTIAL`），不读时钟、不写状态。策略对象全部构造注入：[`ThreadProcessorConfig`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ThreadProcessorConfig.java)、[`ToolProcessorConfig`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ToolProcessorConfig.java) 与共用的 [`ProcessorLeaseConfig`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ProcessorLeaseConfig.java)（heartbeat 间隔必须严格小于租约时长），重试与压缩配置在每个决策点现读。Builtin 的 `ModelProcessorConfig` 与模型重试策略在 [Harness Agent Builtin](harness-agent-builtin.md) 维护。

## Tool 契约

Tool 的身份、定义、调用与结果是模型、配置、权限、目录与持久记录共享的同一份值契约，位于 [`tool`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/tool/) 包。

[`ToolDescriptor`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolDescriptor.java) 的 `name` 是工具唯一的 Agent 侧身份：模型调用、Agent 配置、权限规则、目录条目与 durable binding 都用它，没有第二套内部 ID 或工具版本。名字字母开头、只含字母数字与 `_`、`-`，至多 64 字符；`inputSchema` 复用 Common 的 `InputSchema`；`defaultTimeout` 为整毫秒，`0` 表示没有 deadline；[`ToolSideEffect`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolSideEffect.java) 的 `READ_ONLY` / `IDEMPOTENT` / `NON_IDEMPOTENT` 决定重试与未知结果的处理。[`AgentToolDefinition`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/tool/AgentToolDefinition.java) 把 descriptor 与 [`ToolVisibility`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolVisibility.java)（`SELECTABLE` / `INTERNAL`）组合为模型契约。

[`ToolCall`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolCall.java) 原样保存模型给出的调用 ID、工具名与参数 JSON object；转换只发生在显式的 `validateFor(descriptor)` 中：先要求工具名一致，再按 schema 归一化数字字符串与可缺省的 null，最后严格校验并返回新调用。原始 function call 仍保存在模型结果中，供审计与 wire replay。

[`ToolResult`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolResult.java) 由调用 ID、至多 64 项 `ResultContent`、至多 1 MiB 的 `detailsJson` 与 `error` 标记组成，失败也作为语义结果交给模型。可持久化的 [`ToolResultJsonCodec`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/tool/codec/ToolResultJsonCodec.java) 拒绝 Binary：内存字节必须先在执行边界外部化为受管 Resource。结果预算由 [`ToolResultSizeLimits`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ToolResultSizeLimits.java) 按 canonical JSON 计算（终态 1 MiB、partial 256 KiB），Resource 声明的预览与文本元数据由平台的 [`ToolResultFinalizer`](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/tool/gateway/ToolResultFinalizer.java) 按实际字节复核。模型可见的工具错误统一使用 [`ToolErrorGuidance`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolErrorGuidance.java) 的三段式英文文案：发生了什么、执行事实、下一步怎么做。

## Contributor 与目录

Contributor 是受信 Java 扩展的接入契约（[`contributor.api`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/) 包）：它声明工具、分支自定义状态与上下文投影，内置工具与插件走同一条装配路径。

[`HarnessContributor`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessContributor.java) 只有 `descriptor()` 与 `contribute(registrar)`。[`ContributorDescriptor`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ContributorDescriptor.java) 给出 canonical id、展示名、版本与 `requires`；[`HarnessRegistrar`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessRegistrar.java) 提供 `registerTool`、`registerCustomEntryType` 与 `registerContextProjector`，在 `contribute` 之外调用立即失败。

[`HarnessCatalog.from`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessCatalog.java) 是唯一的冻结入口：先拒绝重复 id、缺失或成环的 `requires`，再按依赖拓扑与 id 字典序调用 `contribute`；注册时强制模型可见工具名全局唯一、自定义类型按 `(contributorId, customType)` 归属，冻结时要求每个工具声明的状态访问都命中本 Contributor 注册过的类型。平台在构建每个插件快照时冻结一次目录（见 [Platform](platform.md#构建期-plugin)）。

[`Tool`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/Tool.java) 是统一的阻塞执行契约：

```java
ToolDescriptor descriptor();
default ToolRequirements requirements();                  // 默认无环境、无状态访问
default Duration resolveTimeout(ToolCall call);           // 默认 descriptor.defaultTimeout()
default Optional<ToolHistoryRenderer> historyRenderer();  // 默认 absent
ToolOutcome execute(ToolExecutionRequest request, Consumer<ToolResult> progress) throws InterruptedException;
```

进入 `execute` 前调用状态已持久化为 RUNNING：返回即终态，业务失败返回 error 结果，运行时异常收敛为结果不确定；取消、Stop 与租约丢失都表现为 interrupt，实现必须响应中断并释放资源。`resolveTimeout` 是执行前唯一的超时解析点，非法的参数级超时抛 `IllegalArgumentException`，由网关收敛为确定性拒绝。[`ToolExecutionRequest`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolExecutionRequest.java) 携带已归一化的调用、已解析的超时与可选执行上下文，不携带 workdir。[`ToolHistoryRenderer`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolHistoryRenderer.java) 是可选纯函数，在原生 Tool 历史无法交给 Provider 时把调用渲染为自然语言动作。

[`ToolRequirements`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolRequirements.java) 冻结三项声明：

```text
environmentSupport       NONE / OPTIONAL / REQUIRED
requiredEnvironmentId    精确要求的目标 Environment；非空时 support 必须为 REQUIRED
stateAccesses[]          访问的分支自定义状态及 READ / WRITE 意图
```

`REQUIRED` 工具只在分支已选择 Environment 时进入模型工具面，执行时经 [`BoundEnvironment`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/BoundEnvironment.java) 阻塞调用一个能力，发送确定性由异常类型表达（Busy/Unavailable 确定未执行，SendUncertain 可能已被接受）。分支状态只经声明修改：[`BranchView`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/BranchView.java) 只读当前 Contributor 在当前分支上的自定义状态与用户 Goal；[`ToolOutcome`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolOutcome.java) 携带结果与有序的 [`AppendCustomEntry`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/AppendCustomEntry.java)（错误结果不得携带），与 Tool 结果在同一事务追加。同一 Assistant 的工具共享冻结快照，因此某状态已被先前 sibling 声明 WRITE 时，后续访问在派发前被确定性拒绝。[`ContextProjector`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ContextProjector.java) 把 `BranchView` 纯函数地投影为片段，平台按目录顺序把它们拼进本次请求唯一的系统指令。

新增 Contributor：实现 `HarnessContributor`（常用 `HarnessContributor.of`），按实际执行面声明环境支持级别；维护分支状态时先注册自定义条目类型再声明访问；内置实现注册为 Spring bean，插件实现从配置实例返回（见 [Platform Plugin API](platform-plugin-api.md)）。

## 内置工具

[`BuiltinHarnessContributor`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/builtin/BuiltinHarnessContributor.java)（id `builtin`）注册第一方工具与 `goal.progress` 自定义条目类型：

| 模型可见 name | 依赖 | 可见性 | 说明 |
| --- | --- | --- | --- |
| `read` | Platform + Environment（OPTIONAL） | SELECTABLE | `kkstudio:` URI 或 `fs.read`，READ_ONLY |
| `write` / `edit` | Environment | SELECTABLE | `fs.write`（IDEMPOTENT）/ `fs.edit`（NON_IDEMPOTENT） |
| `bash` | Environment | SELECTABLE | `process.exec`，NON_IDEMPOTENT |
| `grep` / `find` | Environment | SELECTABLE | `fs.grep` / `fs.find`，READ_ONLY |
| `lsp_goto_definition` / `lsp_workspace_symbols` / `lsp_java_decompile` | Environment | SELECTABLE | 对应 `lsp.*` 能力，READ_ONLY |
| `task` | 无 | INTERNAL | 委派 Subagent 任务，NON_IDEMPOTENT |
| `ask_user` | 无 | SELECTABLE | 冻结问卷并等待人工回答 |
| `get_goal` / `update_goal` | READ / WRITE `goal.progress` | SELECTABLE | 读取用户 Goal 与进度；声明当前 Goal 的终态进度 |

每次请求的工具面由平台从目录与 Agent 配置解析：配置了子代理时加入 INTERNAL `task`，未选 Environment 时过滤宿主工具，`read` 仍保留平台资源读取。宿主工具由 [`EnvironmentCapabilityTool`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/builtin/environment/EnvironmentCapabilityTool.java) 绑定到一个能力，schema 与默认超时取自能力描述符，说明文本在 [`environment/prompts`](../../harness/runtime/src/main/resources/fun/fengwk/kkstudio/harness/builtin/environment/prompts/)。`read` 对 `kkstudio:/skills/...` 读取平台当前发布的 Skill，对 `kkstudio:/resources/<blobId>` 按本次调用的 Thread 校验 Session 引用，对绝对本地路径委托 `BoundEnvironment`。

Goal 正文由用户经 typed `GOAL` 命令写入 branch settings，Agent 只能读取它并声明终态进度：[`GoalProgress`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/builtin/goal/GoalProgress.java)（`goalId`、`complete | blocked`、非空 `reason`）由 [`GoalProgressCodec`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/builtin/goal/GoalProgressCodec.java) 严格编解码；`update_goal` 绑定执行时生效的 `goalId`，没有 Goal 或已有终态声明时拒绝。[`TaskTool`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/TaskTool.java) 把参数交给只接受、不等待的 [`SubagentRunner`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/SubagentRunner.java)，以本次调用为幂等键；[`SubagentConfig`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/SubagentConfig.java) 的深度、单父并发、全局并发与轮数软预算在每个决策点现读。文件窗口读取、LSP 生命周期与异步委派的行为契约见 [内置工具与异步委派](builtin-tools-design.md)。

## Prompt 模板

[`PromptTemplate`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/prompt/PromptTemplate.java) 只识别 `${name}`，变量名匹配 `[A-Za-z_][A-Za-z0-9_]*`，构造时拒绝未闭合的 `${` 与非法变量名；`render` 要求传入的变量集合与模板声明的集合精确一致，值按字面量写入、不做二次解析。[`PromptTemplateLoader`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/prompt/PromptTemplateLoader.java) 按 classpath 读取 UTF-8 文本并缓存解析结果，资源缺失立即失败，prompt 装配错误因此在首次使用时暴露，而不会退化成空模板。

## 严格 JSON 契约

持久状态与 wire 载荷由各自的 Jackson 3 codec 校验：拒绝重复键、尾随内容，以及未声明、缺失或类型错误的字段；自由 JSON 子树（厂商选项、Contributor state、CUSTOM payload、native replay、工具详情）允许自有字段，并以 `BigDecimal` 搬运，`1e309` 与高精度小数不会经 double 丢失数量级或有效位。以字符串保存的 `argumentsJson`、`detailsJson`、`rawUsageJson` 保留原文；厂商选项的 canonical 编码移除无意义的尾零，无法映射为 JSON 的输入明确拒绝。

## 包架构

生产源码位于 `fun.fengwk.kkstudio.harness` 之下，表中省略该前缀：

| 包名 | 职责与边界 |
| --- | --- |
| `runtime` | 同步控制面 `HarnessRuntime` 与包私有控制事务（接受、停止、手工压缩、人工输入）；守卫锁序，执行状态流转委托 `runtime.driver` 与 `runtime.processor` |
| `runtime.admission` | 进程内无等待的并发槽位，不持久化、不跨进程协调 |
| `runtime.compaction` | 压缩阈值、切分规划、结果评估与子执行 spawn；规划与评估是纯函数，具体自动压缩策略由 Driver 提供 |
| `runtime.driver` | 内部 Agent Driver 契约与中立投影：Turn 解析/提交、锁定状态、活动投影与 Stop 结果；不读取具体 Driver 私有状态 |
| `runtime.entry` | 分支配置与回合生命周期值：`BranchSettings`、`GoalSetting`、启动原因与结局 |
| `runtime.execution` | 具体执行代次的窄退出账本：身份登记、单向真实 ACK、显式放弃、journal write-ahead 重放与确认后的 Join 结算推进；不复制 Invocation 状态机 |
| `runtime.history` | Entry、`EntryPath`、`EntrySlice`、回合文法校验与历史 JSON 编解码 |
| `runtime.input` | `ask_user` 问卷与答案的纯值校验和规范化 |
| `runtime.interaction` | 等待输入/审批的只读投影与稳定分页，不持有产品归属 |
| `runtime.invocation.codec` | Tool 持久化列的严格确定性 JSON 编解码；Builtin 的 `ModelRequestSpec` codec 在 driver 模块 |
| `runtime.invocation.model` | 公共子代理绑定值 `SubagentBinding`；Builtin 的 `ModelInvocation` 与 `ModelRequestSpec` 在 driver 模块 |
| `runtime.invocation.tool` | `ToolInvocation` 状态机、`ToolBinding`、审批状态与副作用批次；不执行工具 |
| `runtime.join` | Join 票据、一次终态冻结、回执投影与完成通知渲染的纯函数 |
| `runtime.permission` | 权限规则模型与求值、Bash 命令面分析；只产出策略候选 |
| `runtime.port` | 内核对外部设施的窄端口：Tool 网关、结果物化、历史动作与实时事件；Model/Turn 端口在 driver 模块 |
| `runtime.processor` | Thread / Tool Processor、准入后的阻塞执行、租约心跳与 Processor 配置；同时向 Driver 暴露公开的已认领执行骨架 `ClaimedExecutionSupport`（所有权校验、准入、租约 margin、取消 / 关闭、唯一的 `ClaimLostSignal`），MODEL Work 在 driver 模块 |
| `runtime.realtime` | 有损实时事件及其编解码；恢复以 durable 快照为准 |
| `runtime.retry` | 调用重试与 HTTP 状态策略，只计算可行性与延迟 |
| `runtime.session` | Session 实体、不可变消息内容块与 Assistant 元数据 |
| `runtime.store` | `HarnessStore` 事务原语，领域模型与物理持久化的唯一边界 |
| `runtime.thread` | Thread 状态、执行控制、上下文分类与状态投影 |
| `runtime.thread.command` | 命令邮箱实体与 typed 字段归约 |
| `runtime.tool` | 工具调用错误值及其编解码 |
| `runtime.work` | 调度邮箱的纯函数跃迁、租约与环境亲和标记 |
| `tool` | Tool 身份、定义、调用、结果与错误文案的值契约 |
| `tool.codec` | Tool 定义与结果的严格 codec |
| `contributor.api` | Contributor SPI、`Tool` 执行契约、分支视图与冻结目录 |
| `builtin` | 第一方 Contributor 与内置工具的历史渲染 |
| `builtin.environment` | 绑定环境能力的宿主工具与统一 `read` |
| `builtin.goal` | `get_goal` / `update_goal` 与 `goal.progress` 条目 |
| `builtin.input` | `ask_user` 工具定义 |
| `builtin.subagent` | `task` 工具、委派请求与子代理配置 |
| `prompt` | 严格 prompt 模板与 classpath 加载 |

`tool`、`contributor`、`builtin` 与 `prompt` 是契约包，绝不 import `harness.runtime.*` 的实现；`runtime` 也不 import `builtin`。这些方向由仓库检查 [`check.mjs`](../../scripts/dev/verify/repository/check.mjs) 守护。

## 源码与测试

- 内核测试集中在 [`harness/runtime/src/test`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/)：控制面、Thread/Join、历史与 Store 契约都在本模块内运行，`mvn -pl harness/runtime -am verify` 可独立完成测试与覆盖门禁。
- Runtime test-jar 是唯一中立 kernel 测试基座：提供内存 Store、Store 契约基类、`StoreTestSupport` 与 `FakeAgentDriver`。`agent-builtin` 与 `infra` 的测试通过该 test-jar 复用中立内核 fixture；`agent-builtin` 自己的 test-jar 只导出 Builtin 私有模型语义支撑，二者职责不混。
- 内存参考 Store 与 PostgreSQL 实现同构：`InMemoryHarnessStore` 只持有唯一已提交快照与全局 monitor，`InMemoryHarnessTransaction` 负责复制快照、commit/rollback、事务时钟、id 分配与锁阶梯，九个聚合原语各由一个 `InMemory*Store` 类实现，Driver 私有行经事务核心公开的测试 hook 在同一事务快照内挂载。锁阶梯为 `Session → Thread → Command → Driver → Tool → Work`，不存在第二份状态或按聚合拆分的快照。
- `FakeAgentDriver` 是 `AgentDriver` 的最小确定性实现：默认 no-op，可配置 turn resolution、活动投影、响应物化与停止结果。其私有状态只存在内存、不参与 Store 事务回滚，因此只用于不验证 Driver 私有持久化的内核测试。
- 关键类覆盖由模块自身 `verify` 阶段的 native `jacoco:check` 校验：Runtime 负责 29 个 runtime/kernel 类，逐类 `LINE` 覆盖率不低于 90%。Builtin 的 5 个关键类由 `agent-builtin` 自己的 check 负责，不存在跨模块 Ant report。真实 PostgreSQL 上的 claim / lease / notify 契约由 [Harness Infra](harness-infra.md) 的测试守护，运行方式见 [开发与测试](../operations/development-and-testing.md)。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Infra](harness-infra.md)、[Harness Model](harness-model.md)、[Harness Environment](harness-environment.md)、[Common](common.md)、[Platform](platform.md)。
