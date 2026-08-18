# Harness Runtime 契约

本文定义当前 Runtime 实现必须遵守的类型、JSON、状态、命令、CAS、replay、快照、异常与 wire 契约。

## 1. 模块与 ID

Runtime 领域包包括 `history`（Entry）、`thread`（Thread/Command/Classifier）、`invocation`（Model/Tool）、`work`、`processor`、`session`（消息语义）、`compaction` 与 `cache`。实体主键（Thread/Session/Entry/Invocation/Command id）在领域模型中是 `UUID`，HTTP wire 上编码为 canonical UUID string。HTTP response DTO 中 Java `long`/`Long` 统一编码为 canonical decimal string；请求 mapper 对 cursor/CAS 字段显式按对应领域范围解析，前端使用 `DecimalLong=string`，禁止先转 JavaScript `number`：

```text
实体 id（threadId/sessionId/entryId/invocationId/clientCommandId/stopRequestId/decisionId） -> canonical UUID
command sequence / nextCommandSequence -> [1-9][0-9]*
revision / afterRevision / Model attempt sequence -> 0|[1-9][0-9]*
```

非法格式由 mapper 抛 `IllegalArgumentException`，Controller 统一映射为 400。Catalog 资源使用永久名称身份：Provider/Agent 是 `name`，Model 是 `(providerName, name)`。

## 2. Session、Entry、Thread

```java
public record ThreadState(
    UUID id,
    UUID headEntryId,        // 本 Thread 当前 head Entry（已提交）
    boolean yoloEnabled,
    long nextCommandSequence, // 必须 >= 1（创建即为 1）
    long revision,           // 必须 >= 0
    Instant createdAt,
    Instant updatedAt) {}
```

- `headEntryId` 必须是本 Thread Session 的已提交 Entry id；Thread 的当前 Session、Environment、status 与 branch settings 由 head Entry 分支派生。
- 已提交 Entry append-only；每个 Session 只有一个无 parent ROOT。
- 创建 Thread 时 `nextCommandSequence=1`、`revision=0`；`validateTransition` 强制每次可见变化 revision 恰好 +1，exact replay 恒接受。

Entry 类型固定为十种：

```java
ROOT, TURN_START, MESSAGE, CUSTOM, MODEL_ATTEMPT_FAILURE, CUSTOM_MESSAGE,
ASSISTANT_ERROR, ASSISTANT_ABORTED, COMPACTION, TURN_END
```

Entry payload 由 `HistoryEntryPayloadJsonCodec` 严格编解码（ROOT/TURN_START 携带 `BranchSettings`；`BranchSettings.environment` 是完整 `EnvironmentBinding{name, workspacePath}` 对象或 null——name 为 canonical bounded 小写路由名称、workspacePath 为 canonical 相对 wire 路径（`'.'` 表示 root），`agentName`/tool 名是 canonical 非空名称）。`ROOT` 额外携带可选的 `subagentContext`（子 Agent Session 冻结委派归属）：

```json
{
  "settings": { "environment": null, "agentName": "...", "model": {...}, "activeTools": [...] },
  "subagentContext": null | { "parentThreadId": "<uuid>", "rootThreadId": "<uuid>", "taskInvocationId": "<uuid>", "depth": 2 }
}
```

普通用户 Session 的 `subagentContext` 为 null；`depth` 以普通根 Thread 为 1，子 Session 从 2 开始。`CUSTOM` payload 为嵌套对象形态：`{"pluginId": "...", "customType": "...", "schemaVersion": 1, "data": {...}}`；`CUSTOM_MESSAGE` payload 为 `{"pluginId": "...", "customType": "...", "rendererKey": "...", "message": {...}, "details": {...}}`（`data`/`details` 是嵌套 JSON object，不是 raw JSON 字符串）。`COMPACTION` payload 固定为 `{phase, trigger, tokensBefore, complete, summaryText, firstKeptEntryId, cutEntryId, turnPrefixStartEntryId}`：`tokensBefore` 是 JSON number，Entry ID 是 canonical UUID string。`CUSTOM` 是透明 branch state：不参与 turn grammar、默认不投影给 provider；插件自行定义 schema，`ContextProjector` 才能把需要的状态显式投影。Goal 使用 `goal/state@1` 完整替换快照，当前 branch 最近一条生效。

Model attempt 审计 payload 由同一 codec 严格编解码：

