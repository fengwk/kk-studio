# Harness Runtime 架构

本文是当前 Harness 执行架构事实源，只描述现行模块、durable 事实、写者边界、Agent Loop 与并发协议。

## 1. Runtime 目标与边界

- PostgreSQL 保存唯一 durable truth；`harness_work` 保存唯一调度 mailbox。
- Thread 的 Entry/head 推进串行；Model、Tool 通过各自 Invocation + Work 继续执行。
- Processor 每次处理都是短事务，不在事务内等待外部 I/O（Provider/Tool 执行在事务外）。
- `harness-runtime` 是纯 Java 模块：不依赖 Spring、数据库、Redis、HTTP、WebSocket、Provider SDK 或业务 Tool 实现。
- `harness-runtime-spring` 只做持久化与调度适配：`HarnessStore`（PostgreSQL）、Work dispatcher（claim/NOTIFY/poll）、Redis overlay、本地 Resource store。
- `core` 只提供 Catalog/TurnResolver/ModelGateway/ToolGateway/Environment gateway 适配，不写 `harness_*` 表。

## 2. 模块

```text
harness/
├── tool/                # Tool API、descriptor、ResourceRef、RemoteTool、Daemon v3 wire
├── runtime/             # 纯 Java：Session/Entry/Thread/Command/Invocation/Work/processor
├── plugin/              # 纯 Java trusted build-time 插件 API：Catalog/BranchView/Tool/intents/projector
├── runtime-spring/      # Store/Work/Redis/Resource 适配（PostgreSQL、dispatcher）
└── daemon/              # 独立 Environment 进程，只依赖 tool
```

依赖方向：

```text
web composition root -> core application API / share DTO
web composition root -> harness-runtime-spring -> harness-runtime -> harness-tool
web composition root -> harness-runtime
core -> harness-plugin -> harness-runtime -> harness-tool
core -> harness-runtime -> harness-tool
core -> harness-tool
harness-daemon -> harness-tool
```

## 3. Durable 事实

### Session 与 Entry

Session 只组织一棵 append-only Entry Tree。Entry 类型固定为十种：

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

- `ROOT` 是每个 Session 的唯一无 parent 根，payload 携带初始完整 `BranchSettings`；子 Agent Session 的 ROOT 额外携带可选 `SubagentContext{parentThreadId, rootThreadId, taskInvocationId, depth}` 冻结委派归属（普通用户 Session 为 null）。
- `TURN_START` 打开一次 Model response turn，payload 携带该 turn 的完整 `BranchSettings` 快照与 `TurnStartReason`（`INPUT` / `CONTINUATION` / `COMPACTION`）。
- `MESSAGE` 是对话消息；`USER` / `ASSISTANT` / `TOOL` 语义由 payload 子类型决定（Tool 结果消息带 ToolResult 元数据）。
- `CUSTOM` 是业务插件追加的透明 branch state 节点：允许 ROOT 后任意位置（含 open/closed turn），不参与 turn grammar，provider 消息投影默认忽略；payload 为 `(pluginId, customType, schemaVersion, data)`。Goal 插件用 `goal/state@1` 保存完整替换快照，读取时只取当前 branch 最近一条。
- `MODEL_ATTEMPT_FAILURE` 是普通 Model 自动 retry 的透明审计节点：payload 为 `{attempt:{attempt,sequence,text,thinking}, error:{code,message}, retryAt}`，`Entry.createdAt` 即 `failedAt`。它只允许位于非 compaction open Turn 的唯一 Assistant 结果之前，attempt 必须是连续 `1..N`，不属于对话语义且不投影到 Provider Context。
- `CUSTOM_MESSAGE` 是业务扩展注入的对话消息：冻结 `AgentMessage`（SYSTEM/USER）保持 model-visible，`details` 绝不投影；非插件命令消息使用稳定 core 元数据（`pluginId=core`、`customType=message`、`rendererKey=message`、`details={}`）。
- `ASSISTANT_ERROR` 是 Provider/Assistant-side 失败审计：payload 把 stable `error{code,message}` 与可空 `attempt{attempt,sequence,text,thinking}` 分离；planning/Stop/尚未确认 Provider start 的 barrier 使用 null，已确认 attempt 的 terminal Model 错误即使没有 partial 也保留该 attempt snapshot（text/thinking 可同时为空）。它不投影到 Provider Context。
- `ASSISTANT_ABORTED` 是用户主动 Stop 的 assistant turn：只保存安全 text/thinking，绝不包含 tool call。
- `COMPACTION` 是内部压缩 Model 的 durable summary：冻结 phase/trigger、`tokensBefore`、complete 标记与 `firstKeptEntryId`/`cutEntryId`/`turnPrefixStartEntryId`。它只能作为 `TURN_START(COMPACTION)` 的唯一成功结果；HISTORY 为 incomplete，FULL/TURN_PREFIX 为 complete。
- `TURN_END` 关闭一次 turn，payload 携带 `TurnEndOutcome`（`COMPLETED` / `FAILED` / `STOPPED` / `CANCELLED`）与 `TurnEndReason`（`USER_STOP` / `HISTORY_CUT` / `CANCELLED` / `TURN_FAILED`），以及 continuation obligation。

