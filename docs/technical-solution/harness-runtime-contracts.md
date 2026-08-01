# Harness Runtime 契约

本文定义实现必须遵守的逻辑契约。方法拆分可为测试与事务适配调整，但不得改变：durable fact 唯一所有者、单一写者、terminal-driven resume、PostgreSQL truth / durable execution target、lock order 与 fencing、模块依赖方向。

## 1. 模块与包

```text
harness-tool
  fun.fengwk.kkstudio.harness.tool
    ├── descriptor / content / result
    ├── schema/
    ├── execution/          # Tool SPI
    ├── remote/             # RemoteTool + transport
    ├── daemon/             # wire protocol codecs
    └── codec/

harness-runtime
  fun.fengwk.kkstudio.harness.runtime
    ├── session/ thread/ entry/ execution/ continuation/
    ├── reconcile/ tool/ interaction/              # coordinators + state machines
    ├── model/                                     # provider/model 契约、codec、ModelInvocation
    │   ├── provider/ cache/ codec/
    │   └── plan/ worker/
    ├── realtime/ retry/ port/ configuration/
    └── permission/ skill/ goal/ usage/ cache/

harness-daemon
  fun.fengwk.kkstudio.harness.daemon  # 仅依赖 harness-tool
```

## 2. 核心类型

### 2.1 标识符

Java 内部使用 `long`，API 边界编码为十进制字符串。Runtime 不生成 id，只接收 `HarnessIdGenerator`/Store 分配结果。

### 2.2 Session / Entry

```java
public record Session(
    long id,
    String title,
    Instant createdAt) {}

public record SessionEntry(
    long id,
    Long parentEntryId,
    EntryPayload payload) {}
```

`EntryPayload` 由 runtime entry 包定义；`RuntimeEntryPayloadJsonCodec` 是 durable JSON 边界。Session 不持有 Thread；已提交 Entry 不允许 update/delete。

### 2.3 Thread

```java
public record HarnessThread(
    long id,
    Long headEntryId,
    long inputSequence,
    boolean runnable,
    long executionEpoch,
    Lease processorLease,
    Instant createdAt,
    Instant updatedAt) {}

public record Lease(String token, Instant until) {}
```

Thread 是可跨 Session 复用的 durable runtime process，不保存 `sessionId`：当前 Session 由 head Entry 派生。`headEntryId == null` 表示 UNBOUND，此时拒绝 mailbox 输入且不可被 Reconciler claim。`processorLease == null` 不表示 Thread 必然 IDLE。

### 2.4 Input

```java
public enum InputStatus {
  QUEUED,
  APPLIED,
  CANCELLED
}
```

Input payload 使用 typed command，不把 HTTP DTO 直接传入 Runtime。

### 2.5 Invocation 状态

```java
public enum InvocationStatus {
  QUEUED,
  RUNNING,
  RETRY_WAIT,
  SUCCEEDED,
  FAILED,
  CANCELLED,
  UNKNOWN
}
```

- `SUCCEEDED/FAILED/CANCELLED/UNKNOWN` 为 terminal
- `UNKNOWN` 只用于无法确认副作用结果的执行（如已开始后 lease 过期）
- `RETRY_WAIT` 必须有 `nextAttemptAt`
- `RUNNING` 必须有有效 worker lease 与 `startedAt`

### 2.6 StepResult

```java
public sealed interface StepResult {
  record Progressed() implements StepResult {}
  record Suspended(ContinuationRef continuation) implements StepResult {}
  record Quiescent() implements StepResult {}
  record LostOwnership() implements StepResult {}
  record Failed(Failure failure) implements StepResult {}
}

public record ContinuationRef(ExecutionTarget owner, ExecutionTarget blocker) {}
```

`ContinuationRef` 只标识 durable owner/blocker，不保存 callback、Future 或 Runnable。

## 3. Runtime aggregates

### 3.1 RuntimeConfigSnapshot

`RuntimeConfigSnapshot(AgentSnapshot agent, ModelSnapshot model, String environmentName, List<String> toolNames, List<String> skillNames, boolean yoloEnabled)`。

