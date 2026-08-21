# Harness Session、Thread、Pane 与 Command 模型

本文定义 kk-studio Harness Runtime 当前生效的 Session Tree、Thread materialization、Command mailbox、Chat/Canvas 归属、Pane 交互、Stop 恢复、上传生命周期和前端模块边界。

系统使用单一 clean-slate V1 schema、严格 API/codec 与单轨读写路径。

## 1. 核心模型

系统只保留四个相互正交的概念：

```text
Entry       = 已经发生的不可变语义历史
Thread      = 从哪个 Entry 继续执行的 durable 游标
Command     = 已被服务端接受、等待兑现的 durable 输入
PaneTarget  = 用户当前想查看或从哪里继续的浏览器本地意图
```

设计原则：

1. Session 组织一棵 append-only Entry Tree。
2. Entry 属于 Session，不属于 Thread；共享历史不复制。
3. Thread 属于 Session，是 Tree 上已 materialize 的执行游标。
4. 一个 Session 可以有 `0..N` 个 Thread；多 Thread 共享 Session 历史是核心能力。
5. 选择 Session、Thread 或 Entry 只改变 Pane，不修改任何 durable Thread。
6. `/tree` 选择 Entry 时不创建 Thread；第一次 durable command batch 被原子接受时才 materialize。
7. `/new` 只进入本地 Draft；第一次发送才原子创建 Session、ROOT、Thread、Command 和 Work。
8. Chat 与 Canvas 使用同一套 Pane、Command、Runtime 和 Composer 内核。
9. PostgreSQL 是唯一 durable truth；Work 只负责调度，Redis/realtime 只负责加速和展示。

## 2. 领域关系

```text
Chat 1 ──< N ChatSession >── 1 Session
Canvas 1 ─< N CanvasSession >─ 1 Session

Session
├── 1 ROOT
├── 1..N Entry
├── 0..N Thread
└── 0..N SessionBlobRef

Thread
├── headEntryId -> Entry
├── 0..N ThreadCommand
├── 0..N ModelInvocation
├── 0..N ToolInvocation
└── 0..1 coalesced Thread Work
```

用户 Session 恰好归属于一个 Chat 或 Canvas。内部 Session，例如 Task 和 one-shot，不写 Chat/Canvas 归属。

### 2.1 Session

Session 是一棵独立上下文树的持久化边界：

```text
Session {
  id
  createdAt
}
```

不变量：

- 每个 Session 恰有一个 ROOT。
- Session 不保存 head、version、updatedAt、title、activeThreadId。
- Session 不因 Thread 执行而更新，避免成为多 Thread 并发热点。
- Session 可以在删除全部 Thread 后继续保留 Entry Tree。

### 2.2 Entry

Entry 是不可变语义事实：

```text
Entry {
  id
  sessionId
  parentEntryId
  payload
  createdAt
}
```

不变量：

1. ROOT 的 `parentEntryId` 为 null。
2. 非 ROOT Entry 必须有 parent。
3. parent 必须属于同一个 Session。
4. 一个 Session 只有一个 ROOT。
5. Entry 提交后不得更新、换 parent 或迁移 Session。
6. Entry type 固定为：
   - `ROOT`
   - `TURN_START`
   - `MESSAGE`
   - `CUSTOM`
   - `MODEL_ATTEMPT_FAILURE`
   - `CUSTOM_MESSAGE`
   - `ASSISTANT_ERROR`
   - `ASSISTANT_ABORTED`
   - `COMPACTION`
   - `TURN_END`

Entry Tree 保存 Agent Loop 的完整语义事实；Invocation 保存执行明细，二者不合并。

### 2.3 Thread

Thread 是 Session Tree 上已 materialize 的执行游标：

```text
Thread {
  id
  sessionId
  headEntryId
  materializationHash
  yoloEnabled
  nextCommandSequence
  version
  createdAt
  updatedAt
}
```

不变量：

1. `sessionId` 创建后不可变。
2. `headEntryId` 必须属于同一个 Session。
3. 多个 Thread 可以指向同一个 Entry。
4. head 只能由 Runtime 推进到当前 head 的新 descendant。
5. 不提供任意 head relocation API。
6. Command、Invocation、Work、version 和 YOLO policy 都是 Thread-scoped。
7. BranchSettings、Environment 和对话状态从 root-to-head path 派生，不复制到 Thread 行。
8. Thread 必须与第一批 Command 在同一事务创建，不存在 durable empty Thread。

`materializationHash` 是创建请求的 canonical SHA-256，覆盖：

- materialization target；
- Session、起点 Entry 和客户端预分配的 Thread id；
- 初始 YOLO；
- 新 Session 的 ROOT settings 与 SubagentContext；
- 第一批 ordered Command 的 `clientCommandId + requestHash`。

`requestHash` 与 `materializationHash` 均由服务端基于 canonical semantic request 计算；客户端提供的 hash 若存在只用于一致性校验，不作为 truth。Command 顺序、CUSTOM role、ordered upload handle identity 和所有影响 durable 结果的字段进入 hash，JSON property 顺序不进入语义。

`materializationHash` 只用于第一次 materialization 的精确重放和 ID 复用冲突检测，不参与后续执行。

### 2.4 ThreadCommand

ThreadCommand 是 durable mailbox item：

```text
ThreadCommand {
  threadId
  sequence
  payload
  clientCommandId
  requestHash
  consumedTurnStartEntryId?
  cancelRequestId?
  cancelledAt?
  createdAt
}
```

状态由 terminal marker 派生：

```text
QUEUED     consumedTurnStartEntryId = null, cancelledAt = null
APPLIED    consumedTurnStartEntryId != null
CANCELLED  cancelledAt != null, cancelRequestId != null
```

Command type 固定为六类：

```text
USER_MESSAGE
CUSTOM_MESSAGE
SET_ENVIRONMENT
SET_AGENT
SET_MODEL
SET_ACTIVE_TOOLS
```

不增加 durable Batch 表、Batch 状态或 Batch ID。原子 HTTP batch 只是一次事务请求；Command sequence 和 user-like message 边界足以表达执行顺序。

## 3. PaneTarget

Pane 只保存本地选择，不保存两级 active selection：

```ts
type PaneTarget =
  | {
      kind: 'NEW_SESSION_DRAFT'
    }
  | {
      kind: 'ENTRY_DRAFT'
      sessionId: string
      startEntryId: string
    }
  | {
      kind: 'BOUND_THREAD'
      threadId: string
    }
```

不保存：

```text
selectedSessionId
activeSessionId
activeThreadId
```

`selectedSessionId` 只允许作为 `/thread` 选择面板的临时导航变量。

### 3.1 状态机

```text
                         /thread select T
             ┌──────────────────────────────────┐
             │                                  ▼
┌─────────────────────┐   first batch     ┌───────────────────┐
│ NEW_SESSION_DRAFT   │ ────────────────> │ BOUND_THREAD(T)   │
└─────────────────────┘   S+ROOT+T        └───────────────────┘
          ▲                                      │
          │ /new                                 │ /tree select E
          │                                      ▼
          │                              ┌─────────────────────┐
          └──────────────────────────────│ ENTRY_DRAFT(S,E)    │
                                         └─────────────────────┘
                                                  │
                                                  │ first batch
                                                  │ materialize T
                                                  └──────> BOUND_THREAD(T)
```

`/tree` 和 `/new` 自身产生零数据库写入。

### 3.2 Draft 来源

```text
NEW_SESSION_DRAFT
  base   = 进入 Draft 时的 Chat/Canvas 默认设置或当前 Pane 有效设置
  target = 本地 BranchDraft
  yolo   = 本地初始值

ENTRY_DRAFT
  base   = startEntryId 的 root-to-entry path 派生设置
  target = 本地 BranchDraft
  yolo   = 本地初始值

BOUND_THREAD
  base   = snapshot 对已接受 queued settings 的有效投影
  target = 本地 BranchDraft
  yolo   = Thread durable policy
```

新 Session 首发时把最终 BranchDraft 冻结进 ROOT；第一批通常只携带消息。EntryDraft 与 BoundThread 首发按 `base -> target` 生成最小 SET_* 前缀。

### 3.3 Unknown acceptance

网络超时不增加第四种 PaneTarget。客户端使用正交 sidecar：

```ts
type PendingAcceptance = {
  owner: AgentRuntimeOwnerDTO
  target: PaneTarget
  request: AgentCommandBatchRequestDTO
  branchDraft: BranchDraft
  composerParts: ComposerPart[]
  generation: number
  unknownOutcome: boolean
}
```

要求：

- `sessionId`、`threadId`、`clientCommandId` 在首次请求前生成。
- unknown outcome 逐字节重放原请求（frozen `request`），不生成新 ID。
- PendingAcceptance 持久化到 Pane local storage，刷新后仍可恢复。
- 同一 Pane 同时至多存在一个 PendingAcceptance；未决期间 Composer 可继续编辑，但禁止第二次 durable send。
- 只有收到权威成功响应后才切换到 `BOUND_THREAD`。
- 用户在请求发出后继续输入的新草稿不得被旧请求的成功或失败覆盖。
- completion 只有在 pending identity/generation 仍匹配时才可修改 PaneTarget；被放弃请求的迟到成功只更新后台 Thread projection。
- 确定失败时把冻结的 `composerParts` 前置合并到当前草稿，不覆盖请求后继续输入的内容。

### 3.4 导航门控

导航状态分三类：

1. 后台 Thread 正在 Model/Tool/queued execution：不阻止 `/thread`、`/tree`、`/new`。
2. PendingAcceptance、未决 Stop replay 等精确控制面：阻止最终切换，直到成功、确定失败或用户明确放弃。
3. 未发送正文、附件或 BranchDraft：切换前确认丢弃。