```json
{
  "attempt": { "attempt": 1, "sequence": 3, "text": "partial", "thinking": "" },
  "error": { "code": "TRANSIENT", "message": "provider disconnected" },
  "retryAt": "2026-08-14T00:00:02Z"
}
```

- `MODEL_ATTEMPT_FAILURE` 的 `Entry.createdAt` 是 `failedAt`；`retryAt >= failedAt`。它只允许出现在普通 open Turn 的 Assistant 结果之前，attempt 是严格连续的 `1..N`，不属于对话语义、不投影给 Provider。
- `ASSISTANT_ERROR` payload 固定为 `{"error":{"code","message"},"attempt":null|{attempt,sequence,text,thinking}}`。planning/Stop/尚未确认 Provider start 的 barrier 使用 null；已确认 attempt 的 terminal Model 错误即使没有 partial 也保存空 text/thinking snapshot，并始终把 partial 与 error 分离。
- `ModelAttemptSnapshot.text/thinking` 始终是非 null 原始字符串；两者可同时为空以表示尚无 partial，纯空白合法且不得 trim。上述 `payloadJson` 内部 sequence 是 codec 的 JSON integer；typed snapshot DTO 的 sequence 则是 bigint-safe decimal string。

## 3. 命令 batch

七类命令：

```java
USER_MESSAGE, CUSTOM_MESSAGE, SET_ENVIRONMENT, SET_AGENT, SET_MODEL,
SET_ACTIVE_TOOLS, SET_YOLO
```

请求 wire（`HarnessThreadCommandBatchDTO`）：

```json
{
  "expectedHeadEntryId": "00000000-0000-0000-0000-000000000003",
  "expectedNextCommandSequence": "3",
  "commands": [
    { "type": "SET_AGENT", "clientCommandId": "00000000-0000-0000-0000-000000000101", "agentName": "coder" },
    {
      "type": "USER_MESSAGE",
      "clientCommandId": "00000000-0000-0000-0000-000000000102",
      "contents": [
        { "type": "TEXT", "text": "describe these references" },
        { "type": "ATTACHMENT", "uploadId": "00000000-0000-0000-0000-000000000200" }
      ]
    }
  ]
}
```

契约：

- `expectedHeadEntryId` / `expectedNextCommandSequence` 是 exact CAS cursors，读取自最新 snapshot DTO；无 batch 级 identity 字段。
- `commands` 非空；每个 command 必须有 canonical UUID `clientCommandId`（thread 内唯一，幂等键）；同 batch 内不得重复。
- `USER_MESSAGE` 必须且只能携带一个非空、有序的 `contents` 列表，**不携带 role**（role 恒为 USER）；`contents` 元素只允许 `TEXT(text)` 与 `ATTACHMENT(uploadId)`（READY upload 的 canonical UUID string，入队事务内原子消费物化为 durable `resource(blobId,name,preview)`），未知字段、未知类型、空 `contents` 与非 canonical uploadId 一律拒绝。`text`/`content` 文本 shorthand 已移除：`text` 按未知字段拒绝、`content` 对 USER_MESSAGE 禁用。
- `CUSTOM_MESSAGE` 携带 `content` 与 `role: "SYSTEM" | "USER"`（大写枚举，strict mapper 拒绝其他值）。
- `SET_AGENT` 携带 `agentName`；`SET_MODEL` 携带 `model`（providerName/modelName/variant）；`SET_ACTIVE_TOOLS` 携带 `activeTools` 名称列表；`SET_YOLO` 携带 `yoloEnabled`；`SET_ENVIRONMENT` 携带 `environment`（完整 `{name, workspacePath}` 对象或 null）。
- mapper 对每个 discriminator 严格校验：未知 type、未知/缺失字段、非 canonical 值一律 400；`USER_MESSAGE` 之外的命令 payload 拒绝 `contents`（`role` 仅 `CUSTOM_MESSAGE` 允许）等不相关字段，未知字段（含 `text`）一律拒绝。

### Ordered command-set replay

幂等查找发生在任何 head/sequence/live 检查之前；**没有 batch 级 identity**，replay 是 ordered command-set replay：