`BranchSettings` 是完整不可变设置快照：

```java
public record BranchSettings(
    EnvironmentBinding environment, // 完整 binding{name, workspacePath}，null 表示未选择
    String agentName,
    ModelSelection model,          // providerName/modelName/variant
    List<String> activeTools) {}
```

YOLO 不在分支历史中：它是 Thread 运行时策略。

### Thread

`ThreadState` 的 durable 字段只有：

```text
id
headEntryId            # 始终非空
yoloEnabled            # Thread 当前运行时策略（每次 ModelInvocationRequest 再冻结一次）
nextCommandSequence    # 从 1 开始；每次命令 batch 预留后 +N
revision               # 非负；每次可见状态变化恰好 +1
createdAt / updatedAt
```

Session、Environment、status 与 branch settings 都由 head Entry 分支派生；Thread 行**不**保存 execution epoch、processor lease 或 runnable 标志。`validateTransition` 保证 identity 不变、`nextCommandSequence`/`revision`/`updatedAt` 不回退、可见变化 revision 恰好 +1；exact replay 恒被接受。

### ThreadCommand

有序 mailbox，只接受六类命令：

```text
USER_MESSAGE
CUSTOM_MESSAGE
SET_ENVIRONMENT
SET_AGENT
SET_MODEL
SET_ACTIVE_TOOLS
```

Thread 的 YOLO runtime policy 是直接控制面（`PUT /yolo` 修订 CAS），绝不经过 mailbox。

每行保存 `(thread_id, sequence)` 主键、UUID `client_command_id`（thread 内唯一幂等键）、
`request_hash`（raw 命令 canonical SHA-256）、`consumed_turn_start_entry_id` 与
`cancelled_at`。`USER_MESSAGE` 与 `CUSTOM_MESSAGE` 之外都是 settings diff 命令，被
TURN_START/CONTINUATION 消费但不产生 Message Entry。

### Invocation

| 事实 | durable 字段（要点） |
| --- | --- |
| `harness_model_invocation` | thread、`turn_start_entry_id`（唯一）、`basis_head_entry_id`、完整 frozen `request` JSON（route/provider/tools/skills/**subagentBindings**/YOLO/contextWindow 与可空 compaction metadata）、status、attempt、`stream_checkpoint`（attempt-local 单调 checkpoint）、`failed_attempts`（append-only TRANSIENT 失败前缀）、`result`/`error`/`result_entry_id`、时间 |
| `harness_tool_invocation` | `model_invocation_id`、`assistant_entry_id`、`ordinal`（(assistant_entry_id, ordinal) 唯一）、frozen `request`（binding + plugin provenance/access）、status、attempt、`approval` JSON、`result`/`effects`/`error`/`result_entry_id`、时间 |

冻结 `request` 中的 `ModelDescriptor` 只含 `providerName`/`modelName`/`inputModalities`/`tools`/`reasoning`/`pricing` 六个字段；Provider 连接事实与 cache capability 在每次 attempt 由 Core 按当前 `agent_provider` 行解析（见 [harness-capability-wiring.md](harness-capability-wiring.md)）。

状态机（Model）：

```text
READY -> DISPATCHING -> RUNNING -> SUCCEEDED
  ^                        |  \-> FAILED
  |                        |  \-> CANCELLED
  +--- TRANSIENT retry ----+  \-> UNKNOWN     （ownership 不确定时收敛，不重放副作用）
```

