# Harness Runtime 契约

本文定义当前 Runtime 实现必须遵守的类型、状态、规划、写者、事务与 fencing 契约。

## 1. 模块与 ID

Runtime 模块包含 `session`、`entry`、`thread`、`model`、`tool`、`reconcile`、`interaction`、`usage`、`retry` 与 `realtime`。Runtime 内部使用 `long` durable ID；HTTP 边界使用十进制字符串。Catalog 资源使用永久名称身份：Provider/Agent 是 `name`，Model 是 `(providerName, name)`；普通 lookup 只看 active 行。

## 2. Session、Entry、Thread

```java
public record Session(long id, String title, Instant createdAt) {}

public record HarnessThread(
    long id,
    long headEntryId,
    String environmentName,
    long inputSequence,
    boolean runnable,
    long executionEpoch,
    long revision,
    Lease processorLease,
    Instant createdAt,
    Instant updatedAt) {}
```

`headEntryId` 必须为正数；`environmentName` 可为 null，否则必须是 canonical 非空名称。Session 不保存 Thread；Thread 的当前 Session 通过 head Entry 查询派生。已提交 Entry append-only。创建 Thread 时可原子指定 Environment，静止 Thread 可通过 fenced Environment command 设置或清除它。

Entry 类型固定为：

```java
ROOT, MESSAGE, CUSTOM_MESSAGE, ASSISTANT_ERROR, ASSISTANT_ABORTED
```

Input 类型固定为：

```java
USER_MESSAGE, CUSTOM_MESSAGE
```

USER `MESSAGE` 与 `CUSTOM_MESSAGE` payload 必须携带：

```java
public record TurnSettings(
    String agentName,
    boolean yoloEnabled) {}
```

`agentName` 必须是 canonical 非空名称。该值只保存 Agent 名称引用和 YOLO 开关；消息 DTO 不携带
`environmentName`。Planning 额外读取 Thread 当前 Environment，Environment 不可用时只贡献零
Environment Tool/Skill。

## 3. Invocation 状态

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

- `SUCCEEDED`、`FAILED`、`CANCELLED`、`UNKNOWN` 是 terminal。
- `RUNNING` 必须拥有 worker lease、startedAt、deadlineAt 与 activity。
- `RETRY_WAIT` 必须拥有 `nextAttemptAt`，但不拥有 worker lease。
- Provider I/O 已开始且 ownership 不确定时使用 `UNKNOWN`，不自动重放副作用。

## 4. Per-turn resolver

```java
@FunctionalInterface
public interface TurnExecutionResolver {
  Resolution resolve(TurnSettings settings, String environmentName);
}
```

`Resolution` 只有 `Resolved(ResolvedTurnExecution)` 与 `Failed(PlanningFailure)`。`DatabaseTurnExecutionResolver` 每次调用都读取最新：

```text
TurnSettings.agentName
  -> AgentDefinition
  -> providerName/modelName
  -> active AgentProvider
  -> AgentModel
  -> effective Variant
  -> ToolCatalog
  -> Thread environmentName
  -> READY LiveEnvironment contributions
  -> ToolBinding / SkillBinding
```

Agent 配置中的 Tool 名必须命中可选择目录，未知 Tool 返回 `TOOL_NOT_FOUND`。Platform Tool 总可候选，
READY Environment 才贡献 Environment Tool/Skill，配置的 Environment Tool/Skill 与当前能力取交集；
null、stale 或 offline Environment 只产生零 Environment Tool/Skill，不产生 Environment/Skill 缺失错误。
Planning 成功时 ModelDescriptor
额外冻结当前 Provider 的非负 `providerVersion`。ModelWorker 随后只按 `(providerName, providerVersion)`
读取 append-only Provider revision；Provider 更新或软删除不会改变已持久化 invocation 的首次
dispatch/retry。缺失项对应 `PlanningFailureKind`：

```text
MISSING_TURN_SETTINGS
AGENT_NOT_FOUND
PROVIDER_NOT_FOUND
MODEL_NOT_FOUND
VARIANT_NOT_FOUND
TOOL_NOT_FOUND
INVALID_TURN_SETTINGS
```

解析失败由 Reconciler 写成 `ASSISTANT_ERROR` barrier；不产生假的 ProviderRequest，也不产生 ModelInvocation。

## 5. ModelInvocationPlanner

```java
PlanningResult plan(
    long sessionId,
    long sourceHeadEntryId,
    String environmentName,
    List<SessionEntry> rootToHead);
```

约束：

- path 必须从无 parent ROOT 开始，parent 连续，最后 Entry 等于 `sourceHeadEntryId`。
- Planner 只读取传入 path 与 resolver，不直接访问 Store、Spring、Clock 或 Provider。
- 先检测 response debt，再定位 debt 前缀中最近的 USER/CUSTOM TurnSettings。
- 使用 resolver 的最新结果投影 system prompt、消息、Skill system section 和 Provider tool definitions。
- Prompt Cache finalizer 在冻结前生成最终 cache control。
- queued message 不会修改已经存在 response debt 的 request。

`PlanningResult` 为 `NoDebt`、`Planned(ModelInvocationPlan)` 或 `Failed(PlanningFailure)`。

## 6. ModelInvocationRequest

