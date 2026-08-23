# Harness Runtime 架构

本文是当前 Harness 执行架构事实源，只描述现行模块、durable 事实、写者边界、Agent Loop 与并发协议。

## 1. Runtime 目标与边界

- PostgreSQL 保存唯一 durable truth；`harness_work` 保存唯一调度 mailbox。
- Thread 的 Entry/head 推进串行；Model、Tool 通过各自 Invocation + Work 继续执行。
- Processor 每次处理都是短事务，不在事务内等待外部 I/O（Provider/Tool 执行在事务外）。
- `harness-runtime` 是纯 Java 模块：不依赖 Spring、数据库驱动、HTTP、WebSocket、Provider SDK 或业务 Tool 实现。
- `harness-runtime-spring` 只做持久化与调度适配：`HarnessStore`（PostgreSQL）、Work dispatcher（claim/NOTIFY/poll）、PostgreSQL realtime notification、本地 Resource store。
- `core` 只提供 Catalog/TurnResolver/ModelGateway/ToolGateway/Environment gateway 适配，不写 `harness_*` 表。

## 2. 模块

```text
harness/
├── tool/                # Tool API、descriptor、ResourceRef、RemoteTool、Daemon v3 wire
├── runtime/             # 纯 Java：Session/Entry/Thread/Command/Invocation/Work/processor
├── plugin/              # 纯 Java trusted build-time 插件 API：Catalog/BranchView/Tool/intents/projector
├── runtime-spring/      # Store/Work/notification/Resource 适配（PostgreSQL、dispatcher）
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
- `COMPACTION` 是内部压缩 Model 的 durable summary：payload 只含 `summaryText`；phase/trigger/executionModel/`cutEntryId`/`turnPrefixStartEntryId`/`historyCompactionEntryId` 冻结在对应 `TURN_START` 的 `CompactionStart`。它只能作为 `TURN_START(COMPACTION)` 的唯一成功结果；HISTORY 为 incomplete，FULL/TURN_PREFIX 为 complete（complete 由 phase 派生，不持久化 boolean）。
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

`ThreadState` 的 durable 字段：

```text
id
sessionId                # 创建后不可变；head 必须属于该 Session
headEntryId              # 始终非空
materializationHash      # 创建请求 canonical SHA-256，仅用于首次 materialization replay
yoloEnabled              # Thread 当前运行时策略（直接控制面 PUT /yolo 修改，不进入请求/spec）
nextCommandSequence      # 从 1 开始；每次命令 batch 预留后 +N
version                 # 非负；每次可见状态变化恰好 +1
createdAt / updatedAt
```

Session、Environment、status 与 branch settings 都由 head Entry 分支派生；Thread 行**不**保存 execution epoch、processor lease 或 runnable 标志。`validateTransition` 保证 identity 不变、`nextCommandSequence`/`version`/`updatedAt` 不回退、可见变化 version 恰好 +1；exact replay 恒被接受。

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
| `harness_model_invocation` | thread、`turn_start_entry_id`（唯一）、`basis_head_entry_id`、compact `ModelRequestSpec` JSON（providerType/model/variant/preamble/**toolBindings**/skillBindings/subagentBindings/cacheControl；无 history messages/tools/YOLO/contextWindow/compaction metadata；压缩身份由 basis path 末尾的 `TURN_START.compaction` 表达）、status、attempt、`stream_checkpoint`（attempt-local 单调 checkpoint）、`failed_attempts`（append-only TRANSIENT 失败前缀）、`result`/`error`/`result_entry_id`、时间 |
| `harness_tool_invocation` | `model_invocation_id`、`assistant_entry_id`、`ordinal`（(assistant_entry_id, ordinal) 唯一）、`call`（ToolCall JSON）+ 可空 `binding`（descriptor/type/environment/plugin provenance/access；unknown tool 为 null）、status、attempt、`approval` JSON、`result`/`effects`/`error`、时间 |

冻结 spec 中的 `ModelDescriptor` 只含 `providerName`/`modelName`/`inputModalities`/`tools`/`reasoning`/`pricing` 六个字段；Provider 连接事实与 cache capability 在每次 attempt 由 Core 按当前 `agent_provider` 行解析（见 [harness-capability-wiring.md](harness-capability-wiring.md)）。完整 ProviderRequest 永不持久化：每次 MODEL attempt 由 `ModelRequestMaterializer` 在有效 claim 内从 `basisHeadEntryId + spec` 重建内存请求。

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

terminal 事实约束：`result` 与 `error` 互斥。Model 的 `result_entry_id` 在**本表内**唯一（partial unique index），只属于当前 open Tool phase（null = 结果尚未被 Thread apply；指向当前 Assistant head = Assistant 已写入、等待 Tool batch）；terminal 且挂结果 Entry 的 Model 不再被 apply。普通 Model 在挂结果前由 `ThreadProcessor`/Stop 按序追加全部 `MODEL_ATTEMPT_FAILURE`，再追加唯一 Assistant 结果；`attachResultEntry` 同时清空已物化的 checkpoint/failedAttempts。InMemory/PostgreSQL Store 的 `ModelAttemptMaterialization` 在 update 边界精确比对 EntryPath 中的 attempt/error/partial/时间与 terminal/Stop barrier；compaction invocation 不物化失败 attempt。Tool 无 `result_entry_id`：terminal Tool 行始终表示 outcome 尚未进入 ToolResult Entry，batch apply 后同事务物理删除全部 siblings 再删除父 Model。Tool 的 `effects` 非 null：只有 `SUCCEEDED` 可非空，且 result/effects/status 在同一次 Store update 中原子持久化；terminal 后 effects 不可变。closed history / compaction 完全 Entry-only：`TURN_END` 或 Stop 后 Invocation 行必须不存在。

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
ToolTerminalPending     # siblings 全部 terminal（outcome 尚未进入 Entry）：按 ordinal 经唯一 appender 原子 apply 后删除
```