`DISPATCHING -> RUNNING` 才确认一次 Provider start 并把 attempt 恰好 +1；`RUNNING -> READY` retry 保持 attempt，清除当前 checkpoint，并追加恰好一个同 attempt 的 `ModelAttemptFailure`。`failedAttempts` 必须是按时间排序的连续 `1..N` 前缀且每项只允许 `TRANSIENT`：READY/DISPATCHING 时 `N=attempt`，RUNNING 时 `N=attempt-1`。没有历史条数上限。

状态机（Tool）：

```text
WAITING_APPROVAL -> READY -> DISPATCHING -> RUNNING -> SUCCEEDED
      \-> FAILED                                       \-> FAILED
      \-> CANCELLED                                    \-> CANCELLED
                                                       \-> UNKNOWN
```

terminal 事实约束：`result` 与 `error` 互斥；`result_entry_id` 在**各自表内**唯一（每张 Invocation 表各自的 partial unique index），terminal 且挂 result Entry 的 Invocation 不再被 apply。普通 Model 在挂结果前由 `ThreadProcessor`/Stop 按序追加全部 `MODEL_ATTEMPT_FAILURE`，再追加唯一 Assistant 结果；`attachResultEntry` 同时清空已物化的 checkpoint/failedAttempts。InMemory/PostgreSQL Store 的 `ModelAttemptMaterialization` 在 update 边界精确比对 EntryPath 中的 attempt/error/partial/时间与 terminal/Stop barrier；compaction invocation 不物化失败 attempt。Tool 的 `effects` 非 null：只有 `SUCCEEDED` 可非空，且 result/effects/status 在同一次 Store update 中原子持久化；terminal 后 effects 不可变。

### Work

`harness_work` 是唯一调度 mailbox：

```text
(target_type, target_id)   # THREAD / MODEL / TOOL
available_at               # 最早可 claim 时间
wake_version               # 每次 wake +1，fence 丢失的 wake
lease_token / lease_until  # claim 后独占；过期可被重新 claim
```

它不是事件日志、不是 job queue、不保存任何业务状态。claim 在 `available_at <= now` 且 lease 过期时授予；wake 用 `least(available_at, excluded)` + `wake_version+1` 合并。

## 4. ThreadContext 分类

`ThreadContextClassifier` 基于当前 head 路径与本 Thread 当前 open Turn 的 Invocation 纯分类（unlocked 读），每个 kind 只携带锁定/应用所需的最小事实：

```text
IdleOrHistorical        # 无 open Turn 且无需 continuation，或 open Turn 对 Thread 无 live Model/Tool
ContinuationDue         # head 为 continueModel=true 的 TURN_END，应立即启动 continuation
ModelActive             # open Turn 的 Model 非 terminal 且 head == basis：Work-only 挂起
ModelTerminalPending    # Model terminal 且 head == basis、结果未 apply：立即 apply
ToolActive              # open Turn 的 Tool siblings 非全部 terminal：Work-only 挂起
ToolTerminalPending     # siblings 全部 terminal 且全部未挂结果：按 ordinal 经唯一 appender 原子 apply
```

IDLE_OR_HISTORICAL / CONTINUATION_DUE 快照不暴露 Model、tools 或失败 attempts；ModelActive/ModelTerminalPending 暴露 Model 与尚未物化的 `modelAttemptFailures`；Tool 上下文暴露 Model + 全部 Tool siblings，但不重复暴露已经物化或非当前 Model context 的失败 attempts。分类器对破坏的不变量以 `IllegalStateException` 拒绝，绝不降级为业务 kind。

## 5. Agent Loop（ThreadProcessor）

ThreadProcessor 消费 dispatcher 已 claim 的 THREAD Work，每步短事务按分类执行固定优先级：