- 完整、自足、不可变；不含 secret；credential 只使用稳定 reference（`ModelDescriptor.providerResourceId`）；名称集合按字典序 canonical。
- `environmentName` 可空；空值表示当前 runtime 的本地执行，非空值是本次 Thread 的 RemoteTool 目标。Environment 名称最多 128 个字符，不能包含首尾空白。
- `toolNames` 与 `skillNames` 只允许短名，分别去重并排序；非空 tools/skills 要求 `model.descriptor().tools()` 为 true。
- `withEnvironmentName(String)` 只替换 Environment；`withYoloEnabled(boolean)` 只替换 yolo。每次配置命令写完整 snapshot。

### 3.2 Entry payload JSON

`RuntimeEntryPayloadJsonCodec` 支持 `ROOT/RUNTIME_CONFIG/MESSAGE/CUSTOM_MESSAGE/ASSISTANT_ERROR/ASSISTANT_ABORTED`。`ASSISTANT_ABORTED` 仅承载安全 text/thinking content；空 text/thinking 全部为空时停止路径必须改走 `ASSISTANT_ERROR(CANCELLED)` barrier，绝不物化空 aborted turn。exact field set；String API 拒绝 duplicate field 与 trailing token。Message content discriminator：`text/image/audio/thinking/json/tool_call/tool_result/artifact`。

### 3.3 ModelInvocationPlanner

```java
public Optional<ModelInvocationPlan> plan(
    long sessionId,
    long sourceHeadEntryId,
    List<SessionEntry> rootToHead);
```

- 输入必须是完整连续 root-to-head path，末 Entry 等于 `sourceHeadEntryId`
- 不访问 Store、Spring、Clock 或 live Definition，不写 Entry/head
- 发现 response debt 后只读 debt 前缀；plan 绑定实际 head，并包含该前缀的完整 `RuntimeConfigSnapshot` 与经 Prompt Cache finalization 的冻结 `ModelInvocationRequest`
- 后续 queued 配置不能反向改变已存在的 response debt

### 3.4 ModelInvocation

```java
public record ModelInvocation(
    long id,
    long threadId,
    long sourceHeadEntryId,
    long executionEpoch,
    ModelInvocationRequest request,
    InvocationStatus status,
    int attempt,
    Instant nextAttemptAt,
    Lease workerLease,
    Instant deadlineAt,
    Instant lastActivityAt,
    ProviderResponse result,
    ModelInvocationError error,
    Instant appliedAt,
    Instant createdAt,
    Instant startedAt,
    Instant finishedAt,
    SafeStreamSnapshot safeStreamSnapshot) {}
```

约束：

- `(threadId, sourceHeadEntryId, executionEpoch)` 唯一
- worker 不能写 Entry/head
- claim 后 mutation 同时 fence identity/status、execution epoch、attempt、worker token 与有效 lease
- Provider I/O 已开始但 ownership 丢失时不自动重放；过期 RUNNING lease 收敛为 `UNKNOWN`
- Reconciler 只能 apply terminal 且 `appliedAt == null` 的 result
- worker terminal 事务只写 Invocation terminal 并原子设置 Thread `runnable=true`
- Reconciler apply 成功时写 Assistant/AssistantError Entry、Usage、ToolInvocations、head 和 `appliedAt`
- Provider 执行只使用冻结 `ModelInvocationRequest.providerRequest()`；后续 Tool 路由和 `load_skill` 解析只使用同一 request 的 frozen bindings/skills
- `safeStreamSnapshot` 仅承载 text/thinking 累积；SSE 前以 Thread + Invocation 锁 fenced 写入；retry-attempt CAS 重置；`/stop` 读取 `(threadId, executionEpoch, sourceHeadEntryId)` 且 `safe_stream_snapshot is not null AND applied_at is null` 的未应用行，保留已经写入的部分输出到 `ASSISTANT_ABORTED`

### 3.4.1 ModelInvocationRequest

`ModelInvocationRequest` 是一次 ModelInvocation 的唯一冻结请求：

```java
public record ToolBinding(ToolDescriptor descriptor, String environmentName) {}

public record ModelInvocationRequest(
    ProviderRequest providerRequest,
    List<ToolBinding> toolBindings,
    List<SkillSnapshot> skillSnapshots,
    boolean yoloEnabled) {}
```