IDLE_OR_HISTORICAL / CONTINUATION_DUE 快照不暴露 Model、tools 或失败 attempts；ModelActive/ModelTerminalPending 暴露 Model 与尚未物化的 `modelAttemptFailures`；Tool 上下文暴露 Model + 全部 Tool siblings，但不重复暴露已经物化或非当前 Model context 的失败 attempts。分类器对破坏的不变量以 `IllegalStateException` 拒绝，绝不降级为业务 kind。

## 5. Agent Loop（ThreadProcessor）

ThreadProcessor 消费 dispatcher 已 claim 的 THREAD Work，**每次 claim 恰好执行一个分类动作**（one claim one action）：下一动作一律由同一事务内 `requestWork(THREAD) + completeWork(currentClaim)` 驱动，Processor 不在提交后重新读取自身刚写入的状态。返回值固定为 `COMPLETED` / `RESCHEDULED` / `LOST_OWNERSHIP`。

按分类执行：

```text
MODEL_TERMINAL_PENDING  -> 同一事务原子 apply Model terminal（锁序含 queued Commands）：先按 1..N 追加 MODEL_ATTEMPT_FAILURE，
                             再经 ModelResponsePlanner 分支：SUCCEEDED 追加 ASSISTANT Entry 并挂 Model resultEntryId；
                             无 ToolCall 时追加 TURN_END、删除 ModelInvocation，再基于新 head EntryPath 判定下一步：
                               有 queued USER/CUSTOM_MESSAGE 或 compaction actionable -> 同事务 request THREAD，否则不 wake；
                             有 ToolCall 时按 ordinal materialize ToolInvocation（每 call 一个槽位）：
                               有效 known call -> READY；schema-invalid -> FAILED；unknown -> FAILED(null binding)
                             plugin sibling WRITE 后再 READ/WRITE -> FAILED(SIBLING_STATE_CONFLICT)；
                             仅对 READY 请求 TOOL Work；全部 immediate FAILED 时同事务请求 THREAD self-wake；
                             FAILED/错误追加带可空 attempt snapshot 的 ASSISTANT_ERROR + FAILED TURN_END，
                             删除 ModelInvocation，并按同一新 head 判定 wake（不 always wake、不 self-poll）
TOOL_TERMINAL_PENDING   -> 同一事务按 ordinal 经唯一 ToolOutcomeAppender 原子 apply 全部 terminal Tool siblings：
                             SUCCEEDED 先按 effects 顺序追加 CUSTOM，再追加 Tool Result MESSAGE Entry，
                             并追加 TURN_END(COMPLETED, continueModel=true)，请求 THREAD Work，
                             随后删除全部 Tool Invocations 再删除父 ModelInvocation；
                             下一个 claim 处理 ContinuationDue
MODEL_ACTIVE / TOOL_ACTIVE -> 完成 claim，不 reschedule（Model/Tool terminal 时各自请求 THREAD Work，
                             由 Work mailbox 合并；Thread 提前 claim 时分类为 active 并完成，不产生业务 mutation）
CONTINUATION_DUE        -> 启动 continuation：消费普通配置命令（SET_AGENT/MODEL/ACTIVE_TOOLS），
                             保留 SET_ENVIRONMENT（留给后续 INPUT 完整收割进入 BranchSettings），不产生 Message Entry
IDLE/HISTORICAL 且压缩到期 -> 启动 COMPACTION Turn（消费零 Command，优先于 queued input）
有 queued USER_MESSAGE/CUSTOM_MESSAGE -> 启动 INPUT Turn（先 normalization 旧 open Turn，再 TURN_START(INPUT) + Message）
否则                    -> 完成 claim（是否仍有 Work 由 mailbox 决定，不 self-poll）
```