```text
1. MODEL_TERMINAL_PENDING  -> 原子 apply Model terminal：先按 1..N 追加 MODEL_ATTEMPT_FAILURE；
                              SUCCEEDED 再追加 ASSISTANT Entry 并挂 resultEntryId；
                              无 ToolCall 时追加 TURN_END(COMPLETED, continueModel=false)；
                              有 ToolCall 时按 response ordinal materialize ToolInvocation：通常为 READY；
                              plugin sibling WRITE 后再 READ/WRITE 则直接为 unattached FAILED(SIBLING_STATE_CONFLICT)；
                              仅 READY 等待 TOOL Work
                              FAILED/错误追加带可空 attempt snapshot 的 ASSISTANT_ERROR + FAILED TURN_END
2. TOOL_TERMINAL_PENDING   -> 按 ordinal 经唯一 ToolOutcomeAppender 原子 apply 全部 terminal Tool siblings：
                              SUCCEEDED 先按 effects 顺序追加 CUSTOM，再追加 Tool Result MESSAGE Entry，
                              并固定追加 TURN_END(COMPLETED, continueModel=true)，随后 classifier 进入
                              CONTINUATION_DUE 启动 continuation（不是普通结束）
3. MODEL_ACTIVE / TOOL_ACTIVE -> 完成 claim 返回 SUSPENDED（等 Model/Tool Work）
4. CONTINUATION_DUE        -> 启动 continuation：消费普通配置命令（SET_AGENT/MODEL/ACTIVE_TOOLS/YOLO），
                              保留 SET_ENVIRONMENT（留给后续 INPUT 完整收割进入 BranchSettings），不产生 Message Entry
5. IDLE/HISTORICAL 且压缩到期 -> 启动 COMPACTION Turn（消费零 Command，优先于 queued input）
6. 有 queued USER_MESSAGE/CUSTOM_MESSAGE -> 启动 INPUT Turn（先 normalization 旧 open Turn，再 TURN_START(INPUT) + Message）
7. 否则                    -> 完成 claim 返回 QUIESCENT
```

Turn 启动采用 speculative plan 两段式：

1. **短事务**：锁 Thread、读取 queued Command 快照与 cutoff、分配 candidate Entry ID、构造完整合法 candidate EntryPath（TURN_START + Message），**不写任何 durable 状态**；事务外调用 `TurnResolver`（期间 `WorkHeartbeat` 维持 lease）。
2. **第二短事务**：以 source head / YOLO / cutoff 内 Command 精确快照 / claim ownership 做 CAS，一次性原子提交 normalization + TURN_START + Message + Command markers + Thread 更新 + ModelInvocation/MODEL Work（resolved）或 AssistantError + FAILED TURN_END（rejected）。

Model terminal materialize Tool siblings 时，Runtime 按 ordinal 扫描 frozen plugin state accesses。对同一 `(pluginId, customType)`，`READ+READ` 与 `READ -> WRITE` 允许；已有 `WRITE` 后的 `READ` 或 `WRITE` 机械创建为 unattached `FAILED(kind=SIBLING_STATE_CONFLICT, attempt=0)`，不 dispatch、不请求 TOOL Work，也不把冲突调用登记为后续访问。不同 customType 或不同 pluginId 不冲突。

任何 CAS / claim 损失一律完整 no-op 返回 `LOST_OWNERSHIP`；Resolver 异常/null/heartbeat 失败按单一固定失败延迟（`resolveFailureDelay`）reschedule，绝不静默丢弃 Work。历史/非 applicable open Turn 只做 unlocked 读，绝不先锁 Model/Tool 再落 INPUT normalization。

### Durable compaction

压缩复用现有三 processor、MODEL Work、ModelInvocation retry/lease/Stop/UNKNOWN 语义，不增加表、processor 或 Work target：

```text
TURN_START(COMPACTION)
  -> ModelInvocation（SYSTEM summarization prompt + USER summary prompt，零 tool/skill/cache）
  -> COMPACTION
  -> TURN_END
```