- **全部 `clientCommandId` 已存在**：仅当每个存储 `requestHash` 与本次 raw 请求 hash 相同，且存储 sequence 在请求顺序上连续（`seq[i] == seq[0] + i`）时接受——**忽略 `expectedHeadEntryId`/`expectedNextCommandSequence` 与 QUEUED/APPLIED/CANCELLED lifecycle**，返回原行不变。hash 独立于 ATTACHMENT 消费后的 durable payload 形态。
- 仅部分 id 存在 → `PARTIAL_COMMAND_REPLAY`（缺失命令永不补齐）；已存在 id 但 hash 不同 → `COMMAND_ID_REUSED`；id 全部存在、hash 相同但 sequence 非连续 → `COMMAND_REPLAY_ORDER_MISMATCH`。

### Fresh batch admission

全新 batch 才做双 cursor CAS（任一不匹配 → `STALE_COMMAND_CURSOR` 409），成功后一次性预留全部 sequence（`nextCommandSequence += commands.length`，revision +1）、全部命令写入 QUEUED、请求 THREAD Work，整体原子提交。

包含 `SET_ENVIRONMENT` 的全新 batch 额外要求**真正静止的前置状态**：无 queued `USER_MESSAGE` / `CUSTOM_MESSAGE`、共享 classifier 结果为 `IDLE_OR_HISTORICAL`、且 **THREAD Work 行完全不存在**（行存在即 fence 投机 Resolver/runnable mailbox，无论是否已 lease）。Exact replay 绕过该 admission 检查。

## 4. Snapshot

`GET /{threadId}/snapshot` 返回单事务一致投影：

```json
{
  "revision": "7",
  "thread": { "threadId": "00000000-0000-0000-0000-000000000001", "sessionId": "00000000-0000-0000-0000-000000000002", "headEntryId": "00000000-0000-0000-0000-000000000005",
              "yoloEnabled": false, "nextCommandSequence": "4", "revision": "7",
              "status": "MODEL_READY", "processing": true, "branchSettings": {...},
              "createTime": "...", "updateTime": "..." },
  "entries": [ ... root-to-head path ... ],
  "queuedCommands": [ ... 未消费未取消 ... ],
  "modelInvocation": null | { ... },
  "toolInvocations": [],
  "modelAttemptFailures": [
    {
      "modelInvocationId": "00000000-0000-0000-0000-000000000010",
      "turnStartEntryId": "00000000-0000-0000-0000-000000000004",
      "basisHeadEntryId": "00000000-0000-0000-0000-000000000003",
      "attempt": 1,
      "sequence": "3",
      "text": "partial",
      "thinking": "",
      "errorCode": "TRANSIENT",
      "errorMessage": "provider disconnected",
      "failedAt": "...",
      "retryAt": "..."
    }
  ]
}
```

- `entries` 是当前 head 的 root-to-head path（recursive CTE 顺序）。
- `modelInvocation` 是当前 open Turn 的活跃 Model（**单数**；无则 null）；`toolInvocations` 是其 Tool siblings。`modelAttemptFailures` 只在 `ModelActive` / `ModelTerminalPending` context 暴露该 invocation 尚未物化的连续 TRANSIENT 失败前缀，按 attempt 递增；compaction、Tool、IDLE/historical/continuation context 返回空列表。
- `modelAttemptFailures[].sequence` 是 canonical 非负 `DecimalLong`，可以超过 JavaScript safe integer；前端必须按 `/^(?:0|[1-9]\d*)$/` 验证，非法 item fail closed 且不得据此隐藏 realtime overlay。
- `status` 与 `processing` 是派生展示字段（`IDLE / CONTINUATION_DUE / MODEL_<status> / TOOL_<status> / APPLYING`；仅 IDLE 时 `processing=false`），不是 durable 列。
- `queuedCommands` 只包含 `consumed_turn_start_entry_id is null and cancelled_at is null` 的命令。

## 5. 异常映射

| 情形 | HTTP |
| --- | --- |
| DTO 字段非法、实体 id 非 canonical UUID、sequence/revision 非 strict decimal、未知命令 type、非 canonical 名称 | 400 |
| 作为请求目标的 Thread/Entry 不存在（snapshot、commands、head、stop 路径） | 404 |
| 命令 cursor 过期（`STALE_COMMAND_CURSOR`）、revision 过期（`STALE_REVISION`）、Thread 非 quiescent、terminal apply pending、跨 Session move、move 到 continueModel TURN_END、ordered replay 冲突 | 409 |
| approval target 不存在 / 不属于本 Thread / 无 required approval / 不在适用上下文（`APPROVAL_NOT_APPLICABLE`） | 409 |
| approval 已决定且请求未精确 replay 存储决策（`APPROVAL_DECISION_MISMATCH`） | 409 |
| 命令 batch 被接受进入 mailbox | 202 |
| Thread 创建成功 | 201 |