`providerRequest.tools` 与 `toolBindings` 按顺序严格一一对应，数量、名称、description 和 input schema 必须完全一致；`skillSnapshots` 是本次请求的完整 selected skills。`ModelInvocationRequestJsonCodec` 只接受 `providerRequest`、`toolBindings`、`skillSnapshots`、`yoloEnabled` 四个字段，并对嵌套 descriptor、binding 与 skill snapshot 做严格字段校验。`load_skill` 从该冻结 request 的 `skillSnapshots` 解析正文，不重新读取当前 Agent 或 live Environment 配置。

### 3.5 ToolInvocation

```java
public record ToolInvocation(
    long id,
    long threadId,
    long assistantEntryId,
    long modelInvocationId,
    int ordinal,
    String toolCallId,
    ToolDescriptor descriptor,
    String argumentsJson,
    String environmentName,
    long executionEpoch,
    InvocationStatus status,
    int attempt,
    Instant nextAttemptAt,
    Lease workerLease,
    Instant deadlineAt,
    Instant lastActivityAt,
    ToolResult result,
    ToolInvocationError error,
    Instant appliedAt,
    Instant createdAt,
    Instant startedAt,
    Instant finishedAt,
    ToolPermissionState permissionState,
    boolean yoloEnabled) {}
```

约束：

- `(threadId, assistantEntryId, executionEpoch, ordinal)` 唯一；`(threadId, modelInvocationId)` 外键指向产生该 ToolCall 的 ModelInvocation
- Tool worker 不写 Entry/head
- sibling 全部 terminal 后，Reconciler 在一个事务中按 ordinal 写 Tool Result Entry，并设置所有 sibling `appliedAt`
- `environmentName == null` 时走本地 `ToolWorker -> Tool`；非空时走 `ToolWorker -> RemoteTool -> transport -> Daemon -> Tool`
- 发送前远程目标不可用时写 `FAILED`；发送结果不确定时写 `UNKNOWN`，不得重放该副作用
- `PENDING` 只允许在 `QUEUED`/`RUNNING` 或未执行的取消/设置失败终态；`ALLOWED` 必须在任何外部 Tool I/O 前持久化
- `ASKED` 只允许在无 lease/clock 的 `WAITING_INTERACTION`，且对应一个 OPEN `tool-permission` Interaction 与 parked target
- `DENIED` 只允许在 `FAILED`；批准恢复 `QUEUED + ALLOWED` 并经 target route FIFO gate 调度，拒绝删除 Tool target 并原子唤醒 Thread

### 3.6 Interaction

```java
public enum InteractionStatus {
  OPEN,
  RESOLVED
}

public record Interaction(
    long id,
    long toolInvocationId,
    InteractionRequest request,
    InteractionStatus status,
    InteractionResponse response,
    long version,
    Instant createdAt,
    Instant resolvedAt) {}
```

`ToolPermissionInteractionCodec` 以严格 request/response 产生 approve/deny 决定，由 Interaction transaction adapter 原子应用。

### 3.7 Command coordinators

- `ThreadCommandCoordinator` 拥有 UNBOUND Thread 创建、bootstrap、head 重定位、typed payload 构造、消息/role 校验、idempotency short-circuit、当前 config 选择、SET_AGENT/SET_MODEL/SET_ENVIRONMENT/SET_YOLO 完整快照和 Stop 调用；对 Core 返回 coordinator-owned result，不泄漏 transaction SPI result。
- Thread `bootstrapThread` 在同一事务内创建 Session / ROOT / `RUNTIME_CONFIG`，并将 Thread head 重定位到 `RUNTIME_CONFIG` Entry。Session / ROOT / `RUNTIME_CONFIG` 三件套仅由 `bootstrapThread` 内部私有 helper 落库，未公开为 Runtime SPI。
- `InteractionCoordinator` 拥有 Tool permission projection 和 deterministic approval resolution。

Thread command 入口：

```java
HarnessThread createThread();

BootstrapResult bootstrapThread(
    long threadId, long expectedExecutionEpoch,
    String title, long agentDefinitionId, String environmentName, boolean yoloEnabled);

HarnessThread updateHead(long threadId, long expectedExecutionEpoch, Long headEntryId);
```