- 默认 `reserveTokens=16384`、`maxRecentTokens=20000`；最近一次 complete compaction 之后，本 Thread 最新成功 Model usage 严格大于 `contextWindow-reserveTokens` 时 threshold 触发。后续普通 FAILED/CANCELLED/UNKNOWN 或无 ModelInvocation 的 Resolver Rejected turn 不抹掉该 usage；存在其它 Thread invocation 的 shared turn 是 ownership barrier。terminal `OVERFLOW` 失败可触发一次恢复。
- 有效 recent retention 为 `min(floor(contextWindow*0.5), maxRecentTokens)`；cut point 只允许 USER/ASSISTANT/CUSTOM_MESSAGE/AssistantAborted，绝不切在 ToolResult。`firstKeptEntryId` 向前包含相邻控制元数据，但不跨任何 CompactionPayload。
- split turn 先生成 incomplete HISTORY，再以完全冻结的 ids/trigger/tokens/contextWindow 机械生成 TURN_PREFIX；没有先前 history 的 direct TURN_PREFIX 使用固定文本 `No prior history.`。
- `TURN_START(COMPACTION)...TURN_END` 内全部对话事实对后续 planner、token estimate、Provider Context 与前端 transcript 不可见；停止压缩的 AssistantAborted 不成为未来 cut 或摘要内容。FAILED/STOPPED/CANCELLED/incomplete compaction 只阻止原地立即重试，出现新的普通 turn 后不再充当长期 freshness barrier。
- complete summary 在下一次正常请求中投影为一个 wrapped USER message，并从 `cutEntryId` 本身继续保留上下文。`tokensBefore` 估算 wrapper + retained suffix；文件 read/write/edit 清单从当前 branch 的完整 durable history 累计重算。`<read-files>` / `<modified-files>` 是 Runtime 保留 section：旧 summary 和模型响应中的同名 section 先剥离，最终只机械追加一份 canonical 清单，modified 覆盖 read。
- HISTORY 与 threshold 压缩固定 `continueModel=false`；只有 complete OVERFLOW 压缩为 true。它创建一次 immediate CONTINUATION 恢复；该 retry 再次 overflow 时保留失败并停止，不进入无界压缩循环。后续新的 input/tool continuation 可独立触发压缩。
- phase output budget 为 FULL/HISTORY `floor(0.8*reserveTokens)`、TURN_PREFIX `floor(0.5*reserveTokens)`，最终上限取该预算与有效 model/variant max output 的较小值。
- 所有 prompt 是 strict classpath resource；复制自 Pi 的资源在同目录保留 MIT `NOTICE`。

## 6. ModelProcessor

ModelProcessor 消费 MODEL Work：

1. claim 校验（fake/expired lease token → `LOST_OWNERSHIP` no-op，绝不 cancel 合法 active execution）；
2. 两阶段激活：`ModelGateway.start` 返回 `Started` 后由 Processor 在 durable `markRunning` 之后调用 `Handle.activate` 打开回调 gate；`start` 返回 `Busy` 稍后重试；`Rejected` 确定性终结；`Indeterminate` 收敛为 `UNKNOWN`；
3. 回调（serialized FIFO 单 drainer）：`MODEL_DELTA` 节流写 `stream_checkpoint`（text/thinking 归一化为非 null；至少一侧非空，纯空白合法；首个 safe delta 立即 flush；tool-call fragment 只推 sequence 不入 checkpoint），**commit 后才 best-effort 发布 Redis realtime delta**；
4. retryable `TRANSIENT` terminal 在同一短事务把 accumulator 的完整 text/thinking、最后已提交 sequence、error、`failedAt/retryAt` 追加为 `failedAttempts`，清除 checkpoint，`RUNNING -> READY` 并 reschedule；terminal/duplicate/stale 回调仍严格 fire-once/no-op；
5. 最终 `resultJson`（ProviderResponse 全量 `{text, thinking, toolCalls, stopReason, usage, cost, requestId, serviceTier, rawUsageJson}`）或 `errorJson` 写入 Invocation，并请求 THREAD Work 做 apply。

## 7. ToolProcessor 与结果外部化边界

ToolProcessor 消费 TOOL Work：

1. claim 校验（lost/stale → 完整 no-op）；
2. `ToolGateway.start` 两阶段激活与 Model 同构；`ToolGateway` 先做 preflight（未取消/未过期、冻结 `name@version` 命中固定目录、arguments 是 JSON object），admission 不确定收敛 `UNKNOWN`；
3. **ToolProcessor 接收已验证、已外部化的 `ToolSuccess(result, effects)`**，先做领域校验（toolCallId、禁止 inline Binary、canonical size、effects 上限与 payload 等），再在短事务内做严格 terminal CAS（fire-once、attempt/claim ownership 校验），以一次 Store update 同时落 `SUCCEEDED + result + effects`，不做任何存储外部化；
4. terminal 后请求 THREAD Work 做 sibling apply。本地执行取消（Stop 后）通过 process-local `modelExecutionCanceller` / `toolExecutionCanceller` best-effort 回调。