Turn 启动采用 speculative plan 两段式：

1. **短事务**：锁 Thread、读取 queued Command 快照与 cutoff、分配 candidate Entry ID、构造完整合法 candidate EntryPath（TURN_START + Message），**不写任何 durable 状态**；事务外调用 `TurnResolver.resolve(threadId, candidatePath, compactionPreparation)`（期间 `WorkHeartbeat` 维持 lease）。
2. **第二短事务**：以 source head / cutoff 内 Command 精确快照 / claim ownership 做 CAS，一次性原子提交 normalization + TURN_START + Message + Command markers + Thread 更新 + ModelInvocation（compact spec）/MODEL Work（resolved）或 AssistantError + FAILED TURN_END（rejected）。rejected 追加 barrier 闭合后基于新 head EntryPath 判定：有 deferred input 或 compaction actionable 才同事务 request THREAD，否则不 wake（不 always wake、不 self-poll）。第二事务推进 head 时沿用锁内读取到的当前 Thread YOLO（`advanceHead` 恒保留策略值，不写回 speculative plan 中的旧值）。

Model terminal materialize Tool siblings 时，Runtime 按 ordinal 扫描 frozen plugin state accesses。对同一 `(pluginId, customType)`，`READ+READ` 与 `READ -> WRITE` 允许；已有 `WRITE` 后的 `READ` 或 `WRITE` 机械创建为 unattached `FAILED(kind=SIBLING_STATE_CONFLICT, attempt=0)`，不 dispatch、不请求 TOOL Work，也不把冲突调用登记为后续访问。不同 customType 或不同 pluginId 不冲突。

任何 CAS / claim 损失一律完整 no-op 返回 `LOST_OWNERSHIP`；Resolver 异常/null/heartbeat 失败按单一固定失败延迟（`resolveFailureDelay`）reschedule 返回 `RESCHEDULED`，绝不静默丢弃 Work。历史/非 applicable open Turn 只做 unlocked 读，绝不先锁 Model/Tool 再落 INPUT normalization。

### Durable compaction

压缩复用现有三 processor、MODEL Work、ModelInvocation retry/lease/Stop/UNKNOWN 语义，不增加表、processor 或 Work target：

```text
TURN_START(COMPACTION)
  -> ModelInvocation（SYSTEM summarization prompt + USER summary prompt，零 tool/skill/cache）
  -> COMPACTION
  -> TURN_END
```

