# Harness Kernel 与 Runtime 契约

## 1. 契约边界

本文件定义实现必须遵守的逻辑契约。具体方法拆分可以为测试和事务实现调整，但不得改变：

- durable fact 的唯一所有者；
- 单一写者；
- terminal-driven resume；
- PostgreSQL truth / Redis hint；
- lock order 与 fencing；
- Kernel、Runtime、Adapter 的依赖方向。

## 2. Kernel 类型

Kernel 包建议：

```text
fun.fengwk.kkstudio.harness.kernel
├── session/
├── thread/
├── execution/
└── continuation/
```

### 2.1 标识符

Java 内部继续使用 `long`，API 边界统一编码为十进制字符串。

Kernel 不生成 id，只接收外部 `IdGenerator`/Store 分配结果。

### 2.2 Session/Entry

```java
public record Session(
    long id,
    long mainThreadId,
    String title,
    Long parentSessionId,
    Long parentInvocationId,
    Instant createdAt,
    Instant updatedAt) {}
```

```java
public record SessionEntry(
    long id,
    long sessionId,
    Long parentEntryId,
    EntryType type,
    EntryPayload payload,
    Instant createdAt) {}
```

`EntryPayload` 是无框架接口；JSON codec 位于 PostgreSQL/Web adapter。已提交 Entry 不允许 update/delete。

### 2.3 Thread

```java
public record HarnessThread(
    long id,
    long sessionId,
    long headEntryId,
    long inputSequence,
    boolean runnable,
    long executionEpoch,
    Lease processorLease,
    Instant createdAt,
    Instant updatedAt) {}
```

`Lease`：

```java
public record Lease(String token, Instant until) {}
```

`processorLease == null` 表示当前没有 owner，不表示 Thread 必然 IDLE。

### 2.4 Input

```java
public enum InputStatus {
  QUEUED,
  APPLIED,
  CANCELLED
}
```

Input payload 使用 typed command，不把 HTTP DTO 直接传入 Kernel。

### 2.5 通用 execution primitive

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

其中：

- `SUCCEEDED/FAILED/CANCELLED/UNKNOWN` 为 terminal；
- `UNKNOWN` 只用于无法确认副作用结果的执行；
- `RETRY_WAIT` 必须有 `nextAttemptAt`；
- `RUNNING` 必须有有效 worker lease 和 `startedAt`。

### 2.6 StepResult

```java
public sealed interface StepResult {

  record Progressed() implements StepResult {}

  record Suspended(ContinuationRef continuation) implements StepResult {}

  record Quiescent() implements StepResult {}

  record LostOwnership() implements StepResult {}

  record Failed(Failure failure) implements StepResult {}
}
```

`ContinuationRef` 只标识 durable owner/blocker，不保存 callback、Future 或 Runnable。

```java
public record ContinuationRef(ExecutionTarget owner, ExecutionTarget blocker) {}
```

## 3. Runtime aggregates

Runtime 包建议：

```text
fun.fengwk.kkstudio.harness.runtime
├── reconcile/
├── model/
├── tool/
├── interaction/
├── context/
├── subagent/
├── port/
└── configuration/
```

### 3.1 RuntimeConfigSnapshot

```java
public record RuntimeConfigSnapshot(
    AgentSnapshot agent,
    ModelSnapshot model,
    List<ToolBindingSnapshot> tools,
    List<SkillSnapshot> skills,
    ExecutionPolicySnapshot policy,
    EnvironmentSnapshot environment) implements EntryPayload {}
```

要求：

- 完整、自足、不可变；
- 不包含 secret value；
- credential 只使用稳定 reference；
- 所有集合排序确定，JSON 编码稳定；
- 每次配置命令写完整 snapshot。

### 3.2 ModelInvocation

```java
public record ModelInvocation(
    long id,
    long threadId,
    long sourceHeadEntryId,
    long executionEpoch,
    ModelRequestSnapshot request,
    InvocationStatus status,
    int attempt,
    Instant nextAttemptAt,
    Lease workerLease,
    Instant deadlineAt,
    Instant lastActivityAt,
    ModelTerminalResult result,
    Instant appliedAt,
    Instant createdAt,
    Instant startedAt,
    Instant finishedAt) {}
```