旧 Thread 切走后继续按 `threadId` 接收 snapshot/realtime 更新，不得抢回当前 Pane。

## 4. Command batch 形状

### 4.1 用户输入 batch

已有 Thread 上的 Chat、Canvas 和 Task prompt 输入 batch 必须满足：

```text
SET_ENVIRONMENT?
SET_AGENT?
SET_MODEL?
SET_ACTIVE_TOOLS?
exactly one user-like message
```

user-like message 是：

- `USER_MESSAGE`；或
- role 为 USER 的 `CUSTOM_MESSAGE`。

约束：

1. 一个 batch 恰有一个 user-like message。
2. user-like message 必须是 batch 最后一条 Command。
3. SET_* 不能脱离 user-like message 单独由产品 UI 入队。
4. 每种 SET_* 至多一条，并固定使用上述顺序。
5. batch 中 `clientCommandId` 不重复。
6. ordered Command 在同一事务获得连续 sequence。

NEW_SESSION/ENTRY 的初始 batch 可以在 user-like message 前携带 SYSTEM `CUSTOM_MESSAGE`，用于 one-shot 或 Task 的初始系统上下文。此时 Thread 尚无 live continuation，因此不会与 soft steering 混淆。已有 Thread 的用户输入 batch 禁止携带 SYSTEM `CUSTOM_MESSAGE`。

### 4.2 SYSTEM soft steering batch

内部 Task 可以入队只含一条 SYSTEM `CUSTOM_MESSAGE` 的 steering batch，例如 max-turn reminder。

Steering batch：

- 不创建新用户输入；
- 不含 SET_*；
- 不含 USER role message；
- 恰有一条 SYSTEM `CUSTOM_MESSAGE`；
- 在 sequence 允许的下一个 INPUT 或 CONTINUATION 边界消费。

设置永远只在下一次 INPUT 边界生效，不在当前 Turn 的 continuation 中途切换 Agent、Model、Tools 或 Environment。

### 4.3 一次 INPUT 只消费一条用户输入

ThreadProcessor 启动 INPUT 时：

```text
load QUEUED commands by sequence
-> consume prefix ending at the first user-like message
-> leave later input batches QUEUED
```

因此：

- 每次用户发送对应一个 INPUT Turn；
- 不同 queued 消息的 BranchDraft 不会互相覆盖；
- 后续消息在前一个 Turn 结束后依次执行；
- Stop 可以确定性找回尚未处理的用户消息。

INPUT 消费其 user-like message 之前的 leading steering；CONTINUATION 只消费队首连续 steering，不越过 SET_* 或 user-like message。位于一个 queued 用户输入之后的 steering 不会反向影响该输入的初始 Model call，只能影响其后续 continuation 或更晚的 INPUT。

已有 Thread 的用户输入 batch 禁止 SYSTEM `CUSTOM_MESSAGE`，steering batch 又被约束为单 Command；因此 sequence 与 user-like message delimiter 可以无歧义恢复所有 batch 边界，不需要 durable Batch ID。

## 5. Lazy materialization 与原子接受

Session/Thread materialization 与 Command acceptance 只有一个写入原语：

```java
AcceptedCommands acceptCommands(
    AcceptCommandsCommand command,
    AcceptancePreflight preflight);
```

`AcceptCommandsCommand.target` 是 sealed target：

```text
NEW_SESSION
  sessionId
  threadId
  rootSettings
  subagentContext?
  yoloEnabled

ENTRY
  sessionId
  startEntryId
  threadId
  yoloEnabled

THREAD
  threadId
  expectedHeadEntryId
  expectedNextCommandSequence
```

NEW_SESSION 和 ENTRY 的第一批必须是用户输入 batch；THREAD 同时允许用户输入 batch和内部 SYSTEM steering batch。

### 5.1 客户端 ID

对用户请求：

- NEW_SESSION：客户端预分配 `sessionId`、`threadId` 和全部 `clientCommandId`。
- ENTRY：客户端预分配 `threadId` 和全部 `clientCommandId`。
- THREAD：使用已有 `threadId`，预分配全部 `clientCommandId`。

UUID 只是不可预测的实体身份；服务端仍执行 owner、Session、Entry 和 request hash 校验。

### 5.2 通用事务顺序

```text
1. replay lookup
2. validate owner and target
3. materialize Session/ROOT/Thread when required
4. lock and consume upload handles
5. allocate continuous command sequences
6. insert Command rows
7. upsert Thread Work
8. version + 1
9. commit
```

replay lookup 必须发生在：

- expected cursor 校验之前；
- upload existence/expiry 校验之前；
- Work admission 之前。

原因是成功请求的响应可能丢失，而 upload 已在第一次事务中被消费。精确重放必须直接返回已存在事实。

### 5.3 NEW_SESSION

用户 Session 的单事务：

```text
lock Chat/Canvas owner
-> require sessionId/threadId unused
-> insert Session
-> insert ROOT(final BranchDraft, optional SubagentContext)
-> insert chat_session or canvas_session
-> insert Thread(
     sessionId,
     headEntryId=ROOT,
     materializationHash,
     yoloEnabled,
     nextCommandSequence=1,
     version=0)
-> consume uploads into SessionBlobRef/RESOURCE
-> insert first Commands
-> reserve sequences and version
-> upsert Thread Work
```

任一步骤失败，Session、ROOT、owner relation、Thread、Command、Blob 引用和 Work 全部回滚。

内部 Session 执行相同 Harness 事务，但不写 owner relation。

### 5.4 ENTRY

```text
lock owner and Session
-> require start Entry exists and belongs to Session
-> require threadId unused
-> insert Thread(headEntryId=startEntryId, ...)
-> consume uploads
-> insert first Commands
-> upsert Thread Work
```

该操作：

- 不复制 Entry；
- 不修改任何已有 Thread；
- 不要求其他 Thread quiescent；
- 允许其他 Thread 正在同一个 Session 中执行。

任意已提交且属于 Session 的 Entry 都可以成为 EntryDraft 起点。若路径位于另一个 Thread 拥有的 open historical Turn 中，第一次 INPUT 由现有 historical normalization 补齐 HISTORY_CUT 语义，再启动新 Thread 自己的 Turn。

### 5.5 THREAD

```text
read immutable thread.sessionId
-> lock Session FOR KEY SHARE
-> lock Thread
-> revalidate Thread still belongs to Session
-> exact command-set replay lookup
-> require expected head + next sequence
-> consume uploads
-> insert Commands
-> upsert Work
-> version + 1
```

queued SET_ENVIRONMENT 只在后续 INPUT 边界消费，因此 enqueue 不再要求当前 Thread quiescent。当前 live Turn 始终使用自己 TURN_START 已冻结的 settings。

Session 使用 `FOR KEY SHARE`，因此 sibling Thread acceptance 彼此兼容，但 Session 删除必须等待所有可能写 Session-scoped FK 事实的事务完成。

### 5.6 Materialization replay

NEW_SESSION/ENTRY retry：

```text
find Thread by client-provided threadId
-> require materializationHash identical
-> require Session and owner identical
-> replay ordered command set
-> return original acceptance identity and current projections
```

同一 Thread id 携带不同 materialization hash 返回稳定 `409 MATERIALIZATION_ID_REUSED`。

同一 `clientCommandId`：

- 同 Thread、同 request hash：精确 replay；
- 同 Thread、不同 hash：`409 COMMAND_ID_REUSED`；
- 不同 Thread：允许复用。

ordered command-set replay 必须同时满足：

```text
all requested clientCommandIds exist
stored requestHash all equal
stored sequences form one contiguous range
stored sequence order equals request order
first stored sequence equals the request's original expectedNextCommandSequence
```

NEW_SESSION/ENTRY 的 original expected sequence 固定为 1。partial match、跨历史 batch 拼接、缺失、重排或非连续集合都返回稳定 409。

Replay 精确保证的是接受事实和副作用 identity：

- `sessionId`、`threadId`、Command `clientCommandId/sequence/requestHash` 相同；
- 不重复消费 upload、不重复创建 Work 或实体；
- 返回的 Thread 是当前权威 projection；
- 返回的 Command 可以已经 APPLIED 或 CANCELLED，terminal lifecycle 不属于 acceptance replay equality。

## 6. Entry append 与 Agent Loop

Command 被接受后 Thread 已存在，但 Entry 仍由 Processor 异步追加：

```text
accept Commands
-> durable Thread Work
-> ThreadProcessor locks Session FOR KEY SHARE, then Thread
-> plan one INPUT
-> append normalization suffix when required
-> append TURN_START(full effective BranchSettings, ownerThreadId)
-> append one user-like message
-> mark consumed Commands APPLIED
-> advance Thread head
-> create ModelInvocation / Work
```

设置命令本身不 append Entry。它们由 reducer 折叠到 `TURN_START.settings` 完整快照。

```text
/agent
/environment
/models
  -> local BranchDraft
  -> SET_* + user-like message
  -> TURN_START.settings
```

`/yolo` 不属于 BranchSettings：

- Draft target：修改本地初始值；
- Bound Thread：立即 version CAS 更新 durable policy；
- Tool permission preflight 读取当前 Thread policy。

共享历史 correctness 继续依赖：

- `TURN_START.ownerThreadId`；
- historical normalization；
- compaction ownership barrier；
- Invocation 永不跨 Thread 继承；
- Stop replay 按 owning Thread 界定 raw request id。

### 6.1 Durable compaction

Compaction 是 Thread-owned 的内部 durable Turn，不是 Session 行更新，也不是一条进程内辅助调用：

