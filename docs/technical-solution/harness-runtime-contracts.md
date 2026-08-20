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
    UUID sessionId,        // 创建后不可变；head 必须属于该 Session
    UUID headEntryId,      // 本 Thread 当前 head Entry（已提交）
    String materializationHash, // 创建请求 canonical SHA-256，仅用于首次 materialization replay
    boolean yoloEnabled,
    long nextCommandSequence, // 必须 >= 1（创建即为 1）
    long revision,           // 必须 >= 0
    Instant createdAt,
    Instant updatedAt) {}
```

- `headEntryId` 必须是本 Thread Session 的已提交 Entry id；Thread 的当前 Session、Environment、status 与 branch settings 由 head Entry 分支派生。
- 已提交 Entry append-only；每个 Session 只有一个无 parent ROOT。
- 创建 Thread 时 `nextCommandSequence=1`、`revision=0`；`validateTransition` 强制每次可见变化 revision 恰好 +1，exact replay 恒接受。
- `materializationHash` 是服务端基于 canonical semantic request 计算的 SHA-256（覆盖 materialization target、Session/起点 Entry/客户端预分配 Thread id、初始 YOLO、ROOT settings/SubagentContext 与第一批 ordered Command 的 `clientCommandId + requestHash`），只用于第一次 materialization 的精确重放与 ID 复用冲突检测，不参与后续执行。

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

普通用户 Session 的 `subagentContext` 为 null；`depth` 以普通根 Thread 为 1，子 Session 从 2 开始。`CUSTOM` payload 为嵌套对象形态：`{"pluginId": "...", "customType": "...", "schemaVersion": 1, "data": {...}}`；`CUSTOM_MESSAGE` payload 为 `{"pluginId": "...", "customType": "...", "rendererKey": "...", "message": {...}, "details": {...}}`（`data`/`details` 是嵌套 JSON object，不是 raw JSON 字符串）。`COMPACTION` payload 固定为 `{"summaryText": "..."}`；`phase/trigger/executionModel/cutEntryId/turnPrefixStartEntryId/historyCompactionEntryId` 全部冻结在对应 `TURN_START` 的 `CompactionStart`（`{"phase": "FULL|HISTORY|TURN_PREFIX", "trigger": "THRESHOLD|OVERFLOW|MANUAL", "executionModel": {...}, "cutEntryId": "<uuid>", "turnPrefixStartEntryId": "<uuid>|null", "historyCompactionEntryId": "<uuid>|null"}`），Entry ID 是 canonical UUID string，不向 payload 重复保存 `complete` boolean（由 phase 派生）。`CUSTOM` 是透明 branch state：不参与 turn grammar、默认不投影给 provider；插件自行定义 schema，`ContextProjector` 才能把需要的状态显式投影。Goal 使用 `goal/state@1` 完整替换快照，当前 branch 最近一条生效。

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

六类命令：

```java
USER_MESSAGE, CUSTOM_MESSAGE, SET_ENVIRONMENT, SET_AGENT, SET_MODEL,
SET_ACTIVE_TOOLS
```

请求 wire（`HarnessCommandBatchDTO`）：

```json
{
  "owner": { "type": "CHAT", "id": "00000000-0000-0000-0000-000000000001" },
  "target": {
    "type": "THREAD",
    "threadId": "00000000-0000-0000-0000-000000000010",
    "expectedHeadEntryId": "00000000-0000-0000-0000-000000000003",
    "expectedNextCommandSequence": "3"
  },
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

- `owner` 是 `{type: CHAT|CANVAS, id}`；服务端在返回任何 replay/Session/Thread/Entry/Command 数据前验证 owner 对目标 Session/Thread 的归属。内部 Task/one-shot 不走 owner HTTP DTO，直接调用 Runtime。
- `target` 是严格三态 union：`NEW_SESSION{sessionId, threadId, rootSettings, yoloEnabled}` / `ENTRY{sessionId, startEntryId, threadId, yoloEnabled}` / `THREAD{threadId, expectedHeadEntryId, expectedNextCommandSequence}`；未知 target 字段拒绝。THREAD 的 `expectedHeadEntryId` / `expectedNextCommandSequence` 是 exact CAS cursors，读取自最新 snapshot DTO；无 batch 级 identity 字段。
- `commands` 非空；每个 command 必须有 canonical UUID `clientCommandId`（thread 内唯一，幂等键）；同 batch 内不得重复。
- `USER_MESSAGE` 必须且只能携带一个非空、有序的 `contents` 列表，**不携带 role**（role 恒为 USER）；元素允许 `TEXT(text)`、`ATTACHMENT(uploadId)` 与 `RESOURCE(blobId,name,preview?)`。ATTACHMENT 的 READY upload 在入队事务内原子消费为 durable resource；RESOURCE 只允许复用目标 Session 已有的 blob ref，不重复 retain，新/跨 Session 引用与未知字段、未知类型、空 contents、非 canonical id 一律拒绝。`text`/`content` shorthand 不属于 wire。
- `CUSTOM_MESSAGE` 携带 `content` 与 `role: "SYSTEM" | "USER"`（大写枚举，strict mapper 拒绝其他值）。
- `SET_AGENT` 携带 `agentName`；`SET_MODEL` 携带 `model`（providerName/modelName/variant）；`SET_ACTIVE_TOOLS` 携带 `activeTools` 名称列表；`SET_ENVIRONMENT` 携带 `environment`（完整 `{name, workspacePath}` 对象或 null）。YOLO 不是 command：`PUT /api/ai/runtime/threads/{threadId}/yolo` 直接更新 Thread policy（见 §3「YOLO 直接控制面」）。
- mapper 对每个 discriminator 严格校验：未知 type、未知/缺失字段、非 canonical 值一律 400；`USER_MESSAGE` 之外的命令 payload 拒绝 `contents`（`role` 仅 `CUSTOM_MESSAGE` 允许）等不相关字段，未知字段（含 `text`）一律拒绝。
- **产品 HTTP shape**：`HarnessCommandCreateDTO` 只接受 `SET_ENVIRONMENT/SET_AGENT/SET_MODEL/SET_ACTIVE_TOOLS/USER_MESSAGE`；`CUSTOM_MESSAGE` 在 HTTP 边界显式拒绝（"not allowed on the product HTTP surface"）。batch 必须满足：SET_* 以固定顺序（SET_ENVIRONMENT → SET_AGENT → SET_MODEL → SET_ACTIVE_TOOLS）、每种至多一次、全部出现在消息之前；**恰有一条末尾 `USER_MESSAGE`**。NEW_SESSION/ENTRY 初始 batch 允许在 user-like message 前携带 SYSTEM `CUSTOM_MESSAGE`（仅受信任内部 Java 调用）；THREAD target 要么是恰一条 SYSTEM `CUSTOM_MESSAGE` steering，要么是不含 SYSTEM CUSTOM_MESSAGE、恰有一条末尾 user-like 的用户 batch。

### Ordered command-set replay

幂等查找发生在任何 head/sequence/live 检查之前；**没有 batch 级 identity**，replay 是 ordered command-set replay：

- **全部 `clientCommandId` 已存在**：仅当每个存储 `requestHash` 与本次 raw 请求 hash 相同，且存储 sequence 在请求顺序上连续（`seq[i] == seq[0] + i`）时接受——**忽略 `expectedHeadEntryId`/`expectedNextCommandSequence` 与 QUEUED/APPLIED/CANCELLED lifecycle**，返回原行不变。hash 独立于 ATTACHMENT 消费后的 durable payload 形态。
- 仅部分 id 存在 → `PARTIAL_COMMAND_REPLAY`（缺失命令永不补齐）；已存在 id 但 hash 不同 → `COMMAND_ID_REUSED`；id 全部存在、hash 相同但 sequence 非连续 → `COMMAND_REPLAY_ORDER_MISMATCH`。

### Fresh batch admission

全新 batch 才做双 cursor CAS（任一不匹配 → `STALE_COMMAND_CURSOR` 409），成功后一次性预留全部 sequence（`nextCommandSequence += commands.length`，revision +1）、全部命令写入 QUEUED、请求 THREAD Work，整体原子提交。

queued `SET_ENVIRONMENT` 只在后续 INPUT 边界消费，因此 enqueue 不再要求当前 Thread quiescent；即使 Thread 处于 live Model/Tool 或已有 queued 消息/THREAD Work 也照常接受。Exact replay 绕过 cursor admission 检查。

### YOLO 直接控制面

`PUT /{threadId}/yolo` body `{expectedRevision, yoloEnabled}`：锁 Thread 后同值请求在任何 revision CAS 之前按原样返回当前 Thread（网络重试 no-op，revision/updatedAt 零触碰）；值变化必须匹配 `expectedRevision`（否则 `STALE_REVISION` 409），随后一个原子步骤更新 `yoloEnabled` 且 revision 精确 +1。返回权威 Thread DTO（与 `POST /{threadId}/compact`/`POST /stop` 一致）。不创建 Command/Entry/Work，不唤醒 processors。

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
  ],
  "manualCompaction": { "available": true, "disabledReason": null }
}
```

- `manualCompaction` 是瞬时 advisory sidecar（`HarnessManualCompactionDTO{available, disabledReason?}`，disabledReason ∈ `THREAD_BUSY / OWNERSHIP_BARRIER / NO_RESOLVED_CONTEXT / MODEL_CHANGED / BELOW_MINIMUM / NOTHING_TO_COMPACT`），每次 snapshot 现算；提交 `POST /{threadId}/compact` 仍以 expectedRevision CAS 守护。

- `entries` 是当前 head 的 root-to-head path（recursive CTE 顺序）。
- `modelInvocation` 是当前 open Turn 的活跃 Model（**单数**；无则 null）；`toolInvocations` 是其 Tool siblings。`modelAttemptFailures` 只在 `ModelActive` / `ModelTerminalPending` context 暴露该 invocation 尚未物化的连续 TRANSIENT 失败前缀，按 attempt 递增；compaction、Tool、IDLE/historical/continuation context 返回空列表。
- `modelAttemptFailures[].sequence` 是 canonical 非负 `DecimalLong`，可以超过 JavaScript safe integer；前端必须按 `/^(?:0|[1-9]\d*)$/` 验证，非法 item fail closed 且不得据此隐藏 realtime overlay。
- `status` 与 `processing` 是派生展示字段（`IDLE / CONTINUATION_DUE / MODEL_<status> / TOOL_<status> / APPLYING`；仅 IDLE 时 `processing=false`），不是 durable 列。
- `queuedCommands` 只包含 `consumed_turn_start_entry_id is null and cancelled_at is null` 的命令。

## 5. 异常映射

| 情形 | HTTP |
| --- | --- |
| DTO 字段非法、实体 id 非 canonical UUID、sequence/revision 非 strict decimal、未知命令 type、非 canonical 名称 | 400 |
| 作为请求目标的 Thread/Entry 不存在（snapshot、system-prompt、compact、stop 路径） | 404 |
| 命令 cursor 过期（`STALE_COMMAND_CURSOR`）、revision 过期（`STALE_REVISION`）、terminal apply pending、materialization ID 复用（`MATERIALIZATION_ID_REUSED`）、ordered replay 冲突 | 409 |
| approval target 不存在 / 不属于本 Thread / 无 required approval / 不在适用上下文（`APPROVAL_NOT_APPLICABLE`） | 409 |
| approval 已决定且请求未精确 replay 存储决策（`APPROVAL_DECISION_MISMATCH`） | 409 |
| 命令 batch 被接受进入 mailbox | 200（返回 `session/rootEntry/thread/acceptedCommands/replayed` 权威投影） |
| 手动压缩 availability 不满足或 expectedRevision 过期 | 409 |

typed 冲突 reason 全集：`STALE_REVISION`、`STALE_COMMAND_CURSOR`、`COMMAND_ID_REUSED`、`PARTIAL_COMMAND_REPLAY`、`COMMAND_REPLAY_ORDER_MISMATCH`、`MATERIALIZATION_ID_REUSED`、`STOP_REQUEST_ID_REUSED`、`APPROVAL_NOT_APPLICABLE`、`APPROVAL_DECISION_MISMATCH`。409 的统一错误信封在 `errors.reason` 暴露该稳定枚举，并在 `errors.detail` 保留诊断文本；客户端只能按稳定 reason 做恢复决策。持久化不变量破坏（错误 ownership、mixed sibling、非连续 ordinal、count mismatch）保持 `IllegalStateException`，绝不降级为业务冲突。注意 approval 路径的 Thread/target 缺失同样映射 409（`APPROVAL_NOT_APPLICABLE`），不是 404。

## 6. Head relocation 不存在

**不存在 `MOVE_HEAD` / `PUT head` / standalone Thread create**。`/tree` 选择历史 Entry 只把 Pane 切换为 `ENTRY_DRAFT(sessionId,startEntryId)`（零数据库写入）；第一次 durable batch 以 ENTRY target 提交时，服务端原子 materialize 一个新 Thread（`headEntryId = startEntryId`，不复制 Entry、不修改任何已有 Thread），旧 Thread 永不 relocation。现有 Thread 的 head 只能由 Runtime 在 turn/compaction/stop 执行中推进到当前 head 的新 descendant。

冲突 reason 中不存在 `MOVE_TARGET_CROSS_SESSION` / `MOVE_TARGET_HAS_CONTINUATION_OBLIGATION` / `THREAD_NOT_QUIESCENT`：materialization 冲突统一表达为 `MATERIALIZATION_ID_REUSED`（同 threadId 不同 session/hash）与 `STALE_COMMAND_CURSOR`（THREAD 全新 batch head/sequence 不匹配）。

## 7. Stop

```java
public record StopCommand(UUID threadId, UUID stopRequestId, long expectedRevision) {}
```

协议（在 Thread 行锁内）：

```text
lock Thread
  -> load head path（root 推导 session）
  -> load session 全部不可变 Entry（含兄弟分支）
  -> findReplay(ownerThreadId == thread.id, stopRequestId)   // 在 revision CAS 之前
  -> 命中 -> REPLAYED（返回与原 Stop 相同的 stoppedTurnEndEntryId /
                       cancelledCommandCount / cancelledUserMessages，零 mutation）
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