typed 冲突 reason 全集：`STALE_REVISION`、`STALE_COMMAND_CURSOR`、`COMMAND_ID_REUSED`、`PARTIAL_COMMAND_REPLAY`、`COMMAND_REPLAY_ORDER_MISMATCH`、`THREAD_NOT_QUIESCENT`、`TERMINAL_APPLY_PENDING`、`MOVE_TARGET_CROSS_SESSION`、`MOVE_TARGET_HAS_CONTINUATION_OBLIGATION`、`STOP_REQUEST_ID_REUSED`、`APPROVAL_NOT_APPLICABLE`、`APPROVAL_DECISION_MISMATCH`。409 的统一错误信封在 `errors.reason` 暴露该稳定枚举，并在 `errors.detail` 保留诊断文本；客户端只能按稳定 reason 做恢复决策。持久化不变量破坏（错误 ownership、mixed sibling、非连续 ordinal、count mismatch）保持 `IllegalStateException`，绝不降级为业务冲突。注意 approval 路径的 Thread/target 缺失同样映射 409（`APPROVAL_NOT_APPLICABLE`），不是 404。

## 6. MOVE_HEAD

```java
public record MoveHeadCommand(UUID threadId, UUID targetEntryId, long expectedRevision) {}
```

- 同 target → no-op（返回当前 Thread，不 bump revision）。
- revision CAS；target 必须存在；**target 必须与当前 head 同 Session**（`MOVE_TARGET_CROSS_SESSION`）。
- 必须无 queued commands、无 live Model/Tool context、无 terminal apply pending（`THREAD_NOT_QUIESCENT` / `TERMINAL_APPLY_PENDING`）。
- 不能指向 `continueModel=true` 的 TURN_END（`MOVE_TARGET_HAS_CONTINUATION_OBLIGATION`）。
- 成功后 `advanceHead`（revision +1）并删除 THREAD Work。

## 7. Stop

```java
public record StopCommand(UUID threadId, UUID stopRequestId, long expectedRevision) {}
```

协议（在 Thread 行锁内）：

```text
lock Thread
  -> load head path
  -> findReplay(threadId, stopRequestId)   // 在 revision CAS 之前
  -> 命中 -> REPLAYED（返回被重放的 stoppedTurnEndEntryId，cancelledCommandCount=0，零 mutation）
  -> revision CAS（STALE_REVISION 409）
  -> 无 live Turn（IDLE）：
       有 queued Commands -> 取消它们（cancelled_at），revision +1（touchRevision），
                            不写任何 stop marker，返回 IDLE + cancelledCommandCount
       无 queued          -> 真正 no-op（revision 不变，返回原 Thread）
  -> CONTINUATION_DUE：
       追加 TURN_START(CONTINUATION) + ASSISTANT_ERROR(CANCELLED)
       再追加 TURN_END(STOPPED, USER_STOP)
  -> Model/Tool active：
       先按 attempt 顺序物化尚未落 Entry 的 MODEL_ATTEMPT_FAILURE
       有安全 text/thinking -> ASSISTANT_ABORTED
       Model 无安全内容      -> CANCELLED barrier（ASSISTANT_ERROR）
       Tool siblings         -> 按当前状态写 CANCELLED/UNKNOWN Tool Result
       追加 TURN_END(STOPPED, USER_STOP)
       取消全部 queued Commands（cancelled_at）
       更新 Thread（advanceHead，revision +1）并清理/唤醒 Work
```

`StopResult.Status`：`STOPPED`（本次停止了一个 Turn）、`IDLE`（无 live Turn：可取消 queued 且 revision +1，不写 durable stop 标记；无 queued 时真正 no-op）、`REPLAYED`（精确重放先前 Stop，不取消命令）。客户端重试必须发送**完全相同**的 `stopRequestId` 与**原始** `expectedRevision`；服务端 replay 先于 CAS，因此原始 revision 重试恒安全。

## 8. Tool approval

输入 wire（`HarnessToolApprovalDTO`）：

```json
{
  "decision": "ALLOW" | "DENY",
  "decisionId": "00000000-0000-0000-0000-000000000301",
  "actor": "web",
  "reason": null | "text"
}
```