`ToolResultExternalizer` 在 CoreToolGateway callback bridge 只做**瞬时** Resource 外部化：插件
Tool 先把声明式 intents 映射并校验为 `ToolEffectBatch`；effects 合法后才按
`ResourceStore.reference -> put -> exact ref check` 生成 `ResourceRef`，随后把
`ToolSuccess(result,effects)` 交给 ToolProcessor 落 terminal 事实；partial 拒绝
Binary/Resource 且零存储 I/O。durable 物化发生在 `ToolOutcomeAppender` 追加 TOOL Entry
之前：同一 Store 事务内由 `ToolResultHistoryMaterializer` 摄入全局 `storage_blob`，消息只保存
`resource(blobId,name,preview)` 与 Session Blob Ref，绝不保存瞬时 URI。

## 8. Command 控制面（HarnessRuntime）

`HarnessRuntime` 是同步 command/control/query 门面，每个方法恰好一个事务，锁序固定：

```text
Thread -> Commands -> ModelInvocation -> ToolInvocation siblings -> Work
```

实现 `createThread`、`enqueueCommands`、`moveHead`、`stop`、`decideToolApproval` 与 `getThreadSnapshot`。

- `enqueueCommands`：幂等查找先于任何 head/sequence/live 检查——全部 `clientCommandId` 已存在、对应 `requestHash` 相同且 sequence 连续时是 **ordered command-set replay**（忽略 expected cursors 与 QUEUED/APPLIED/CANCELLED lifecycle，返回原行）；部分存在/hash 不同/非连续顺序分别 `PARTIAL_COMMAND_REPLAY` / `COMMAND_ID_REUSED` / `COMMAND_REPLAY_ORDER_MISMATCH`；全新 batch 才做双 cursor CAS（`STALE_COMMAND_CURSOR`），一次性预留全部 sequence（`revision` +1），含 `SET_ENVIRONMENT` 的 batch 额外要求真正静止前置状态（无 queued USER_MESSAGE/CUSTOM_MESSAGE、classifier 为 IDLE_OR_HISTORICAL、无 THREAD Work 行）。
- `moveHead`：revision CAS；同 target no-op；**只允许同 Session**；无 queued command；无 live/terminal-pending context；不能指向 `continueModel=true` 的 TURN_END。
- `stop`：先 `findReplay`（同 thread + stopRequestId 的 durable TURN_END，在 revision CAS **之前**）→ `REPLAYED`；否则 revision CAS。`IDLE_OR_HISTORICAL` 取消 queued（有取消则 revision +1、不写 stop marker；无 queued 则真正 no-op）；`CONTINUATION_DUE` 先物化 `TURN_START(CONTINUATION)` + `ASSISTANT_ERROR(CANCELLED)`，再追加 `TURN_END(STOPPED)`；Model/Tool active 则写安全 `ASSISTANT_ABORTED` 或取消/不确定 Tool Result，再追加 `TURN_END(STOPPED)`。Tool terminal winner 在 Stop 路径也共用 `ToolOutcomeAppender`，成功 sibling 的 effects 不会丢失；所有 STOPPED 路径同时取消 queued 并 fence 后续 callback。
- `decideToolApproval`：`decisionId` 幂等；已决定请求精确 replay（保留原 `decidedAt`，无 revision bump，不请求 Work）；未决定请求必须位于锁定的 TOOL_ACTIVE 上下文，mutation/`decidedAt` 抬升到 Thread/head/Model/siblings/approval 的最新 durable 时间，`ALLOWED` → `READY` + TOOL Work，`DENIED` → `FAILED` + THREAD Work，revision 恰好 touch 一次；Work request 仍使用原始本地调度时钟。

## 9. Dispatcher 与 Work 协议

```text
durable mutation
  -> work wake（available_at = least(...), wake_version+1）
  -> PostgreSQL NOTIFY（harness_runtime_work channel，仅可用性提示）
  -> listener -> dispatcher wake（合并，单 drain）
  -> periodic poll（due scan）
  -> claim next work（Work-only 短事务，round-robin THREAD/MODEL/TOOL）
  -> bounded handoff -> ThreadProcessor / ModelProcessor / ToolProcessor
```

通知可重复、乱序或丢失；`wake_version` fence 丢失的 wake，periodic poll 与启动/重连 wake 提供最终收敛。claim 成功后必须二选一：worker 已接受 handoff 或立即 reschedule，禁止 claim→reject 热循环。

## 10. Stop、approval 与 realtime