Stop 成功关闭 live Turn 的同一事务内：先净化 Work mailbox（deleteWork 的 owner 校验反查 Model/Tool Invocation 行），再删除全部 Tool Invocations、删除父 ModelInvocation（删除前执行与 `ModelAttemptMaterialization` 等价的严格校验），advance Thread；closed turn 不保留任何 Invocation 行，迟到 callback 因行已删除或 claim 失效而 no-op。

`StopResult.Status`：`STOPPED`（本次停止了一个 Turn）、`IDLE`（无 live Turn：可取消 queued 且 revision +1，**不写 durable stop 标记**；无 queued 时真正 no-op，同 `stopRequestId` 再调用仍是 IDLE 而非 REPLAYED）、`REPLAYED`（精确重放先前 receipt，不重复取消命令）。HTTP DTO 固定为 `{status,thread,stoppedTurnEndEntryId,cancelledCommandCount,cancelledUserMessages[]}`，无独立 `replayed` 字段；取消消息按 sequence 升序，以 `{sequence,clientCommandId,messageJson}` 暴露 canonical USER AgentMessage。durable key 是「被关闭 TURN_START 的 `ownerThreadId` + `closeRequestId`」，在 Thread 锁内做 Session 级不可变查找：同 raw `stopRequestId` 只在自己的 turn 上产生 replay，另一 Thread 的相同 raw id 被忽略而非冲突，owning Thread 迁移到同 Session 的兄弟分支后仍可命中。STOPPED 的不确定重试必须发送**完全相同**的 `stopRequestId` 与**原始** `expectedRevision`，服务端 replay 先于 CAS；marker-free IDLE 若已取消 queued 并推进 revision，响应丢失后的旧 revision 重试可返回 `STALE_REVISION`，客户端按权威 snapshot 的 basis fence 收敛。

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