durable `approval` JSON 使用领域枚举 `ALLOWED` / `DENIED`（不是输入值）。契约：

- 未决定请求必须命中锁定的 TOOL_ACTIVE 上下文中的 `WAITING_APPROVAL` Invocation；mutation 与 `decidedAt` 抬升到已锁定
  Thread/head/Model/siblings/approval 的最新 durable 时间，Work request 保持原始本地调度时钟；`ALLOWED` → `READY`（请求 TOOL
  Work），`DENIED` → `FAILED`（请求 THREAD Work）；revision 恰好 touch 一次。
- 已决定请求按 `(threadId, toolInvocationId, decisionId)` 精确 replay：返回当前锁定 Invocation（原 `decidedAt` 保留），无 revision bump、无 Work 请求；不一致 → `APPROVAL_DECISION_MISMATCH` 409。
- 前端对同一 decision 复用同一 `decisionId`；切换 decision 时 mint 新 ID。

## 9. Invocation 冻结请求

`ModelInvocationRequest`：

```java
public record ModelInvocationRequest(
    EnvironmentBinding environment,  // 本请求的单一 Environment binding（可 null）
    ProviderRequest providerRequest,  // exact Provider transport payload
    List<ToolBinding> toolBindings,
    List<SkillBinding> skillBindings,
    List<SubagentBinding> subagentBindings,
    boolean yoloEnabled,
    int contextWindow,
    CompactionRequest compaction) {}
```

- `providerRequest.tools` 与 `toolBindings` 必须数量、顺序、名称一一对应；tool/skill/subagent binding 名称各自不得重复；每个 environment-bound tool/skill 必须引用本请求 route。
- `SubagentBinding(name, description)`：`name` 是 canonical 非空短名（≤64 字符），`description` 是可空展示描述快照（≤512 字符）；随 request 冻结，task 执行绝不依据后续 Agent 配置扩权。
- `contextWindow` 是创建时冻结的正 int；threshold、retention 与 overflow retry 均使用该值，不受后续 Model config 修改影响。
- `compaction == null` 表示正常调用；非 null 时 tool/skill/subagent/provider tools 必须全部为空，并冻结 phase/trigger/tokensBefore/firstKept/cut/prefix。`tokensBefore` 是 JSON number，三个 Entry ID 是 canonical UUID strings。
- `ToolBinding(descriptor, type, environment, plugin)`：`PLATFORM` binding 的 environment 为 null，`ENVIRONMENT` binding 指向具体 binding（可为 null）；descriptor 的 type 与 binding type 一致。
- `plugin` 为 null 或 `PluginToolBinding(pluginId, contributionLocalName, stateAccesses)`；仅 `PLATFORM` 可携带 plugin，identifier 必须 canonical，state accesses 按 customType 唯一且 mode 仅 `READ` / `WRITE`。该 provenance 随 request 冻结，retry 不按工具名重新归属。
- `ModelDescriptor` 只含 `providerName`/`modelName`/`inputModalities`/`tools`/`reasoning`/`pricing` 六个字段；Provider 连接事实与 cache capability 在每次 attempt 由 Core 按当前 `agent_provider` 行解析（见 [harness-capability-wiring.md](harness-capability-wiring.md)）。
- retry 重放同一份 frozen request；当前失败 attempt 的 text/thinking/error 不修改该 request。后续 turn 的 `DatabaseTurnResolver` 只白名单投影 MESSAGE/CUSTOM_MESSAGE/ASSISTANT_ABORTED 与插件 ContextProjector，`MODEL_ATTEMPT_FAILURE` / `ASSISTANT_ERROR` 永不进入 Provider messages。ToolInvocation 执行同一 binding，不从最新 Agent/Environment 重新选择。
- JSON codec 只接受固定顶层字段并严格校验嵌套结构（未知字段拒绝）。

### Tool success effects

`ToolInvocation.effects` 是非 null 的 `ToolEffectBatch`，wire 固定为：

```json
{
  "version": 1,
  "customEntries": [
    {
      "pluginId": "example",
      "customType": "snapshot",
      "schemaVersion": 1,
      "data": {}
    }
  ]
}
```

