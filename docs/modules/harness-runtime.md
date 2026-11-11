# Harness Runtime

`harness-runtime` 是 Agent 执行内核的公共层。它定义与具体执行后端无关的会话协议：Session 的只追加 Entry Tree 保存历史，Thread 的命令邮箱保存已接受的输入，ToolInvocation 保存工具在途调用，Work 保存调度义务；用户输入、工具副作用与控制操作都沿这些持久事实推进，崩溃恢复复用同一组状态转换。同一模块还承载三类面向扩展的契约：Tool 值契约、Contributor SPI 与第一方内置工具，以及 prompt 模板。

Runtime 经 [`spi.driver`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/driver/) 窄契约调用组合根注册的 Driver，按 `ExecutionSelection.driver` 路由；不动态加载、不读取 Driver 私有状态。Builtin 是当前唯一的生产 Driver，详见 [Harness Agent Builtin](harness-agent-builtin.md)。

模块是纯 Java：生产依赖只有 [Common](common.md)、[Harness Environment](harness-environment.md)、[Harness Model](harness-model.md)、JMustache、Jackson 3 与 SLF4J，不感知 Spring、JDBC、HTTP、模型 SDK 或 Git。持久化、调度分发、网关、目录、工具权限策略与产品用例在 [Platform](platform.md)，模型契约与协议编码在 [Harness Model](harness-model.md)，组合根在 [Web](web.md)。

## 持久模型

```text
Session ─ Entry Tree        只追加的对话历史；分支 = 从 ROOT 到某个 head 的路径
Thread  ─ head + 邮箱        指向自己的 head，持有执行控制、输入水位与已接受的 Command
ToolInvocation              工具在途调用、审批记录、结果与副作用批次
ModelInvocation（Builtin Driver 私有）  当前回合在途模型生成的状态机行，终态物化进历史后删除
Work                         每个 target（内核 THREAD / TOOL 与各 Driver 类型）至多一行的调度义务与租约
Join                         一次子执行委派的票据，只记凭据与冻结的终态坐标
```

推进没有内存中的 Agent Loop：每一步都是一个短事务，读取这些事实、写入下一组事实，并在同事务里请求后续 Work。Dispatcher 认领 Work 后交给对应 Processor；通知丢失或进程重启后，周期恢复会重新认领，执行到哪一步完全由持久状态决定。

## 同步入口与事务边界

[`HarnessRuntime`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/HarnessRuntime.java) 是外部与内核交互的同步命令与控制入口，覆盖命令接受与 spawn、Stop、审批与人工输入、YOLO 与重命名、手工压缩，以及快照、执行树与 Join 查询。

同一个门面还承担只读规划事实，调用方无需接触实现类：`previewInputCandidatePath` 用正式规划装配尚未落库命令 batch 的 candidate 路径，`findCompactionTurnStart` 按结果 Entry 返回已关闭压缩 turn 冻结的元数据（缺失即 fail closed）。两者都是纯委托，不写 durable 状态，也不复制算法。请求输入 token 估算不经过门面：算法作为 [`runtime.compaction.TokenEstimator`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/compaction/TokenEstimator.java) 留在实现层，内核外部只经 [`spi.compaction.RequestTokenEstimator`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/compaction/RequestTokenEstimator.java) 端口消费，端口实现由组合根以方法引用绑定，因此既没有第二份口径，也不需要任何 Runtime bean 参与装配。

每个控制操作在**一个** [`HarnessStore.Transaction`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/store/HarnessStore.java) 内完成全部写入并原子提交；手工压缩也只用一个短事务（锁内复验、同步 resolve、提交）。查询同样走 Store 事务，快照查询会加锁。Stop 的本地取消登记在 `afterCommit` 上，只在物理事务真正提交后执行，外层回滚即不取消，取消失败也不回滚已提交的事实。

业务拒绝使用通用 [`DomainException`](../../common/src/main/java/fun/fengwk/kkstudio/common/error/DomainException.java)：资源不存在为 `not_found`，业务冲突为 `conflict`，其稳定 reason 来自 [`HarnessConflictReason`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/HarnessConflictReason.java)，涵盖游标冲突、幂等身份复用、审批或输入不适用、压缩不可用等；被破坏的持久化不变量（所有权错误、sibling 混合挂接、`callIndex` 不连续）以 `IllegalStateException` 拒绝继续，绝不降级为业务错误。

多行事务按同一层级取锁，实现层在真正取锁前拒绝逆序：

```text
前置 advisory 围栏：task 准入（仅接受路径）-> tree（根 UUID 升序）
行锁阶梯：Session -> Thread -> Command -> Driver 私有行 -> Tool -> Work
```

