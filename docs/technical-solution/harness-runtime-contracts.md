# Harness Runtime 契约

本文定义当前 Runtime 实现必须遵守的类型、JSON、状态、命令、CAS、replay、快照、异常与 wire 契约。

## 1. 模块与 ID

Runtime 领域包包括 `history`（Entry）、`thread`（Thread/Command/Classifier）、`invocation`（Model/Tool）、`work`、`processor`、`session`（消息语义）与 `cache`。Runtime 内部使用 `long` durable ID；HTTP 边界使用 strict decimal strings：

```text
id / sequence          -> [1-9][0-9]*
revision / afterRevision -> 0|[1-9][0-9]*
```

非法格式由 mapper 抛 `IllegalArgumentException`，Controller 统一映射为 400。Catalog 资源使用永久名称身份：Provider/Agent 是 `name`，Model 是 `(providerName, name)`。

## 2. Session、Entry、Thread

```java
public record ThreadState(
    long id,
    long headEntryId,        // 必须为正
    boolean yoloEnabled,
    long nextCommandSequence, // 必须 >= 1（创建即为 1）
    long revision,           // 必须 >= 0
    Instant createdAt,
    Instant updatedAt) {}
```

- `headEntryId` 必须为正；Thread 的当前 Session、Environment、status 与 branch settings 由 head Entry 分支派生。
- 已提交 Entry append-only；每个 Session 只有一个无 parent ROOT。
- 创建 Thread 时 `nextCommandSequence=1`、`revision=0`；`validateTransition` 强制每次可见变化 revision 恰好 +1，exact replay 恒接受。

Entry 类型固定为八种：

```java
ROOT, TURN_START, MESSAGE, CUSTOM, CUSTOM_MESSAGE, ASSISTANT_ERROR, ASSISTANT_ABORTED, TURN_END
```

Entry payload 由 `HistoryEntryPayloadJsonCodec` 严格编解码（ROOT/TURN_START 携带 `BranchSettings`；`BranchSettings.environmentId` 是 canonical 非 nil UUID 或 null，`agentName`/`thinkingLevel`/tool 名是 canonical 非空名称）。`CUSTOM` payload 为嵌套对象形态：`{"pluginId": "...", "customType": "...", "schemaVersion": 1, "data": {...}}`；`CUSTOM_MESSAGE` payload 为 `{"pluginId": "...", "customType": "...", "rendererKey": "...", "message": {...}, "details": {...}}`（`data`/`details` 是嵌套 JSON object，不是 raw JSON 字符串）。`CUSTOM` 是透明 branch state：不参与 turn grammar、默认不投影给 provider。

## 3. 命令 batch

八类命令：

```java
USER_MESSAGE, CUSTOM_MESSAGE, SET_ENVIRONMENT, SET_AGENT, SET_MODEL,
SET_THINKING_LEVEL, SET_ACTIVE_TOOLS, SET_YOLO
```

请求 wire（`HarnessThreadCommandBatchDTO`）：

```json
{
  "expectedHeadEntryId": "1",
  "expectedNextCommandSequence": "3",
  "commands": [
    { "type": "SET_AGENT", "clientCommandId": "cid-1", "agentName": "coder" },
    { "type": "USER_MESSAGE", "clientCommandId": "cid-2", "content": "hello" }
  ]
}
```

契约：

- `expectedHeadEntryId` / `expectedNextCommandSequence` 是 exact CAS cursors，读取自最新 snapshot DTO；无 batch 级 identity 字段。
- `commands` 非空；每个 command 必须有非空 `clientCommandId`（thread 内唯一，幂等键）；同 batch 内 `clientCommandId` 不得重复。
- `USER_MESSAGE` 携带 `content`，**不携带 role**（role 恒为 USER，strict mapper 拒绝多余字段）；`CUSTOM_MESSAGE` 携带 `content` 与 `role: "SYSTEM" | "USER"`（大写枚举，strict mapper 拒绝其他值）。
- `SET_AGENT` 携带 `agentName`；`SET_MODEL` 携带 `model`（providerName/modelName/variant）；`SET_THINKING_LEVEL` 携带 `thinkingLevel`；`SET_ACTIVE_TOOLS` 携带 `activeTools` 名称列表；`SET_YOLO` 携带 `yoloEnabled`；`SET_ENVIRONMENT` 携带 `environmentId`（canonical lowercase nonnil UUID 或 null）。
- mapper 对每个 discriminator 严格校验：未知 type、未知/缺失字段、非 canonical 值一律 400；`USER_MESSAGE` 之外的命令 payload 拒绝 `role`/`content` 等不相关字段。

### Ordered command-set replay

幂等查找发生在任何 head/sequence/live 检查之前；**没有 batch 级 identity**，replay 是 ordered command-set replay：