```text
owned closed normal Turn
-> compaction due
-> THREAD Work
-> TURN_START(reason=COMPACTION, ownerThreadId)
-> ModelInvocation(request.compaction)
-> COMPACTION payload or ASSISTANT_ERROR / ASSISTANT_ABORTED
-> TURN_END
```

它复用已有：

- Thread version/head；
- ModelInvocation 与 MODEL Work；
- lease、retry 和 crash recovery；
- Stop 与迟到结果 fence；
- Entry append-only audit。

不增加 Compaction 表、Session summary 列、Thread compaction status 或独立调度器。

#### 6.1.1 触发

部署配置只有两个字段（来自 `SystemSettings.AiRuntime`，经 `HarnessCompactionConfiguration` 装配）：

```text
keepRecentTokens = 20000   // compactionKeepRecentTokens 默认值
fallbackModel    = null    // compactionFallbackModel 默认值；null 表示不做一次性 fallback
```

派生预算（每次 planning 瞬时重算）：

```text
effectiveKeepRecentTokens = min(keepRecentTokens, floor(contextWindow / 2))
effectiveReserve          = min(16384, maxOutputTokens)
softThreshold             = max(effectiveKeepRecentTokens, contextWindow - effectiveReserve)
manualMinimum             = min(keepRecentTokens * 2, floor(contextWindow / 2))
outputBudget              = min(maxOutput, floor(0.8 * effectiveReserve), removedPrefixEstimate)
```

Trigger 固定为三态：

```text
THRESHOLD
  active same-owner CONTINUATION_DUE 或真实 queued user demand
  AND (
    current Thread 最近 compatible owned successful Provider usage
    + 该 Assistant 之后的可见消息估算（含 ToolResult）
  ) > softThreshold

OVERFLOW
  current Thread 最新 owned Turn 以 OVERFLOW 失败
  -> compact
  -> immediate CONTINUATION
  -> 最多一次

MANUAL
  用户在 Bound Thread 发起 /compact
  -> availability 门控（THREAD_BUSY / OWNERSHIP_BARRIER / NO_RESOLVED_CONTEXT /
     MODEL_CHANGED / BELOW_MINIMUM / NOTHING_TO_COMPACT）
  -> expectedVersion CAS 提交 MANUAL plan
  -> 与自动触发共用 MODEL Work、一次 fallback 与 crash recovery
```

执行优先级：

```text
owned HISTORY -> TURN_PREFIX obligation
-> active continuation boundary 上 actionable Compaction
-> normal CONTINUATION
-> idle/historical boundary 上 actionable Compaction
-> queued user input
```

Compaction 消费零 Command。完全结束的 idle run 在没有新 user demand 时不因 soft threshold 自唤醒；长工具链则在每个 `continueModel=true` turn-end boundary 先评估压缩，避免等到最终 idle 才处理已经过大的上下文。压缩期间新输入仍可作为 durable Command 排队。

#### 6.1.2 切分与阶段

切分算法使用当前 root-to-head EntryPath：

1. 从最新 complete `COMPACTION` 的 `cutEntryId` 开始；没有时从 ROOT 后开始。
2. 从路径尾部按 token 估算反向累加。
3. 最近保留预算达到 `effectiveKeepRecentTokens` 后选择下一个合法 cut。
4. cut 只允许可见 USER、ASSISTANT、CUSTOM_MESSAGE、ASSISTANT_ABORTED；绝不切在 ToolResult。
冻结两个 Entry anchor：

```text
cutEntryId
  后续 Provider Context 中首个 retained context message

turnPrefixStartEntryId
  cut 所属 logical Agent segment 的首个 user-like message；
  segment 从最近 INPUT 开始并跨后续 CONTINUATION durable turns
```

Entry 从不删除；retained boundary 只引用 `cutEntryId`，BranchSettings 和控制元数据始终从完整 root-to-head path 派生。最新 complete compaction 是 logical Agent segment 回扫的硬边界。

阶段：

| Phase | 语义 | durable 结果 |
| --- | --- | --- |
| `FULL` | 完整历史摘要 | complete `COMPACTION` |
| `HISTORY` | split turn 的历史部分 | incomplete `COMPACTION` |
| `TURN_PREFIX` | split turn 的早期部分 | 与紧邻 HISTORY 合并后的 complete `COMPACTION` |

Completeness 由 Phase 派生：

```text
HISTORY                  -> incomplete
FULL / TURN_PREFIX       -> complete
```

不在 payload 中重复保存 `complete` boolean。

`HISTORY -> TURN_PREFIX` 复用已冻结的 `cutEntryId`、`turnPrefixStartEntryId` 和 trigger，不重新选 cut。

最终 TURN_PREFIX payload 必须自包含：

```text
summaryText =
  immediately preceding matching HISTORY.summaryText
  // direct TURN_PREFIX 使用固定 "No prior history."
  + stable separator
  + TURN_PREFIX model output
  + canonical file sections
```

不能只保存第二次模型的 prefix 输出，也不能扫描并合并任意更早的 stale HISTORY。

最终 `CompactionPayload`：

```text
summaryText
```

`phase`、`trigger`、`executionModel`、`cutEntryId`、`turnPrefixStartEntryId`、`historyCompactionEntryId` 全部冻结在对应 `TURN_START` 的 `CompactionStart` 中，不重复写入 payload；complete 由 phase 与 TURN_END outcome 派生。Threshold 使用 owned normal Turn 的权威 Provider usage，并补算该 Assistant 之后的可见消息；cut planning 估算在规划时瞬时重算。

#### 6.1.3 请求与 Provider Context

Compaction 请求只使用：

```text
当前 path 派生的 Model provider/name/variant
transient CompactionPreparation（candidate path 已冻结在 TURN_START.compaction / CompactionStart）
fixed compaction prompt resources
```

不读取或绑定：

```text
Agent system prompt
Environment live state
Plugin ContextProjector
Tool / Skill / Subagent
YOLO
prompt-cache writes
```

输出预算：

```text
FULL / HISTORY  min(model output limit, floor(effectiveReserve * 0.8), removedPrefixEstimate)
TURN_PREFIX     min(model output limit, floor(effectiveReserve * 0.5), removedPrefixEstimate)
```

普通 Model 请求的 compaction-aware history：

```text
preamble
-> latest complete COMPACTION summary as one wrapped USER message
-> entries from cutEntryId onward
```

以下内容不进入 Provider Context：

- 被 summary 替代的旧前缀；
- `TURN_START(COMPACTION)...TURN_END` 控制 Turn；
- `MODEL_ATTEMPT_FAILURE`；
- `ASSISTANT_ERROR`；
- failed/stopped/incomplete compaction result。

Entry 历史不删除。Compaction 只改变 Provider Context 投影。

#### 6.1.4 失败与恢复

- Compaction Model 使用普通 Invocation retry 和 Work crash recovery。
- Compaction retry checkpoint 不物化 `MODEL_ATTEMPT_FAILURE` Entry，保持现有审计契约。
- failed/stopped Compaction Turn 保留 durable Entry，但对 transcript、token estimate 和 Provider Context不可见。
- failed/stopped Compaction 不自唤醒；owned incomplete HISTORY 只产生一次机械 TURN_PREFIX wake，不进入 threshold retry spin。
- complete OVERFLOW Compaction 只产生一次 immediate CONTINUATION；再次 OVERFLOW 后保留失败并停止该恢复链。
- 成功 THRESHOLD FULL/TURN_PREFIX/fallback 若压缩前存在 same-owner normal continuation，则把该 obligation 重写到新 TURN_END 并继续；foreign owner 不可借用。失败、无增益或 fallback 耗尽后停止该恢复链。
- Stop 终止当前 Thread 拥有的 Compaction Invocation；queued 用户输入按普通 Stop receipt 取消并恢复到 Composer。
- owned incomplete HISTORY 与 TURN_PREFIX 之间是 durable continuation obligation；Stop 在 phase gap 获胜时追加一个 `TURN_START(COMPACTION) -> ASSISTANT_ERROR(CANCELLED) -> TURN_END(STOPPED)` barrier，确定性取消该 obligation。
- Model retry 始终从 Invocation 的 immutable `basisHeadEntryId` 重建摘要请求，不能从已经推进的当前 Thread head 查找 previous checkpoint。

#### 6.1.5 文件清单

摘要末尾的：

```text
<read-files>
...
</read-files>

<modified-files>
...
</modified-files>
```

由 Runtime 根据历史 Assistant `read/write/edit` Tool Call 重算，不信任模型返回的 reserved section。规则：

- 旧 reserved section 先剥离；
- malformed reserved tag fail closed；
- 每次完整结果从 ROOT 到 `cutEntryId` 重算累计文件事实，不只扫描本次新增摘要区间；
- `write/edit` 覆盖同路径的 `read`；
- 结果按路径排序；
- Tool result 摘要输入最多保留 2000 字符。

### 6.2 与 `~/proj/pi` 的关系

`~/proj/pi` 同时存在两套 compaction 形态：

1. `packages/coding-agent` 是当前完整可运行实现：`AgentSession` 编排 summarization，并以一个 retained-entry reference 保存最终 summary。
2. `packages/agent` 是新的 Harness 形态：Compaction 是 operation record，`CompactionEntry` 内嵌 `retainedTail`；但当前 `AgentHarness.compact()` 仍未完成，不作为 kk-studio 行为事实源。

kk-studio 与可运行 Pi 实现保持算法对齐：