- 配置只有 `compactionKeepRecentTokens=20000` 与可空 `compactionFallbackModel`（`SystemSettings.AiRuntime` 经 `HarnessCompactionConfiguration` 装配；`SystemSettingsSchemaProvider` 定义 UI schema）；派生 `effectiveKeepRecentTokens = min(compactionKeepRecentTokens, floor(contextWindow / 2))`、`effectiveReserve = min(16384, maxOutputTokens)`、`softThreshold = max(effectiveKeepRecentTokens, contextWindow - effectiveReserve)`、`manualMinimum = min(compactionKeepRecentTokens * 2, floor(contextWindow / 2))`、`outputBudget = min(maxOutput, floor(0.8 * effectiveReserve), removedPrefixEstimate)`（TURN_PREFIX 用 `0.5 * effectiveReserve`）。THRESHOLD 在两类边界可执行：尚有 same-owner `CONTINUATION_DUE` obligation，或 idle/historical Thread 已有真实 queued user demand。上下文量使用最近 compatible owned successful Assistant 的 Provider usage，加该 Assistant 之后仍进入 Provider Context 的可见消息估算（包括 ToolResult）。完全结束的 idle run 不因 soft threshold 自唤醒。terminal `OVERFLOW` 失败仍可触发一次恢复。
- 有效 recent retention 为 `effectiveKeepRecentTokens`；cut point 只允许 USER/ASSISTANT/CUSTOM_MESSAGE/AssistantAborted，绝不切在 ToolResult。retained 边界由 `cutEntryId` 表达，相邻控制元数据从完整 root-to-head path 派生。
- logical Agent segment 从最近 INPUT 的首个 user-like message 开始，并跨越其后的 CONTINUATION durable turns；最新 complete compaction 是扫描边界。segment 内 cut 产生 split 时，先生成 incomplete HISTORY，再以完全冻结的 `CompactionStart`（phase/trigger/executionModel/cutEntryId/turnPrefixStartEntryId/historyCompactionEntryId）机械生成 TURN_PREFIX；没有先前 history 的 direct TURN_PREFIX 使用固定文本 `No prior history.`。
- `TURN_START(COMPACTION)...TURN_END` 内全部对话事实对后续 planner、token estimate、Provider Context 与前端 transcript 不可见；停止压缩的 AssistantAborted 不成为未来 cut 或摘要内容。FAILED/STOPPED/CANCELLED/incomplete compaction 只阻止原地立即重试，出现新的普通 turn 后不再充当长期 freshness barrier。
- 闭合后的 wake 判定不 self-poll：完全结束的普通 turn 只在 queued user、fallback 或 hard-overflow action 已确定时 wake；Tool batch 的 `continueModel=true` 固定 wake 下一 claim，并在该 `ContinuationDue` claim 先评估 THRESHOLD。FAILED/STOPPED/CANCELLED compaction 与无增益结果不原地重试；HISTORY 下一阶段、complete OVERFLOW retry，以及成功 THRESHOLD 后保留的原 normal continuation 都由显式 THREAD wake 驱动。
- complete summary 在下一次正常请求中投影为一个 wrapped USER message，并从 `cutEntryId` 本身继续保留上下文。removed-prefix 估算在 planning 时瞬时重算；`CompactionPayload` 只含 `summaryText`，phase/trigger/executionModel 与 cut/prefix/history anchors 冻结在 `TURN_START.compaction`，complete 由 phase 与终态派生。文件 read/write/edit 清单从当前 branch 的完整 durable history 累计重算。`<read-files>` / `<modified-files>` 是 Runtime 保留 section：旧 summary 和模型响应中的同名 section 先剥离，最终只机械追加一份 canonical 清单，modified 覆盖 read。
- completed HISTORY 以 `continueModel=true` 机械启动 TURN_PREFIX；complete OVERFLOW 以 true 启动一次 immediate retry；成功 THRESHOLD FULL/TURN_PREFIX/fallback 仅在压缩前存在 same-owner normal continuation 时以 true 恢复该 obligation。失败、无增益或 fallback 耗尽后不冒险继续扩张上下文。foreign owner 的 continuation 不能被借用。
- phase output budget 为 `min(model output limit, floor(effectiveReserve * 0.8))`（FULL/HISTORY）与 `min(model output limit, floor(effectiveReserve * 0.5))`（TURN_PREFIX），最终上限再取与 removed-prefix estimate 的较小值。
- 所有 prompt 是 strict classpath resource；复制自 Pi 的资源在同目录保留 MIT `NOTICE`。