- `customEntries` 有序且最多 16 项；普通 Tool 使用空 batch。
- 只有 `SUCCEEDED` 可携带非空 effects；`FAILED` / `CANCELLED` / `UNKNOWN` 与所有非 terminal 状态必须为空。
- success 的 `result + effects + status` 在同一次 Store update 中原子持久化；terminal transition 不得修改 effects。
- apply 时唯一 `ToolOutcomeAppender` 先按 effects 顺序追加 CUSTOM，再追加 Tool Result Entry；`resultEntryId` 始终指向 Tool Result，而不是最后一个 CUSTOM。正常 Thread apply 与 Stop winner 共用该实现。
- 同一 Assistant sibling 的 frozen state accesses 按 ordinal 静态检查：某个 `(pluginId, customType)` 出现 WRITE 后，后续 READ/WRITE invocation 直接成为 `FAILED(kind=SIBLING_STATE_CONFLICT)`，不 dispatch；READ+READ、READ→WRITE、不同 key、不同 pluginId 均允许。

### task 工具契约

`task` 是内部 `PLATFORM` Tool（`rendererKey=task`、`NON_IDEMPOTENT`），其调用与结果仍是普通 ToolInvocation/TOOL MESSAGE 事实，不引入新表或新状态机：

- 运行中进度以**非 durable** Redis `TOOL_PARTIAL` 心跳发布（约 1s 一次），`details.kind=task.status`，payload 是**完整 JSON 快照**：`{threadId, subagentType, state, depth, turns, toolCalls, lastActivity, approvals[], descendants[]}`；`state` 为 `queued` / `running_model` / `running_tool` / `waiting_approval`，`approvals[]` 项为 `{invocationId, toolName, reason}`（该状态 Thread 的待决审批），`descendants[]` 是应用相同字段契约的扁平活动子树状态。descendant relay 仅用于实时呈现与审批寻址，不参与调度、并发计数或终态判定；前端按规范化完整快照整帧替换/语义去重，绝不追加或 delta 合并。
- 终态 ToolResult 文本为 `<task id="..." state="completed|error|cancelled">` envelope：成功含 `<task_result>` 报告，失败/取消含 `<task_error>`（报告正文最多保留 8000 字符）；`details.kind=task.result`，`details` 携带 `threadId`/`subagentType`/`state`。`id` 即子 ThreadId（canonical UUID），可作 `session_id` 恢复。
- 恢复契约：`session_id` 必须指向同 parent/root 归属的既有子 Session，且子 Thread quiescent；`maxTurns` 是软预算——达到后每 5 turn 注入一条 SYSTEM `CUSTOM_MESSAGE` 提醒（`rendererKey=message`），不是硬终止。

`ProviderResponse` 是 terminal `resultJson` 的 canonical shape（stop reason 与 tool calls 正交）：

```java
public record ProviderResponse(
    String text, String thinking,
    List<ProviderToolCall> toolCalls,
    GenerationStopReason stopReason,
    ModelUsage usage, ModelCost cost,
    String requestId, String serviceTier, String rawUsageJson) {}
```

### Durable compaction

- `TurnStartReason.COMPACTION` 消费零 Command，candidate path 只追加 TURN_START；`CompactionPreparation` 作为 transient plan 事实传给 Resolver，并与 frozen `CompactionRequest` 逐字段机械比对。
- 成功结果只能是 `CompactionPayload`；正常 invocation 不能挂 COMPACTION，compaction invocation 不能挂普通 Assistant MESSAGE。Store 要求 invocation 已 SUCCEEDED、payload metadata 与 request 精确相等，且 firstKept/cut/prefix 都在 result 前并满足 `firstKept <= cut`、`prefix < cut`。
- phase/complete 固定：HISTORY 为 incomplete；FULL/TURN_PREFIX 为 complete。completed TURN_END 只有 complete OVERFLOW 允许 `continueModel=true`。
- HISTORY 之后只读取紧邻、已完成且 metadata 匹配的 partial；TURN_PREFIX 不扫描更早 stale partial。direct TURN_PREFIX 的 history 文本固定为 `No prior history.`。
- Threshold freshness 只被 complete CompactionPayload 消费；普通 failed invocation 与无 invocation 的 Resolver Rejected 不覆盖当前 Thread 最新成功 usage，FAILED/STOPPED/CANCELLED/incomplete compaction 只阻止立即原地重试。若 turn 存在其它 Thread 的 ModelInvocation，则作为 shared-history ownership barrier。
- `<read-files>` / `<modified-files>` 是 Runtime-owned reserved section：previous summary 入 prompt 前剥离，response 在 terminal success 前校验并剥离，最后只追加一次从完整 durable branch history 重算的 canonical 清单；modified 覆盖 read，空白或破坏 reserved 标签结构的 path 忽略。
- immediate overflow recovery continuation 再次 OVERFLOW 时不再压缩；该失败 Entry/TURN_END 保持 durable。