## 9. Invocation 持久化与请求重建

ModelInvocation 持久化 `basisHeadEntryId + compact ModelRequestSpec`，完整 ProviderRequest 只在 MODEL attempt 内存中存在：

```java
public record ModelRequestSpec(
    ProviderType providerType,
    ModelDescriptor model,
    ModelVariant variant,
    List<AgentMessage> preambleMessages,
    List<ToolBinding> toolBindings,
    List<SkillBinding> skillBindings,
    List<SubagentBinding> subagentBindings,
    ProviderCacheControl cacheControl,
    CompactionRequest compaction) {}
```

- `providerType` 随 invocation 冻结：每次 attempt 仍读取当前 Provider 行的 credential/base URL/timeout，但当前行的 type 必须与 spec 一致，不一致时本次 attempt 确定性失败（禁止在同一 invocation 中切换协议）。不持久化完整 history `messages`、可由 `toolBindings` 派生的 `ProviderRequest.tools`、顶层 Environment、YOLO、contextWindow 与 attempt-only Resource URL。
- `preambleMessages` 只保存不能从 EntryPath 重建的 Resolver 输出（composed Agent system prompt、`<current_environment>`/date/note 投影、skill/subagent prompt 描述、插件 ContextProjector 输出）；普通对话历史由 EntryPath 重建，preamble 是 bounded 非历史上下文。
- tool/skill/subagent binding 名称各自不得重复；每个 environment-bound tool/skill 必须引用同一 Environment route。
- `SubagentBinding(name, description)`：`name` 是 canonical 非空短名（≤64 字符），`description` 是可空展示描述快照（≤512 字符）；随 spec 冻结，task 执行绝不依据后续 Agent 配置扩权。
- `contextWindow` 只在 `TurnStartPayload` 中持久化（见 §2），不在 spec 中重复保存。
- `compaction == null` 表示正常调用；非 null 时 tool/skill/subagent bindings 必须全为空。`CompactionPayload` 只含 `summaryText`；`phase/trigger/executionModel/cutEntryId/turnPrefixStartEntryId/historyCompactionEntryId` 冻结在 `CompactionStart`（存于 TURN_START），Entry ID 是 canonical UUID strings，complete 由 phase 与 TURN_END outcome 派生。
- `ToolBinding(descriptor, type, environment, plugin)`：`PLATFORM` binding 的 environment 为 null，`ENVIRONMENT` binding 指向具体 binding（可为 null）；descriptor 的 type 与 binding type 一致。
- `plugin` 为 null 或 `PluginToolBinding(pluginId, contributionLocalName, stateAccesses)`；仅 `PLATFORM` 可携带 plugin，identifier 必须 canonical，state accesses 按 customType 唯一且 mode 仅 `READ` / `WRITE`。该 provenance 随 spec 冻结，retry 不按工具名重新归属。
- `ModelDescriptor` 只含 `providerName`/`modelName`/`inputModalities`/`tools`/`reasoning`/`pricing` 六个字段；Provider 连接事实与 cache capability 在每次 attempt 由 Core 按当前 `agent_provider` 行解析（见 [harness-capability-wiring.md](harness-capability-wiring.md)）。
- 每次 MODEL attempt 由唯一 `ModelRequestMaterializer` 在有效 claim 内从 immutable `EntryPath + spec` 纯重建内存 `ProviderRequest`（preamble + compaction-aware Entry 历史投影 → `ProviderMessageProjector` → messages；Provider tools 由 `toolBindings` 派生）。它不访问 catalog、Environment registry 或插件 ContextProjector，也不持有事务。retry 重放同一 spec；当前失败 attempt 的 text/thinking/error 不修改该 spec。后续 turn 的 `DatabaseTurnResolver` 仍白名单投影 MESSAGE/CUSTOM_MESSAGE/ASSISTANT_ABORTED 与插件 ContextProjector，`MODEL_ATTEMPT_FAILURE` / `ASSISTANT_ERROR` 永不进入 Provider messages。ToolInvocation 执行同一 binding，不从最新 Agent/Environment 重新选择。
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
- apply 时唯一 `ToolOutcomeAppender` 先按 effects 顺序追加 CUSTOM，再追加 Tool Result Entry；ToolInvocation 无 `resultEntryId`（terminal Tool 行始终表示 outcome 尚未进入 Entry，batch apply 后同事务删除全部 Tool siblings 再删除父 ModelInvocation）。正常 Thread apply 与 Stop winner 共用该实现。
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