| 能力 | 一致语义 |
| --- | --- |
| 自动阈值 | projected context tokens 严格大于 soft threshold |
| 默认预算 | reserve 16384、recent 20000 |
| token 估算 | 文本约 `ceil(chars / 4)`，媒体使用固定占位预算 |
| cut point | 可以切 USER/ASSISTANT/custom，不切 ToolResult |
| split turn | history summary + turn-prefix summary |
| previous summary | 迭代更新而非从头总结全部历史 |
| 输出预算 | history 0.8 reserve，turn-prefix 0.5 reserve |
| 文件清单 | 累积 read/modified files |
| Tool result | 摘要序列化截断到 2000 字符 |
| Cache | one-shot summary 请求不写 prompt cache |
| Overflow | compact-and-retry 最多一次 |

架构差异：

| 维度 | Pi coding-agent | kk-studio |
| --- | --- | --- |
| 执行游标 | 一个 Session 当前 leaf | Session `0..N` durable Thread，可并行执行 |
| Compaction lifecycle | AgentSession 内存编排 | durable internal Turn + Invocation + Work |
| 持久化结果 | 单个 CompactionEntry | `TURN_START -> COMPACTION -> TURN_END` |
| split turn | 一个操作中最多调用两次 LLM，最后写一条 Entry | HISTORY 与 TURN_PREFIX 分成两个可恢复 durable Turn |
| retained suffix | retained-entry reference | 单一 `cutEntryId` 引用，不复制 immutable Entry |
| 运行恢复 | 主要依赖当前进程/Session reload | lease、Work、Invocation 与 Store 支持进程重启恢复 |
| Stop | AbortController | Thread-scoped durable Stop + late-result fence |
| 输入并发 | 普通 prompt 在 compaction 中被拒绝；部分队列等待 | Command 可 durable 排队，Compaction 消费零 Command |
| usage ownership | 当前 active branch 的最近 assistant usage | 只借用当前 Thread owned usage，shared history 有 barrier |
| manual | `/compact [instructions]` | `/compact` 手动压缩：availability 门控 + expectedVersion CAS，固定 deterministic prompt，不支持 per-Agent instructions |
| hook | extension 可取消或替换 summary | 固定 deterministic prompt，无 summary hook |
| `/tree` | 可把离开分支摘要注入目标分支 | 选择 EntryDraft/Thread，不隐式合并 sibling 分支 |
| recent budget | 直接使用 `keepRecentTokens` | 额外限制为 context window 的一半 |

### 6.3 多 Thread Compaction 不变量

Compaction 同时具有两种不同归属：

```text
execution ownership
  TURN_START.ownerThreadId
  ModelInvocation / Work / Stop
  -> 永远 Thread-scoped

complete summary fact
  COMPACTION Entry
  -> 属于 Session Entry Tree
  -> 位于 sibling root-to-head path 时可以共享
```

Owner barrier：

| 事实或义务 | 是否可以被 sibling 共享 |
| --- | --- |
| complete summaryText、cutEntryId、canonical file sections | 可以 |
| normal Turn usage 触发 threshold | 不可以 |
| `TURN_END.continueModel=true` continuation | 不可以 |
| incomplete HISTORY → TURN_PREFIX obligation | 不可以 |
| OVERFLOW compact-and-retry obligation | 不可以 |
| Model/Tool Invocation、Work、Stop、late result | 不可以 |

最终规则：

1. Trigger usage 只能来自当前 Thread owned normal Turn；遇到其他 `ownerThreadId` 即停止向前借用 usage。
2. 完整 `COMPACTION` 是 Entry-only context checkpoint；其 owner Thread 删除后，位于保留路径上的 sibling 仍可使用 summary。
3. incomplete `HISTORY` 不是可共享 checkpoint；`TURN_PREFIX` obligation 只能由该 COMPACTION Turn 的 `ownerThreadId` 继续。
4. sibling Thread 遇到 foreign incomplete HISTORY 时忽略整个内部 Compaction Turn，不继承 Invocation、不启动 TURN_PREFIX，直接处理自己的后续输入。
5. `TURN_END.continueModel=true` 只有其引用 TURN_START 的 `ownerThreadId` 等于当前 Thread 时才是 `CONTINUATION_DUE`；foreign continuation 视为 historical。
6. complete OVERFLOW checkpoint 可以共享，但其 retry/continuation obligation 绝不共享。
7. failed/stopped Compaction 不成为 freshness 或 Provider Context barrier。
8. 新 materialized Thread 在第一条 owned INPUT 前不根据 foreign normal Turn usage 自动压缩。
9. Thread 从 complete Compaction 后的 Entry materialize 时直接继承 summary；从更早 Entry materialize 时形成独立路径，不受该 summary 影响。
10. `ownerThreadId` 是不可变 UUID 事实，不要求 owner Thread 行仍存在。

`compactionPreparation` 对 incomplete HISTORY 的机械延续使用 owner 校验：

```text
latest Turn is completed COMPACTION/HISTORY incomplete
AND latest TURN_START.ownerThreadId == currentThread.id
  -> prepare TURN_PREFIX
otherwise
  -> no inherited continuation
```

`ThreadContextClassifier` 的 continuation 分类也必须 owner-aware：

```text
head is TURN_END(continueModel=true)
AND referenced TURN_START.ownerThreadId == currentThread.id
  -> CONTINUATION_DUE
otherwise
  -> IDLE_OR_HISTORICAL
```

### 6.4 最终 Compaction 边界

保留：

- durable internal Compaction Turn；
- FULL/HISTORY/TURN_PREFIX；
- 最小 `cutEntryId / turnPrefixStartEntryId` anchor；
- fixed prompts、file sections、one-shot cache policy；
- threshold/overflow 自动触发；
- MANUAL 手动压缩（`/compact` 命令 + `POST /{threadId}/compact`，availability 门控 + expectedVersion CAS）；
- current Thread ownership barrier；
- complete summary 的 Session Entry 共享。

不增加：

- per-Agent/custom compaction instructions；
- extension summary replacement hook；
- Pi branch summary；
- copied `retainedTail`；
- payload 外的重复 retained/tokens/completeness 字段；
- Compaction 专用表、status 列或调度器。

理由是这些能力都不是上下文容量 correctness 所必需；其中 branch summary 和 copied tail 还会模糊 kk-studio 的 sibling Thread 隔离与 immutable shared-history 模型。

## 7. Stop 与 Composer 恢复

### 7.1 StopResult

领域 `StopResult` 保留 replay fact 与完整取消内容：

```text
StopResult {
  replayed
  thread
  stoppedTurnEndEntryId?
  cancelledCommandCount
  cancelledUserMessages[]
}
```

`thread` 始终是响应时的当前权威 projection，不属于 replay receipt equality。Replay 精确相等的是 `stoppedTurnEndEntryId`、被取消 Command identity 和 `cancelledUserMessages`。

派生语义：

```text
stoppedTurnEndEntryId != null   停止了 live Turn
cancelledCommandCount > 0       取消了 queued Commands
两者都没有                       no-op
replayed = true                 命中 durable receipt
```

`cancelledUserMessages` 按 Command sequence 升序：

```text
CancelledUserMessage {
  sequence
  clientCommandId
  contents
}
```

返回 USER_MESSAGE 和 USER role CUSTOM_MESSAGE；不把 SET_* 或 SYSTEM steering 填回用户输入框。

HTTP DTO 固定为：

```text
HarnessThreadStopResultDTO {
  status                       // STOPPED / IDLE / REPLAYED
  thread
  stoppedTurnEndEntryId?
  cancelledCommandCount
  cancelledUserMessages[] {
    sequence
    clientCommandId
    messageJson                // canonical USER AgentMessage JSON
  }
}
```

wire 无独立 `replayed` 字段；queued-only replay 的 `stoppedTurnEndEntryId` 仍为 null，取消计数与消息和原 receipt 一致。

### 7.2 Stop 事务

```text
read immutable thread.sessionId
-> lock Session FOR KEY SHARE
-> lock Thread
-> find replay receipt before version CAS
-> require expectedVersion
-> classify current Thread context
-> reject terminal-apply-pending
-> lock/delete owned Work
-> mark every QUEUED Command:
     cancelledAt = now
     cancelRequestId = stopRequestId
-> stop only current Thread owned Model/Tool Invocation
-> append TURN_END(USER_STOP) when a live owned Turn exists
-> update Thread head/version
-> commit
-> best-effort cancel external Model/Tool execution
```

Replay receipt：

- live Turn：Session Entry 中 `TURN_END.closeRequestId`，并通过其 `TURN_START.ownerThreadId` 限定 Thread；
- queued Commands：`threadId + cancelRequestId`；
- 相同 raw stopRequestId 在不同 Thread 上互不冲突。

无 live Turn 且没有 queued Command 时不写 marker、不增加 version。此 no-op 没有 durable replay receipt；如果其后 Thread version 已变化，旧请求重试返回 stale version。

Stop identity 是 `(threadId, stopRequestId)`；`expectedVersion` 只作为首次执行 fence，不属于 receipt identity：

- transport outcome unknown：逐字节重放原请求；
- 确定收到 `STALE_VERSION`：清理该 pending operation，刷新 snapshot；下一次 Stop 使用新 stopRequestId 与当前 version；
- 通过 version fence 的 Stop 取消其获得 Thread lock 时存在的全部 queued Commands。

### 7.3 前端恢复

Stop 发起时：

```text
PendingStopOperation {
  stopRequestId
  expectedVersion
  basisHeadEntryId
  basisVersion
}
```

操作 identity 作为 per-Thread local sidecar 持久化，直到 Stop receipt 已成功应用、已知 409 或权威 basis 变化；页面刷新或 transport outcome unknown 后继续沿用同一 request body 重试。当前草稿由 Composer draft storage 独立保存。

成功响应后：