约束：

- 同一 Thread/source head 最多一个非终态 ModelInvocation；
- worker 不能写 Entry/head；
- Reconciler 只能 apply terminal 且 `appliedAt == null` 的 result；
- apply 成功时写 Assistant/AssistantError Entry、Usage、ToolInvocations、head 和 `appliedAt`。

### 3.3 ToolInvocation

```java
public enum ToolExecutionLocation {
  PLATFORM,
  ENVIRONMENT
}
```

```java
public record ToolInvocation(
    long id,
    long threadId,
    long assistantEntryId,
    int ordinal,
    String toolCallId,
    ToolDescriptorSnapshot descriptor,
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
    ToolTerminalResult result,
    Instant appliedAt,
    Instant createdAt,
    Instant startedAt,
    Instant finishedAt) {}
```

约束：

- `(threadId, assistantEntryId, ordinal)` 唯一；
- Tool worker 不写 Entry/head；
- sibling 全部 terminal 后，Reconciler 在一个事务中按 ordinal 写 Tool Result Entry，并设置所有 sibling `appliedAt`；
- ENVIRONMENT 必须有 environmentName，PLATFORM 必须没有。

### 3.4 Interaction

```java
public enum InteractionStatus {
  OPEN,
  RESOLVED,
  CANCELLED,
  EXPIRED
}
```

```java
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

InteractionHandler：

```java
public interface InteractionHandler {

  String type();

  InteractionProjection project(InteractionRequest request);

  InteractionResolution resolve(
      InteractionRequest request,
      InteractionResponse response);
}
```

Handler 不直接控制事务提交；它返回 deterministic resolution，由 Interaction transaction adapter 原子应用。

## 4. Runtime ports

Runtime port 不暴露 MyBatis Mapper、RedisTemplate、HTTP DTO 或 Spring transaction 类型。

### 4.1 IdGenerator

```java
public interface HarnessIdGenerator {
  long nextSessionId();
  long nextThreadId();
  long nextEntryId();
  long nextInputId();
  long nextModelInvocationId();
  long nextToolInvocationId();
  long nextInteractionId();
}
```

PostgreSQL adapter 使用 sequence。sequence gap 是合法行为。

### 4.2 ActivationNotifier

```java
public interface ActivationNotifier {
  void notifyAfterCommit(ExecutionTarget target);
}
```

`ExecutionTarget`：

```java
public enum ExecutionTargetKind {
  THREAD,
  MODEL_INVOCATION,
  TOOL_INVOCATION
}
```

```java
public record ExecutionTarget(ExecutionTargetKind kind, long id) {}
```

Notifier 失败不得回滚已提交业务事务；Recovery 负责补偿。

### 4.3 RealtimeEventSink

```java
public interface RealtimeEventSink {
  void append(RealtimeEvent event);
}
```

RealtimeEvent 不是 durable transaction 的返回值或判断条件。Redis sink 失败只记录观测错误，不改变 Invocation terminal。

### 4.4 ModelExecutor

```java
public interface ModelExecutor {
  ModelExecutionHandle execute(
      ModelExecutionRequest request,
      ModelExecutionListener listener);
}
```

Listener：started、delta、completed、failed。Adapter 必须支持 best-effort cancel；Runtime 仍以 worker token/epoch 判断 callback 是否可提交。

### 4.5 ToolExecutor

```java
public interface ToolExecutor {
  ToolExecutionHandle execute(
      ToolExecutionRequest request,
      ToolExecutionListener listener);
}
```

Platform 与 Environment transport 实现同一接口。

## 5. Transaction ports

Runtime 不在多个通用 Repository 之间自行拼事务。PostgreSQL adapter 提供按用例定义的原子 transaction ports。

### 5.1 ThreadCommandTransactions

- 创建 Session/Main Thread/ROOT/初始配置；
- 创建 Branch Thread；
- enqueue Input 并分配 sequence；
- Stop：epoch++、fence、取消 queued Input/Invocation；
- 每个成功事务返回需要 afterCommit notify 的 target。

### 5.2 ThreadReconcileTransactions

- claim/renew Thread lease；
- 读取一致的 reconcile snapshot；
- apply Model terminal；
- apply Tool terminals；
- harvest TURN_BOUNDARY；
- 创建 ModelInvocation 并释放 Thread；
- quiesce；
- 所有 mutation 校验 thread token + execution epoch。

### 5.3 ModelInvocationTransactions

- claim/renew worker lease；
- record activity；
- terminal success/failure/retry；
- success 或最终 failure 与 Thread runnable 同事务；
- transient retry 只写 `RETRY_WAIT/nextAttemptAt`，到期后发送 ModelInvocation signal；
- late/duplicate callback 返回 lost ownership/already terminal，不抛出不可恢复异常。

### 5.4 ToolInvocationTransactions

- claim/renew worker lease；
- resolve retry/timeout/cancel；
- terminal 与 Thread runnable 同事务；transient retry 只重新调度 ToolInvocation；
- Interaction 允许继续时转为 QUEUED 并返回 Tool signal；
- Interaction 拒绝时 terminal 并返回 Thread signal。

### 5.5 InteractionTransactions

- create OPEN Interaction 并 suspend owner；
- resolve/cancel/expire；
- 校验 version/status；
- 原子应用 handler resolution 与下一 target 的 dispatchable/runnable 状态。

## 6. Reconciler snapshot

一次 snapshot 至少包含：

- Thread 与 lease/epoch；
- current head 及必要 tail/path；
- terminal-unapplied ModelInvocation；
- current Assistant 的 ToolInvocations；
- due continuation/retry；
- TURN_BOUNDARY 所需 queued Inputs。

snapshot 可以由多个 SQL 在同一短事务/隔离级别中组成，但不得在 Runtime 中出现逐 Entry JDBC 循环。

## 7. Worker 生命周期

### 7.1 ModelWorker

```text
signal/recovery
  -> claim ModelInvocation
  -> resolve credential reference
  -> execute Provider
  -> delta to RealtimeEventSink
  -> success/final failure via ModelInvocationTransactions
  -> afterCommit Thread signal

