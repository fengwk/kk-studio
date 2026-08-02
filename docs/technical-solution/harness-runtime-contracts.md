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
    long inputSequence,
    boolean runnable,
    long executionEpoch,
    long revision,
    Lease processorLease,
    Instant createdAt,
    Instant updatedAt) {}
```

`headEntryId` 必须为正数。Session 不保存 Thread；Thread 的当前 Session 通过 head Entry 查询派生。已提交 Entry append-only。

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
    String environmentName,
    boolean yoloEnabled) {}
```

`agentName` 必须是 canonical 非空名称；`environmentName` 可以为 null，否则必须是 canonical
非空名称。null 允许 model-only 或只含本地 Tool 的 Agent 执行；Agent 配置了 Environment
Tool 或 Skill 时，null 会明确产生 `ENVIRONMENT_REQUIRED`，不会静默移除能力。该值只保存名称引用和 YOLO 开关。

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
  Resolution resolve(TurnSettings settings);
}
```

`Resolution` 只有 `Resolved(ResolvedTurnExecution)` 与 `Failed(PlanningFailure)`。`DatabaseTurnExecutionResolver` 每次调用都读取：

```text
TurnSettings.agentName
  -> AgentDefinition
  -> providerName/modelName
  -> active AgentProvider
  -> AgentModel
  -> effective Variant
  -> READY LiveEnvironment
  -> ToolBinding / SkillBinding
```

Planning 成功时 ModelDescriptor 额外冻结当前 Provider 的非负 `providerVersion`。ModelWorker 随后只按 `(providerName, providerVersion)` 读取 append-only Provider revision；Provider 更新或软删除不会改变已持久化 invocation 的首次 dispatch/retry。缺失项对应 `PlanningFailureKind`：

```text
MISSING_TURN_SETTINGS
AGENT_NOT_FOUND
PROVIDER_NOT_FOUND
MODEL_NOT_FOUND
VARIANT_NOT_FOUND
ENVIRONMENT_REQUIRED
ENVIRONMENT_NOT_FOUND
TOOL_NOT_FOUND
SKILL_NOT_FOUND
INVALID_TURN_SETTINGS
```

解析失败由 Reconciler 写成 `ASSISTANT_ERROR` barrier；不产生假的 ProviderRequest，也不产生 ModelInvocation。

## 5. ModelInvocationPlanner

```java
PlanningResult plan(
    long sessionId,
    long sourceHeadEntryId,
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
- `skillBindings` 是本次选中的 Skill binding；
- `yoloEnabled` 与 request 同时冻结；
- JSON codec 只接受这四个顶层字段并严格校验嵌套结构。

Model retry 从持久化 request 重放同一份 ProviderRequest、ToolBinding、SkillBinding 与 YOLO。ToolInvocation 执行同一 binding，不从最新 Agent 或 Environment 重新选择。

## 7. ToolInvocation 与 Interaction

ToolInvocation 的 durable 字段包括 Thread、Assistant Entry、Model Invocation、ordinal、toolCallId、descriptor、arguments、Environment target、epoch、状态、attempt、lease、deadline、result/error、`appliedAt`、permission state 与 YOLO。

Environment target 为空时执行本地 Tool；非空时经 RemoteTool 发送。外部 I/O 前必须先持久化最终 descriptor/arguments 与 permission decision：

```text
PENDING -> ALLOWED -> external Tool I/O
PENDING -> ASKED  -> OPEN Interaction
PENDING -> DENIED -> FAILED
```

批准恢复同一 ToolInvocation 为 `QUEUED + ALLOWED`，拒绝为 `FAILED + DENIED`。每个 Tool 最多一个 OPEN Interaction。

## 8. Command coordinator

```java
HarnessThread createThread(String title);

HarnessThread updateHead(
    long threadId,
    long expectedExecutionEpoch,
    long headEntryId);

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

`createThread` 的 PostgreSQL transaction 原子写入 Session、ROOT 和已绑定 Thread。`updateHead` 只接受正 `headEntryId`，要求 Thread 静止并以 epoch CAS fencing。message/custom message 先按幂等键短路，再写 Input、sequence、runnable 和 execution target。

## 9. Reconciler transactions

事务端口至少提供：

- claim/renew/release Thread lease；
- 读取当前 head path、queued Inputs、terminal Invocations 与 blockers；
- 追加 Entry 并应用 planning failure；
- harvest 一个 TURN_INPUT_BATCH；
- 创建 ModelInvocation 并释放 Thread lease；
- 应用 Model terminal、Tool sibling terminal、Usage 与 head；
- 在锁内 recheck 后 quiesce。

所有 mutation 都校验 Thread token、execution epoch 和当前 head。Model terminal、Thread runnable 与 execution target 在同一事务提交。

## 10. Stop 与 head fencing

Stop 与 head update 都先锁 Thread 并检查 `expectedExecutionEpoch`：

```text
lock Thread
  -> verify epoch and quiescent state
  -> append stop barrier when required
  -> cancel queued/cancellable facts
  -> epoch++
  -> clear lease and runnable/target
```

旧 epoch 的 Model/Tool terminal callback 必须返回 ownership lost，不能写入新 head。

## 11. Realtime 与 ports

`RealtimeEventSink.append` 只写 bounded Redis projection；sink 失败不改变 durable terminal。`ExecutionTargetStore` 维护 Thread/Model/Tool target，并以 PostgreSQL NOTIFY 唤醒 dispatcher。Runtime 不执行 SQL，也不直接调用 Provider、Redis、HTTP 或 WebSocket。

客户端恢复顺序是 REST snapshot → durable revision SSE → Redis realtime overlay。revision 是唯一 durable cursor。