```java
public record ToolBinding(
    ToolDescriptor descriptor,
    ToolType type,
    String environmentName) {}

public record ModelInvocationRequest(
    ProviderRequest providerRequest,
    List<ToolBinding> toolBindings,
    List<SkillBinding> skillBindings,
    boolean yoloEnabled) {}
```

这是一次 Model Invocation 的唯一冻结请求：

- `providerRequest` 是完整、exact 的 Provider transport payload；
- `providerRequest.tools` 与 `toolBindings` 数量、顺序、名称、description、input schema 必须完全一致；
- 每个 `ToolBinding` 同时冻结 descriptor、product-level `type` 与 `environmentName`；`PLATFORM` binding 的目标为 null，`ENVIRONMENT` binding 指向具体 Environment；
- `skillBindings` 是本次选中的 Skill binding；
- `yoloEnabled` 与 request 同时冻结；
- 有 Skill binding 时隐式加入内部 Platform Tool `load_skill`，它不属于 Agent 可选择目录；
- JSON codec 只接受这四个顶层字段并严格校验嵌套结构。

Model retry 从持久化 request 重放同一份 ProviderRequest、ToolBinding、SkillBinding 与 YOLO。ToolInvocation 执行同一 binding，不从最新 Agent 或 Environment 重新选择。

Provider 返回的 ToolCall 必须命中本次冻结 `providerRequest.tools`。如果返回不可见或未知 Tool，
Model invocation 终结为可恢复错误，错误消息包含请求的 Tool 名称和本次可用 Tool 名称；Reconciler
追加 `ASSISTANT_ERROR` barrier，不物化 ToolInvocation，后续输入仍可继续处理。

## 7. ToolInvocation 与 Interaction

ToolInvocation 的 durable 字段包括 Thread、Assistant Entry、Model Invocation、ordinal、
toolCallId、含 type 的 descriptor、arguments、`environmentName`、epoch、状态、attempt、lease、
deadline、result/error、`appliedAt`、permission state 与 YOLO。

`PLATFORM` binding 由本地 Platform Tool 执行；`ENVIRONMENT` binding 经 RemoteTool 发送到冻结的
Environment。外部 I/O 前必须先持久化最终 descriptor/arguments 与 permission decision：

```text
PENDING -> ALLOWED -> external Tool I/O
PENDING -> ASKED  -> OPEN Interaction
PENDING -> DENIED -> FAILED
```

批准恢复同一 ToolInvocation 为 `QUEUED + ALLOWED`，拒绝为 `FAILED + DENIED`。每个 Tool 最多一个 OPEN Interaction。

## 8. Command coordinator

```java
HarnessThread createThread(String title, String environmentName);

HarnessThread updateHead(
    long threadId,
    long expectedExecutionEpoch,
    long headEntryId);

HarnessThread updateEnvironment(
    long threadId,
    long expectedExecutionEpoch,
    String environmentName);

EnqueueResult submitUserMessage(
    long threadId,
    TurnSettings settings,
    String content,
    String idempotencyKey,
    long expectedExecutionEpoch);

EnqueueResult submitCustomMessage(
    long threadId,
    TurnSettings settings,
    String role,
    String content,
    String idempotencyKey,
    long expectedExecutionEpoch);

StopResult stop(long threadId, long expectedExecutionEpoch);
```

`createThread` 的 PostgreSQL transaction 原子写入 Session、ROOT 和已绑定 Thread，可同时写入 nullable
Environment。`updateHead` 只接受正 `headEntryId`，要求 Thread 静止并以 epoch CAS fencing。
`updateEnvironment` 同样要求 Thread 静止与 epoch 匹配，可设置或清除 Environment；成功递增
`executionEpoch` 与 `revision`，运行中或 epoch 陈旧返回 `409`。message/custom message 先按幂等键
短路，再写 Input、sequence、runnable 和 execution activation。

## 9. Reconciler transactions

事务端口至少提供：

- claim/renew/release Thread lease；
- 读取当前 head path、queued Inputs、terminal Invocations 与 blockers；
- 追加 Entry 并应用 planning failure；
- harvest 一个 TURN_INPUT_BATCH；
- 创建 ModelInvocation 并释放 Thread lease；
- 应用 Model terminal、Tool sibling terminal、Usage 与 head；
- 在锁内 recheck 后 quiesce。

所有 mutation 都校验 Thread token、execution epoch 和当前 head。Model terminal、Thread runnable 与 execution activation 在同一事务提交。

## 10. Stop 与 head fencing

Stop 与 head update 都先锁 Thread 并检查 `expectedExecutionEpoch`：

```text
lock Thread
  -> verify epoch and quiescent state
  -> append stop barrier when required
  -> cancel queued/cancellable facts
  -> epoch++
  -> clear lease and runnable/activation
```

旧 epoch 的 Model/Tool terminal callback 必须返回 ownership lost，不能写入新 head。

## 11. Realtime 与 ports

`RealtimeEventSink.append` 只写 bounded Redis projection；sink 失败不改变 durable terminal。`ExecutionActivationStore` 维护 Thread/Model/Tool 的 ExecutionActivation，并以 PostgreSQL NOTIFY 唤醒 dispatcher。Runtime 不执行 SQL，也不直接调用 Provider、Redis、HTTP 或 WebSocket。

客户端恢复顺序是 REST snapshot → durable revision SSE → Redis realtime overlay。revision 是唯一 durable cursor。