transient failure
  -> RETRY_WAIT
  -> due scheduler emits ModelInvocation signal
```

### 7.2 ToolWorker

```text
signal/recovery
  -> claim ToolInvocation
  -> check unresolved Interaction
  -> execute PLATFORM/ENVIRONMENT transport
  -> partial to RealtimeEventSink
  -> terminal via ToolInvocationTransactions
  -> afterCommit Thread signal

transient failure
  -> RETRY_WAIT
  -> due scheduler emits ToolInvocation signal
```

### 7.3 SubagentTool

`task` Tool 的 PLATFORM 实现：

1. 以 ToolInvocation id 作为幂等键创建 Child Session/Main Thread；
2. Child Session 的 `parentInvocationId` 唯一；
3. Child terminal 事务完成父 ToolInvocation；
4. 不轮询、不创建 Task record。

## 8. Query/API 边界

Query side 可以组合 PostgreSQL facts 生成 DTO，但不得把 query projection 写回 Kernel aggregate。

保留的核心查询：

- Session/Thread/Branch；
- Entry path；
- queued Inputs；
- Model/Tool Invocations；
- open Interactions；
- Usage/Artifact。

删除：

- Task/RootActivity API；
- Tool-specific decision API；
- PostgreSQL ThreadEvent history API。

新增：

- generic Interaction query/response API；
- snapshot bootstrap API 或等价组合查询；
- Redis-backed realtime SSE tail。

## 9. 包级注释与公共类型要求

- Kernel、Runtime reconcile、transaction adapter、Interaction SPI 必须有 package-level 职责说明。
- 公共状态 enum 必须明确 terminal/transition 不变量。
- DTO 与 persistence DO 不进入 Kernel/Runtime 公共方法。
- Java 代码体禁止使用全限定类名，遵守仓库 Spotless/Checkstyle。