- **全部 `clientCommandId` 已存在**：仅当每个存储 payload 与请求 payload 相同，且存储 sequence 在请求顺序上连续（`seq[i] == seq[0] + i`）时接受——**忽略 `expectedHeadEntryId`/`expectedNextCommandSequence` 与 QUEUED/APPLIED/CANCELLED lifecycle**，返回原行不变。
- 仅部分 id 存在 → `PARTIAL_COMMAND_REPLAY`（缺失命令永不补齐）；已存在 id 但 payload 不同 → `COMMAND_ID_REUSED`；id 全部存在、payload 相同但 sequence 非连续 → `COMMAND_REPLAY_ORDER_MISMATCH`。

### Fresh batch admission

全新 batch 才做双 cursor CAS（任一不匹配 → `STALE_COMMAND_CURSOR` 409），成功后一次性预留全部 sequence（`nextCommandSequence += commands.length`，revision +1）、全部命令写入 QUEUED、请求 THREAD Work，整体原子提交。

包含 `SET_ENVIRONMENT` 的全新 batch 额外要求**真正静止的前置状态**：无 queued USER/CUSTOM 消息、共享 classifier 结果为 `IDLE_OR_HISTORICAL`、且 **THREAD Work 行完全不存在**（行存在即 fence 投机 Resolver/runnable mailbox，无论是否已 lease）。Exact replay 绕过该 admission 检查。

## 4. Snapshot

`GET /{threadId}/snapshot` 返回单事务一致投影：

```json
{
  "revision": "7",
  "thread": { "threadId": "1", "sessionId": "2", "headEntryId": "5",
              "yoloEnabled": false, "nextCommandSequence": "4", "revision": "7",
              "status": "TOOL_RUNNING", "processing": true, "branchSettings": {...},
              "createTime": "...", "updateTime": "..." },
  "entries": [ ... root-to-head path ... ],
  "queuedCommands": [ ... 未消费未取消 ... ],
  "modelInvocation": null | { ... },
  "toolInvocations": [ ... ]
}
```

- `entries` 是当前 head 的 root-to-head path（recursive CTE 顺序）。
- `modelInvocation` 是当前 open Turn 的活跃 Model（**单数**；无则 null）；`toolInvocations` 是其 Tool siblings；IDLE/historical/continuation 快照不暴露 Invocation。
- `status` 与 `processing` 是派生展示字段（`IDLE / CONTINUATION_DUE / MODEL_<status> / TOOL_<status> / APPLYING`；仅 IDLE 时 `processing=false`），不是 durable 列。
- `queuedCommands` 只包含 `consumed_turn_start_entry_id is null and cancelled_at is null` 的命令。

## 5. 异常映射

| 情形 | HTTP |
| --- | --- |
| DTO 字段非法、id/revision 非 strict decimal、未知命令 type、非 canonical 名称/UUID | 400 |
| 作为请求目标的 Thread/Entry 不存在（snapshot、commands、head、stop 路径） | 404 |
| 命令 cursor 过期（`STALE_COMMAND_CURSOR`）、revision 过期（`STALE_REVISION`）、Thread 非 quiescent、terminal apply pending、跨 Session move、move 到 continueModel TURN_END、ordered replay 冲突 | 409 |
| approval target 不存在 / 不属于本 Thread / 无 required approval / 不在适用上下文（`APPROVAL_NOT_APPLICABLE`） | 409 |
| approval 已决定且请求未精确 replay 存储决策（`APPROVAL_DECISION_MISMATCH`） | 409 |
| 命令 batch 被接受进入 mailbox | 202 |
| Thread 创建成功 | 201 |

typed 冲突 reason 全集：`STALE_REVISION`、`STALE_COMMAND_CURSOR`、`COMMAND_ID_REUSED`、`PARTIAL_COMMAND_REPLAY`、`COMMAND_REPLAY_ORDER_MISMATCH`、`THREAD_NOT_QUIESCENT`、`TERMINAL_APPLY_PENDING`、`MOVE_TARGET_CROSS_SESSION`、`MOVE_TARGET_HAS_CONTINUATION_OBLIGATION`、`STOP_REQUEST_ID_REUSED`、`APPROVAL_NOT_APPLICABLE`、`APPROVAL_DECISION_MISMATCH`。持久化不变量破坏（错误 ownership、mixed sibling、非连续 ordinal、count mismatch）保持 `IllegalStateException`，绝不降级为业务冲突。注意 approval 路径的 Thread/target 缺失同样映射 409（`APPROVAL_NOT_APPLICABLE`），不是 404。

## 6. MOVE_HEAD

```java
public record MoveHeadCommand(long threadId, long targetEntryId, long expectedRevision) {}
```

- 同 target → no-op（返回当前 Thread，不 bump revision）。
- revision CAS；target 必须存在；**target 必须与当前 head 同 Session**（`MOVE_TARGET_CROSS_SESSION`）。
- 必须无 queued commands、无 live Model/Tool context、无 terminal apply pending（`THREAD_NOT_QUIESCENT` / `TERMINAL_APPLY_PENDING`）。
- 不能指向 `continueModel=true` 的 TURN_END（`MOVE_TARGET_HAS_CONTINUATION_OBLIGATION`）。
- 成功后 `advanceHead`（revision +1）并删除 THREAD Work。