## 6. ModelProcessor

ModelProcessor 消费 MODEL Work：

1. claim 校验（fake/expired lease token → `LOST_OWNERSHIP` no-op，绝不 cancel 合法 active execution）；
2. 两阶段激活：先由 `ModelRequestMaterializer` 在有效 MODEL claim 内从 `basisHeadEntryId + compact spec` 重建内存 ProviderRequest（不持有长事务、不访问 catalog/Environment/插件）；随后 `ModelGateway.start` 返回 `Started` 后由 Processor 在 durable `markRunning` 之后调用 `Handle.activate` 打开回调 gate。`activate` 与 abandon 由单一 monitor 仲裁：abandon 先于 activation 则不调用 activate，activation 已开始则把 cancel 推迟到 activate 返回，外部调用序只能是 ACTIVATE → CANCEL。`start` 返回 `Busy` 稍后重试；`Rejected` 确定性终结；`Indeterminate` 收敛为 `UNKNOWN`；
3. 回调（serialized FIFO 单 drainer）：`MODEL_DELTA` 节流写 `stream_checkpoint`（text/thinking 归一化为非 null；至少一侧非空，纯空白合法；首个 safe delta 立即 flush；tool-call fragment 只推 sequence 不入 checkpoint），通过 transaction-aware PostgreSQL `NOTIFY` 在 **commit 后**发布 realtime delta；
4. retryable `TRANSIENT` terminal 在同一短事务把 accumulator 的完整 text/thinking、最后已提交 sequence、error、`failedAt/retryAt` 追加为 `failedAttempts`，清除 checkpoint，`RUNNING -> READY` 并 reschedule；lease fence 使用原始本地时钟，`failedAt` 则抬升到 Thread/Model durable 时间与上一 `retryAt` 的下界，保证时钟回拨后仍可物化为单调 Entry；terminal/duplicate/stale 回调仍严格 fire-once/no-op；
5. 最终 `resultJson`（ProviderResponse 全量 `{text, thinking, toolCalls, stopReason, usage, cost, requestId, serviceTier, rawUsageJson}`）或 `errorJson` 写入 Invocation，并请求 THREAD Work 做 apply。Provider resolution 校验当前 Provider 行的 type 等于 spec 冻结的 type 后方可 start；credential/endpoint/timeout 与 Resource signed URL 保持 attempt-time live。

## 7. ToolProcessor 与结果外部化边界

ToolProcessor 消费 TOOL Work：

1. claim 校验（lost/stale → 完整 no-op）；
2. `ToolGateway.start` 两阶段激活及 activate/abandon 仲裁与 Model 同构；`ToolGateway` 先做 preflight（未取消/未过期、冻结 `name@version` 命中固定目录、arguments 是 JSON object），admission 不确定收敛 `UNKNOWN`；
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

`HarnessRuntime` 是同步 command/control/query 门面，每个方法恰好一个事务，锁序固定（Session → Thread → Commands → ModelInvocation → ToolInvocation siblings → Work；Session 用 KEY SHARE，正常写入不串行化 sibling Thread）：

```text
Session KEY SHARE -> Thread -> Commands -> ModelInvocation -> ToolInvocation siblings -> Work
```

实现 `acceptCommands`（NEW_SESSION / ENTRY / THREAD 单原语）、`findThreadCommand`、`stop`、`decideToolApproval`、`setThreadYolo`、`getThreadSnapshot`、`getSessionEntries`、`listThreadsBySession`；不提供 create/delete（Session/Thread 只允许在第一批 Command 被接受时创建，深删除由 Core `HarnessSessionDeletionService` 经 `HarnessStore.Transaction` 编排）。