## 10. TurnResolver

```java
sealed interface TurnResolver.Result
    permits Resolved, Rejected {}

record Resolved(ModelInvocationRequest request) {}
record Rejected(AssistantError error) {}   // 确定性拒绝：写入 durable barrier
```

- 同步、无副作用、事务外：`resolve(threadId, candidatePath, yoloEnabled, compactionPreparation)`；调用方在短事务内锁 Thread、捕获 Command 快照与 YOLO、分配 Entry ID 并构造 candidate path 后调用；实现只读最新 Catalog/Environment 事实，不写 Store、不持有行锁、不得按 candidate Entry ID 回查 Store。
- 抛异常表示临时基础设施失败，由 Processor reschedule；`Rejected` 产生 `AssistantError` barrier（`ASSISTANT_ERROR` + `FAILED` TURN_END），不产生 ModelInvocation。
- 所有确定性拒绝共用稳定 `AssistantError` code `PLANNING_FAILED`，message 携带具体原因。
- **Environment route 规则（工具）**：ENVIRONMENT 工具一律按最新 `BranchSettings.environment()` 完整 binding 绑定（可为 null/缺失/未 READY），规划阶段**绝不拒绝**；实际 Tool start 时按冻结 binding 确定性判定——null binding 或目标不可用（未注册/未 READY/心跳过期）→ `Rejected`（`UNAVAILABLE`），durable `FAILED` ToolResult 对模型可见，turn 正常收敛。绝不回看更旧的 branch settings。
- **最新 Agent 工具规则**：每个新 turn 从最新 Agent config 派生工具集合；`config.tools` 按原顺序绑定，skills 非空时追加内部 `load_skill`，subagents 非空且当前 Session depth 小于 `maxDepth` 时追加内部 `task`。历史 `BranchSettings.activeTools` 不限制也不扩张本 turn。
- **Environment route 规则（skills）**：Agent skills 必须由**最新选中** Environment 精确提供且可用（缺失 → `agent skills require the latest selected environment which is not live: <name>`；未 READY → `...which is not ready: <name>`；分支无 binding → `...but the branch has no environment`）；`load_skill` 不在 selectable catalog。
- **Subagent 规则（task）**：allowlist 每个名称必须解析到现存 Agent（缺失 → `subagent not found: <name>`），名称 + 描述（可空）冻结为有序 `subagentBindings`；达到最大深度时不暴露 `task`；执行绝不重读父 Agent 配置扩权。

## 11. Realtime

- `RealtimeEventSink.append` 只写 bounded Redis projection；sink 失败不改变 durable terminal。
- 浏览器经应用事件 WebSocket（`/api/events/v1`，见 [application-event-channel.md](application-event-channel.md)）订阅：`revision`/`version` 事件携带 durable cursor（canonical 非负十进制），`realtime` 事件的 data 是 Redis delta envelope JSON 对象且不携带 cursor。
- 客户端恢复顺序：REST snapshot → 应用事件通道订阅（`subscribed` ack 携带建立瞬间 cursor）→ Redis realtime overlay；durable revision 是唯一恢复游标。
- 前端把 `resultJson`/`errorJson` 当作 terminal 边界：durable terminal projection 无条件压过更高 sequence 的 Redis overlay；`resultEntryId` 落地后移除 overlay。
- snapshot failure 以 `(modelInvocationId, attempt)` fence 同 attempt 的 stale Model overlay；只有 invocation 仍处于相同 attempt 的 READY/DISPATCHING 时该 failure 是 live retry countdown，下一 attempt 已 RUNNING 后转为静态历史。终态 error 将 checkpoint partial 与 `errorJson` 分开投影，刷新后由 `MODEL_ATTEMPT_FAILURE` / `ASSISTANT_ERROR.attempt` 恢复相同可见轨迹。
- Runtime 不向 RealtimeEventSink 发布 compaction ModelDelta；其 checkpoint 仅作 Stop/恢复 durable fact。前端再按 `TURN_START(COMPACTION)...TURN_END` 状态化抑制该 turn 的 COMPACTION/ERROR/ABORTED Entry，latest turn 是 COMPACTION 时也不渲染 snapshot Model overlay。