## 7. Stop

```java
public record StopCommand(long threadId, String stopRequestId, long expectedRevision) {}
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
  "decisionId": "stable-client-key",
  "actor": "web",
  "reason": null | "text"
}
```

durable `approval` JSON 使用领域枚举 `ALLOWED` / `DENIED`（不是输入值）。契约：

- 未决定请求必须命中锁定的 TOOL_ACTIVE 上下文中的 `WAITING_APPROVAL` Invocation；`ALLOWED` → `READY`（请求 TOOL Work），`DENIED` → `FAILED`（请求 THREAD Work）；revision 恰好 touch 一次。
- 已决定请求按 `(threadId, toolInvocationId, decisionId)` 精确 replay：返回当前锁定 Invocation（原 `decidedAt` 保留），无 revision bump、无 Work 请求；不一致 → `APPROVAL_DECISION_MISMATCH` 409。
- 前端对同一 decision 复用同一 `decisionId`；切换 decision 时 mint 新 ID。

## 9. Invocation 冻结请求

`ModelInvocationRequest`：

```java
public record ModelInvocationRequest(
    EnvironmentId environmentId,      // 本请求的单一 Environment route（可 null）
    ProviderRequest providerRequest,  // exact Provider transport payload
    List<ToolBinding> toolBindings,
    List<SkillBinding> skillBindings,
    boolean yoloEnabled) {}
```

- `providerRequest.tools` 与 `toolBindings` 必须数量、顺序、名称一一对应；tool/skill binding 名称不得重复；每个 environment-bound tool/skill 必须引用本请求 route。
- `ToolBinding(descriptor, type, environmentId)`：`PLATFORM` binding 的 environmentId 为 null，`ENVIRONMENT` binding 指向具体 route；descriptor 的 type 与 binding type 一致。
- `ModelDescriptor` 只含 `providerName`/`modelName`/`tools`/`reasoning`/`pricing` 五个字段；Provider 连接事实与 cache capability 在每次 attempt 由 Core 按当前 `agent_provider` 行解析（见 [harness-capability-wiring.md](harness-capability-wiring.md)）。
- retry 重放同一份 request；ToolInvocation 执行同一 binding，不从最新 Agent/Environment 重新选择。
- JSON codec 只接受固定顶层字段并严格校验嵌套结构（未知字段拒绝）。

`ProviderResponse` 是 terminal `resultJson` 的 canonical shape：

```java
public record ProviderResponse(
    String text, String thinking,
    List<ProviderToolCall> toolCalls,
    ProviderStopReason stopReason,
    ModelUsage usage, ModelCost cost,
    String requestId, String serviceTier, String rawUsageJson) {}
```

## 10. TurnResolver

```java
sealed interface TurnResolver.Result
    permits Resolved, Rejected {}

record Resolved(ModelInvocationRequest request) {}
record Rejected(AssistantError error) {}   // 确定性拒绝：写入 durable barrier
```

- 同步、无副作用、事务外：调用方在短事务内锁 Thread、捕获 Command 快照与 YOLO、分配 Entry ID 并构造 candidate path 后调用；实现只读最新 Catalog/Environment 事实，不写 Store、不持有行锁、不得按 candidate Entry ID 回查 Store。
- 抛异常表示临时基础设施失败，由 Processor reschedule；`Rejected` 产生 `AssistantError` barrier（`ASSISTANT_ERROR` + `FAILED` TURN_END），不产生 ModelInvocation。
- 所有确定性拒绝共用稳定 `AssistantError` code `PLANNING_FAILED`，message 携带具体原因。
- **Environment route 规则**：非 null `environmentId` **无论 activeTools 内容**都必须 registry 精确命中且 READY（`environment not found` / `environment is not ready` 拒绝）；null `environmentId` 只允许 platform-only 且无 skills 的 turn——任何 ENVIRONMENT tool（`environment tool requires a selected environment: <name>`）或 Agent skill（`agent skills require a selected environment`）都是确定性拒绝，绝不静默省略。
- Agent skills 只从 Agent config 读取，必须由选中 READY Environment 精确提供，且 `activeTools` 必须显式包含内部 `load_skill`（`agent has skills but activeTools must include load_skill` 拒绝）；`load_skill` 不是 Resolver 隐式追加，也不在 selectable catalog。

## 11. Realtime

- `RealtimeEventSink.append` 只写 bounded Redis projection；sink 失败不改变 durable terminal。
- revision SSE 帧使用 durable revision 作为 `Last-Event-ID`/`afterRevision` cursor；Redis delta 事件没有 SSE id。
- 客户端恢复顺序：REST snapshot → durable revision SSE → Redis realtime overlay；revision 是唯一 durable cursor。
- 前端把 `resultJson`/`errorJson` 当作 terminal 边界：durable terminal projection 无条件压过更高 sequence 的 Redis overlay；`resultEntryId` 落地后移除 overlay。