`bootstrapThread` 只接受 UNBOUND Thread，在一个事务内创建 Session/ROOT/RUNTIME_CONFIG 并把 head 绑定到 `RUNTIME_CONFIG` Entry；初始配置包含 Agent、Model、Environment、tools、skills 和 yolo。`updateHead` 的 `headEntryId` 可空：非空为 bind/rebind，空为 unbind。二者与所有 mailbox 命令、Stop 一样，都必须携带 `expectedExecutionEpoch`。

`SET_ENVIRONMENT` 接收可空 Environment 名称；`null` 清除目标并切回本地 runtime。命令只替换 `RuntimeConfigSnapshot.environmentName`，保留 Agent、Model、tools、skills 与 yolo。配置命令通过 `expectedExecutionEpoch` 做 CAS fencing，重复 idempotency key 直接返回既有 Input。

Coordinator 不依赖 Spring/DTO/HTTP；Core boundary 负责十进制字符串解析、Spring 外层事务、durable target mutation 与 DTO 映射。

## 4. Runtime ports

### 4.1 HarnessIdGenerator

```java
public interface HarnessIdGenerator {
  long nextSessionId();
  long nextThreadId();
  long nextEntryId();
  long nextInputId();
  long nextModelInvocationId();
  long nextToolInvocationId();
  long nextInteractionId();
  long nextArtifactId();
}
```

PostgreSQL sequence 分配；gap 合法。

### 4.2 ExecutionTarget

```java
public enum ExecutionTargetKind {
  THREAD,
  MODEL_INVOCATION,
  TOOL_INVOCATION
}

public record ExecutionTarget(ExecutionTargetKind kind, long id) {}
```

Core 的 PostgreSQL target store 为每个 `(kind, id)` 保留唯一 durable row，并以 `dispatch_enabled` 作为
显式 gate。`schedule` 创建/启用普通 target；非空 Environment Tool materialization 使用 `park` 创建 disabled
row，随后按 `created_at, assistant_entry_id, ordinal, id` 只启用目标 FIFO head。`lockDue`、due scan 和
nearest-due timing 忽略 disabled row，`lock`/`findAll` 仍可观察 parked row。schema trigger 在 commit 后
投递 PostgreSQL `NOTIFY`；`PostgresqlExecutionTargetListener`、startup/reconnect、Environment READY 与
nearest-due timer 只调用 coalesced dispatcher wake，通知失败或丢失永不回滚业务事务。

### 4.3 RealtimeEventSink

```java
public interface RealtimeEventSink {
  void append(RealtimeEvent event);
}
```

`RealtimeEventType`：`MODEL_DELTA`、`TOOL_PARTIAL`。sink 失败只记观测错误，不改变 Invocation terminal。可重试 Invocation 的 event 必须携带 attempt。Core Redis adapter 在每次 append 时从持久化全局策略解析 `MAXLEN`，因此调整对既有 Stream 的下一次写入生效。

### 4.4 Model / Tool 执行

```java
public interface ModelExecutor {
  ModelExecutionHandle execute(
      ModelExecutionRequest request,
      ModelExecutionListener listener);
}

public interface Tool {
  ToolDescriptor descriptor();

  ToolExecutionHandle execute(
      ToolExecutionRequest request,
      ToolExecutionListener listener);
}
```

Adapter 必须支持 best-effort cancel；Runtime 以 worker token/epoch 判断 callback 是否可提交。

### 4.5 RuntimeConfigSource

```java
import java.util.Objects;

public interface RuntimeConfigSource {
  RuntimeConfigSnapshot resolveAgent(long definitionId, boolean yoloEnabled);

  default RuntimeConfigSnapshot resolveAgent(
      long definitionId, String environmentName, boolean yoloEnabled) {
    return resolveAgent(definitionId, yoloEnabled).withEnvironmentName(environmentName);
  }

  default RuntimeConfigSnapshot replaceAgent(RuntimeConfigSnapshot current, long definitionId) {
    Objects.requireNonNull(current, "current");
    return resolveAgent(definitionId, current.environmentName(), current.yoloEnabled())
        .withEnvironmentName(current.environmentName());
  }

  RuntimeConfigSnapshot replaceModel(
      RuntimeConfigSnapshot current, long modelId, String requestedVariant);
}
```

