# Harness Runtime 契约

本文定义实现必须遵守的逻辑契约。方法拆分可为测试与事务适配调整，但不得改变：durable fact 唯一所有者、单一写者、terminal-driven resume、PostgreSQL truth / Redis hint、lock order 与 fencing、模块依赖方向。

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
    ├── realtime/ retry/ port/ extension/ configuration/
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

`RuntimeConfigSnapshot(AgentSnapshot agent, ModelSnapshot model, List<ToolBinding> tools, List<SkillSnapshot> skills, boolean yoloEnabled)`。

- 完整、自足、不可变；不含 secret；credential 只使用稳定 reference（`ModelDescriptor.providerResourceId`）；集合排序确定。
- `tools` 与 `skills` 做 defensive copy：`tools` Provider tool name 唯一并按 `(name, version, environmentName nullsFirst)` 排序；`skills` name 唯一并按 name 字典序。
- `tools` 非空时 `model.descriptor().tools()` 必须为 true。
- ENVIRONMENT 工具 binding 的 `environmentName` 与每个 `SkillSnapshot.sourceEnvironment()` 必须全部相等（PLATFORM 工具不参与）；`withYoloEnabled(boolean)` 仅替换顶层 `yoloEnabled`。
- 每次配置命令写完整 snapshot。

### 3.2 Entry payload JSON

`RuntimeEntryPayloadJsonCodec` 支持 `ROOT/RUNTIME_CONFIG/MESSAGE/CUSTOM_MESSAGE/ASSISTANT_ERROR`。exact field set；String API 拒绝 duplicate field 与 trailing token。Message content discriminator：`text/image/audio/thinking/json/tool_call/tool_result/artifact`。

### 3.3 ModelInvocationPlanner

```java
public Optional<ModelInvocationPlan> plan(
    long sessionId,
    long sourceHeadEntryId,
    List<SessionEntry> rootToHead);
```

- 输入必须是完整连续 root-to-head path，末 Entry 等于 `sourceHeadEntryId`
- 不访问 Store、Spring、Clock 或 live Definition，不写 Entry/head
- 发现 response debt 后只读 debt 前缀；plan 绑定实际 head，并包含该前缀的完整 `RuntimeConfigSnapshot` 与经 Prompt Cache finalization 的完整 `ProviderRequest`
- 后续 queued 配置不能反向改变已存在的 response debt

### 3.4 ModelInvocation

```java
public record ModelInvocation(
    long id,
    long threadId,
    long sourceHeadEntryId,
    long executionEpoch,
    ProviderRequest request,
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
    Instant finishedAt) {}
```

约束：

- `(threadId, sourceHeadEntryId, executionEpoch)` 唯一
- worker 不能写 Entry/head
- claim 后 mutation 同时 fence identity/status、execution epoch、attempt、worker token 与有效 lease
- Provider I/O 已开始但 ownership 丢失时不自动重放；过期 RUNNING lease 收敛为 `UNKNOWN`
- Reconciler 只能 apply terminal 且 `appliedAt == null` 的 result
- worker terminal 事务只写 Invocation terminal 并原子设置 Thread `runnable=true`
- Reconciler apply 成功时写 Assistant/AssistantError Entry、Usage、ToolInvocations、head 和 `appliedAt`
- Provider 执行只使用冻结 `ProviderRequest` 的 providerType/providerResourceId/model

### 3.5 ToolInvocation

```java
public enum ToolExecutionLocation {
  PLATFORM,
  ENVIRONMENT
}

public record ToolInvocation(
    long id,
    long threadId,
    long assistantEntryId,
    int ordinal,
    String toolCallId,
    ToolDescriptor descriptor,
    String argumentsJson,
    ToolExecutionLocation location,
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
    Instant finishedAt) {}
```

约束：

- `(threadId, assistantEntryId, executionEpoch, ordinal)` 唯一
- Tool worker 不写 Entry/head
- sibling 全部 terminal 后，Reconciler 在一个事务中按 ordinal 写 Tool Result Entry，并设置所有 sibling `appliedAt`
- ENVIRONMENT 必须有 environmentName，PLATFORM 必须没有
- 本地：`ToolWorker -> Tool`；远程：`ToolWorker -> RemoteTool -> transport -> Daemon -> Tool`