- Stop 的 durable key 是 `(threadId, stopRequestId)`：重试同 ID 恒命中 replay，`expectedRevision` 只用于未 replay 的首发 CAS；`REPLAYED` 返回被重放的 `stoppedTurnEndEntryId` 且 `cancelledCommandCount=0`。
- 前端对 ambiguous Stop 保留完整操作（stopRequestId + 原始 expectedRevision + basis head/revision）：basis 未变时精确重试，basis 被权威 snapshot 证明变化时自动 retire 并 mint 新 ID（同步 fence 见 [frontend-implementation-design.md](frontend-implementation-design.md)）。
- 客户端先读取 Thread snapshot，再经应用事件通道（`/api/events/v1`）订阅 revision。Redis `realtime` 只提供正常 turn 的 text/thinking/tool partial overlay；snapshot 的 `modelAttemptFailures` 提供 active retry 的 durable partial/error/retry 时间，终态后由 root-to-head path 上的 `MODEL_ATTEMPT_FAILURE`/`ASSISTANT_ERROR.attempt` 恢复。revision/resync/subscribed 只触发 snapshot invalidate；同 `(modelInvocationId,attempt)` 的 durable failure 会 fence stale overlay，terminal `resultJson`/`errorJson` 无条件压过更高 sequence。Runtime 不发布 compaction ModelDelta/attempt failure，前端仍按 TURN_START reason 抑制完整 COMPACTION turn 及其 Model overlay。事件通道帧协议见 [application-event-channel.md](application-event-channel.md)。

## 11. Subagent 委派（task）

`task` 是内部 `PLATFORM` Tool，由 Core `TaskTool` 实现，**不增加表、状态机或调度器**：父 Thread 的 ToolInvocation 照常走 approval/Work/ToolProcessor，子 Agent 则是另一个普通 durable Harness Thread（其执行仍由既有 ThreadProcessor 驱动）。

- 子 Thread 创建复用 `HarnessRuntime.createThread`：ROOT 携带 `SubagentContext{parentThreadId, rootThreadId, taskInvocationId, depth}`（普通根 depth=1，子 Session 从 2 开始；rootThreadId 在整棵委派树不变）；Thread YOLO 继承父 Thread；branch settings 由 `AgentBranchSettingsMaterializer` 按最新 catalog 物化（activeTools = config.tools + skills 非空时内部 `load_skill` + subagents 非空且 depth < maxDepth 时内部 `task`）。
- 委派权限冻结在父 `ModelInvocationRequest.subagentBindings`（Agent 名称 + 描述）；TaskTool 执行只消费该冻结 allowlist，绝不重读父 Agent 配置扩权。运行中由 `TaskTool` 轮询子 Thread snapshot：以 durable 指纹（revision/head/model/tool siblings）判定活动，idle 超时排除 active tool 时间；约 1s 一次发布非 durable `TOOL_PARTIAL` 心跳（`details.kind=task.status` 完整 JSON 快照）。活动 task 的进程内 registry 只 relay 扁平 descendant 状态给祖先心跳，使根 Thread 可审批任意深度调用；durable 子 Thread 仍是唯一执行事实。`maxTurns` 软预算达界后每 5 turn 入队 SYSTEM `CUSTOM_MESSAGE` 提醒。
- 恢复（`session_id` = 子 ThreadId，canonical UUID）要求同 parent/root 归属且子 Thread quiescent；Stop/取消子 Thread 保留可恢复 Session（`cancelChild` 复用 `HarnessRuntime.stop` 的 `task-{invocationId}-cancel` stopRequestId）。进程内 `SubagentRunRegistry` 只做并发 reservation（每父/每根上限、resume 单飞），进程重启后仅由 durable Thread 恢复。
- 子 Agent 的工具审批仍复用既有 `decideToolApproval`（以子 ThreadId 定位），approval 事实/`WAITING_APPROVAL` 语义与父 Thread 完全一致；子工具执行经同一 ToolGateway 管线，权限判定同 YOLO/Allow/Ask/Deny 规则。

相关文档：

- [harness-runtime-contracts.md](harness-runtime-contracts.md)
- [harness-storage-runtime.md](harness-storage-runtime.md)
- [harness-capability-wiring.md](harness-capability-wiring.md)
- [environment-daemon-gateway.md](environment-daemon-gateway.md)