- `acceptCommands(target, preflight)`：单个写入原语。NEW_SESSION 原子创建 Session + ROOT（rootSettings + 可选 SubagentContext）+ Thread（head=ROOT，version 0 / nextCommandSequence 1）+ owner relation（preflight）+ Commands（sequence 从 1 起）+ THREAD Work；ENTRY 校验 startEntry 属于 Session 后创建 Thread（head=startEntryId，不复制 Entry）+ Commands + Work；THREAD 先读 immutable `thread.sessionId` 并 KEY SHARE Session、再锁 Thread 复核，exact ordered replay 查找必须先于任何 cursor/preflight admission，全新 batch 要求精确 expected head + next sequence（否则 `STALE_COMMAND_CURSOR`）。materialization replay：同 hash + 同 Session 的 client threadId 精确重放原始初始命令（验证 requestHash 相等且 sequence 从 1 连续）；同 threadId 不同 session/hash 返回 `MATERIALIZATION_ID_REUSED`。
- `stop`：先在 Thread 锁内做 Session 级不可变查找，`findReplay` 按「被引用 TURN_START 的 `ownerThreadId` == thread + `closeRequestId` == stopRequestId」精确命中（在 version CAS **之前**）→ `REPLAYED`；否则 version CAS。另一 Thread 的相同 raw id 被忽略而非冲突，owning Thread 迁移到兄弟分支后仍可命中。`IDLE_OR_HISTORICAL` 取消 queued（有取消则 version +1、不写 stop marker；无 queued 则真正 no-op）；`CONTINUATION_DUE` 先物化 `TURN_START(CONTINUATION)` + `ASSISTANT_ERROR(CANCELLED)`，再追加 `TURN_END(STOPPED)`；Model/Tool active 则写安全 `ASSISTANT_ABORTED` 或取消/不确定 Tool Result，再追加 `TURN_END(STOPPED)`。Tool terminal winner 在 Stop 路径也共用 `ToolOutcomeAppender`，成功 sibling 的 effects 不会丢失；所有 STOPPED 路径同时取消 queued、先净化 Work mailbox 再删除当前 Tool/Model Invocations、fence 后续 callback，closed turn 不保留 Invocation 行。
- `setThreadYolo`：锁 Thread → 同值在任何 version CAS 前 no-op（支持网络重试）→ 变化时 version CAS（`STALE_VERSION` 409）→ 更新 `yoloEnabled` 且 version +1；不创建 Command/Entry/Work，也不唤醒 processors。
- `decideToolApproval`：`decisionId` 幂等；已决定请求精确 replay（保留原 `decidedAt`，无 version bump，不请求 Work）；未决定请求必须位于锁定的 TOOL_ACTIVE 上下文，mutation/`decidedAt` 抬升到 Thread/head/Model/siblings/approval 的最新 durable 时间，`ALLOWED` → `READY` + TOOL Work，`DENIED` → `FAILED` + THREAD Work，version 恰好 touch 一次；Work request 仍使用原始本地调度时钟。

## 9. Dispatcher 与 Work 协议

```text
durable mutation
  -> work wake（available_at = least(...), wake_version+1）
  -> PostgreSQL NOTIFY（harness_runtime_work channel，仅可用性提示）
  -> Web 共享 notification loop -> dispatcher wake（合并，单 drain）
  -> periodic poll（due scan）
  -> claim next work（Work-only 短事务，round-robin THREAD/MODEL/TOOL）
  -> bounded handoff -> ThreadProcessor / ModelProcessor / ToolProcessor
```

通知可重复、乱序或丢失；`wake_version` fence 丢失的 wake，periodic poll 与启动/重连 wake 提供最终收敛。claim 成功后必须二选一：worker 已接受 handoff 或立即 reschedule，禁止 claim→reject 热循环。