### 3.6 Interaction

```java
public enum InteractionStatus {
  OPEN,
  RESOLVED,
  CANCELLED,
  EXPIRED
}

public record Interaction(
    long id,
    ExecutionTarget owner,
    String handlerType,
    InteractionRequest request,
    InteractionStatus status,
    InteractionResponse response,
    Instant expiresAt,
    long version,
    Instant createdAt,
    Instant resolvedAt) {}
```

Handler 返回 deterministic resolution，由 Interaction transaction adapter 原子应用，不直接控制事务提交。

### 3.7 Command coordinators

- `SessionCommandCoordinator` 冻结 bootstrap Agent config，并通过 `ThreadCommandTransactions` 创建 Session/ROOT/RUNTIME_CONFIG；不创建 Thread。产品默认值由 Core 提供。
- `ThreadCommandCoordinator` 拥有 UNBOUND Thread 创建、bootstrap、head 重定位、typed payload 构造、消息/role 校验、idempotency short-circuit、当前 config 选择、SET_AGENT/SET_MODEL/SET_YOLO 完整快照和 Stop 调用；对 Core 返回 coordinator-owned result，不泄漏 transaction SPI result。
- `InteractionCoordinator` 拥有 handler lookup、projection、expiry 判定和 deterministic resolution。

Thread command 入口：

```java
HarnessThread createThread();

BootstrapResult bootstrapThread(
    long threadId, long expectedExecutionEpoch,
    String title, long agentDefinitionId, boolean yoloEnabled);

HarnessThread updateHead(long threadId, long expectedExecutionEpoch, Long headEntryId);
```

`bootstrapThread` 只接受 UNBOUND Thread，在一个事务内创建 Session/ROOT/RUNTIME_CONFIG 并把 head 绑定到 `RUNTIME_CONFIG` Entry。`updateHead` 的 `headEntryId` 可空：非空为 bind/rebind，空为 unbind。二者与所有 mailbox 命令、Stop 一样，都必须携带 `expectedExecutionEpoch`。

Coordinator 不依赖 Spring/DTO/HTTP；Core boundary 负责十进制字符串解析、Spring 外层事务、after-commit signal 与 DTO 映射。

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

### 4.2 ActivationNotifier

```java
public interface ActivationNotifier {
  void notifyAfterCommit(ExecutionTarget target);
}

public enum ExecutionTargetKind {
  THREAD,
  MODEL_INVOCATION,
  TOOL_INVOCATION
}

public record ExecutionTarget(ExecutionTargetKind kind, long id) {}
```

Notifier 失败不得回滚已提交业务事务；Recovery 补偿。

### 4.3 RealtimeEventSink

```java
public interface RealtimeEventSink {
  void append(RealtimeEvent event);
}
```

`RealtimeEventType`：`MODEL_DELTA`、`TOOL_PARTIAL`。sink 失败只记观测错误，不改变 Invocation terminal。可重试 Invocation 的 event 必须携带 attempt。

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
public interface RuntimeConfigSource {
  RuntimeConfigSnapshot resolveAgent(long definitionId, boolean yoloEnabled);