```text
cancelled messages in sequence order
-> convert durable contents to ComposerPart
-> use two newlines between message boundaries
-> prepend before current unsent draft
-> persist draft
```

规则：

- Resource 恢复成 durable Resource pill，不恢复已消费的 upload handle。
- recovered Resource 重新提交为 `RESOURCE(blobId,name,preview?)`；只有目标 Session 已有 blob ref 才接受，不重复 retain，新/跨 Session 引用回滚整个接受事务。
- 当前 BranchDraft 保持不变。
- 多个 cancelled 输入合并后形成一次新的未来 submission，统一使用当前 BranchDraft；不承诺恢复每条旧输入各自的历史 settings。
- 同一 stopRequestId 的 replay 响应最多应用一次。

ComposerPart 统一为：

```ts
type ComposerPart =
  | TextPart
  | UploadPart
  | ResourcePart
```

local storage 保存文本与 durable Resource；attachment/upload 注册表和浏览器 File 只在当前页面会话存活，含 attachment 的草稿不写入持久化 draft。

## 8. Upload 与 Blob 生命周期

### 8.1 上传状态

```text
PENDING storage_upload
  blobId = null
  temp object = uploads/{uploadId}/original

READY storage_upload
  blobId != null
  upload owns exactly one Blob reference
```

`expiresAt` 同时约束 PENDING 和 READY。

### 8.2 Command 接受时消费

在 Command 接受事务内：

```text
lock READY upload
-> require not expired
-> materialize USER_MESSAGE attachment as durable RESOURCE(blobId, name, preview)
-> establish harness_session_blob_ref
-> idempotently delete upload-scoped temp object
-> delete storage_upload
-> transfer or release upload-owned Blob reference
```

若 Session 尚未引用 Blob，upload 的一份 retain 直接转移给 SessionBlobRef；若 Session 已引用该 Blob，删除 upload 后 release 多余 retain。

Command payload 和 Stop 恢复结果只保存 durable Resource，不再依赖 upload handle。

### 8.3 回收

回收只保留三个入口：

```text
client best-effort DELETE（pill 删除、Draft 明确丢弃、owner 销毁）
periodic server recovery
startup recovery
```

服务端周期任务是权威回收路径：

```text
fixed delay
-> expire one SKIP LOCKED upload batch
-> sweep one DELETING blob batch
```

启动恢复循环排空历史积压；日常 API 不再承担机会式 GC，避免上传请求耦合清理延迟。

并发规则：

- consume、client DELETE 和 GC 都先锁同一 `storage_upload` 行；
- 谁先持锁谁完成唯一一次 delete/release；
- missing row 对 DELETE/GC 视为成功；
- 多实例通过 `FOR UPDATE SKIP LOCKED` 安全并行。

物理对象删除分两类：

```text
upload-scoped temp object
  唯一 durable locator 是 storage_upload.id
  -> 必须先幂等删除 temp object，再删除 upload 行
  -> 对象删除失败则保留行供周期/启动恢复重试
  -> 对象删除成功但数据库回滚时，后续重试仍可幂等收敛

durable Blob object
  storage_blob(DELETING) 是 durable locator
  -> 数据库先提交 DELETING
  -> afterCommit / periodic sweep 删除对象和 Blob 行
```

因此进程崩溃不会在丢失唯一数据库 locator 后留下永久 PENDING 临时对象。

## 9. Chat、Canvas、Session 与 Thread 查询

### 9.1 归属

使用两张有真实外键的关联表：

```text
chat_session(chatId, sessionId, createdAt)
canvas_session(canvasId, sessionId, createdAt)
```

不使用失去数据库外键能力的多态 owner 表。

创建归属时锁 Session，检查两张表后写入其中一张，保证用户 Session 只属于一个 owner。

`chat` 和 `canvas_document` 不保存当前 Session 或 Thread。Canvas Graph 与 Agent Session 生命周期独立。

### 9.2 Session summary

```text
GET /api/ai/chat/{chatId}/sessions
GET /api/canvases/{canvasId}/sessions
```

返回：

```text
sessionId
createdAt
lastActivityAt
firstMessagePreview
threadCount
```

派生规则：

- `lastActivityAt` 取 Session、Entry、Thread 活动时间最大值；
- 用户消息按 `createdAt, id` 确定性排序；标题顺序为首条用户文本、首个附件文件名、`Session {shortId}`（shortId = session UUID 前 8 位）；
- 不增加 Session title 列。

### 9.3 Thread summary 与 Tree

```text
GET /api/ai/runtime/sessions/{sessionId}/threads
GET /api/ai/runtime/sessions/{sessionId}/entries
```

Thread summary：

```text
threadId
createdAt
updatedAt
status
model
headMessagePreview
```

status 从 Thread context、Invocation 和 queued Command 派生，不增加 Thread status 列。

Session 可以没有 Thread。`/thread` 第二级为空时提供“浏览 Tree 并从 Entry 继续”，最终设置 `ENTRY_DRAFT`，仍不立即创建 Thread。

`/thread` 选择状态机：

```text
SESSION_SEARCH
  Enter Session -> THREAD_SEARCH(sessionId)
  Esc           -> close picker

THREAD_SEARCH(sessionId)
  Enter Thread  -> BOUND_THREAD(threadId)
  Browse Tree   -> select Entry -> ENTRY_DRAFT(sessionId, entryId)
  Esc           -> SESSION_SEARCH
```

BoundThread 打开时默认高亮其 Session/Thread；EntryDraft 默认高亮其 Session。picker 中的 `sessionId` 只存在于面板临时状态。

## 10. 前端架构

Chat 与 Canvas 只保留薄 owner wrapper：

```text
ChatWorkspacePane(owner={CHAT, chatId})
CanvasAgentThread(owner={CANVAS, canvasId})
                    │
                    ▼
               AgentPane
               ├── agent-pane/pane-target.ts（PaneTarget / PendingAcceptance sidecar 持久化）
               ├── useAgentPaneController（三态 FSM）
               ├── InteractionPanels（SelectionPanel / HistoryBranchPanel / ThreadInteractionPanel）
               ├── draft target ──────────────► ThreadPanel（ThreadComposer + WidgetStack + Footer）
               └── BOUND_THREAD ──► ChatPanel ──► ThreadPanel
                                                ├── ConversationView
                                                ├── DebugView
                                                └── WidgetStack / ThreadComposer
```

模块职责：

```text
agent-pane/pane-target.ts
  PaneTarget 与 PendingAcceptance sidecar 的本地持久化；不持久化普通 BranchDraft

useAgentPaneController
  三态 FSM、命令可用性、导航门控、first-send materialization、本地 BranchDraft 持有

AgentPane
  owner wrapper 之下的共享 pane：draft 与 bound 都渲染 ThreadPanel，BOUND_THREAD 经 ChatPanel
  注入 transcript/activity

ChatPanel
  面板级 Thread 适配器：基于 ThreadPanel 封装 transcript/activity/composer 输入

useAgentThreadController
  Bound Thread snapshot、realtime、enqueue、stop、approval、yolo

ThreadProjectionCache
  复用现有 TanStack Query cache，按 threadId 保存后台 projection，按 version 丢弃过期更新

ThreadComposer
  ComposerPart 编辑、上传、history、selection bookmark、Stop prepend transaction

SelectionPanel / HistoryBranchPanel / ThreadInteractionPanel
  Session/Thread/Agent/Environment 选择、Entry Tree 与 slash 命令共用的交互面板
```

禁止再分别实现 Chat/Canvas command switch、draft send pipeline 或 runtime client。

### 10.1 Slash commands

`+` 菜单和纯文本 slash 输入使用同一命令注册表：

| 命令 | 语义 |
| --- | --- |
| `/thread` | Session 搜索 → Thread 搜索 → 切换 `BOUND_THREAD`；零 Thread Session 可进入 Tree |
| `/agent` | 修改本地 BranchDraft；随下一条输入提交 `SET_AGENT + SET_ACTIVE_TOOLS` |
| `/environment` | 修改本地 BranchDraft；随下一条输入提交 `SET_ENVIRONMENT` |
| `/yolo` | Draft 修改初始值；Bound Thread 立即 CAS 更新 durable policy |
| `/models` | 修改本地 BranchDraft；随下一条输入提交 `SET_MODEL` |
| `/tree` | 选择当前 Session 的 Entry，并切换到 `ENTRY_DRAFT` |
| `/stop` | 停止当前 Thread 并恢复被取消的用户消息 |
| `/compact` | 在 Bound Thread 发起手动压缩：先经 availability 门控（THREAD_BUSY / OWNERSHIP_BARRIER / NO_RESOLVED_CONTEXT / MODEL_CHANGED / BELOW_MINIMUM / NOTHING_TO_COMPACT），再以 expectedVersion CAS 提交 MANUAL Compaction Turn |
| `/new` | 切换到 `NEW_SESSION_DRAFT` |
| `/upload` | 插入 ordered upload part |
| `/debug` | Conversation 与当前 Thread Debug View 间切换 |
| `/shortcuts` | 打开快捷键目录 |

### 10.2 可用性

| PaneTarget | 可用命令 |
| --- | --- |
| `NEW_SESSION_DRAFT` | `thread`、`agent`、`environment`、`yolo`、`models`、`upload`、`shortcuts` |
| `ENTRY_DRAFT` | `thread`、`agent`、`environment`、`yolo`、`models`、`tree`、`new`、`upload`、`shortcuts` |
| `BOUND_THREAD` | 全部（`/compact` 以 snapshot `manualCompaction.available` 为前置门控） |

Chat 和 Canvas 对同一个 PaneTarget 使用同一矩阵。

### 10.3 Debug 与快捷键