### 9.1 事件驱动与周期等待边界

| 路径 | 当前机制 | 是否读取内部 durable 状态 | 保留理由 |
| --- | --- | --- | --- |
| Harness work dispatch | PostgreSQL `LISTEN/NOTIFY` 唤醒 + 低频 periodic safety poll | safety poll 会 claim work | NOTIFY 不是 durable queue；周期兜底用于启动、丢通知和恢复 |
| TaskTool child observation | `HarnessThreadChangeSource` version/resync + registry descendant signal | 仅首次与 version wake 读取 snapshot | 主流程事件驱动；取消主动 wake；heartbeat 使用缓存 |
| Harness one-shot | `HarnessThreadChangeSource` version/resync | 仅首次与 version wake 读取 snapshot | 100ms timed wait 只检查 caller active/deadline，不读取 snapshot |
| Canvas graph/run | `canvas_version` PostgreSQL notification + 标准 Snapshot | 前端按更高 version、重连或 resync 拉取 | Function run 不轮询状态 |
| Application event connection | WebSocket callback + reconnect backoff + 20s heartbeat | 否 | transport liveness 与断线恢复 |
| Work heartbeat | fixed-rate lease renew | 是，更新 work lease | 分布式 ownership 协议，不是 UI 状态轮询 |
| Environment 列表 | 页面可见时 10s React Query refresh | 是 | Daemon/进程 liveness 边界；当前 wire 没有 Environment collection version |
| ComfyUI / Seedance / OpenCLI | adapter 专用 executor 中按外部 API 状态等待 | 否（外部系统） | 外部平台没有可复用的 kk-studio 事件源；均有 timeout、取消和测试 |
| PostgreSQL notification loop | 单连接固定 LISTEN work/thread/canvas；`getNotifications(5s)` + 1s reconnect backoff | Thread/Canvas 初始订阅读权威 cursor；通知 handler 只解析 payload | socket wait、重连 resync 与轻量 fan-out，不固定查询业务表 |

不得重新引入以下模式：

- Task/one-shot 无事件时固定读取 Thread snapshot；
- Canvas RUNNING node 的固定 `refetchInterval`；
- 用 heartbeat 替代 durable version 或 version 事实。

## 10. Stop、approval 与 realtime

- Stop 的 durable key 是「被关闭 TURN_START 的 `ownerThreadId` + `closeRequestId`」（Thread 锁内 Session 级不可变查找）：重试同 raw ID 且 owner Thread 未变时恒命中 replay，`expectedVersion` 只用于未 replay 的首发 CAS；`REPLAYED` 返回与被重放 Stop 相同的 `stoppedTurnEndEntryId`、`cancelledCommandCount` 与 ordered `cancelledUserMessages`。另一 Thread 复用同一 raw id 不产生冲突；IDLE 不写 stop marker，marker-free IDLE 恒不是 REPLAYED。
- Stop HTTP 结果为 `{status,thread,stoppedTurnEndEntryId,cancelledCommandCount,cancelledUserMessages[]}`，无独立 `replayed` 字段；每条取消消息为 `{sequence,clientCommandId,messageJson}`。前端把完整操作（stopRequestId + 原始 expectedVersion + basis head/version）保存在 per-Thread local sidecar：basis 未变时精确重试，basis 变化时 retire；成功响应按 sequence 将 TEXT/RESOURCE 转为 ComposerPart，以两个换行连接并前置到当前草稿，同一 stopRequestId 最多应用一次。
- 客户端先读取 Thread snapshot，再经应用事件通道（`/api/events/v1`）订阅 version。PostgreSQL `realtime` notification 只提供正常 turn 的 text/thinking/tool partial live overlay；snapshot 的 `modelAttemptFailures` 提供 active retry 的 durable partial/error/retry 时间，终态后由 root-to-head path 上的 `MODEL_ATTEMPT_FAILURE`/`ASSISTANT_ERROR.attempt` 恢复。version/resync/subscribed 只触发 snapshot invalidate；同 `(modelInvocationId,attempt)` 的 durable failure 会 fence stale overlay，terminal `resultJson`/`errorJson` 无条件压过更高 sequence。Runtime 不发布 compaction ModelDelta/attempt failure，前端仍按 TURN_START reason 抑制完整 COMPACTION turn 及其 Model overlay。事件通道帧协议见 [application-event-channel.md](application-event-channel.md)。