  RuntimeConfigSnapshot replaceModel(
      RuntimeConfigSnapshot current, long modelId, String requestedVariant);
}
```

这是 command-time live resource 冻结 SPI。Core 实现可以读取 Definition、Model、Provider、ready Environment 与 extension descriptors，但不得发起 Provider I/O。纯 `SET_YOLO` 变换由 `RuntimeConfigSnapshot.withYoloEnabled` 完成。

## 5. Transaction ports

Runtime 不在多个通用 Repository 之间自行拼事务。PostgreSQL adapter 提供用例级原子 ports。

### 5.1 ThreadCommandTransactions

- 创建 UNBOUND Thread
- 使用调用方已冻结的初始 `RuntimeConfigSnapshot` 创建 Session/ROOT/RUNTIME_CONFIG
- bootstrap：在同一事务内创建 Session 并把 UNBOUND Thread 的 head 绑定到 `RUNTIME_CONFIG` Entry
- updateHead：静止校验通过后以 `expectedExecutionEpoch` CAS 写入可空 head，epoch++ 并清 lease/`runnable`
- enqueue Input 并分配 sequence；UNBOUND Thread 拒绝入队
- Stop：epoch++、fence、取消 queued Input、可安全取消的 Invocation 与该 Thread 的 OPEN Interaction
- 成功事务返回 afterCommit notify targets

head 重定位与 enqueue/Stop 都先锁 Thread 行并校验 `expectedExecutionEpoch`；epoch 不匹配即 stale，事务整体拒绝。

### 5.2 ThreadReconcileTransactions

- claim/renew Thread lease
- 读取一致 reconcile snapshot
- apply Model terminal / Tool terminals
- suspend blocker 并在 Thread lock 下 recheck
- harvest TURN_BOUNDARY
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
- Interaction 允许继续时转 QUEUED 并返回 Tool signal
- Interaction 拒绝时 terminal 并返回 Thread signal

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
- TURN_BOUNDARY 所需 queued Inputs

不得在 Runtime 中出现逐 Entry JDBC 循环。

## 7. Worker 生命周期

### 7.1 ModelWorker

```text
signal/recovery
  -> find claimable ModelInvocation
  -> resolve frozen request 的短生命周期执行资源（仅 QUEUED/RETRY_WAIT）
  -> claim（写入 lease/fencing）
  -> execute Provider
  -> delta to RealtimeEventSink
  -> success/final failure via ModelInvocationTransactions
  -> afterCommit Thread signal

transient failure
  -> RETRY_WAIT
  -> due scheduler emits ModelInvocation signal
```

关键协议：

- 每次 claim 生成新 worker token；CAS 返回 `LOST_OWNERSHIP` 时只关闭本地 handle
- `QUEUED -> RUNNING` 建立 `startedAt`、总 `deadlineAt` 与 activity；`RETRY_WAIT -> RUNNING` 不延长总 deadline
- 已过期 `RUNNING` lease 落 `UNKNOWN`，不能重新调用 Provider
- Provider delta 只写 best-effort realtime；terminal CAS 才写 durable terminal 并标记 Thread runnable
- external idempotency key 稳定于 `(invocationId, attempt)`
- notifier/realtime 异常不得回滚 durable transition

### 7.2 ThreadReconciler

```text
THREAD signal/recovery
  -> claim runnable Thread reconcile lease
  -> terminal Model apply
  -> terminal Tool sibling apply
  -> durable blocker suspend
  -> response-debt ModelInvocation create
  -> one TURN_BOUNDARY harvest
  -> quiesce
```

- claim 要求 `runnable=true`、`head_entry_id` 非空且 processor lease 为空/过期；不递增 stop/rebind 使用的 `execution_epoch`
- terminal Model apply 从 immutable path 重新 planning，并要求重建 `ProviderRequest` 与 durable request 完全相等
- Tool sibling 全部 terminal 且未 applied 才能批量 apply
- 成功 suspend/create/quiesce 原子清 lease 并设 `runnable=false`；异常 release 保持 `runnable=true`

### 7.3 ToolWorker

```text
signal/recovery
  -> claim ToolInvocation
  -> check unresolved Interaction
  -> execute PLATFORM Tool 或 ENVIRONMENT RemoteTool
  -> partial to RealtimeEventSink
  -> terminal via ToolInvocationTransactions
  -> afterCommit Thread signal
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
- Root activity（查询投影）

客户端恢复：

1. REST snapshot
2. SSE `realtime`（Redis stream-id cursor）

## 9. 扩展贡献

仅允许：

| 贡献 | 用途 |
| --- | --- |
| `BeforeToolCallInterceptor` / `AfterToolCallInterceptor` | Tool 前后修改链（含 PermissionBoundary） |
| `HarnessLifecycleObserver` | 提交后观测 |
| `ProviderFactory` | ProviderType -> adapter |
| `ToolFactory` | name@version -> Tool |
| disposer | Host 关闭清理 |

## 10. 包级注释与公共类型

- runtime 协调包、transaction adapter、Interaction SPI 保持 package-level 职责说明
- 公共状态 enum 明确 terminal/transition 不变量
- DTO 与 persistence DO 不进入 runtime 公共方法
- Java 代码体禁止全限定类名，遵守 Spotless/Checkstyle