- `GenerationStopReason` 固定为 `COMPLETE` / `LENGTH` / `FILTERED`，不存在 `TOOL_CALLS`：`COMPLETE` 可以有或没有 calls，`LENGTH` 可以有或没有已观测 calls，`FILTERED` 的 calls 必须为空。Provider adapter 各自显式做 finish mapping，公共层不得根据 `hasToolCalls` 覆盖 stop reason。
- terminal `resultJson` 由 `ModelResponseValidator`（canonical 不变量）与纯函数 `ModelResponsePlanner`（先 stop reason、再 binding lookup、再 schema validation）处理：`COMPLETE` 无 calls → completed；`COMPLETE` 有效 calls → 每 call 一个 READY ToolInvocation + TOOL Work；schema-invalid / unknown → immediate FAILED 槽位；`LENGTH` 无 calls → `FAILED/OUTPUT_TRUNCATED`，有 calls → 每 call FAILED（不执行）；`FILTERED` → `FAILED/CONTENT_FILTERED`；null/unknown/impossible response → `INVALID_RESPONSE` 并按 InvocationRetryPolicy 重试。
- `ToolResult` 不携带 terminate 标志；普通 Tool batch 完成后固定反馈模型，主 Agent 是否结束只由后续模型结果或显式控制事实决定。