## 11. Subagent 委派（task）

`task` 是内部 `PLATFORM` Tool，由 `harness.runtime.subagent.TaskTool` 实现，**不增加表、状态机或调度器**：父 Thread 的 ToolInvocation 照常走 approval/Work/ToolProcessor，子 Agent 则是另一个普通 durable Harness Thread（其执行仍由既有 ThreadProcessor 驱动）。

- 子 Thread 创建复用 `runtime.acceptCommands(NEW_SESSION, SubagentContext)`：原子创建 Session + ROOT（`SubagentContext{parentThreadId, rootThreadId, taskInvocationId, depth}`；普通根 depth=1，子 Session 从 2 开始；rootThreadId 在整棵委派树不变）+ Thread（yolo 继承父 Thread）+ Commands（SYSTEM CUSTOM_MESSAGE + USER_MESSAGE prompt）；branch settings 由 `AgentBranchSettingsMaterializer` 按最新 catalog 物化（activeTools = config.tools + skills 非空时内部 `load_skill` + subagents 非空且 depth < maxDepth 时内部 `task`）。
- 委派权限冻结在父 `ModelRequestSpec.subagentBindings`（Agent 名称 + 描述）；TaskTool 执行只消费该冻结 allowlist，绝不重读父 Agent 配置扩权。运行中由 `TaskTool` 经内部 `HarnessThreadChangeSource` 事件化观察子 Thread（`ChangeGate.awaitChange` 等 version wake 到达才读 snapshot，无固定轮询）：以 durable 指纹（version/head/model/tool siblings）判定活动，idle 超时排除 active tool 时间；约 1s 一次基于缓存 snapshot 发布非 durable `TOOL_PARTIAL` 心跳（`details.kind=task.status` 完整 JSON 快照）。活动 task 的进程内 registry 只 relay 扁平 descendant 状态给祖先心跳，使根 Thread 可审批任意深度调用；durable 子 Thread 仍是唯一执行事实。`maxTurns` 软预算达界后每 5 turn 入队 SYSTEM `CUSTOM_MESSAGE` 提醒。
- 恢复（`session_id` = 子 ThreadId，canonical UUID）要求同 parent/root 归属且子 Thread quiescent，resume 用 THREAD target + settings diff + prompt 的用户输入 batch；Stop/取消子 Thread 保留可恢复 Session（`cancelChild` 复用 `HarnessRuntime.stop` 的 `task-{invocationId}-cancel` stopRequestId）。进程内 `SubagentRunRegistry` 在同一 synchronized reservation 中执行每父直接子级上限 `subagentMaxConcurrency`（默认 10）与同 root tree 总上限 `subagentMaxTotalConcurrency`（默认 0；0 表示不额外限制，`SystemSettings.AiRuntime` 的 primitive `int` 必填，非 null），resume 同样占槽并受单飞保护；超限直接拒绝而非排队。两个值由 SystemSettings schema/UI 配置，每个新 turn / 新 task 调用现读；进程重启后的执行事实仍只由 durable Thread 恢复。
- 子 Agent 的工具审批仍复用既有 `decideToolApproval`（以子 ThreadId 定位），approval 事实/`WAITING_APPROVAL` 语义与父 Thread 完全一致；子工具执行经同一 ToolGateway 管线，权限判定同 YOLO/Allow/Ask/Deny 规则。

相关文档：

- [harness-runtime-contracts.md](harness-runtime-contracts.md)
- [harness-storage-runtime.md](harness-storage-runtime.md)
- [harness-capability-wiring.md](harness-capability-wiring.md)
- [environment-daemon-gateway.md](environment-daemon-gateway.md)
