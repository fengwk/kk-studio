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
│   └── plan/
├── tool/
├── interaction/
├── entry/
├── subagent/
├── port/
└── configuration/
```

### 3.1 RuntimeConfigSnapshot

```java
public record RuntimeConfigSnapshot(
    AgentSnapshot agent,
    ModelSnapshot model,
    List<ToolBinding> tools,
    List<SkillSnapshot> skills,
    ExecutionPolicySnapshot policy,
    EnvironmentSnapshot environment) implements EntryPayload {}
```

要求：

- 完整、自足、不可变；
- 不包含 secret value；
- credential 只使用稳定 reference；
- 所有集合排序确定，JSON 编码稳定；
- Tool 非空时 Model descriptor 必须支持 Tool；
- ENVIRONMENT ToolBinding 与 Skill source 必须等于冻结 environment；
- 每次配置命令写完整 snapshot，不从 live Definition 补历史字段。

### 3.2 Entry payload JSON

`RuntimeEntryPayloadJsonCodec` 提供：

```java
String encode(EntryPayload payload);
ObjectNode encodeNode(EntryPayload payload);
EntryPayload decode(EntryType type, String json);
EntryPayload decodeNode(EntryType type, JsonNode value);
```

支持 `ROOT/RUNTIME_CONFIG/MESSAGE/CUSTOM_MESSAGE/COMPACTION/ASSISTANT_ERROR/LABEL/BRANCH_SUMMARY`。所有对象使用 exact field set；String API 拒绝 duplicate field 与 trailing token。Message content discriminator 固定为 `text/image/audio/thinking/json/tool_call/tool_result/artifact`。raw JSON 字符串不归一化；`argumentsJson` 与 `detailsJson` 必须是单一 object，Tool Result 不允许嵌套 Tool Call/Tool Result。

### 3.3 ModelInvocationPlanner

```java
public Optional<ModelInvocationPlan> plan(
    long sessionId,
    long sourceHeadEntryId,
    List<SessionEntry> rootToHead);
```

输入必须是同一 Session 的完整连续 root-to-head path，最后一个 Entry 必须等于 `sourceHeadEntryId`。planner 不访问 Store、Spring、Clock 或 live Definition，不写 Entry/head。发现 response debt 后只读取 debt 前缀；返回的 plan 仍绑定实际 head，并同时包含 debt 前缀实际使用的完整 `RuntimeConfigSnapshot` 与经 Prompt Cache finalization 的完整 `ProviderRequest`。后续 queued 配置不能反向改变已存在的 response debt。

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

- `(threadId, sourceHeadEntryId, executionEpoch)` 唯一；
- worker 不能写 Entry/head；
- claim 后 mutation 同时 fence Invocation identity/status、execution epoch、attempt、worker token 与有效 lease；
- Provider I/O 已开始但 worker ownership 丢失时不自动重放，过期 RUNNING lease 收敛为 UNKNOWN；
- Reconciler 只能 apply terminal 且 `appliedAt == null` 的 result；
- worker terminal transaction 只写 Invocation terminal 并原子设置 Thread `runnable=true`；
- Reconciler apply 成功时写 Assistant/AssistantError Entry、Usage、ToolInvocations、head 和 `appliedAt`。

### 3.5 ToolInvocation

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

### 3.6 Interaction

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

RealtimeEvent 不是 durable transaction 的返回值或判断条件。Redis sink 失败只记录观测错误，不改变 Invocation terminal。可重试
Invocation 的 event 必须携带 attempt，供 snapshot-first 客户端过滤旧 attempt 的 lossy partial。

### 4.4 ModelExecutor

```java
public interface ModelExecutor {
  ModelExecutionHandle execute(
      ModelExecutionRequest request,
      ModelExecutionListener listener);
}
```

Listener：delta、completed、failed。Invocation 在 durable claim 时进入 RUNNING 并建立 startedAt；Adapter 必须支持
best-effort cancel，Runtime 仍以 worker token/epoch 判断 callback 是否可提交。

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

- agentless 创建 Session/Main Thread/ROOT；
- 创建 Branch Thread；
- enqueue Input 并分配 sequence；
- Stop：epoch++、fence、取消 queued Input/Invocation；
- 每个成功事务返回需要 afterCommit notify 的 target。

### 5.2 ThreadReconcileTransactions

- claim/renew Thread lease；
- 读取一致的 reconcile snapshot；
- apply Model terminal；
- apply Tool terminals；
- suspend blocker 并在 Thread lock 下 recheck；
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
  -> find claimable ModelInvocation
  -> resolve frozen request 的短生命周期执行资源（仅 QUEUED/RETRY_WAIT；不发起 Provider I/O）
  -> claim ModelInvocation（写入 lease/fencing）
  -> execute Provider
  -> delta to RealtimeEventSink
  -> success/final failure via ModelInvocationTransactions
  -> afterCommit Thread signal

transient failure
  -> RETRY_WAIT
  -> due scheduler emits ModelInvocation signal
```

协议细节：

- `findClaimable` 不是 ownership；每次 claim 必须生成新 worker token，所有后续 mutation 都必须比较 Invocation
  id/status、execution epoch、attempt、worker token 与 lease 有效期。CAS 返回 `LOST_OWNERSHIP` 时只关闭本地
  handle，不能覆盖 durable 状态。