### Durable compaction

- `TurnStartReason.COMPACTION` 消费零 Command，candidate path 只追加 TURN_START；`CompactionPreparation` 作为 transient plan 事实传给 Resolver，并与 frozen `CompactionRequest` 逐字段机械比对。
- 成功结果只能是 `CompactionPayload`（`summaryText`）；正常 invocation 不能挂 COMPACTION，compaction invocation 不能挂普通 Assistant MESSAGE。Store 要求 invocation 已 SUCCEEDED、payload metadata 与 request 精确相等，且 cut/prefix anchor 在 result 前满足 `cutEntryId` 为历史 retained 边界、`turnPrefixStartEntryId` 指向 split turn 首个 user-like message。
- phase/complete 固定：HISTORY 为 incomplete；FULL/TURN_PREFIX 为 complete。completed HISTORY 用 `continueModel=true` 机械启动 TURN_PREFIX；complete OVERFLOW 用 true 启动一次 immediate retry；成功 THRESHOLD FULL/TURN_PREFIX/fallback 仅在压缩前存在 same-owner normal continuation 时以 true 恢复该 obligation，foreign owner 不可借用。
- HISTORY 之后只读取紧邻、已完成且 metadata 匹配的 partial；TURN_PREFIX 不扫描更早 stale partial。direct TURN_PREFIX 的 history 文本固定为 `No prior history.`。
- Threshold freshness 只被 complete CompactionPayload 消费；普通 FAILED/CANCELLED/UNKNOWN turn 与 Resolver Rejected turn 不覆盖当前 Thread 最新成功 usage。THRESHOLD 在 active ContinuationDue 或 queued user demand 边界执行，context tokens 为最近 compatible owned successful Provider usage 加其后可见消息估算（含 ToolResult）；完全 idle 且无 demand 不自唤醒。FAILED/STOPPED/CANCELLED/incomplete compaction 只阻止立即原地重试。shared-history ownership barrier 是 Entry-only 事实：`TurnStartPayload.ownerThreadId != currentThreadId` 的 shared turn 停止向前借用 usage，不查询其它 Thread 的 Invocation 行。
- **MANUAL**：`compactThread(CompactThreadCommand{threadId, expectedRevision})` 先锁 Thread 校验 expectedRevision，经 `manualDecision` 计算 availability（THREAD_BUSY / OWNERSHIP_BARRIER / NO_RESOLVED_CONTEXT / MODEL_CHANGED / BELOW_MINIMUM / NOTHING_TO_COMPACT），再以 `CompactionTrigger.MANUAL` 构建 plan；plan 短事务 → 事务外 Resolver → 第二事务提交 COMPACTION Turn + MODEL Work（commitManual 再次校验 revision + source head + queued 快照，消费零 Command）。与自动触发共用 MODEL Work、一次 fallback 与 crash recovery。
- `<read-files>` / `<modified-files>` 是 Runtime-owned reserved section：previous summary 入 prompt 前剥离，response 在 terminal success 前校验并剥离，最后只追加一次从完整 durable branch history 重算的 canonical 清单；modified 覆盖 read，空白或破坏 reserved 标签结构的 path 忽略。
- immediate overflow recovery continuation 再次 OVERFLOW 时不再压缩；该失败 Entry/TURN_END 保持 durable。

## 10. TurnResolver

```java
sealed interface TurnResolver.Result
    permits Resolved, Rejected {}

record Resolved(ModelRequestSpec spec) {}
record Rejected(AssistantError error) {}   // 确定性拒绝：写入 durable barrier
```

- 同步、无副作用、事务外：`resolve(threadId, candidatePath, compactionPreparation)`；调用方在短事务内锁 Thread、捕获 Command 快照、分配 Entry ID 并构造 candidate path 后调用；实现只读最新 Catalog/Environment 事实，不写 Store、不持有行锁、不得按 candidate Entry ID 回查 Store。YOLO 不参与 Resolver（它是 Thread 运行时策略，由直接控制面维护，不使进行中的 plan 失效）。
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