tree 锁只用于结构或跨 Thread 的写操作（spawn、Stop、删除、fork、Join 结算、压缩 spawn）与整树查询，以 Thread 行上不可变的 `rootThreadId` 定位并在锁内重读确认；单 Thread 控制与 Model/Tool 状态提交只锁自己的 Thread 及其 Model/Tool/Work，与持 tree 锁的写操作在同一 Thread 行上线性化。task 接受另取全局准入锁，使跨树的子代理额度判定与接受原子。创建、请求或删除 Work 的业务事务必须先锁 owning Thread；Dispatcher 的 claim 与 heartbeat 是唯一只锁单条 Work 的事务，且不得制造新的业务唤醒。同级排序与树内交付的细节见 [Platform](platform.md#事务边界与锁序)。

## Entry Tree 与回合文法

`Entry` 是不可变记录 `(id, sessionId, parentEntryId, payload, createdAt, providerReplayState)`，类型是封闭集合 [`EntryType`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/history/EntryType.java)：

| 类型 | 语义 |
| --- | --- |
| `ROOT` | 唯一根，保存初始 [`BranchSettings`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/entry/BranchSettings.java)：agent、[`ExecutionSelection`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/entry/ExecutionSelection.java)（`driver` + `settings` JSON 对象）与可空的 Environment name |
| `TURN_START` | 打开回合，冻结完整 settings、实际执行配置 `executedSelection`、owner Thread、启动原因（`INPUT` / `CONTINUATION` / `COMPACTION` / `STOP`）与解析出的上下文窗口和输出预算 |
| `MESSAGE` | USER / ASSISTANT / TOOL 消息；只有 ASSISTANT 消息可以携带 `providerReplayState` |
| `CUSTOM_MESSAGE` | 可信调用方注入的消息 |
| `MODEL_ATTEMPT_FAILURE` | 与 Provider 无关的重试审计 |
| `ASSISTANT_ERROR` / `ASSISTANT_ABORTED` | 异常与中止屏障 |
| `COMPACTION` | 摘要结果：非空 `summaryText` 与真实模型输出的元数据 |
| `NATIVE_COMPACTION` | 原生压缩审计事实：保存 Provider 或外部 Agent 原生压缩的触发器与 token 统计，不承载真实模型 settings，也不改变回合边界 |
| `TURN_END` | 关闭回合，保存 [`TurnEndOutcome`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/entry/TurnEndOutcome.java)（`COMPLETED` / `FAILED` / `STOPPED` / `CANCELLED`，失败原因含 `EXECUTION_FAILED`）与 `continueModel` 延续义务 |
| `NOTIFICATION` | 系统通知（子结果交付、任务预算提醒），对模型投影为上下文 USER，但不提升权限，也不是产品可提交的输入 |
| `SETTINGS` | 回合间安全边界上的 settings 应用快照，不调度模型 |
| `FORK` | 分支事实节点 `{mode, sourceEntryId, sourceThreadId}`，模型可见文本是固定英文通知 |

执行父子关系不进历史，而是 Thread 行上不可变的 `parentThreadId`，因此 Entry Tree 始终是对话历史而不是运行树。Environment 是执行根的设置；子 Agent 的初始 settings 不带环境，也不能接受 `SET_ENVIRONMENT`。每次子回合规划在执行树事务内读取执行根已生效的当前环境，并将当时的 name 冻结在回合快照中，再解析为内部路由身份。环境切换在根的安全边界生效后，后续子回合使用新环境；已冻结的模型请求与工具调用继续使用原环境。目录只出现在每次工具调用自己的 arguments 里，历史中没有目录状态。

[`EntryPath`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/history/EntryPath.java) 是从 `ROOT` 到 head 的不可变连续路径（同 Session、父链相接、ID 不重复、首节点是唯一的 `ROOT`）。[`EntrySlice`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/history/EntrySlice.java) 是同一条链的读取视图：`baseEntryId` 为空时就是这条完整路径；非空时只含该祖先之后、不含游标本身的连续子链，空列表表示游标就是 head。增量不满足 `EntryPath` 的 ROOT 约束，不能当作完整路径使用。[`TurnPathValidator`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/history/TurnPathValidator.java) 在其上校验回合文法：

- `SETTINGS`、`FORK`、`NATIVE_COMPACTION` 是透明节点，不打开也不关闭回合，且只能位于回合之间；其余节点必须落在唯一 open 的 `TURN_START` 内，前一回合未关闭时不得再开。
- 每回合至多一个 Assistant 结果（ASSISTANT 消息、`ASSISTANT_ERROR`、`ASSISTANT_ABORTED` 或 `COMPACTION`）；TOOL 消息紧随带 `ToolCall` 的 ASSISTANT 消息，`callIndex` 从 0 起严格连续，调用 ID、工具名与所属 Assistant 精确匹配。
- `INPUT` 回合在 Assistant 结果前至少有一条 USER 或 `CUSTOM_MESSAGE`；`CONTINUATION` 偿还上一回合的 `continueModel=true`，不消费用户消息；`COMPACTION` 不出现 USER 或 `CUSTOM_MESSAGE`，成功结果只能是 `COMPACTION`；`STOP` 是显式停止的持久屏障，只含一条 `ASSISTANT_ERROR(CANCELLED)` 与随后的 `STOPPED` `TURN_END`。
- `TURN_END` 只能关闭当前 open 的回合，并按结局校验前置条件。路径可以在任意前缀截断，用于恢复未闭合的回合。

## Thread

[`ThreadState`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/thread/ThreadState.java) 只保存 Thread 自己拥有的事实：创建后不可变的身份（Session、父 Thread、执行根 `rootThreadId`、创建请求哈希、创建时间），可变的 head、名称、执行控制、输入水位、命令序号与 `version`。

- `version` 是结构与控制状态的 CAS 游标，不是快照内容版本：任何非精确重放的变更让它严格 +1，精确重放原样接受；Model 的流式 checkpoint 在同一 version 内推进。名称与 YOLO 由控制面直接更新，同值 no-op，不产生 Command、Entry 或 Work。
- `rootThreadId` 是执行根：根指向自身，子代理继承父 Thread 的执行根。`yolo_enabled` 开关只属于执行根，子代理恒为 false，其有效值取自执行根。根开关只修改根行，不递归修改子行或子行版本；Chat/Project 开关仅初始化新根。权限判定读取执行根的开关。YOLO 影响尚未完成权限决定的调用，不自动批准 `WAITING_APPROVAL`，不回答问卷，也不回滚已派发工具。
- 人工接口只能控制执行根：自由输入、设置、YOLO、Stop、压缩、重命名与分支创建均校验目标为执行根；审批与回答是写回子调用的合法例外。待办事实只有 ToolInvocation 的 `WAITING_APPROVAL` 与 `WAITING_INPUT`，不复制待办表。归属解析按来源 Thread 的执行根定位，再读取根的产品绑定；响应保留原始调用身份，另提供 `rootThreadId`。人工交互沿执行父关系汇聚到根；子任务结果由原始 `ThreadJoin` 的订阅方持有，结果通知与执行唤醒仍交给直接派发子任务的父 Agent。树级 Stop 与 YOLO 不改变 Join 的订阅方、预算和回执身份。
- [`ThreadExecutionControl`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/thread/ThreadExecutionControl.java) 只有 `RUNNABLE` / `STOPPED`：Stop 把目标与全部后代置为 `STOPPED`，跨重启保留；显式的新用户输入或新任务只恢复目标本身。`inputThroughSequence` 由 `INPUT` 回合推进；Stop 物化已排队通知时也同步覆盖这些通知的序号，保证状态投影与 Join 判据对“已消费通知”一致。

对外状态都从 Thread 自身事实派生，不递归子树。[`ThreadSnapshot`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/thread/ThreadSnapshot.java) 包含当前生效的 [`EntryCurrentState`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/history/EntryCurrentState.java) 与返回片段 [`EntrySlice`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/history/EntrySlice.java)，不无条件持有全量 Entry 历史路径。快照的 `runtimeStatus()` 统一为四态：`RUNNING`、`EXIT_PENDING`、`WAITING` 与 `IDLE`，与 `executionControl`（`RUNNABLE` / `STOPPED`）完全正交。四态判定是 `api` 侧的公共纯值算法 [`ThreadSnapshot.evaluateRuntimeStatus`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/thread/ThreadSnapshot.java)：输入只有调用方已读到的事实——执行控制、runtime 上下文分类投影出的 [`ThreadLiveState`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/thread/ThreadLiveState.java)、当前 `EntryCurrentState`、排队命令与已应用未消费通知水位、未完成直接子 Join 计数与 owner 结算义务、未确认执行——快照投影与执行树节点投影共用同一判定，消费方不必重新推导。判据：本地活跃执行（`DRIVER_ACTIVE` / `DRIVER_RESPONSE_PENDING` / `TOOL_ACTIVE` / `TOOL_TERMINAL_PENDING`）、自身 open owner compaction 或仍有未完成的直接 `SETTLE_OWNER_TURN` 子 Join 始终为 `RUNNING`；没有上述活跃流程，但仍有已登记且未确认退出的执行代次在阻塞收敛或待启动动作时为 `EXIT_PENDING`；本地已让出执行但存在未完成直接 `NOTIFY_PARENT` 子 Join（`incompleteChildJoins > 0`）为 `WAITING`；其余完全静止为 `IDLE`。待启动输入需求（排队命令或未消费的已应用通知水位）仅在 `RUNNABLE` 下体现为 `RUNNING`，在 `STOPPED` 下保留通知但不自启（明确抑制本地回合启动，不冒充 RUNNING）。非 `IDLE` 状态下 `outcome()` 同样由公共纯函数 `evaluateOutcome` 强制抑制为 null，严禁在等待子任务或活跃时宣称 `COMPLETED`。`EXIT_PENDING` 不因 `RUNNABLE` / `STOPPED` 改变，不等同 `IDLE`，也不放宽 Join 或输入门禁；活跃 Driver/Tool 优先于残留退出，因此正常正在执行且尚未确认退出的代次仍显示 `RUNNING`。显式 `ABANDONED` 或已确认真实退出不阻塞，但退出 ACK 只证明本地 body 返回与资源释放完成，不扩张为对远端进程或副作用的普遍事实，四态仍不能单独证明外部进程已退出。Join 的普通结算另走 runtime 侧 [`ThreadActivityEvaluator.isQuiescent`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/thread/ThreadActivityEvaluator.java)：它只回答「本地是否已收敛为可向父结算的静止」，与四态共用同一批控制敏感事实，但不复算四态。`executionFacts()` 另给三个独立事实：`hasWork`、`waitingForHuman` 与 `hasUnknownToolOutcome`。

[`ThreadContextClassifier`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/thread/ThreadContextClassifier.java) 是纯函数：输入 ThreadState、`EntryCurrentState`、Driver 按 open 回合锁定的中立状态与 Tool siblings，输出唯一的 [`ThreadContext`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/thread/ThreadContext.java)（`IdleOrHistorical`、`ContinuationDue`、`GenerationActive`、`GenerationTerminalPending`、`ToolActive`、`ToolTerminalPending`）；任何所有权或配对不一致都直接抛 `IllegalStateException`。这一分类是内核内部事实，只映射为对外公开的 [`ThreadLiveState`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/thread/ThreadLiveState.java)；快照与 Processor 共用这一分类。

## 命令邮箱

`acceptCommands` 接收一批有序命令与一个 sealed target：

- `NEW_SESSION`：调用方预分配 Session/Thread ID，同一事务创建 Session、ROOT、Thread、Commands 与 THREAD Work；同一 `threadId` 携带相同创建哈希为精确重放，不同哈希为 `THREAD_ID_REUSED`。
- `NEW_THREAD`：在同 Session 的合法切点（ROOT 或已闭合的 `TURN_END`）追加一个 `FORK` Entry 作为新 Thread 的 head，不复制前缀、不移动源 Thread；未闭合的 STOP 回合不可作为切点。
- `NEW_FORKED_SESSION`：把执行根在合法切点处的有效上下文复制到新 Session 并创建独立执行根；根 settings 从切点处的生效快照推导，不复制 Join、命令或调用。
- `THREAD`：续写既有 Thread。精确的有序重放先于一切游标与 preflight 准入；单条纯 `USER_MESSAGE` 不带游标、按锁内邮箱尾部入队，其它批次必须携带 `expectedHeadEntryId` 与 `expectedNextCommandSequence` 并精确匹配（不匹配为 `STALE_COMMAND_CURSOR`）。

命令类型包括人工输入 `USER_MESSAGE`、可信调用方输入 `CUSTOM_MESSAGE`（task、自动业务、运行时注入的 `SystemReminder`）与系统通知 `NOTIFICATION`。配置命令 `SET_AGENT -> SET_EXECUTION -> SET_ENVIRONMENT` 按此顺序位于消息之前，每种至多一次；`TurnPlanBuilder` 只把生效配置冻结进 `TURN_START.settings`，不向模型注入配置提醒。约束如下：

- 创建型 target 与统一 `spawn` 批次必须以恰好一条 user-like 任务输入结尾。
- 既有 Thread 接受纯设置批次：在回合间的安全边界上立即归约为一条 `SETTINGS` Entry，不打开回合、不调度模型、不恢复 `STOPPED`。
- 产品 HTTP 面只接受 `USER_MESSAGE` 与三个 `SET_*` 前缀；`SystemReminder` 必须有完整的开闭定界符，普通消息伪造前缀不会被当作 steering。YOLO 不走邮箱。

命令状态由标记字段派生：无标记为 `QUEUED`，有 `appliedEntryId` 为 `APPLIED`，成对的 `stopRequestId` / `cancelledAt` 为 `CANCELLED`。Session 显示名在创建时从首批末尾 user-like 消息派生（前 40 个码点，无文本时回退 `session-` 加 UUID 前缀），ROOT Thread 固定为 `main`，分支名由调用方给出；手工名称经 [`Names.normalize`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/Names.java) 折叠空白，超过 256 个码点报错，绝不截断。

`getThreadSnapshot` 支持可选的 `afterEntryId` 增量游标（祖先匹配时返回轻量增量 `EntrySlice`，不匹配时回退全量快照），只锁目标 Thread 及其当前适用的 Model/Tool，返回事务内最新已提交的 checkpoint。`getThreadTree` 持执行根的 tree 锁读取同根全部 Thread（同 Session 的其它执行根不在其中），返回轻量只读节点 [`ThreadTreeNode`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/ThreadTreeNode.java)，节点上的 `turnCount`、`toolCallCount` 与 `outcome` 只按各自 root-to-head 路径计算，不构建完整快照，查询不写任何事实。

## ToolInvocation

[`ToolInvocation`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/tool/ToolInvocation.java) 记录冻结的 `ToolCall`、[`ToolBinding`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/tool/ToolBinding.java)、所属 Assistant 与 `callIndex`、审批记录、结果与副作用批次：

```text
READY            -> WAITING_INPUT（ask_user）/ WAITING_APPROVAL（Ask）/ RUNNING（Allow 且准入通过）/ FAILED / CANCELLED
WAITING_APPROVAL -> READY（Allowed）/ FAILED（Denied）/ CANCELLED
WAITING_INPUT    -> SUCCEEDED（已作答或明确拒答）/ CANCELLED
RUNNING          -> SUCCEEDED / FAILED / CANCELLED / UNKNOWN / READY（重试）
```

`attempt` 同样在副作用之前持久化；RUNNING 工具的旧租约恢复绝不重放，只在能够重新挂接在途远端调用时继续等待结果，否则收敛为 `UNKNOWN`（见下文）。`WAITING_INPUT` 与 `WAITING_APPROVAL` 刻意分离：问卷不能授权工具，审批也不能代替回答，两者都只占 durable 行，不占 Worker。`ToolBinding` 冻结工具定义、Contributor 归属、环境支持级别与 Environment id/name；无环境选择的 `REQUIRED` 工具在规划时已被过滤，网关对已移除或定义已变化的 binding 返回确定性失败。真实工具的所有终态都把原 binding 写入结果元数据，删除临时行或 fork 不会丢失原定义。

[`ToolOutcomeQuery`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/ToolOutcomeQuery.java) 按 Thread 和 invocation 身份查询稳定终态：调用行仍在时读取 terminal Invocation，物化后从当前路径的 ToolResult Entry 读取，并校验所属 Thread 与 Turn。返回的 [`ToolOutcomeView`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/ToolOutcomeView.java) 保留调用身份、终态与稳定内容；`resultError` 是模型可见的业务错误标志，与生命周期 `status` 正交，执行 `SUCCEEDED` 也可能携带业务错误。

### 问答与审批

`ask_user` 在接受时冻结问卷：

```json
{
  "questions": [
    {
      "question": "交付格式？",
      "options": [
        {"label": "PDF", "recommended": true},
        {"label": "Markdown"}
      ]
    },
    {
      "question": "需要哪些附件？",
      "multiple": true,
      "options": [{"label": "图片"}, {"label": "源文件"}]
    }
  ]
}
```

答案按问题位置提交，例如 `{"answers":[["PDF"],["图片","源文件"]]}`。单选默认，每题提供自定义输入；推荐仅做标记，等待用户选择。答案须覆盖所有题，单选恰好一个，多选去重并允许一项自定义文本；问卷至少一题，题数、选项数与文本长度不设业务上限。模型只提供问题与选项，问卷由接受路径冻结。同题选项标签唯一，单选至多一个推荐，多选允许多个；选项为空时使用自定义回答。

用户明确提交回答或拒答。拒答形成 `{"declined":true}` 工具结果；Stop 取消等待，迟到回复被拒绝。委派子 Thread 也可等待回答，祖先按自身状态与执行树对账恢复，不递归投影子树等待。

回答回执保存 submissionId、actor 与 acceptedAt。终态结果追加到 Entry 后，在同一事务删除 Invocation，并把 invocationId、调用坐标与回执转入 Runtime 历史元数据。清理后重试沿历史父链核验原 Thread；相同提交返回原接受事实，同键异内容、冲突提交和已取消目标被拒绝。问卷答案保存在结果内容，审批审计保存在 Runtime 元数据。审批允许只授予调用资格，执行结果由工具实际返回。

## Work

[`Work`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/work/Work.java) 是每个 target 一行的调度邮箱：`availableAt`、`wakeVersion`、租约与可选的 `requiredEnvironmentId`。所有跃迁都是纯函数：`request` 把 `availableAt` 提前并让 `wakeVersion` +1，`claim` 写入租约，`complete` 在 `wakeVersion` 未变时删除行、被新唤醒推进时只清租约，`reschedule` 清租约且不让旧 claim 推迟新唤醒。租约 token、有效期与认领时的 `wakeVersion` 共同拦截迟到的旧执行体。

`Work`、`WorkTarget`、`WorkTargetType` 与已认领快照 `ClaimedWork` 是持久化与分发共用的值契约，位于 [`spi.work`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/work/)，与 [`spi.store`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/store/)、[`spi.driver`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/driver/) 同处 SPI，见[端口与策略](#端口与策略)。

时间只有一个权威域：生产是数据库时钟，内存实现是测试时钟；持久化时间戳都取自事务的 `tx.now()`。时间只记录事实发生的时刻，不承担排序——Entry 的树内顺序由父链表达，推进由 version 与状态机表达；Session 级列表读取按存储追加顺序（`harness_entry.entry_seq`，创建顺序）分页，写入先校验父链因此父节点必先于子节点。`requiredEnvironmentId` 只在 WorkTargetType 自己声明环境亲和性时允许非空（当前生产为 TOOL），THREAD 与 Builtin MODEL Work 必须为空；它在首次创建时冻结、之后不可改变；按当前节点是否持有该 Environment 的 READY 连接租约分发由平台实施。

## Processor

Thread / Tool claim 由内核 Processor 处理；Driver 注册的 Work 处理器驱动一次模型调用或进程外 Agent 执行，期间续租、提交 checkpoint 与终态。调度器按 `WorkTargetType.rank()` 顺序轮转已注册处理器的子集，跳过未注册类型的 Work；内核只固定 THREAD 与 TOOL，Builtin 的 MODEL 由 Driver 注册。后续调度由同事务的 `requestWork` 驱动。

[`ThreadProcessor`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ThreadProcessor.java) 校验 claim 与准入后取执行树锁，按分类结果行动：

- `GENERATION_TERMINAL_PENDING`：写入 Assistant / Error / Compaction 结果；关闭回合或进入 Tool 阶段。进入 Tool 阶段时，无键调用照常派发；同一批 Environment `edit` / `write` 按 Environment 与绝对路径词法键仅派发每个键的首个未结束调用。
- `TOOL_TERMINAL_PENDING`：按 `callIndex` 写入副作用与 Tool 结果，追加 `TURN_END(continueModel=true)`，删除调用行。
- `TOOL_ACTIVE`：每次 THREAD 唤醒都重查批次；对每个文件键，在没有 TOOL Work 的情况下派发首个 `READY`、attempt 为 0 且审批为空的调用。前项结束事务请求 THREAD Work，故后继派发与唤醒同事务提交，可在进程重启后恢复；无键调用不改变既有派发行为。
- `GENERATION_ACTIVE`：外部执行仍在进行，归还 claim。
- `CONTINUATION_DUE` / `IDLE_OR_HISTORICAL`：先处理适用的自动压缩；有排队输入或通知需求时启动 `INPUT` 回合并一次性消费冻结 cutoff 内的完整队列，否则偿还续写义务或直接完成。

需要规划的回合在单个事务内完成：锁 Thread、捕获命令快照、构造只存在于内存的 candidate 路径，同步调用 Driver 解析，机械校验其结果与 candidate 一致后提交 `TURN_START`、消息、命令标记与 Driver 私有 generation。只有显式的 `TurnResolveTransientException` 在回滚后按失败延迟重排，其它解析失败落 durable `FAILED` 终态，绝不静默丢失 Work。回合规划、Driver 状态机推进与钩子语义详见 [Agent Driver](#agent-driver)。

[`ToolProcessor`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ToolProcessor.java) 的短事务只锁 own Thread -> Tool -> Work（执行路径不锁定 Driver 私有行）。执行根开启 YOLO 时直接放行，否则在事务外调用 `ToolGateway.preflight`：`Allow` 进入执行，`Ask` 转 `WAITING_APPROVAL`（可附带编辑审查），`Deny` 转 `FAILED`。prepare 在同一锁内一次性冻结这次执行的上下文事实，作为 [`ToolGateway.ExecutionScope`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/ToolGateway.java) 供后续 `admit` 使用：own Thread 及其 `sessionId`、不可变执行根 `rootThreadId` 与真实执行根行的 `rootSessionId`（缺失即失败，绝不按当前 Thread 合成）、该调用冻结的 assistant 入口 `assistantEntryId`，以及该调用 `TURN_START` 冻结的 [`TurnMediaCapabilities`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/tool/TurnMediaCapabilities.java)。上下文读取失败回滚事务并按 admission 失败延迟重排；重新挂接既有远端执行只需 Thread 身份，不读取上下文。网关据此装配 `ToolExecutionContext`，admission、工具执行、插件资源鉴权与 `read` 媒体判定因此都不再查询 Harness 存储，也不读 live 模型或 Driver 私有状态。放行后同样经 `admit` 准入、先持久化 RUNNING，再由 [`ToolExecution`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ToolExecution.java) 在独占虚拟线程上执行；终态提交之后才释放并发租约与目录租用，因此取消只需 interrupt。只有 `retryable` 且工具为 `READ_ONLY` / `IDEMPOTENT` 的失败才重试；`UNKNOWN` 是终态，与其它终态一样物化为失败结果交给下一轮模型，不阻塞 Thread。工具增量只支持文本与 JSON，作为有损 `TOOL_PARTIAL` 发布。节点优雅退出时，排空会中断本地等待线程并释放对应 TOOL Work 的租约，使远端仍在执行的工具可以由具备该环境连接的其它节点立即接手并继续等待结果；超时未退出的本地执行保留未确认事实，非远端等待的执行直接随关闭退出。

认领到 `RUNNING` 工具时，若它绑定了 Environment，且当前 attempt 恰好有一条状态为 `UNCONFIRMED` 或 `REMOTE_ACKNOWLEDGED` 的 REMOTE 退出事实，ToolProcessor 通过 `ToolGateway.reattach` 重新挂接这次在途远端调用：不重发 INVOKE，`attempt` 不变，在 `reattachWindow`（默认 10 分钟）内等待终态；超时或远端已找不到该调用时收敛为 `UNKNOWN`。新 owner 以本 claim 登记自己的 TOOL 事实，挂接体退出时确认为 `STUDIO_EXITED`；远端退出确认按库中原始身份确认 REMOTE 事实，原 owner 的 TOOL 事实保持原状。本节点不持有该 Environment 的 READY 路由时短延迟重排，不改变工具状态。其余情况（本地工具、尚未发出远端调用、`NOT_STARTED`、多条 REMOTE 事实）收敛为 `UNKNOWN`（`LEASE_EXPIRED`）。

Model / Tool Processor 以组合方式共用公开的已认领执行骨架 [`ClaimedExecutionSupport`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/work/ClaimedExecutionSupport.java)（Work SPI）：所有权校验、按 invocation 的准入与 execution 登记、派发前的租约 margin、本地取消 / 关闭与活跃执行查询只实现一次，Processor 自己只负责请求物化、Gateway 准入与终态提交。具体准入由同一类级强类型 `ClaimAdmissionGuard` 按 invocation 原子协调，事务内最终所有权围栏失败一律抛出唯一的 [`ClaimLostSignal`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/work/ClaimLostSignal.java)。准入操作在原子计算中复核数据库 claim 所有权，不同有效 token 可替换旧 execution，捕获后在锁外执行 abandon；相同 token 去重，失权 claim 不得取消当前执行。关闭守卫确保 close 期间迟到准入不创建 slot 执行，未 attach 的 ticket 在 close 或 cancel 后拒绝 attach；准备与外部调用均在准入锁外进行，最终持久化仍须通过 durable ownership fence。

每个具体执行代次在进入 body 或发出远端调用前登记为独立的退出事实，不依赖逻辑 Invocation 行是否仍存在。Studio 本地执行事实类型为 `DRIVER` 与 `TOOL`，退出时均确认为 `STUDIO_EXITED` 或由重启恢复确认为 `OWNER_EXITED`；远端子执行（`REMOTE`）登记时必须绑定其宿主 `EnvironmentId`，且其父执行代次类型必须为 `TOOL` 或 `DRIVER`。本地 `DRIVER` / `TOOL` 在允许 body 或远端调用前统一经 [`LocalExecutionLifecycle`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/execution/LocalExecutionLifecycle.java) 取得物理句柄并完成 PREPARED 与数据库登记；`REMOTE` 不占用本地物理句柄，也不写 PREPARED，只在收到精确代次的正向 ACK 或确定未启动时写退出证明。admission 取得的每个 run 在失权、取消或离场时都恰 close 一次；body 返回且本地 admission close 成功后，执行线程才确认 `STUDIO_EXITED`；远端子执行在完整 identity 匹配后确认 `REMOTE_ACKNOWLEDGED`。已登记但确定未进入 body 的代次，只在 close 成功后确认 `NOT_STARTED`；close 失败保留 `UNCONFIRMED`，不得偷偷二次 close 或确认。取消、lease 丢失与逻辑终态本身都不等于退出：body 开始后仍须等待真实返回并完成资源释放，未进入 body 的已排队任务也由统一 finally 收敛。最后一次真正改变退出事实的 ACK 在同一事务递增 Thread version；若普通终态已物化则直接补结算 Join，否则只有存在源命令已应用的未完成 Join 才请求 THREAD Work。重复 ACK 不重复 version、wake 或通知；STOPPED Thread 在 ACK 事务内递归结算已收敛祖先。

### 本地物理生命周期与两阶段执行日志

[`LocalExecutionLifecycle`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/execution/LocalExecutionLifecycle.java) 是 Builtin Driver 与 Tool Processor 唯一使用的物理生命周期端口；[`ClaimAdmissionGuard`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/work/ClaimAdmissionGuard.java) 仍只负责 Work 准入、lease 所有权与 supersede，取消、失权或从准入表移除都不等于物理退出。

- `start()` 只调用一次，先冻结上一进程遗留的日志集合完成首轮恢复，再允许新执行登记；目录无法枚举时拒绝继续启动，数据库瞬态故障只保留对应记录。恢复结果区分已确认 `OWNER_EXITED`、已清理和仍需重试或人工处理的集合。
- `begin(identity, registration)` 先取得物理句柄，再原子写入并 fsync PREPARED，最后执行数据库登记事务；只有事务返回且确认登记成功，调用方才允许 attach body 和进入 Gateway body / 发出远端调用。PREPARED 写入失败时不登记、不执行；数据库提交失败或结果不明时不进入 body，释放已取得资源后按精确身份对账，确定未启动只在对账与 close 成功后以 `NOT_STARTED` 结束。
- 取消在 attach body 之前也会被记住；`attachBody` 在未登记、已结束或已取消时拒绝放行。取消、supersede 与 lease 失效只请求取消并打断已附着 body，不从物理集合移除句柄，也不释放本地资源。
- `provenExit` 只在 body 与本地资源都结束后写出证明；仍有存活 body 时拒绝声称退出，未登记代次不能声称 `STUDIO_EXITED`。资源关闭失败或 body 仍未结束时调用 `exitNotProven`，保留 PREPARED 与未确认事实，并保留日志目录锁到 JVM 退出。
- `drain(timeout)` 先停止新登记，用单调时钟在同一个 deadline 内等待全部物理句柄结束；到期后只请求取消剩余 body 并返回仍未结束的执行，不伪造排空完成。仍有物理 body、资源未释放或退出证明未落账时，`close` 不得无条件释放内核目录锁。

执行日志只有一个独占目录和一个内核文件锁，记录只有 PREPARED 与 EXIT_PROOF 两个单向阶段，冻结完整执行身份与证据时刻，不保存模型输出、用户输入或业务结果。EXIT_PROOF 额外保存目标真实证据与确认时刻。启动恢复先重放 EXIT_PROOF，再在 Thread 锁内按权威数据库状态对账冻结的旧 PREPARED：事实存在且未确认时确认 `OWNER_EXITED`；事实已确认或从未登记时清理记录；身份冲突、损坏或暂态读取失败时原样保留并报告，交给同一个重放器重试或人工处理。冻结集合之外的当前进程 PREPARED 永远不会被当作上一进程退出。

确认与重放共用同一入口：数据库任何读取或提交之前先把正向证据写入日志，只有确认与 Join 结算事务提交成功后才删除记录。worker 每 1 秒把本进程 proof 与磁盘 EXIT_PROOF 按 execution 身份合并重放，每个 execution 每轮最多尝试一次；健康文件继续重放，无法解码、身份不符或类型异常的文件逐文件保留并去重告警，目录无法枚举或目录锁不可用仍整体失败。数据库或文件瞬态失败按 1/2/4/8/16/30 秒上限退避，成功后重置；事实缺失或 canonical proof 与数据库/日志的真实身份冲突保留证明并低频核对，不阻断其它记录。删除失败不回滚已提交的确认事实，保留 proof 重试；首次日志写入失败时 proof 只保留在本进程内等待重试，不能据此宣称跨进程 durable。

### 退出确认与放弃

真实退出证据是 `STUDIO_EXITED`、`OWNER_EXITED`、`REMOTE_ACKNOWLEDGED` 与 `NOT_STARTED` 四种。`OWNER_EXITED` 只表示本地执行所有者已不可能继续执行，不表示 finally 成功执行、不表示业务成功，更不表示 REMOTE 停止；Schema 与应用只允许它用于本地 `DRIVER` / `TOOL`。本地 owner 退出不确认 REMOTE，不重跑 Bash、不重复发送模型请求，也不把 `UNKNOWN` 变成成功；远端仍须精确 ACK 或保持未确认。日志写入只证明正向证据已落盘，不证明远端进程已经退出。

`ABANDONED` 是用户显式放弃继续等待确认的独立事实，不是物理退出，也不能伪造 ACK。只有 `UNCONFIRMED` 阻止收敛；首次放弃要求 Thread 已 `STOPPED`、执行属于该 Thread 且 `expectedVersion` 精确匹配，重复放弃或已真实确认是安全 replay，容忍旧 version。真实证据到达后可以把 `ABANDONED` 升级为已确认状态并保留原 `abandonedAt`；两种时刻都不得早于 `registeredAt`，但 `confirmedAt` 可以早于 `abandonedAt`，绝不改写观测时间。退出事实行（包括放弃行）保留到 Thread 深删除，没有 TTL，也不以 lease 到期、`cluster_node` 路由记录或 journal 存在推断宿主已死亡。

跨实例远端退出对账由 [`RemoteExitReconciler`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/execution/RemoteExitReconciler.java) 承载：当目标 Environment 达到 READY 状态时，它在专用虚拟线程 Executor 上异步加载该环境全部未确认的远端退出事实（`loadUnconfirmedRemote(environmentId)`），不阻塞事件回调线程。对账仅处理 TOOL 父执行事实（跳过父执行为 DRIVER 的事实，由对应 Driver 的对账链路处理）：检查其对应的 ToolInvocation 状态，若工具当前为 `RUNNING` 则跳过，留给后续认领该工具的 ToolProcessor 恢复挂接；若工具非 `RUNNING`（包括工具记录已被物理清理），则通过 `EnvironmentCapabilityTransport.attach` 挂接仅确认退出的监听器。监听器在 `onExitConfirmed()` 到达时调用 `ExecutionExitConfirmer.confirm(fact, ExecutionExitState.REMOTE_ACKNOWLEDGED)`，沿用库中既有事实身份入账，不额外发起新的执行代次；终态结果与常规异常（含 `RECOVER_NOT_FOUND`）均被忽略且保持 `UNCONFIRMED`。单条挂接遇 `IllegalArgumentException`（当前节点已存在活跃执行）仅跳过该条继续处理后续事实，遇 `EnvironmentCapabilityUnavailableException`（路由不可用）则中止本轮对账。

Thread 由轻量 `ThreadAdmissionGuard` 独立防重，旧 action 未结束时同 token 或异 token 均返回 LOST，不抢占。

preflight 创建的 execution 在返回 `STARTED` 前由 Processor 负责清理：Allow、Ask、Deny 的回写失败或启动失败均停止 heartbeat 并释放本地登记；成功移交后由执行线程负责释放，不能再由 preflight 清理。两类 Processor 共用 [`WorkHeartbeat`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/work/WorkHeartbeat.java)：调度器只做周期唤醒与非阻塞分派，独立 worker 执行续租事务；续租失败、调度拒绝或分派拒绝都视为失去所有权，统一触发一次 `onLostOwnership`。

### 审批与受管文件变更

`decideToolApproval` 只在工具处于 `WAITING_APPROVAL` 且 Thread 处于 `ToolActive` 时接受：`ALLOW` 转 READY 并安排 TOOL Work，`DENY` 转 FAILED 并唤醒 THREAD；相同决策幂等，不同决策为 `APPROVAL_DECISION_MISMATCH`。需要人工审批的环境 `fs.edit` 必须携带有效的编辑审查（canonical 路径、全量 diff 与原始字节 SHA-256），否则网关在调用 transport 前以 `EDIT_REVIEW_REQUIRED` 拒绝；审查中的 `expectedFile` 作为内部元数据下发，Daemon 在文件锁内核对哈希，审查后文件变化即拒绝写入。成功后权威 diff 保存在结果的 `details.editReview`。YOLO 与自动 Allow 不要求审查快照。

## Agent Driver

[`AgentDriver`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/driver/AgentDriver.java) 是 Runtime 与具体执行后端之间的窄 SPI：Builtin 用它驱动 Studio 进程内的模型调用，进程外的 Agent 后端也以同一契约接入。契约与它使用的中立投影（`DriverCapabilities`、`TurnResolution`、`LockedDriverState`、`DriverStopResult` 等）同属 `spi.driver`；`LockedDriverState#projection()` 返回的 generation 公开快照定义在 [`api.generation`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/generation/)，消费方读快照不需要依赖 SPI。注册、路由与未安装 Driver 替身是实现细节，留在 `runtime.driver`。

`spi.driver` 也提供供 Driver 和 Runtime 共用的工具构件：[`ToolDeclarationPlanner`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/driver/ToolDeclarationPlanner.java) 规划冻结绑定下的工具声明，[`ToolOutcomeAppender`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/driver/ToolOutcomeAppender.java) 将终态工具结果追加到历史。

### 职责划分

- **Runtime 拥有公共事实**：只追加的 Entry Tree、Thread 状态与命令邮箱、`ToolInvocation` 与审批、`Work` 调度邮箱，以及执行代次的退出事实。
- **Driver 负责回合的 generation**：解析回合计划；在 `TURN_START` 提交到结果物化之间持有私有状态（如 Builtin 的 `ModelInvocation`）；在事务外经 Work 执行；把终态结果物化为公共 Entry。
- **状态隔离**：Runtime 只经 [`LockedDriverState`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/driver/LockedDriverState.java) 读取中立属性——`activity()`（`DriverActivity`）、`projection()`（[`GenerationProjection`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/generation/GenerationProjection.java)）、`requestHeadEntryId()`、`resultEntryId()`、`workTarget()` 与 `cancelsToolBatch()`——不读取也不强转 Driver 私有类型。

### 选择与路由

- **执行选择**：[`BranchSettings`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/entry/BranchSettings.java) 携带 [`ExecutionSelection`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/entry/ExecutionSelection.java)`(driver, settingsJson)`；接受根设置与 `SET_EXECUTION` 命令时，未注册的 driver 直接拒绝，已注册的由其 `validateSettings` 校验 settings。
- **注册与路由**：[`DriverRegistry`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/driver/DriverRegistry.java) 收集组合根注册的全部 Driver，按 `driver` id 路由。已有 Thread 的 driver 未注册时由 [`UninstalledAgentDriver`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/driver/UninstalledAgentDriver.java) 承接：拒绝规划新回合，但 Thread 仍可读取与停止。两者都是 Runtime 内部实现，因此留在 `runtime.driver` 而不进入对外 SPI。
- **能力**：[`DriverCapabilities`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/driver/DriverCapabilities.java)`(fork, manualCompaction)`。`fork=false` 时拒绝以该 Driver 的分支为源的 `NEW_THREAD` 与 `NEW_FORKED_SESSION`；`manualCompaction=false` 时手工压缩不可用。Thread 的 Driver 创建后不可变：fork 不能更换 Driver，更换 Driver 需要新建根会话。

### 方法契约

全部方法都在调用方已开启的事务内执行：不开启事务、不做外部 I/O、不执行工具、不绕过历史校验。

- `resolveTurn`：按 candidate 路径返回 [`TurnResolution`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/driver/TurnResolution.java)：`Rejected`（确定性拒绝）、`Compaction`（先压缩）或 `Accepted`。接受后 Runtime 提交 `TURN_START` 与输入 Entry，并在同一事务内调用 `Accepted.install`，由 Driver 创建私有状态并请求自己的 Work。
- `readState` / `lockState`：读取或锁定当前 open 回合的 Driver 状态；只有 `lockState` 的结果可用于执行与变更。
- `applyResponse`：generation 已终止但尚未物化（`GENERATION_TERMINAL_PENDING`）时，由 ThreadProcessor 或 Stop 调用，把结果原子物化为公共 Entry 与工具调用。
- `completeToolBatch`：工具结果与 `TURN_END` 已追加、Thread head 已推进之后调用，供 Driver 校验并清理该 generation 的私有状态。
- `prepareAutomaticCompaction`：由 Driver 自己的策略决定是否需要自动压缩；Runtime 只负责压缩回合的结构与子执行结算。
- `stop`：撤销执行资格，按需追加 barrier Entry，返回 [`DriverStopResult`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/driver/DriverStopResult.java)`(barrierEntryId, executionIds)`。
- `allowsQueuedInputHarvest`（默认 `true`）：Runtime 在应用排队设置、`ContinuationDue` 与新 `INPUT` 回合这三个收割边界询问 Driver；Driver 的私有周期仍在进行时返回 `false`，排队输入留待下一个边界。
- `completeStop`（默认 no-op）：Stop 推进公共 Thread head 之后调用，供 Driver 把私有记录对齐到已提交的 head。
- `LockedDriverState.cancelsToolBatch()`（默认 `false`）：为 `true` 表示待物化的失败结果可以与它已声明的工具批次共存，物化时原子终止这批工具；普通结果不能与未收尾的工具批次共存。

### 私有持久化与锁序

Driver 私有状态经 Driver 自己的 Store 视图读写，视图只投影调用方已有的事务，不开启事务。Builtin 的 `BuiltinStore` 由 Platform Runtime 的 PostgreSQL 实现，与 Runtime 共享连接、数据库时间与锁序跟踪。所有事务遵守同一锁阶梯：

```text
Session -> Thread -> Commands -> Driver 私有行 -> ToolInvocation -> Work
```

### 执行与停止

Driver 的 Work 处理器在事务外认领并执行，可复用 [`ClaimedExecutionSupport`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/work/ClaimedExecutionSupport.java)，并为每个执行代次登记 `ExecutionExitKind.DRIVER` 退出事实。Driver 经 [`AgentDriver#works()`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/driver/AgentDriver.java) 注册自己的 Work 类型与处理器，经 `LockedDriverState.workTarget()` 声明当前 generation 的 Work。Stop 锁定内核 THREAD、Driver 声明的 Work 与 TOOL。

`HarnessWorkDispatcher` 每种 `WorkTargetType` 只注册一个处理器；组合根从 `DriverRegistry` 汇总 Driver Work，再与 THREAD / TOOL 合并，不在 Web 手工登记具体 Processor。

`stop` 返回的 `executionIds` 在 Stop 事务提交后，交给组合根提供的 Driver 执行取消函数 best-effort 取消。

### 进程外 Driver 的公共构件

- **`NATIVE_COMPACTION`**：Driver 报告的原生上下文压缩审计记录，对路径透明：不消费输入、不开关回合、不改写历史。
- **`TurnEndReason.EXECUTION_FAILED`**：完整的公开结果已经提交之后外部执行才失败时，以该原因关闭回合，保留已提交的结果，不再追加 AssistantError。

### 新增 Driver

新增 Driver 不需要修改 Runtime：

1. **模块**：新建 `harness/agent-<id>`，包根 `fun.fengwk.kkstudio.harness.<id>` 只属于该模块；只依赖 `harness-runtime` 的公开 API，需要 Environment 时再依赖 `harness-environment`。在根 POM 登记模块，在仓库检查 [`check.mjs`](../../scripts/dev/verify/repository/check.mjs) 中登记该模块允许的内部依赖与模块文档。
2. **持久化与执行**：私有 Store 视图与表由 `platform-runtime` 的对应 Driver 适配包实现；Work 处理器复用 `ClaimedExecutionSupport`，通过 `AgentDriver#works()` 注册；本地取消与活跃检查实现 `cancelWork` / `hasActiveWork`。
3. **Thread 深删**：实现 `lockThreadStateForDeletion` / `deleteLockedThreadState`，由 `DriverRegistry` 在两阶段生命周期中调用；Runtime 的 Session 深删必须传入同一注册表，不存在省略 Driver 生命周期的删除入口；平台 `PostgresqlThreadStore` 只负责通用锁序、Work/Tool 删除与 Thread 行，不引用具体 Driver 表。
4. **装配**：在 Web 组合根注册 `AgentDriver` bean（由 `DriverRegistry` 收集）；Dispatcher 从 `DriverRegistry` 取得 Work 处理器，`HarnessRuntime` 与 `HarnessRuntimeLifecycle` 使用同一注册表完成本地取消与活跃检查；在 `scripts/dev/verify/repository/check.mjs` 中按文件放行装配所需的导入。
5. **测试**：复用 `harness-runtime` 的 test-jar：内存 Store、Store 契约基类与 `FakeAgentDriver`。

## 压缩

压缩回合自身不调用模型：父 Thread 打开 `TURN_START(COMPACTION)`，在同一事务里经统一 [`AgentSpawner`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/AgentSpawner.java) 创建新 Session 上的子 Thread、首条摘要命令与 `SETTLE_OWNER_TURN` 票据，然后在 durable wait 中等待结算：

```text
父 TURN_START(COMPACTION) -> COMPACTION(summaryText) -> TURN_END
子 TURN_START(INPUT)      -> Driver generation（Compaction Agent）-> ASSISTANT MESSAGE(summaryText) -> TURN_END
```

自动压缩的阈值判定由各 Driver 通过 `AgentDriver.prepareAutomaticCompaction` 提供；Builtin 的 owned `HISTORY` -> hard overflow -> eligible threshold 策略见 [Harness Agent Builtin](harness-agent-builtin.md#自动压缩)。Runtime 只负责压缩回合结构与子执行结算。

子执行使用目录中的 `Compaction` 内置 Agent，约束为父回合的输出预算与禁止自动压缩；提示缓存遵循该 Agent 选用的 variant。[`CompactionStart`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/compaction/CompactionStart.java) 冻结阶段、触发原因、执行模型、输出预算、切分锚点与子身份，后来排队的输入不会污染它。

[`CompactionConfig`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/compaction/CompactionConfig.java) 只有 `keepRecentTokens` 一个参数，其余阈值都由它与上下文窗口 `C`、输出上限派生：

```text
effectiveKeep    = min(keepRecentTokens, C/2)
effectiveReserve = min(16384, maxOutputTokens)
softThreshold    = max(effectiveKeep, C - effectiveReserve)
manualMinimum    = min(keepRecentTokens*2, C/2)
```

[`CompactionPlanner`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/compaction/CompactionPlanner.java) 是纯函数：从尾部估算长度，越过 `effectiveKeep` 后取下一个合法切点，绝不切在 Tool 结果或压缩回合内部；切点落在 Agent 轮次中间时先出 HISTORY 摘要，再由续写义务驱动 TURN_PREFIX 摘要。最新一批尚未被后续 assistant 消费的工具调用不会被提前摘要。[`CompactionResultEvaluator`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/compaction/CompactionResultEvaluator.java) 把截断、过滤、返回工具调用、空摘要、保留段解析失败与无 token 收益判为失败；可重试的失败走普通调用重试预算，耗尽则子执行失败、父回合失败且不交付部分结果。

手工压缩走 `compactThread`：单事务复验 expected version 与可用性，成功时原子提交压缩回合、子 Thread 与票据。[`ManualCompactionAvailability`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/ManualCompactionAvailability.java) 是给界面的瞬时投影（Thread 已停止、忙碌、模型已变化、低于最小量、无可压缩内容等），不能替代提交时的复验。`STOPPED` 是持久执行事实：即使历史仍满足压缩阈值，`manualDecision` 也在 resolve 与任何写入之前直接返回 `THREAD_STOPPED`；先由显式新输入恢复为 `RUNNABLE` 才允许再次压缩。

## 停止

Stop 在 tree 锁内校验目标 version 与 `stopRequestId`，按 `parentThreadId` 停止目标及全部后代（目标已空闲或已停止时仍覆盖后代）。事务先物化已提交的 Model/Tool 结果，再闭合在途回合，按类型 rank 顺序锁定并清理 THREAD、Driver 声明的 Work 与 TOOL；只有退出事实与直接子 Join 已收敛时才冻结 Join 结果，否则保留未完成 Join，由后续退出 ACK 推进。无 open 回合时追加 `TURN_START(STOP) → ASSISTANT_ERROR(CANCELLED) → TURN_END(STOPPED)` 屏障；已有 open 回合时按真实结果闭合，必要时补齐未知工具结果，绝不嵌套回合或伪造成功。

[`StopResult`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/StopResult.java) 返回完整受影响集合；每个 [`StoppedThreadReceipt`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/StoppedThreadReceipt.java) 记录停止边界、取消数量与被退回的人工输入（`USER_MESSAGE`）。`CUSTOM_MESSAGE` 与设置命令被取消但不退回；`NOTIFICATION` 不取消，而是物化进历史、同步推进 `inputThroughSequence` 且不唤醒模型。整次 Stop 只持久化每个 Thread 的 `(thread_id, stop_request_id)` 身份与停止边界，按 `root_stop_request_id` 归组；精确重放返回原集合、不重停新工作，后代的派生身份被当作新请求复用时为 `STOP_REQUEST_ID_REUSED`。

显式新输入恢复 STOPPED 目标前，必须已无 `UNCONFIRMED` 执行，且没有未完成的直接 `NOTIFY_PARENT` 或 `SETTLE_OWNER_TURN` 子 Join；`ABANDONED` 或已确认执行不阻塞恢复。设置类维护输入不触发恢复，因此不受此限制。已收敛后代的退出 ACK 可递归完成祖先的 Join 结算，不依赖祖先重新启动。

## 人工输入与权限

`ask_user` 不经过 preflight 或网关：ToolProcessor 在锁内把 READY 冻结为 `WAITING_INPUT`，问卷由调用 arguments 冻结。`submitToolInput` 在单事务内校验调用仍处于当前 `TOOL_ACTIVE` 上下文，按问卷校验并规范化答案，答案与提交回执同事务落盘（答复或明确拒答都收敛为 `SUCCEEDED`），随后唤醒 THREAD 物化结果；答案非法、目标不适用与提交身份冲突分别为 `INPUT_SUBMISSION_INVALID`、`INPUT_SUBMISSION_NOT_APPLICABLE` 与 `INPUT_SUBMISSION_MISMATCH`。`listPendingInteractions` 按 `(createdAt, id)` keyset 分页读取两种等待态，产品归属由上层解析。

[Tool 权限策略](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/tool/permission/PermissionEvaluator.java) 由平台在 Tool 网关的 preflight 边界实现：`PermissionEvaluator` 按有序规则产出 Allow / Ask / Deny 候选：规则键是 `*` 或模型可见工具名，从全局到具体工具求值，数组顺序即求值顺序；文件工具的路径取调用显式的绝对 `path` 或 `vfs:/` 路径，按 gitignore 语义匹配，VFS 路径保留 `vfs:/` 前缀，其他非绝对路径只命中通配规则；文件工具不携带 `workdir`。`bash` 的 `workdir` 是 `process.exec` 的调用参数，不参与文件路径规则，也不存在隐藏默认目录。真实文件边界、符号链接与进程隔离由 Environment Daemon 在执行入口落实。部署范围内的 permission 设置由 [`ToolSettingsProvider`](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/tool/permission/ToolSettingsProvider.java) 在每次 preflight 前现读，不缓存快照。

## 子智能体委派与 Join

内置 `task` 工具经 `AgentTaskService` 调用统一 `spawn`，在同一事务接受源 prompt、创建或续用子 Thread、写入 Join 票据并请求 Work。工具固定等待 Join 冻结后，把报告或明确失败/取消原因作为本次 tool result 返回；异步任务仍使用同一持久接受路径并保留父通知链。

`harness_thread_join` 的一行只记凭据：调用身份与请求指纹、父子 Thread、源命令序号、agent 与 `maxTurns`、交付方式，以及一次冻结的 `terminalEntryId` / `finalAnswerEntryId` 与交付命令序号。子任务并发额度由 `NOTIFY_PARENT` 与 `SETTLE_TOOL_RESULT` 的未完成 Join 直接推导，不设独立字段。[`JoinDelivery`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/join/JoinDelivery.java) 有四种：

- `NOTIFY_PARENT`：异步子任务，计入额度；同一事务校验父可接受本次调用、父 head 匹配与深度/并发额度，父已 `STOPPED` 时拒绝新委派。子完成后冻结结果并向父投递 `SUBAGENT_RESULT` 通知。续用只能向既有子追加 prompt，不能改父关系。
- `SETTLE_TOOL_RESULT`：同步 task 子任务，计入额度；Join 结算只冻结结果并唤醒父，不生成异步通知。等待中的工具线程重读固定 Join 并返回其收据。
- `RECEIPT_ONLY`：没有父的内部 root 任务，不计额度，只供票据观察。
- `SETTLE_OWNER_TURN`：压缩等运行时子执行，不计额度，结算后只唤醒父 Thread 的 open 回合；父已 `STOPPED` 时只推进一次 owner `version` 使结算后的派生状态可见，不请求 Work、不自动恢复执行。

同一父子对至多一个有效未完成 Join；子 Thread 无未完成的 `SETTLE_OWNER_TURN` 时，新续接可取代旧等待，否则须完成 owner 结算后才可继续。精确重放仍返回原票据。

普通结算要求：源命令已应用；没有尚未冻结结果的直接子 Join（含 owner 结算义务）、本 Thread 没有待处理输入且没有 `UNCONFIRMED` 执行——`ABANDONED` 或已确认执行不阻塞，因此多级委派自底向上自然收敛，既不等于首次本地 Idle，也不等待整棵子树永久静止。Stop 结算允许纳入尚未应用的源命令，但仍要求同一收敛事实。普通终态已物化但 Join 仍被未确认执行阻挡时，最后一次退出 ACK 在同一事务内补结算，不依赖后续 Work 恰好再次到达。普通终态物化与这次迟到确认共用同一最终回答规则：只有 `COMPLETED`、无 `continueModel` 义务且紧邻 `TURN_END` 之前是 ASSISTANT 消息时才冻结 `finalAnswerEntryId`；失败、续作与非 ASSISTANT 结尾一律为空。结果固定在源命令应用位置到终态边界的历史范围内，子树后续推进不改写旧回执。`maxTurns` 是软预算，超出时在规划中物化一条 `TASK_BUDGET` 通知，每个 Join 只提醒一次。

结算与父通知入队在同一事务完成：`NOTIFY_PARENT` 结算原子冻结终态结果、写入父 `NOTIFICATION` 命令、推进父序列并请求 Work，通过唯一次 `updateJoin` 直接落盘为 delivered 状态，不持久化已匹配但未送达的中间态。通知的消息由 `api.join` 纯函数渲染（`<subagent_result>` 包裹任务原文与结果），不读取子线程当前 head。显式停止的父不会被唤醒：通知直接物化进历史，待父接受新输入后由 `INPUT` 回合消费。

## 端口与策略

[`spi`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/) 包定义内核对外部设施的反向依赖，不泄漏 Spring、JDBC 或 HTTP 类型：

| 端口 | 契约 |
| --- | --- |
| [`ToolGateway`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/ToolGateway.java) | `preflight` 返回 `Allow` / `Ask` / `Deny`；`admit` 接收携带审批快照的执行请求，返回 `Admitted(run)` / `Busy` / `Rejected`；`Run.close` 释放本地资源。各方法共用的 [`ToolInvocationRequest`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/ToolInvocationRequest.java) 只冻结一次调用的 ToolCall 与 binding，随调用传递、不持久化 |
| [`ToolResultHistoryMaterializer`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/ToolResultHistoryMaterializer.java) | 在 Tool 结果 Entry 插入前、同一事务内把瞬时 Resource 外部化为 durable 内容 |
| [`ToolHistoryActionResolver`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/ToolHistoryActionResolver.java) | 按冻结 binding 找到 Tool 并渲染历史动作文本 |
| [`RealtimeEventSink`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/RealtimeEventSink.java) | best-effort 写入有损的 delta / checkpoint / partial 事件，失败不回滚任何事实 |

同一命名空间还放专用契约子包：[`spi.store`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/store/) 是领域模型与物理持久化的唯一边界，[`spi.driver`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/driver/) 是 Agent Driver 契约，见 [Agent Driver](#agent-driver)；它投影出的 generation 公开快照在 [`api.generation`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/generation/)。两者共同引用的 Work 邮箱值契约在 [`spi.work`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/work/)，不反向依赖 `runtime.*`。压缩配置与重试策略这些部署配置同样是反向依赖：压缩配置经 [`spi.compaction`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/compaction/) 在每个决策点现读，重试策略直接使用 JDK `Supplier` / `Function`，都不缓存快照；Tool 权限策略与工具设置不是内核端口，由平台在 Tool 网关边界实现，见 [Platform](platform.md#model-与-tool-执行)。

三种准入结论各对应唯一的 durable 后续：`Busy` 确定未开始，安全重排；`Rejected` 是确定性失败；`Admitted` 先落 RUNNING 再执行，此后请求或副作用都可能已发生，Model 记一次失败 attempt、Tool 收敛为 `UNKNOWN`，严禁盲目重放。内核不反查 owner、Issue 或阶段等产品门禁，需要终止在途执行时由业务显式调用 Stop。

并发槽位 `ConcurrencyAdmission` 属于平台实现，由 Model 与 Tool 网关各自持有，内核只声明端口；[`InvocationRetryPolicy`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/retry/InvocationRetryPolicy.java) 只计算是否允许下一次重试及其延迟（`FIXED` / `EXPONENTIAL`），不读时钟、不写状态，[`InvocationRetryDecision`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/retry/InvocationRetryDecision.java) 才把策略、错误可重试性与已消耗 attempt 合成唯一决策。策略对象全部构造注入：[`ThreadProcessorConfig`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ThreadProcessorConfig.java)、[`ToolProcessorConfig`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ToolProcessorConfig.java) 与共用的 [`ProcessorLeaseConfig`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/work/ProcessorLeaseConfig.java)（heartbeat 间隔必须严格小于租约时长），重试与压缩配置在每个决策点现读。Builtin 的 `ModelProcessorConfig` 与模型重试策略在 [Harness Agent Builtin](harness-agent-builtin.md) 维护。

## Tool 契约

Tool 的身份、定义、调用与结果是模型、配置、权限、目录与持久记录共享的同一份值契约，位于 [`tool`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/tool/) 包。

[`ToolDescriptor`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolDescriptor.java) 的 `name` 是工具唯一的 Agent 侧身份：模型调用、Agent 配置、权限规则、目录条目与 durable binding 都用它，没有第二套内部 ID 或工具版本。名字字母开头、只含字母数字与 `_`、`-`，至多 64 字符；`inputSchema` 复用 Common 的 `InputSchema`；`defaultTimeout` 为整毫秒，`0` 表示没有 deadline；[`ToolSideEffect`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolSideEffect.java) 的 `READ_ONLY` / `IDEMPOTENT` / `NON_IDEMPOTENT` 决定重试与未知结果的处理。[`AgentToolDefinition`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/tool/AgentToolDefinition.java) 把 descriptor 与 [`ToolVisibility`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolVisibility.java)（`SELECTABLE` / `INTERNAL`）组合为模型契约。

[`ToolCall`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolCall.java) 原样保存模型给出的调用 ID、工具名与参数 JSON object；转换只发生在显式的 `validateFor(descriptor)` 中：先要求工具名一致，再按 schema 归一化数字字符串与可缺省的 null，最后严格校验并返回新调用。原始 function call 仍保存在模型结果中，供审计与 wire replay。

[`ToolResult`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolResult.java) 由调用 ID、至多 64 项 `ResultContent`、至多 1 MiB 的 `detailsJson` 与 `error` 标记组成，失败也作为语义结果交给模型。可持久化的 [`ToolResultJsonCodec`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/tool/codec/ToolResultJsonCodec.java) 拒绝 Binary：内存字节必须先在执行边界外部化为受管 Resource。结果预算由 [`ToolResultSizeLimits`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/ToolResultSizeLimits.java) 按 canonical JSON 计算（终态 1 MiB、partial 256 KiB），与平台网关共用同一上限，Resource 声明的预览与文本元数据由平台的 [`ToolResultFinalizer`](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/tool/gateway/ToolResultFinalizer.java) 按实际字节复核。模型可见的工具错误统一使用 [`ToolErrorGuidance`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolErrorGuidance.java) 的三段式英文文案：发生了什么、执行事实、下一步怎么做。

## Contributor 与目录

Contributor 是受信 Java 扩展的接入契约（[`contributor.api`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/) 包）：它声明工具，内置工具与插件走同一条装配路径。

[`HarnessContributor`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessContributor.java) 只有 `descriptor()` 与 `contribute(registrar)`。[`ContributorDescriptor`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ContributorDescriptor.java) 给出 canonical id、展示名、版本与 `requires`；[`HarnessRegistrar`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessRegistrar.java) 在 `contribute` 期间注册工具。

[`HarnessCatalog.from`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessCatalog.java) 是唯一的冻结入口：先拒绝重复 id、缺失或成环的 `requires`，再按依赖拓扑与 id 字典序调用 `contribute`；注册时强制模型可见工具名全局唯一。平台在构建每个插件快照时冻结一次目录（见 [Platform](platform.md#构建期-plugin)）。

[`Tool`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/Tool.java) 是统一的阻塞执行契约：

```java
ToolDescriptor descriptor();
default ToolRequirements requirements();                  // 默认无环境、无状态访问
default Duration resolveTimeout(ToolCall call);           // 默认 descriptor.defaultTimeout()
default Optional<ToolHistoryRenderer> historyRenderer();  // 默认 absent
ToolOutcome execute(ToolExecutionRequest request, Consumer<ToolResult> progress) throws InterruptedException;
```

进入 `execute` 前调用状态已持久化为 RUNNING：返回即终态，业务失败返回 error 结果，运行时异常收敛为结果不确定；取消、Stop 与租约丢失都表现为 interrupt，实现必须响应中断并释放资源。`resolveTimeout` 是执行前唯一的超时解析点，非法的参数级超时抛 `IllegalArgumentException`，由网关收敛为确定性拒绝。[`ToolExecutionRequest`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolExecutionRequest.java) 携带已归一化的调用、已解析的超时与可选执行上下文，不携带 workdir。[`ToolHistoryRenderer`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolHistoryRenderer.java) 是可选纯函数，在原生 Tool 历史无法交给 Provider 时把调用渲染为自然语言动作。

[`ToolRequirements`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolRequirements.java) 冻结两项声明：

```text
environmentSupport       NONE / ROUTED / REQUIRED
```

`ROUTED` 是逐调用路由的文件工具（`read`、`write`、`edit`、`grep`、`find`）：工具层面不固定执行位置，每次调用按 `path` 走 Platform VFS 或绑定的 Environment；`REQUIRED` 才表示只能在环境上执行，未选择环境时从模型工具面过滤。

`REQUIRED` 工具只在分支已选择 Environment 时进入模型工具面，执行时经 [`BoundEnvironment`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/BoundEnvironment.java) 阻塞调用一个能力，发送确定性由异常类型表达（Busy/Unavailable 确定未执行，SendUncertain 可能已被接受）。

新增 Contributor：实现 `HarnessContributor`（常用 `HarnessContributor.of`），按实际执行面声明环境支持级别；内置实现注册为 Spring bean，插件实现从配置实例返回（见 [Platform Plugin API](platform-plugin-api.md)）。

## 内置工具

[`BuiltinHarnessContributor`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/contributor/builtin/BuiltinHarnessContributor.java)（id `builtin`）注册第一方工具：

| 模型可见 name | 依赖 | 可见性 | 说明 |
| --- | --- | --- | --- |
| `read` / `write` / `edit` / `grep` / `find` | Platform VFS + Environment（ROUTED） | SELECTABLE | 按调用 `path` 路由：`vfs:` 走 VFS，绝对路径走环境 |
| `bash` | Environment（REQUIRED） | SELECTABLE | `process.exec`，NON_IDEMPOTENT |
| `lsp_goto_definition` / `lsp_workspace_symbols` / `lsp_java_decompile` | Environment | SELECTABLE | 对应 `lsp.*` 能力，READ_ONLY |
| `task` | 无 | INTERNAL | 委派 Subagent 任务，NON_IDEMPOTENT |
| `ask_user` | 无 | SELECTABLE | 冻结问卷并等待人工回答 |

每次请求的工具面由平台从目录与 Agent 配置解析：配置了子代理时加入 INTERNAL `task`，未选 Environment 时只过滤 `REQUIRED` 工具（`bash` 与 LSP），文件工具始终可见。五个文件工具由 [`FileTool`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/contributor/builtin/environment/FileTool.java) 实现：schema 与默认超时取自同名能力描述符，`vfs:` 路径调用 [`VfsGateway`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/spi/VfsGateway.java)，绝对路径委托 `BoundEnvironment`，没有绑定环境时非 `vfs:` 路径返回「未执行」并提示会话目录。`bash` 与 LSP 由 [`EnvironmentCapabilityTool`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/contributor/builtin/environment/EnvironmentCapabilityTool.java) 绑定能力执行。

[`TaskTool`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/contributor/builtin/subagent/TaskTool.java) 把参数交给 [`SubagentRunner`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/contributor/builtin/subagent/SubagentRunner.java)，以本次调用为幂等键；模型固定以 `async=false` 等待 Join 冻结结果。等待仅发生在独立工具执行线程：Join 终态事务通过子 Thread version change 唤醒该线程，线程重读固定 Join 并投影 ToolResult，不阻塞事务或调度线程。父 Stop 中断等待并将 task Invocation 持久结算为 CANCELLED。[`SubagentConfig`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/contributor/builtin/subagent/SubagentConfig.java) 的深度、单父并发、全局并发与轮数软预算在每个决策点现读。文件窗口读取、LSP 生命周期、同步 task 与保留的异步通知契约见 [内置工具](builtin-tools.md)。

## Prompt 模板

[`PromptRenderer`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/prompt/PromptRenderer.java) 从 classpath 读取 UTF-8 Markdown，以 JMustache 编译并缓存模板；变量或条件缺失时失败。双花括号默认执行 XML 转义，三花括号显式插入原文，插入的值不会再次执行。工具 JSON schema 作为资源原样读取，不作为模板执行。Harness 的模板按 system、tools、messages、compaction 分目录；Platform 和 Project 的消息资源由各模块持有。Agent 用户正文不作为模板执行。

System prompt 固定按 `[agent]` → `[harness]` → `[paths]` → `[environment]` → `[skills]` → `[subagents]` 排列，段间空一行。文案只存在于 Markdown 模板中，Platform 的 composer 只准备视图数据：`[agent]` 在 Agent 正文非空时出现；`[harness]` 始终出现；`[paths]`、`[environment]` 与其中的记忆、绝对路径、VFS 路径、可写和宿主字段说明按本轮实际注入的工具生成，无环境时不出现 bash/LSP 说明；`[skills]` 与 `[subagents]` 分别只在有可注入技能或已配置子 Agent 时出现。Prompt 不含日期或上层产品上下文，Issue 等任务内容只进入任务消息。

`session_dir` 固定使用执行根所在会话，`thread_dir` 在同一会话下使用当前 Thread；子 Agent 不会因为自身运行在独立会话而得到不同的 VFS 根。执行根会话由 Builtin Driver 在调用 `TurnResolver` 前从根 Thread 行解析并作为显式参数传入，preview 与 debug 规划复用同一入口。

`[skills]` 按 Agent 配置顺序列出技能名称与描述，不包含文件路径、交付方式、摘要或 Environment 安装状态。

## 严格 JSON 契约

持久状态与 wire 载荷由各自的 Jackson 3 codec 校验：拒绝重复键、尾随内容，以及未声明、缺失或类型错误的字段；自由 JSON 子树（厂商选项、native replay、工具详情）允许自有字段，并以 `BigDecimal` 搬运，`1e309` 与高精度小数不会经 double 丢失数量级或有效位。以字符串保存的 `argumentsJson`、`detailsJson` 保留原文；Provider 的 `rawUsageJson` 只是响应内的文本事实，不进入 durable 历史。厂商选项的 canonical 编码移除无意义的尾零，无法映射为 JSON 的输入明确拒绝。

Harness 的严格读取配置集中在模型模块的 [`HarnessJson`](../../harness/model/src/main/java/fun/fengwk/kkstudio/harness/model/HarnessJson.java)：普通树、保精度树和选项 canonical 化各使用对应的共享 mapper；请求体定价另保留自己的字节与嵌套深度限制。object 判定、精确字段集合与标量原语统一走模型模块的 [`JsonCodecSupport`](../../harness/model/src/main/java/fun/fengwk/kkstudio/harness/model/codec/JsonCodecSupport.java)，各 codec 只声明自己的字段集合、错误上下文与值类型语义；持久化身份中的 UUID 经 [`Uuids.parseCanonical`](../../common/src/main/java/fun/fengwk/kkstudio/common/id/Uuids.java) 解析，非 canonical 文本直接拒绝。

平台 HTTP 投影直接承载 codec 定义的 JSON 节点：Entry、队列命令与取消输入的 `payload`，
工具调用的 `result` / `error` / `approval`、交互的 `approval`，以及 Debug 的
`inputSchema` / `configuration`。模型输出的 `argumentsJson` 可能不是合法 JSON，Provider 请求的
`requestJson` 也保持原文字符串；execution 的 `settings` 是 JSON 对象。64 位整数（非负）在 HTTP 请求与响应中
都是规范十进制字符串，客户端输入错误在产生处以 `DomainException` 表达。

## 包架构

生产源码位于 `fun.fengwk.kkstudio.harness` 之下，公开值类型集中在 `api` 根与 `api.*` 子包，它们的 wire codec 在 `codec`，`runtime.*` 只保留实现与仅内核可见的内部值；表中省略该前缀：

| 包名 | 职责与边界 |
| --- | --- |
| `api` | 对外公开的消费契约与公开值类型：`HarnessChangeSubscriptions` 暴露 Thread version、执行树与待处理交互的变更订阅，事件与句柄不可变，不泄漏 Store 或 channel 协议；同一根包承载控制面入参与查询结果的形状（`AcceptCommandsCommand`、`SpawnRequest`、`StopResult`、`StoppedThreadReceipt`、`ThreadTreeNode`、`ToolOutcomeView`、`ManualCompactionAvailability`、`Names`）及冲突原因名称 `HarnessConflictReason`；实现由平台提供 |
| `api.command` | 命令邮箱的公开值类型：`ThreadCommand`、封闭 payload 族与派生的 `ThreadCommandState`；只做不变量校验与纯派生，JSON 形态与 request hash 在 `codec`，harvest 归约在 `runtime.thread.command` |
| `api.compaction` | 压缩的公开值类型：`CompactionConfig`（只含 `keepRecentTokens` 与阈值换算的纯函数）、`CompactionPhase` / `CompactionTrigger`、切分事实 `CompactionPreparation` 与 `TURN_START` 上冻结的 `CompactionStart` |
| `api.entry` | 分支配置与回合边界值：`BranchSettings`、`ExecutionSelection`、`ModelSelection`、`TurnStartReason` 与 `TurnEndOutcome` |
| `api.execution` | 具体执行代次的退出证据：`ExecutionExitFact`、`ExecutionExitState` 与 `ExecutionExitKind`（只有 `DRIVER` / `TOOL` / `REMOTE`，Driver 私有模型调用统一归 `DRIVER`）；真实证据含本地 `STUDIO_EXITED` / `OWNER_EXITED`、远端 `REMOTE_ACKNOWLEDGED` 与 `NOT_STARTED`，`OWNER_EXITED` 只允许本地 `DRIVER` / `TOOL`；登记、确认、journal 与恢复扫描在 `runtime.execution` |
| `api.generation` | Thread 快照中 generation 部分的公开投影：`GenerationProjection` 承载稳定身份、`GenerationStatus` 生命周期状态、已完成的文本/思考输出与结构化错误，`FailureProjection` 描述未物化的失败 attempt；消费方读快照无需依赖 `spi.driver`，包内不暴露 Driver 私有调用实体与 Provider 协议 |
| `api.history` | Entry Tree 的公开值类型：`Entry`、sealed payload 家族、`EntryPath` / `EntrySlice` 路径视图、`SubagentBinding`、`EntryType` 与 `TurnPathValidator` 回合文法校验；payload 编解码与 payload mapper 在 `codec`，历史归一化与最终回答判定在 `runtime.history` |
| `api.interaction` | 等待输入/审批的只读投影与冻结环境的待领取 Work 候选；先按执行根和环境聚合再按代表键分页，也可按调用读取 Work 可领取性与时间边界。环境在线状态、名称及产品归属由平台判断 |
| `api.join` | Join 票据与交付凭据：`ThreadJoin`、`JoinDelivery`、`ThreadJoinOutcome`、`ThreadJoinReceipt` 与完成通知渲染 `ThreadJoinCompletionRenderer`；持久化推进与交付在 `runtime.join` |
| `api.realtime` | 有损实时投影的公开契约：sealed `RealtimeEvent`（Model delta / Model checkpoint / Tool partial）及其 wire 种类 `RealtimeEventType`、oversize payload 的跨节点上限 `RealtimePayloadLimits` 与不可用错误 `RealtimePayloadUnavailableException`；wire 编解码在 `codec`，通知信封、缓冲与恢复在平台实现，本包不持有 durable state |
| `api.retry` | Invocation 自动重试的公开配置值：`InvocationRetryPolicy`（最大重试次数、退避算法与延迟区间，含纯退避计算）、`InvocationRetryBackoffStrategy`（`FIXED` / `EXPONENTIAL`）与只判定 400–599 HTTP 失败是否进入预算的 `ModelHttpErrorPolicy`；不读时钟、不持久化，判定实现在 `runtime.retry`，部署配置以 JDK `Supplier` / `Function` 现读 |
| `api.session` | Session 聚合、sealed 消息内容家族与 `AssistantMessageMetadata`；`SystemReminder` 用精确定界符构造与判别运行时注入的 USER 上下文提醒；wire codec 在 `codec` |
| `api.thread` | Thread 级公开值：`ThreadState`、`ThreadSnapshot`、`ThreadExecutionControl`、`ThreadRuntimeStatus`、`ThreadExecutionFacts` 与记录 live 分类的 `ThreadLiveState`、创建时冻结的 `ThreadConstraints` / `SpawnTrigger`，以及停止 / 执行根开关 / 手动压缩的控制请求形状；不引用 Store、Driver 或 `runtime` 内部类型 |
| `api.tool` | Tool invocation 的公开值：`ToolInvocation` 与 `ToolInvocationStatus`、`ToolBinding` / `ContributorBinding` 及 `ToolBindingIdentifiers`、审批与输入回执、`ToolInvocationError`；只含不变量与纯状态转换，执行、调度在 `runtime`，wire 编解码在 `codec` |
| `codec` | api 值类型的严格确定性 JSON wire codec 与由此派生的 request hash：`AgentMessageJsonCodec` 定义消息与 durable 内容记录的形态，`ThreadCommandPayloadJsonCodec` 在其上定义 typed command payload 并用 canonical 信封计算 hash，`RealtimeEventJsonCodec` 定义 `api.realtime` 投影事件的 live overlay 形态；`HistoryEntryPayloadJsonCodec` 按 `EntryType` 定义 Entry payload 的 durable 形态（子树委派包私有 `HistoryValueCodecs` 与 `AgentMessageJsonCodec`），`HistoryPayloadMapper` 只做终态 ToolInvocation / history normalization 到 payload 的纯映射；Tool 持久化列与 Daemon 预览共用的 `ToolBindingJsonCodec` / `ToolApprovalJsonCodec` / `ToolEditReviewJsonCodec` 共享 `InvocationJsonSupport` 的严格 JSON 原语，Builtin 的 `ModelRequestSpec` 列 codec 在 harness/agent-builtin；不含状态机、持久化与 mailbox 归约 |
| `runtime` | 同步控制面 `HarnessRuntime` 与包私有控制事务（接受、停止、手工压缩、人工输入）、spawn、执行树锁与 Session 删除 / fork 复制；守卫锁序，执行状态流转委托 `spi.driver` 契约与 `runtime.processor`；门面同时只读转发 `runtime.processor` / `runtime.compaction` 的规划事实（candidate 路径、压缩 turn 冻结元数据） |
| `runtime.compaction` | 切分规划、prompt、摘要装配与结果评估、子执行 spawn；规划与评估是纯函数，自动压缩策略由 Driver 提供，配置 provider 在 `spi.compaction`，公开值类型在 `api.compaction`；`TokenEstimator` 是不读 Store、不读时钟的 token 估算唯一实现，切分、no-gain 判定、自动压缩阈值与请求估算端口共用它 |
| `runtime.driver` | Driver 的注册与路由装配：`DriverRegistry` 与未安装 Driver 替身 `UninstalledAgentDriver`；契约在 `spi.driver`，generation 投影在 `api.generation` |
| `runtime.execution` | 具体执行代次的窄退出账本：物理句柄与 PREPARED/EXIT_PROOF 两阶段日志、身份登记、单向真实 ACK、显式放弃、旧 PREPARED 的 `OWNER_EXITED` 恢复、跨实例远端退出对账与确认后的 Join 结算推进；证据形状在 `api.execution`，不复制 Invocation 状态机 |
| `spi.execution` | 本地物理执行端口：`LocalExecutionLifecycle`、`LocalExecutionIdentity`、`LocalExecutionHandle`、`LocalExecutionDrain` 与上一进程恢复结果；Builtin Driver 与 Tool Processor 只经此登记、取消、证明退出和排空，不访问 Driver 私有表 |
| `runtime.history` | 历史归一化、最终回答判定与 Tool 结果模型内容；Entry payload 的 JSON 编解码与 payload mapper 在 `codec`，值类型（含 `ToolResultMetadata` / `ToolResultReason`）与回合文法校验在 `api.history` |
| `runtime.input` | `ask_user` 问卷与答案的纯值校验和规范化 |
| `runtime.join` | Join 的持久化推进、历史投影与父通知交付；票据、交付凭据与渲染在 `api.join` |
| `spi` | 内核对外部设施的窄端口：Tool 网关及其 transient 执行请求 `ToolInvocationRequest`、Tool 结果编码尺寸契约 `ToolResultSizeLimits`、结果物化、历史动作、实时事件与内部 Thread 变更唤醒源；Builtin 的 Model/Turn 端口在 harness/agent-builtin |
| `spi.compaction` | `CompactionConfigProvider`：Runtime 对部署配置的反向依赖，每个压缩决策点现读 `CompactionConfig`，不缓存快照、不持有订阅；`RequestTokenEstimator`：Turn 解析对请求输入 token 估算的反向依赖，只接收 `EntryPath` 与 system instruction，实现由组合根绑定到 `runtime.compaction.TokenEstimator`，消费方不接触 runtime 实现类型 |
| `spi.driver` | Agent Driver SPI：`AgentDriver`、`DriverCapabilities`、`TurnResolution`、`LockedDriverState`、`DriverStopResult` 等，以及共用工具构件 `ToolDeclarationPlanner` / `ToolOutcomeAppender`；组合根与外部 Driver 依赖此包，Runtime 不读取具体 Driver 私有状态，其投影出的 `GenerationProjection` 在 `api.generation`，见 [Agent Driver](#agent-driver) |
| `runtime.processor` | Thread / Tool Processor、准入后的阻塞执行与 Processor 配置；共用执行骨架在 `spi.work`，Builtin 的 MODEL Work 在 harness/agent-builtin |
| `runtime.retry` | 调用重试判定 `InvocationRetryDecision`：把 `api.retry` 策略、错误是否可重试与已消耗 attempt 合成唯一决策，只计算可行性与延迟，不读时钟、不写状态；策略值在 `api.retry`，部署配置以 JDK `Supplier` / `Function` 现读 |
| `spi.store` | `HarnessStore` 事务原语，领域模型与物理持久化的唯一边界 |
| `spi.work` | Work 调度邮箱的持久化与分发值契约，以及已认领执行骨架 `ClaimedExecutionSupport`、准入、租约心跳和失权信号；不依赖 `runtime.*` |
| `runtime.thread` | Thread 的 live/historical 分类与活动事实评估；`ThreadState`、执行控制与状态投影在 `api.thread` |
| `runtime.thread.command` | 命令 payload 的 harvest 归约与值校验；payload JSON 编解码与 request hash 在 `codec`，实体与状态派生在 `api.command` |
| `tool` | Tool 身份、定义、调用、结果、消费者媒体能力（`TurnMediaCapabilities`）与错误文案的值契约 |
| `tool.codec` | Tool 定义与结果的严格 codec |
| `contributor.api` | Contributor SPI、`Tool` 执行契约与冻结目录 |
| `contributor.builtin` | 第一方 Contributor 与内置工具的历史渲染 |
| `contributor.builtin.environment` | 绑定环境能力的宿主工具与统一 `read` |
| `contributor.builtin.input` | `ask_user` 工具定义 |
| `contributor.builtin.subagent` | `task` 工具、委派请求与子代理配置 |
| `prompt` | 严格 prompt 模板与 classpath 加载 |

`tool`、`contributor`（含第一方 `contributor.builtin`）与 `prompt` 是契约包，绝不 import `harness.runtime.*` 的实现；`runtime` 也不 import `contributor.builtin`。这些方向由仓库检查 [`check.mjs`](../../scripts/dev/verify/repository/check.mjs) 守护。

## 源码与测试

- 内核测试集中在 [`harness/runtime/src/test`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/)：控制面、Thread/Join、历史与 Store 契约都在本模块内运行，`mvn -pl harness/runtime -am verify` 可独立完成测试与覆盖门禁。执行物理生命周期与恢复的重点入口是 [`ExecutionLifecycleTest`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/ExecutionLifecycleTest.java)、[`ExecutionExitConfirmerRecoveryTest`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/ExecutionExitConfirmerRecoveryTest.java) 与 [`FileExecutionExitJournalTest`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/execution/FileExecutionExitJournalTest.java)。
- Runtime test-jar 是唯一中立 kernel 测试基座：提供内存 Store、Store 契约基类、`StoreTestSupport` 与 `FakeAgentDriver`。`agent-builtin` 与 `platform-runtime` 的测试通过该 test-jar 复用中立内核 fixture；`agent-builtin` 自己的 test-jar 只导出 Builtin 私有模型语义支撑，二者职责不混。
- 内存参考 Store 与 PostgreSQL 实现同构：`InMemoryHarnessStore` 只持有唯一已提交快照与全局 monitor，`InMemoryHarnessTransaction` 负责复制快照、commit/rollback、事务时钟、id 分配与锁阶梯，九个聚合原语各由一个 `InMemory*Store` 类实现，Driver 私有行经事务核心公开的测试 hook 在同一事务快照内挂载。锁阶梯为 `Session → Thread → Command → Driver → Tool → Work`，不存在第二份状态或按聚合拆分的快照。
- `FakeAgentDriver` 是 `AgentDriver` 的最小确定性实现：默认没有私有状态，Stop 只为位于 request basis 的活跃 generation 追加 CANCELLED barrier；可配置 turn resolution、活动投影、响应物化与停止结果。其私有状态只存在内存、不参与 Store 事务回滚，因此只用于不验证 Driver 私有持久化的内核测试。
- 关键类覆盖由各模块自身 `verify` 阶段的 `jacoco:check` 按各自 POM 中的类清单校验，逐类 `LINE` 覆盖率不低于 90%。真实 PostgreSQL 上的 claim / lease / notify 契约由 [Platform](platform.md#harness-持久化与调度) 的测试守护，运行方式见 [开发与测试](../operations/development-and-testing.md)。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Model](harness-model.md)、[Harness Environment](harness-environment.md)、[Common](common.md)、[Platform](platform.md)。