- `QUEUED -> RUNNING` 建立 `startedAt`、总 `deadlineAt` 和初始 activity。`RETRY_WAIT -> RUNNING`
  只保留首次建立的总时钟，递增 `attempt`，并以本次外部 Provider attempt 开始时刻刷新 activity，重新开始
  idle timeout 计量；worker heartbeat 绝不刷新 activity。
- 已过期 `RUNNING` lease 的接管不能安全确认 Provider 外部副作用，必须落为 `UNKNOWN`，不能重新调用
  Provider。
- Provider delta 只写 best-effort realtime projection，并按 cadence 持久化真实 activity；它不写
  Entry/head，也不唤醒 Thread。完整 response、Provider final error 或 cancellation 的 terminal CAS 才在同一
  事务写入尚未 flush 的最后真实 delta activity、标记 owning Thread runnable，commit 后再发 Thread signal；terminal
  callback 的到达时刻本身不伪装成 activity。由最终 response 补齐的 synthetic delta 只能在 success CAS commit 后投影，
  防止失去 ownership 的 callback 泄漏未被 durable result 接受的最终片段。
- 瞬态失败的第一个 retry ordinal 等于本次失败的 `attempt`；只有 policy 允许且
  `now + delay < deadlineAt` 才写 `RETRY_WAIT`。retry mutation 不标记 Thread runnable，只安排
  `MODEL_INVOCATION` delayed signal；它与 terminal mutation 一样原子保留尚未 flush 的最后真实 delta activity，recovery
  按 PostgreSQL due scan 兜底。
- total deadline、idle timeout 和 lease 互相独立。watchdog terminal 化后 best-effort 取消本地 handle；已开始
  terminal CAS 时继续 heartbeat，直到 CAS 成功、丢失 ownership 或本地 handle 关闭。
- 首次 attempt 从执行资源的 total timeout 建立 durable deadline；retry 即使重新解析执行资源也不得延长该 deadline。idle
  timeout 是当前 attempt 的冻结值；RUNNING 崩溃后直接 UNKNOWN，因此 recovery 不需要从 realtime state 重建 idle
  watchdog。ModelExecutionRequest 携带当前 attempt，外部 idempotency key 稳定于 `(invocationId, attempt)`；ModelExecutor
  的 transport timeout 还必须受 durable deadline 上限约束。
- 初始 lease/deadline/idle watchdog 无法注册时，Provider 尚未执行，可按瞬态 setup failure 进入 retry/final failure；Provider
  已启动后的本地 timer/activity 基础设施故障只关闭本地 handle，等待 lease recovery 保守落 UNKNOWN。
- notifier 和 realtime sink 都是 best-effort。其异常不得回滚或替代 durable transition；进程 stop 仅取消本地
  handle，留给 lease recovery。

生产部署通过 `kk-studio.harness.runtime` 配置 Model Worker：

- `model-worker-lease-duration` 默认 `30s`，`model-worker-heartbeat-interval` 默认 `10s`，
  `model-worker-activity-flush-interval` 默认 `100ms`；heartbeat 必须短于 lease。
- `model-recovery-interval` 默认 `1s`，`model-recovery-batch-size` 默认 `100`。生命周期以 fixed-delay 从
  启动即刻开始扫描，每次最多领取一个 batch，直到没有可 claim 的 Invocation。
- 仅当 `workers-enabled=true` 时启动恢复轮询；停止时先取消轮询，再关闭本地 Model 执行 handle，durable 状态由
  lease recovery 保守处理。

### 7.2 ThreadReconciler

```text
THREAD signal/recovery
  -> claim runnable Thread processor lease
  -> terminal Model apply
  -> terminal Tool sibling apply
  -> durable blocker suspend
  -> response-debt ModelInvocation create
  -> one TURN_BOUNDARY harvest
  -> quiesce
```

协议细节：

- claim 只要求 `runnable=true` 且 processor lease 为空/过期；不递增 stop 使用的 `execution_epoch`，ownership 期间保持 `runnable=true`。
- terminal Model apply 从 immutable source-head path 重新 planning，并要求重建的 `ProviderRequest` 与 durable request 完全相等；成功路径在同一事务追加 Assistant Entry、完整 usage ledger、冻结的 PLATFORM/ENVIRONMENT ToolInvocation、推进 head 并设置 `appliedAt`。
- Tool sibling 只有全部 terminal 且全部未 applied 时才能批量 apply；结果按 ordinal 追加 Text/JSON/Artifact Tool message，失败与 UNKNOWN 使用 strict typed `ToolInvocationError`，取消生成 canonical cancellation error。
- blocker suspension 忽略尚不能越过 blocker 的后续 queued Input；blocker terminal/替换才继续本次 activation。quiesce 则把 action、blocker、response debt 和 queued Input 全部视为 work。
- 成功 suspend、ModelInvocation creation 与 quiesce 原子清 lease 并设置 `runnable=false`；异常兜底 release 保持 `runnable=true`。
- 进程内 dispatcher 按 Thread 合并 activation，并保留 active 期间的一次 rerun edge；跨节点并发仍以 PostgreSQL claim 为权威。恢复生命周期从启动即刻 fixed-delay 扫描 `runnable=true` 且 lease 可用的 Thread。
- 默认 `thread-processor-lease-duration=30s`、`thread-worker-concurrency=8`、`thread-reconciler-max-steps=16`、`thread-recovery-interval=1s`、`thread-recovery-batch-size=100`。

### 7.3 ToolWorker

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

### 7.4 SubagentTool

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