`/debug` 只展示当前 Thread：

- root-to-head Entry path；
- queued/applied/cancelled Commands；
- Model/Tool Invocation；
- Work/realtime overlay；
- retry、usage、error。

快捷键 catalog 是唯一用户可见事实源。Debug View：

```text
ArrowUp / ArrowDown  选择相邻 Event
Escape               有 detail 时关闭 detail，否则返回 Conversation
```

组件内部的无障碍键盘行为可以本地实现，但用户可发现的快捷键必须登记到 catalog 并有实现测试。

## 11. Runtime 与 HTTP API

### 11.1 事务边界

用户请求由唯一应用服务承接：

```text
StudioCommandAcceptanceService
└── one PostgreSQL transaction
    ├── authorize and KEY SHARE lock owner scope
    └── HarnessRuntime.acceptCommands(command, acceptancePreflight)
        ├── replay lookup / target validation
        ├── materialize Session/ROOT/Thread when required
        ├── acceptancePreflight
        │   ├── insert NEW_SESSION owner relation
        │   └── validate/consume uploads
        └── insert Commands / Work
```

该服务是唯一 Spring 数据库事务边界；HarnessStore、Chat/Canvas repository 和 Storage repository 必须使用同一个 DataSource 和 physical transaction，禁止 `REQUIRES_NEW`。Upload validation/consume 是 `acceptCommands` 内 replay lookup 之后、Command insert 之前的事务步骤。

Harness Runtime 不依赖 Chat、Canvas、Storage 或 Spring 类型。应用层提供的 transaction-aware `acceptancePreflight` 只接收已验证的 Session 和 ordered Commands：NEW_SESSION 时在 Session 插入后写 owner relation，所有新请求都在其中物化 upload；精确 replay 不调用 preflight。

内部 Task/one-shot 直接调用同一个 Runtime 原语，并使用同一 HarnessStore/Storage 事务，不经过用户 owner DTO。

### 11.2 Runtime facade

`HarnessRuntime` facade 提供固定操作集合：

```text
acceptCommands
listThreadsBySession
getSessionEntries
getThreadSnapshot
stop
decideToolApproval
setThreadYolo
findThreadCommand
```

Session/Thread 只由 `acceptCommands` materialization 创建（第一批 Command 被原子接受时）；head 仅由 Runtime 沿 descendant 推进（turn/compaction/stop 执行中）。

手动压缩与 system-prompt 预览不在 `HarnessRuntime` facade：`compactThread` / `manualCompactionAvailability` 由 `ThreadProcessor` 提供，`getSystemPromptPreview` 由 Core 的 `SystemPromptPreviewService` 提供；Thread 删除走 `HarnessStore.Transaction`（由 Core `HarnessSessionDeletionService` 编排），Runtime facade 不暴露 delete。

### 11.3 用户写 API

Chat 与 Canvas 共用：

```text
POST /api/ai/runtime/command-batches
```

请求：

```text
owner:
  type: CHAT | CANVAS
  id

target:
  NEW_SESSION:
    sessionId
    threadId
    rootSettings
    yoloEnabled

  ENTRY:
    sessionId
    startEntryId
    threadId
    yoloEnabled

  THREAD:
    threadId
    expectedHeadEntryId
    expectedNextCommandSequence

commands[]
```

服务端在返回任何 replay、Session、Thread、Entry 或 Command 数据前验证 owner 对目标 Session/Thread 的归属。内部 Task/one-shot 不走 owner HTTP DTO，直接调用 Runtime。

用户 HTTP 只接受 SET_* + 末尾 `USER_MESSAGE` 的产品输入 batch；`CUSTOM_MESSAGE` 和 SYSTEM steering 只开放给受信任的内部 Java 调用，不通过 Chat/Canvas HTTP 控制面暴露。

响应返回权威：

```text
session
rootEntry
thread
acceptedCommands
replayed
```

`session`、`rootEntry` 只在 NEW_SESSION 或 materialization replay 时返回。

`thread` 是响应时的当前权威 projection；`acceptedCommands` 保留 immutable acceptance identity，但其 APPLIED/CANCELLED terminal marker 可以已经变化。`replayed=true` 表示未重复产生接受副作用，不承诺 mutable DTO 与第一次 HTTP 响应逐字段相同。

其他 API：

```text
GET  /api/ai/runtime/threads/{threadId}/snapshot
GET  /api/ai/runtime/threads/{threadId}/system-prompt
POST /api/ai/runtime/threads/{threadId}/compact
PUT  /api/ai/runtime/threads/{threadId}/yolo
POST /api/ai/runtime/threads/{threadId}/stop
POST /api/ai/runtime/threads/{threadId}/tool-invocations/{id}/approval
```

`system-prompt` 返回按当前 root-to-head branch 最新 Agent / Environment / skills / subagents 现算的只读预览（不冻结 ModelInvocation、不校验 Tool catalog）；`compact` 请求体携带 `expectedVersion`（exact 十进制 version cursor），提交成功后返回权威 Thread 投影、`turnStartEntryId` 与可选 `modelInvocationId`。snapshot 同时携带 `manualCompaction.available / disabledReason` 瞬时 availability sidecar；提交仍以 expectedVersion CAS 守护。

## 12. 持久化模型

### 12.1 Session、Entry、Thread

```sql
create table harness_session (
    id uuid primary key,
    created_at timestamptz(3) not null
);

create table harness_entry (
    id uuid primary key,
    session_id uuid not null,
    parent_entry_id uuid,
    entry_type varchar(32) not null,
    payload jsonb not null check (jsonb_typeof(payload) = 'object'),
    created_at timestamptz(3) not null,
    constraint uk_harness_entry_session_id unique (session_id, id),
    constraint fk_harness_entry_session foreign key (session_id)
        references harness_session(id),
    constraint fk_harness_entry_parent foreign key (session_id, parent_entry_id)
        references harness_entry(session_id, id),
    constraint ck_harness_entry_type check (
        entry_type in (
            'ROOT',
            'TURN_START',
            'MESSAGE',
            'CUSTOM',
            'MODEL_ATTEMPT_FAILURE',
            'CUSTOM_MESSAGE',
            'ASSISTANT_ERROR',
            'ASSISTANT_ABORTED',
            'COMPACTION',
            'TURN_END'
        )
    ),
    constraint ck_harness_entry_parent_shape check (
        (entry_type = 'ROOT' and parent_entry_id is null)
        or (entry_type <> 'ROOT' and parent_entry_id is not null)
    ),
    constraint ck_harness_entry_parent_not_self check (
        parent_entry_id is null or parent_entry_id <> id
    )
);

create unique index uk_harness_entry_single_root
    on harness_entry(session_id)
    where entry_type = 'ROOT';

create index idx_harness_entry_parent
    on harness_entry(session_id, parent_entry_id);

create table harness_thread (
    id uuid primary key,
    session_id uuid not null,
    head_entry_id uuid not null,
    materialization_hash char(64) not null,
    yolo_enabled boolean not null,
    next_command_sequence bigint not null check (next_command_sequence >= 1),
    version bigint not null check (version >= 0),
    created_at timestamptz(3) not null,
    updated_at timestamptz(3) not null,
    constraint fk_harness_thread_session foreign key (session_id)
        references harness_session(id),
    constraint fk_harness_thread_head foreign key (session_id, head_entry_id)
        references harness_entry(session_id, id),
    constraint ck_harness_thread_materialization_hash check (
        materialization_hash ~ '^[0-9a-f]{64}$'
    ),
    constraint ck_harness_thread_time_order check (updated_at >= created_at)
);

create index idx_harness_thread_session
    on harness_thread(session_id, created_at, id);
```

### 12.2 Command

```sql
create table harness_thread_command (
    thread_id uuid not null,
    sequence bigint not null check (sequence > 0),
    command_type varchar(32) not null,
    payload jsonb not null check (jsonb_typeof(payload) = 'object'),
    client_command_id uuid not null,
    request_hash char(64) not null,
    consumed_turn_start_entry_id uuid,
    cancel_request_id uuid,
    cancelled_at timestamptz(3),
    created_at timestamptz(3) not null,
    primary key (thread_id, sequence),
    constraint fk_harness_thread_command_thread foreign key (thread_id)
        references harness_thread(id),
    constraint fk_harness_thread_command_consumed foreign key (consumed_turn_start_entry_id)
        references harness_entry(id),
    constraint uk_harness_thread_command_client
        unique (thread_id, client_command_id),
    constraint ck_harness_thread_command_type check (
        command_type in (
            'USER_MESSAGE',
            'CUSTOM_MESSAGE',
            'SET_AGENT',
            'SET_MODEL',
            'SET_ACTIVE_TOOLS',
            'SET_ENVIRONMENT'
        )
    ),
    constraint ck_harness_thread_command_request_hash check (
        request_hash ~ '^[0-9a-f]{64}$'
    ),
    constraint ck_harness_thread_command_terminal check (
        consumed_turn_start_entry_id is null
        or (cancel_request_id is null and cancelled_at is null)
    ),
    constraint ck_harness_thread_command_cancel_pair check (
        (cancel_request_id is null) = (cancelled_at is null)
    ),
    constraint ck_harness_thread_command_cancel_time check (
        cancelled_at is null or cancelled_at >= created_at
    )
);

create index idx_harness_thread_command_queued
    on harness_thread_command(thread_id, sequence)
    where consumed_turn_start_entry_id is null
      and cancelled_at is null;

create index idx_harness_thread_command_cancel_request
    on harness_thread_command(thread_id, cancel_request_id, sequence)
    where cancel_request_id is not null;
```

不增加 StopReceipt 或 durable Batch 表。