这是 command-time live resource 冻结 SPI。Core 实现可以读取 Definition、Model、Provider、ready Environment 与统一 `ToolCatalog`，但不得发起 Provider I/O。`replaceAgent` 依据新的 definition 重新解析 Agent、Model、tools 与 skills，仅沿用 current 的 `environmentName` 与 `yoloEnabled`；纯 `SET_ENVIRONMENT` / `SET_YOLO` 变换分别由 `RuntimeConfigSnapshot.withEnvironmentName` / `withYoloEnabled` 完成。

## 5. Transaction ports

Runtime 不在多个通用 Repository 之间自行拼事务。PostgreSQL adapter 提供用例级原子 ports。

### 5.1 ThreadCommandTransactions

- 创建 UNBOUND Thread
- 使用调用方已冻结的初始 `RuntimeConfigSnapshot` 创建 Session/ROOT/RUNTIME_CONFIG
- bootstrap：在同一事务内创建 Session 并把 UNBOUND Thread 的 head 绑定到 `RUNTIME_CONFIG` Entry
- updateHead：静止校验通过后以 `expectedExecutionEpoch` CAS 写入可空 head，epoch++ 并清 lease/`runnable`
- enqueue Input 并分配 sequence；UNBOUND Thread 拒绝入队
- Stop：原子 epoch fence + 持久化 `ASSISTANT_ABORTED` 或 `ASSISTANT_ERROR(CANCELLED)` barrier + 取消 queued Input、可安全取消的 Invocation 与该 Thread 的 OPEN Interaction。Stop 必须沿用 Reconciler 的 `ModelInvocationPlanner` 判定当前 head 是否仍有未终结 response debt；若存在 response debt 且 `(threadId, epoch, sourceHeadEntryId)` 下存在 `safe_stream_snapshot is not null AND applied_at is null` 行，则把该快照追加为仅含 text/thinking 的 `ASSISTANT_ABORTED` Entry 并把 head rebind 上去；否则写 `ASSISTANT_ERROR(CANCELLED)`。Stop 不修改已 RUNNING/SUCCEEDED 的 invocation 行——它只是 epoch fence，旧 generation 的 terminal CAS 在新 epoch 下被拒绝。
- 成功事务在同一事务内写入或更新 durable target；schema trigger 负责提交后的 PostgreSQL NOTIFY

head 重定位与 enqueue/Stop 都先锁 Thread 行并校验 `expectedExecutionEpoch`；epoch 不匹配即 stale，事务整体拒绝。

### 5.2 ThreadReconcileTransactions

- claim/renew Thread lease
- 读取一致 reconcile snapshot
- apply Model terminal / Tool terminals
- suspend blocker 并在 Thread lock 下 recheck
- harvest TURN_INPUT_BATCH
- 创建 ModelInvocation 并释放 Thread
- quiesce
- 所有 mutation 校验 thread token + execution epoch

### 5.3 ModelInvocationTransactions

- claim/renew worker lease
- record activity
- terminal success/failure/retry
- success 或最终 failure 与 Thread runnable 同事务
- transient retry 只写 `RETRY_WAIT/nextAttemptAt`
- late/duplicate callback 返回 lost ownership/already terminal

### 5.4 ToolInvocationTransactions

- claim/renew worker lease
- resolve retry/timeout/cancel
- terminal 与 Thread runnable 同事务
- Tool permission ASK 原子写最终计划、`WAITING_INTERACTION + ASKED`、OPEN Interaction 与 parked target
- `tool-permission` Interaction 的批准原子转 `QUEUED + ALLOWED` 并启用正确的 Tool target；拒绝/过期原子写 `FAILED + DENIED`、删除 Tool target、调度 Thread target 与下一环境 head

### 5.5 InteractionTransactions

- create OPEN Interaction 并 suspend owner
- resolve/cancel/expire
- 校验 version/status
- 原子应用 handler resolution 与下一 target 的 dispatchable/runnable

## 6. Reconciler snapshot

一次 snapshot 至少包含：

- Thread 与 lease/epoch
- current head 及必要 path
- terminal-unapplied ModelInvocation
- current Assistant 的 ToolInvocations
- durable blocker
- TURN_INPUT_BATCH 所需的 snapshot queued Inputs

