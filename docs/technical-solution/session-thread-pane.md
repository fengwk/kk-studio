# Harness Session、Thread 与 Pane

本文定义 kk-studio Harness Runtime 当前生效的 Session Tree、Thread materialization、Command mailbox、PaneTarget 三态、SYSTEM steering、Stop 恢复、upload/blob 生命周期与前端模块边界。表结构见 [storage-models.md](storage-models.md)（唯一 V1），执行协议见 [harness-runtime-architecture.md](harness-runtime-architecture.md)，Agent Loop 细节见 [harness-agent-loop.md](harness-agent-loop.md)。

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
9. PostgreSQL 是唯一 durable truth；Work 只负责调度，realtime notification 只负责加速和展示，恢复依赖 snapshot 与 poll。

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

NEW_SESSION 和 ENTRY 的第一批必须是用户输入 batch；THREAD 同时允许用户输入 batch 和内部 SYSTEM steering batch。

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

设置命令本身不 append Entry。它们由 reducer 折叠到 `TURN_START.settings` 完整快照：

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
-> establish session_blob_ref
-> idempotently delete upload-scoped temp object
-> delete storage_upload
-> transfer or release upload-owned Blob reference
```

若 Session 尚未引用 Blob，upload 的一份 retain 直接转移给 SessionBlobRef；若 Session 已引用该 Blob，删除 upload 后 release 多余 retain。

Command payload 和 Stop 恢复结果只保存 durable Resource，不再依赖 upload handle。

### 8.3 回收

回收只保留两个入口：

```text
client best-effort DELETE（pill 删除、Draft 明确丢弃、owner 销毁）
Storage Maintenance（startup wake + coalesced wake + fixed-delay poll）
```

服务端周期任务是权威回收路径：

```text
fixed delay
-> expire one SKIP LOCKED upload batch
-> sweep one DELETING blob batch
```

startup wake 排空历史积压；日常 reserve/complete API 不再承担机会式 GC，避免上传请求耦合清理延迟。

并发规则：

- consume 在外层事务锁定未过期且未 claim 的 READY 行；
- client DELETE 与 GC 先以短事务写成对 `cleanup_token/cleanup_until`，随后释放行锁并在事务外删除对象；
- complete/consume 对已过期或已 claim 行 fail closed，不能抢回 cleanup 所有权；
- finalize/release 以 cleanup token fence，lease 过期后任意实例可通过 `FOR UPDATE SKIP LOCKED` 重新 claim。

物理对象删除分两类：

```text
upload-scoped temp object
  唯一 durable locator 是 storage_upload.id
  -> 必须先幂等删除 temp object，再删除 upload 行
  -> 对象删除失败则保留行与 cleanup lease，lease 过期后重试
  -> 对象删除成功但数据库回滚时，后续重试仍可幂等收敛

durable Blob object
  storage_blob(DELETING) 是 durable locator
  -> 数据库先提交 DELETING
  -> afterCommit 只本地 wake；Storage Maintenance 删除对象和 Blob 行
```

因此进程崩溃不会在丢失唯一数据库 locator 后留下永久 PENDING 临时对象。

## 9. Chat、Canvas、Session 与 Thread 查询

### 9.1 归属

使用两张有真实外键的关联表：

```text
chat_session(chatId, sessionId, createdAt)
canvas_session(canvasId, sessionId, createdAt)
```

不使用失去数据库外键能力的多态 owner 表。创建归属时锁 Session，检查两张表后写入其中一张，保证用户 Session 只属于一个 owner。

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
HarnessCommandAcceptanceOrchestrator
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

手动压缩与 system-prompt 预览不在 `HarnessRuntime` facade：`compactThread` / `manualCompactionAvailability` 由 `ThreadProcessor` 提供，`getSystemPromptPreview` 由 Platform 的 `SystemPromptPreviewService` 提供；Thread 删除走 `HarnessStore.Transaction`（由 Platform `SessionDeletionOrchestrator` 编排），Runtime facade 不暴露 delete。

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

## 12. 并发与锁

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

## 13. 生命周期

### 13.1 删除 Thread

```text
lock Thread
-> delete Thread/Model/Tool Work
-> delete ToolInvocation
-> delete ModelInvocation
-> delete ThreadCommand
-> delete Thread
```

不删除 Session、Entry、SessionBlobRef 或 sibling Thread。删除后仍可从保留 Entry 创建 `ENTRY_DRAFT`，在下一次发送时 materialize 新 Thread。

### 13.2 删除 Session

```text
lock owner relation and Session
-> lock Threads by UUID
-> for each Thread, lock Command -> Model -> Tool -> Work and atomically delete scoped facts
-> release every SessionBlobRef
-> delete Entries
-> delete owner relation
-> delete Session
```

### 13.3 删除 Chat/Canvas

```text
list owned Sessions by UUID
-> deep delete each Session
-> delete Chat/Canvas scoped facts
-> delete owner
```

Canvas Resource Blob 和 Session Blob 都通过显式 retain/release 管理，不使用级联删除绕过 refcount。

## 14. Subagent 与 one-shot

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

## 15. 代码形态

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