`consumed_turn_start_entry_id` 的最小 FK 只能保证 Entry 存在；HarnessStore 在写入时必须额外验证：

```text
entry.type == TURN_START
entry.sessionId == command.thread.sessionId
TURN_START.ownerThreadId == command.threadId
```

该不变量由 Store integration/structure test 固化，不为此向 Command 重复增加 `session_id` 或引入数据库 trigger。

### 12.3 Owner relation

```sql
create table chat_session (
    session_id uuid primary key,
    chat_id uuid not null,
    created_at timestamptz(3) not null,
    foreign key (chat_id) references chat(id) on delete restrict,
    foreign key (session_id) references harness_session(id) on delete restrict
);

create index idx_chat_session_chat
    on chat_session(chat_id, session_id);

create table canvas_session (
    session_id uuid primary key,
    canvas_id uuid not null,
    created_at timestamptz(3) not null,
    foreign key (canvas_id) references canvas_document(id) on delete restrict,
    foreign key (session_id) references harness_session(id) on delete restrict
);

create index idx_canvas_session_canvas
    on canvas_session(canvas_id, session_id);
```

删除 `chat_thread` 和 `canvas_document.thread_id`。

### 12.4 Blob relation

```sql
create table harness_session_blob_ref (
    session_id uuid not null,
    blob_id uuid not null,
    primary key (session_id, blob_id),
    foreign key (session_id) references harness_session(id),
    foreign key (blob_id) references storage_blob(id)
);

create index idx_harness_session_blob_ref_blob
    on harness_session_blob_ref(blob_id, session_id);
```

### 12.5 Canvas 辅助表

Canvas command dedup 只保存判断精确重放所需的 identity：

```sql
create table canvas_command_dedup (
    canvas_id uuid not null,
    command_id uuid not null,
    request_hash char(64) not null,
    primary key (canvas_id, command_id),
    constraint ck_canvas_command_dedup_request_hash check (
        request_hash ~ '^[0-9a-f]{64}$'
    ),
    constraint fk_canvas_command_dedup_canvas foreign key (canvas_id)
        references canvas_document(id) on delete restrict
);
```

表字段固定为 `canvas_id / command_id / request_hash`。精确 replay 保证 command identity 和副作用不重复，响应返回当前 Canvas projection。

Function Run 对 Resource 的生命周期保护统一命名为 pin：

```sql
create table canvas_function_resource_pin (
    canvas_id uuid not null,
    node_id uuid not null,
    request_id uuid not null,
    role varchar(16) not null,
    resource_id uuid not null,
    primary key (canvas_id, node_id, request_id, role, resource_id),
    constraint ck_canvas_function_resource_pin_role check (
        role in ('INPUT', 'OUTPUT')
    ),
    constraint fk_canvas_function_resource_pin_node
        foreign key (canvas_id, node_id)
        references canvas_node(canvas_id, id) on delete restrict
);

create index idx_canvas_function_resource_pin_resource
    on canvas_function_resource_pin(canvas_id, resource_id);
```

`INPUT` / `OUTPUT` pin 只保护 Canvas Resource 生命周期，不参与 Blob refcount；`resource_id` 不增加 FK，因为 OUTPUT 可以是尚未物化的预分配 Resource id。

### 12.6 V1 同步约束

最终 V1、Runtime schema mirror、seed 与 schema tests 必须同时满足：

```text
canvas_document 不含 thread_id、uk_canvas_document_thread 或对应 FK/comment
chat_thread 不存在
canvas_command_dedup 恰含 canvas_id、command_id、request_hash
canvas_function_resource_ref 不存在
canvas_function_resource_pin 及 idx_canvas_function_resource_pin_resource 存在
harness_thread 含 session_id、materialization_hash 与同 Session head FK
harness_thread_command 含 cancel_request_id 与 cancel receipt index
chat_session、canvas_session、harness_session_blob_ref 使用本节定义的结构
```

### 12.7 后端边界映射

- `ThreadState` / `HarnessThreadDTO` 的 `sessionId` 必填；`materializationHash` 只属于领域/Store。PostgreSQL Thread 读写显式携带 `session_id`，head 由同 Session FK 约束。
- `HarnessRuntime.acceptCommands(target, acceptancePreflight)` 是唯一命令接受原语；Session/Thread materialization 是该原语接受首批 Command 的事务结果。深删除由 Core `HarnessSessionDeletionService` 经 `HarnessStore.Transaction` 按 Work/Invocation/Command/Thread/Entry/Session 顺序编排。
- complete Compaction checkpoint 可沿 EntryPath 共享；incomplete HISTORY 与 normal continuation obligation 都要求 `TURN_START.ownerThreadId == current Thread`。`CompactionPayload` 只含 `summaryText`，其余冻结事实位于 `TURN_START.compaction`。
- Chat 与 Canvas 都通过 `StudioCommandAcceptanceService` 绑定 Session ownership；`ChatServiceImpl.deleteChat` 按 `chat_session` 深删 Sessions，Canvas 按 `canvas_session` 深删 Sessions。Canvas document/DTO 不持有 Thread id。
- 产品写入口为 `POST /api/ai/runtime/command-batches`；Session 查询为 owner-scoped sessions、`GET /api/ai/runtime/sessions/{sessionId}/threads` 与 `/entries`；Thread head 只由 Runtime 沿当前分支推进。
- Command acceptance DTO 使用 NEW_SESSION / ENTRY / THREAD target；Stop DTO 返回 `status/thread/stoppedTurnEndEntryId/cancelledCommandCount/cancelledUserMessages(sequence,clientCommandId,messageJson)`，无独立 `replayed` 字段。
- `CanvasCommandDedupDO` / Mapper 只处理 `canvasId/commandId/requestHash`；Function Run 的 Resource 生命周期边使用 `CanvasFunctionResourcePin` 命名。

其余 ModelInvocation、ToolInvocation、Work、Canvas 和 Storage 表继续保持各自职责，不向 Thread 或 Session 复制状态。

## 13. 并发与锁

全局锁序：

```text
Chat/Canvas owner scope FOR KEY SHARE（正常请求）
or FOR UPDATE（归属/删除）
-> Session FOR KEY SHARE（正常写入 Session-scoped FK facts）
   or Session FOR UPDATE（归属/删除）
-> Thread（多行时按 UUID）
-> Command / Invocation / Work
```

普通执行不更新 Session。任何会 append Entry、写 SessionBlobRef 或触碰其他 Session-scoped FK 的事务，先通过 Thread 的 immutable `sessionId` 对 Session 取 `FOR KEY SHARE`，再锁 Thread；不同 Thread 的 KEY SHARE 彼此兼容。纯 Thread-scoped 且不触碰 Session FK 的操作可以只锁 Thread。

关键并发语义：

1. Command enqueue 与 Stop 都锁 Thread，因此形成确定全序。
2. 通过 version fence 的 Stop 取消其获得 Thread lock 时存在的全部 queued Command；确定性 stale Stop 不产生任何取消。
3. 外部 Model/Tool 调用不持有数据库锁。
4. 每次应用迟到结果前重新锁 Thread，并验证 Invocation/Turn 仍由当前 Thread live ownership 持有。
5. 两个 Thread 可从同一个 parent Entry 并行 append 不同 child。
6. 新 Thread 不读取或继承 sibling Invocation。
7. compaction 不能跨越 foreign open-turn ownership barrier。
8. Work producer 与 drain completion 都在 Thread 锁内判断 runnable state，防止 lost wakeup。
9. version 是对外 projection/CAS fence；每次对外可见 Thread 变化恰好 `+1`。

## 14. 生命周期

### 14.1 删除 Thread

```text
lock Thread
-> delete Thread/Model/Tool Work
-> delete ToolInvocation
-> delete ModelInvocation
-> delete ThreadCommand
-> delete Thread
```

不删除 Session、Entry、SessionBlobRef 或 sibling Thread。删除后仍可从保留 Entry 创建 `ENTRY_DRAFT`，在下一次发送时 materialize 新 Thread。

### 14.2 删除 Session

```text
lock owner relation and Session
-> lock Threads by UUID
-> delete every Thread scoped fact
-> release every SessionBlobRef
-> delete Entries
-> delete owner relation
-> delete Session
```

### 14.3 删除 Chat/Canvas

```text
list owned Sessions by UUID
-> deep delete each Session
-> delete Chat/Canvas scoped facts
-> delete owner
```

Canvas Resource Blob 和 Session Blob 都通过显式 retain/release 管理，不使用级联删除绕过 refcount。

## 15. Subagent 与 one-shot

Task 和 one-shot 使用同一 materialization 事务：

```text
Session + ROOT + Thread + initial prompt Commands + Work
```

Task Session：

- 不写 Chat/Canvas owner relation；
- 由 TaskService 保持单一 primary Thread；
- `session_id` 与 `<task id="...">` 编码真实 Session UUID；
- realtime/result 同时携带 `sessionId` 与 `threadId`；
- SYSTEM soft steering 使用只含 SYSTEM CUSTOM_MESSAGE 的 steering batch；
- resume 时 Agent/Model/Tools 变化与下一条 prompt 放入同一用户输入 batch。

## 16. 代码形态

```text
AcceptCommandsTarget 三态
ThreadCommand 六类型
ownerThreadId
historical normalization
compaction ownership barrier
complete Compaction checkpoint 可共享、incomplete continuation owner-only
durable Compaction Turn + ModelInvocation + Work
version CAS
SessionBlobRef + Blob refcount
精简 canvas_command_dedup
canvas_function_resource_pin
一个 AgentPane command registry
一个 Stop recovery/editor transaction
一个周期 Storage recovery
```

## 17. 数据库引导