不得在 Runtime 中出现逐 Entry JDBC 循环。

## 7. Worker 生命周期

### 7.1 ModelWorker

```text
MODEL_INVOCATION target dispatch
  -> find specified claimable ModelInvocation
  -> resolve frozen request 的短生命周期执行资源（仅 QUEUED/RETRY_WAIT）
  -> claim（写入 lease/fencing）
  -> execute Provider
  -> delta to RealtimeEventSink
  -> success/final failure via ModelInvocationTransactions
  -> terminal transaction schedules Thread target

transient failure
  -> RETRY_WAIT
  -> durable target reschedule，nearest-due dispatcher wake
```

关键协议：

- 每次 claim 生成新 worker token；CAS 返回 `LOST_OWNERSHIP` 时只关闭本地 handle
- `QUEUED -> RUNNING` 建立 `startedAt`、总 `deadlineAt` 与 activity；`RETRY_WAIT -> RUNNING` 不延长总 deadline
- 已过期 `RUNNING` lease 落 `UNKNOWN`，不能重新调用 Provider
- Provider delta 只写 best-effort realtime；terminal CAS 才写 durable terminal 并标记 Thread runnable
- external idempotency key 稳定于 `(invocationId, attempt)`
- PostgreSQL notification/realtime 异常不得回滚 durable transition

### 7.2 ThreadReconciler

```text
THREAD target dispatch
  -> claim runnable Thread reconcile lease
  -> terminal Model apply
  -> terminal Tool sibling apply
  -> durable blocker suspend
  -> response-debt ModelInvocation create
  -> one TURN_INPUT_BATCH harvest（snapshot 中全部 queued Input 按 sequence 原子物化）
  -> quiesce
```

- claim 要求 `runnable=true`、`head_entry_id` 非空且 processor lease 为空/过期；不递增 stop/rebind 使用的 `execution_epoch`
- terminal Model apply 从 immutable path 重新 planning，并要求重建 `ProviderRequest` 与 durable request 完全相等
- Tool sibling 全部 terminal 且未 applied 才能批量 apply
- 成功 suspend/create/quiesce 原子清 lease 并设 `runnable=false`；异常 release 保持 `runnable=true`

### 7.3 ToolWorker

```text
TOOL_INVOCATION target dispatch
  -> claim specified ToolInvocation
  -> check unresolved Interaction
  -> execute local Tool or non-null Environment RemoteTool
  -> partial to RealtimeEventSink
  -> terminal via ToolInvocationTransactions
  -> terminal transaction schedules Thread target
```

## 8. Query / API 边界

Query 可组合 PostgreSQL facts 生成 DTO，不得把 query projection 写回 Runtime aggregate。

Web 只消费 Core application API 与 share DTO；不得直接导入 Harness domain/worker、Redis adapter、LiveEnvironment registry 或 concrete Environment gateway。Core observability/environment/realtime API 在返回 Web 前完成 Runtime/Tool/Redis 类型隔离。

核心查询：

- Session 列表/详情、全局 Thread 列表与详情（Thread 的 `sessionId`/`sessionTitle` 由 head Entry LEFT JOIN 派生，UNBOUND Thread 仍必须出现在结果中）
- Entry path
- queued Inputs
- Model/Tool Invocations
- open Interactions
- Usage/Artifact

客户端恢复：

1. REST snapshot
2. 无 SSE id 的 Redis `realtime` overlay（stream-id 仅在当前服务端 tail 内部推进）

## 9. 能力装配

Spring 直接收集：

| 贡献 | 用途 |
| --- | --- |
| `BeforeToolCallInterceptor` / `AfterToolCallInterceptor` | Tool 前后修改链（含 PermissionBoundary） |
| `ProviderFactory` | ProviderType -> adapter |
| `ToolFactory` | name@version -> Tool |

## 10. 包级注释与公共类型

- runtime 协调包、transaction adapter、Interaction SPI 保持 package-level 职责说明
- 公共状态 enum 明确 terminal/transition 不变量
- DTO 与 persistence DO 不进入 runtime 公共方法
- Java 代码体禁止全限定类名，遵守 Spotless/Checkstyle