```text
core/src/main/resources/db/migration/V1__schema.sql
harness/runtime-spring/.../harness-runtime-schema.sql
dev/e2e/test seed
PostgreSQL schema structure tests
```

`V1__schema.sql` 与 runtime schema mirror 定义唯一 schema 基线，profile seeds 只写环境数据；缺失事实使用 NULL。部署不维护增量 schema migration chain 或并行写路径。

## 18. 自动化验收

### 18.1 Materialization

- [ ] `/new` 和 `/tree` 各自产生零数据库写入。
- [ ] NEW_SESSION 首发原子创建 Session、ROOT、owner relation、Thread、Commands 和 Work。
- [ ] ENTRY 首发原子创建 Thread、Commands 和 Work，不复制 Entry。
- [ ] 任一步骤失败都不留下 orphan Session、empty Thread、half-consumed upload。
- [ ] 两个 EntryDraft 从同一 Entry 并发发送，创建两个共享历史的 Thread。
- [ ] 相同 materialization request 返回相同接受 identity 和当前 projection。
- [ ] 相同 Thread id、不同 materialization hash 返回稳定 409。
- [ ] response 丢失且 upload 已消费后，重试仍命中 replay。
- [ ] requestHash/materializationHash 由服务端 canonical semantic request 计算。
- [ ] exact command-set replay 拒绝 partial、缺失、重排、非连续和跨 batch 拼接。

### 18.2 Command 与 Agent Loop

- [ ] 产品输入 batch 恰有一个末尾 user-like message。
- [ ] SET_* 不能作为产品 settings-only batch 入队。
- [ ] 已有 Thread 的用户输入 batch 拒绝 SYSTEM CUSTOM_MESSAGE。
- [ ] steering batch 恰有一条 SYSTEM CUSTOM_MESSAGE。
- [ ] ThreadProcessor 每次 INPUT 只消费到第一条 user-like message。
- [ ] 两条 queued 用户消息形成两个独立 INPUT Turn。
- [ ] leading steering 在下一个 eligible INPUT/CONTINUATION 消费，不越过 queued 用户输入。
- [ ] SET_* 不在 continuation 中途生效。
- [ ] TURN_START 冻结完整 BranchSettings，设置命令不产生独立 Entry。
- [ ] queued SET_ENVIRONMENT 可在 live Turn 期间接受，并只影响下一次 INPUT。

### 18.3 Shared history

- [ ] 任意 committed Entry 可成为 EntryDraft 起点。
- [ ] foreign open Turn 起点按现有规则 normalization。
- [ ] 新 Thread 不继承 sibling Model/Tool Invocation。
- [ ] `TURN_START.ownerThreadId` 始终等于实际执行 Thread。
- [ ] Stop 不关闭 foreign historical open Turn。
- [ ] foreign normal Turn usage 不触发新 Thread 的 compaction。
- [ ] complete Compaction summary 位于 sibling path 时可以共享。
- [ ] owner Thread 删除后 complete summary 仍可由 sibling 使用。
- [ ] foreign `TURN_END.continueModel=true` 不产生 CONTINUATION_DUE。
- [ ] owned `TURN_END.continueModel=true` 继续正常执行。
- [ ] complete OVERFLOW summary 可共享，但 sibling 不继承 overflow retry。
- [ ] foreign incomplete HISTORY 不启动 TURN_PREFIX。
- [ ] owned incomplete HISTORY 机械启动冻结的 TURN_PREFIX。
- [ ] 数据库拒绝 Thread 指向其他 Session 的 Entry。
- [ ] 删除原 owner Thread 后，Entry 中的 durable ownerThreadId 仍足以完成 normalization。
- [ ] 不存在任意 head relocation API。

### 18.4 Compaction

- [ ] threshold 使用当前 Thread owned successful usage，且优先于 queued input。
- [ ] Compaction 消费零 Command，queued input 在完成后继续处理。
- [ ] FULL 产生 complete payload，HISTORY 产生 incomplete payload，TURN_PREFIX 产生合并后的 complete payload。
- [ ] complete 只由 phase 派生，payload 不存 redundant boolean。
- [ ] HISTORY → TURN_PREFIX 复用冻结 cut/prefix anchors，不重新选 cut。
- [ ] TURN_PREFIX 最终 summary 自包含紧邻匹配 HISTORY + prefix，绝不合并 stale partial。
- [ ] ToolResult 永不成为 cut point。
- [ ] `effectiveKeepRecentTokens = min(keepRecentTokens, floor(contextWindow / 2))`；`effectiveReserve = min(16384, maxOutputTokens)`。
- [ ] Compaction payload 只含 summaryText；anchor、trigger、executionModel 与 phase 冻结在 TURN_START.CompactionStart，complete 由 phase 与 TURN_END outcome 派生。
- [ ] repeated compaction 从 latest complete cutEntryId 开始。
- [ ] latest complete summary + cut 后 suffix 是唯一 Provider Context 投影。
- [ ] internal Compaction Turn、失败 attempt/error 不进入 transcript 或 Provider Context。
- [ ] failed/stopped Compaction 不 self-wake；owned incomplete HISTORY 恰好 wake 一次 TURN_PREFIX。
- [ ] Stop 在 owned HISTORY phase gap 写 stopped Compaction barrier，partial 不会在后续输入时复活。
- [ ] OVERFLOW compact-and-continuation 最多一次。
- [ ] Stop Compaction 后迟到 Model 结果不能 append。
- [ ] file sections 由 Runtime 重算，malformed reserved tags fail closed。
- [ ] complete file sections 每次从 ROOT 到 cut 累计重算。
- [ ] MANUAL `/compact`：availability 门控与 expectedVersion CAS 缺一不可，THREAD_BUSY / OWNERSHIP_BARRIER / NO_RESOLVED_CONTEXT / MODEL_CHANGED / BELOW_MINIMUM / NOTHING_TO_COMPACT disabledReason 确定性返回。
- [ ] 不存在 summary replacement hook、branch summary 或 copied retainedTail。

### 18.5 Stop

- [ ] 通过 version fence 的 Stop 处理当前 live Turn 和加锁时全部 queued Commands。
- [ ] deterministic stale 后沿用 stopRequestId、刷新 version 重试。
- [ ] cancelled user messages 严格按 sequence 返回。
- [ ] 返回 durable Resource，不返回已消费 upload handle。
- [ ] queued-only Stop 可通过 command cancelRequestId 精确 replay。
- [ ] live Stop 可通过 TURN_END 与 command receipt 精确 replay。
- [ ] replay receipt 字段稳定，Thread 返回当前 projection。
- [ ] 相同 raw stopRequestId 在两个 Thread 独立工作。
- [ ] no-op Stop 不写 marker、不增加 version。
- [ ] Stop 后到达的 Model/Tool 结果不能 append 或 revive Turn。
- [ ] 前端恢复保留当前草稿、caret、selection、resource order 和可见滚动位置。
- [ ] 多条消息合并后使用当前 BranchDraft，不伪造逐消息 settings 恢复。
- [ ] 同一 stopRequestId 的 replay 不重复 prepend。

### 18.6 Upload

- [ ] consume、DELETE、GC 并发时只释放一次 Blob reference。
- [ ] 周期 GC 在无新上传、无重启时仍清除过期 PENDING/READY upload。
- [ ] PENDING temp object 在删除最后 durable locator 前完成幂等删除。
- [ ] temp object 删除成功后数据库回滚仍可在重试中收敛。
- [ ] durable Blob 通过 DELETING 状态在 crash 后继续 sweep。
- [ ] 启动恢复排空积压和 DELETING blob。
- [ ] submission rollback 后 upload 与 Blob retain 完整回滚。
- [ ] 已消费 upload 过期不影响 Stop Composer recovery。

### 18.7 Frontend 与 API

- [ ] PaneTarget 永远只有三态。
- [ ] unknown acceptance 只使用 PendingAcceptance sidecar。
- [ ] 每个 Pane 同时最多一个 PendingAcceptance，未决期间禁止第二次 durable send。
- [ ] 放弃请求的迟到成功不修改 active PaneTarget。
- [ ] definite failure 把 frozen draft 前置合并到新草稿，不覆盖用户后续输入。
- [ ] Chat 与 Canvas 对三种 target 使用同一命令矩阵测试。
- [ ] `/thread` 严格执行 Session → Thread 两级搜索。
- [ ] 零 Thread Session 可通过 Tree 进入 EntryDraft。
- [ ] `/tree` 选择不创建 Thread。
- [ ] Canvas `/new` 不修改 Canvas Graph。
- [ ] `/debug` 完整可用（Conversation/Debug 互斥主视图）。
- [ ] 切换 Pane 不停止后台 Thread，非活动 Thread 的迟到 realtime 不修改 active target。

### 18.8 结构与覆盖率

- [ ] schema 只承载当前 Entity 必需字段：Session/Thread 无 title/status 冗余列，settings 只存在于 BranchSettings。
- [ ] owner relation 只建立 `session_id` 主键和 owner listing index。
- [ ] canvas_command_dedup 只包含 canvasId、commandId 和 requestHash。
- [ ] 资源引用统一使用 canvas_function_resource_pin。
- [ ] nullable terminal marker 使用 NULL 和 partial index，不使用 sentinel。
- [ ] Store 拒绝把 Command 标记为由错误类型、错误 Session 或错误 ownerThreadId 的 Entry 消费。
- [ ] Session delete 与 acceptance/Entry append 并发测试无锁环。
- [ ] Chat/Canvas runtime command implementation 只有一份。
- [ ] materialization、Stop、historical normalization 和 upload ownership 核心路径行覆盖率达到 90% 以上。
