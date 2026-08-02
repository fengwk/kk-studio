# Harness Runtime 架构

本文是当前 Harness 执行架构事实源，只描述现行模块、durable 事实、写者边界与 activation 协议。

## 1. Runtime 目标与边界

- PostgreSQL 保存唯一 durable truth，`harness_execution_activation` 保存唯一 durable activation queue。
- Thread 的 Entry/head 推进串行；Model、Tool、Interaction 通过各自 durable facts 继续执行。
- Thread activation 只做短时状态收敛，不在 activation 内等待外部 I/O。
- PostgreSQL listener、dispatcher wake、Environment READY 和 nearest-due timer 驱动 activation。
- Runtime domain 不依赖 Spring、数据库、Redis、HTTP、WebSocket、Provider SDK 或业务 Tool 实现。

## 2. 模块

```text
harness/
├── tool/       # Tool API、descriptor、RemoteTool、Daemon 协议
├── runtime/    # Session/Entry/Thread/Invocation/Interaction/Reconciler/Model 契约
└── daemon/     # 独立 Environment 进程，只依赖 tool
```

| 模块 | 当前职责 |
| --- | --- |
| `harness-tool` | `ToolDescriptor`、Tool API、schema、RemoteTool、Daemon wire |
| `harness-runtime` | Session、Entry、HarnessThread、ThreadInput、Planner、Reconciler、Model/Tool Worker、Interaction |
| `harness-daemon` | Environment 连接、本地 Tool 执行与 invocation journal |
| `core` | Spring composition、PostgreSQL adapter、dispatcher、Catalog/Environment resolver、Provider adapter |
| `web` | HTTP/SSE/WebSocket 与 DTO 适配 |

依赖方向：

```text
web -> core application API / share DTO
core -> harness-runtime -> harness-tool
core -> harness-tool
harness-daemon -> harness-tool
```

## 3. Durable 事实

### Session 与 Entry

Session 只组织一棵 append-only Entry Tree。Entry 类型固定为：

```text
ROOT
MESSAGE
CUSTOM_MESSAGE
ASSISTANT_ERROR
ASSISTANT_ABORTED
```

`ROOT` 是每个 Session 的唯一无 parent 根。Chat-scoped Thread 创建事务原子生成 Session、ROOT 与 head 指向 ROOT 的 Thread。

USER/CUSTOM message 的 Entry payload 还保存 compact `TurnSettings`：

```java
public record TurnSettings(
    String agentName,
    String environmentName,
    boolean yoloEnabled) {}
```

该值只携带名称与开关，不包含 Agent、Model、Provider、Tool 或 Skill 的展开定义。

### Thread

`HarnessThread` 保存：

```text
id
headEntryId      # 始终非空
inputSequence
runnable
executionEpoch
revision
processor lease
createdAt / updatedAt
```

Thread 行不保存 active Agent、Model、Variant、Environment、Tools 或 Skills。当前 Session 从 head Entry 派生，运行状态由查询投影：

```text
有效 processor lease                         -> RUNNING
open Interaction / 非终态 Invocation / queued Input -> WAITING
runnable=true                                -> RUNNABLE
无上述工作                                    -> IDLE
```

head 重定位是非空 Entry 的 CAS 更新，支持同 Session 或跨 Session 的历史路径切换。

### ThreadInput

mailbox 只接受 `USER_MESSAGE` 与 `CUSTOM_MESSAGE`。输入通过幂等键去重，按 sequence 排序，状态为 `QUEUED`、`APPLIED` 或 `CANCELLED`。

## 4. Reconciler

`ThreadReconciler` 持有短时 processor lease，是执行阶段 Entry/head 的唯一写者。一个 activation 的优先级为：

1. 应用 terminal Model Invocation；
2. 应用同一 Assistant 的 terminal Tool sibling；
3. 有 blocker 时挂起并等待 continuation；
4. 对当前 response debt 进行 planning；
5. 没有主要工作时按 sequence harvest snapshot 中全部 queued Input；
6. 从最终 head 最多创建一次 Model Invocation；
7. 没有工作时在 Thread 锁内 recheck 并 quiesce。

TURN_INPUT_BATCH 的边界是 activation 开始时读取的全部 queued Input。batch 之后到达的 Input 留给下一轮；已有 response debt 总是先于更晚输入完成。

## 5. Per-turn resolution 与 planning

`ModelInvocationPlanner` 从 root-to-head path 找到 response debt，并定位最近的 USER/CUSTOM TurnSettings。每次 plan 都调用 `DatabaseTurnExecutionResolver`：

1. 按 `agentName` 读取 active Agent；
2. 从 Agent 读取 `providerName`、`modelName` 与 variant；
3. 按名称读取 active Provider 和 `(providerName, modelName)` Model，并读取 Provider 当前 version；
4. 解析 Model config 与 effective Variant；
5. 读取指定且 READY 的 Environment；
6. 按 Agent tools/skills 解析本地或 Environment ToolBinding/SkillBinding；
7. 读取 ProviderFactory 的 cache capability 与 pricing；
8. 返回本轮的 system prompt、带冻结 `providerVersion` 的 ModelDescriptor、Variant、bindings 与 YOLO。

缺失 Agent、Provider、Model、Variant、Environment、Tool 或 Skill 返回 typed `PlanningFailure`。Reconciler 追加 `ASSISTANT_ERROR` barrier；不会创建虚假的 ProviderRequest 或 ModelInvocation。

## 6. ModelInvocation

规划器先投影语义消息，构造 Provider tools，应用 Prompt Cache finalizer，再创建：

```java
public record ModelInvocationRequest(
    ProviderRequest providerRequest,
    List<ToolBinding> toolBindings,
    List<SkillBinding> skillBindings,
    boolean yoloEnabled) {}
```

`providerRequest` 是 exact Provider transport payload。ModelDescriptor 的 `providerName/providerVersion` 是 Provider revision identity。Provider tools 与 `toolBindings` 按顺序、名称、描述和 schema 一一对应；SkillBinding 和 YOLO 同时冻结。写入 `harness_model_invocation.request` 后，ModelWorker 只回放这份 request，retry 也只解析同一 Provider revision。

Model Invocation 状态为 `QUEUED`、`RUNNING`、`RETRY_WAIT`、`SUCCEEDED`、`FAILED`、`CANCELLED`、`UNKNOWN`。retry 仍属于同一个 Invocation，只增加 attempt 并重新调度相同 request。Provider 已开始但 ownership 不确定时写 `UNKNOWN`。

## 7. ToolInvocation

Model terminal apply 时，Reconciler 按 Provider tool call 与冻结 ToolBinding materialize ToolInvocation。ToolInvocation 保存 descriptor、arguments、`environmentName`、permission state 与 request 的 `yoloEnabled`。

```text
environmentName == null
  -> ToolWorker -> local Tool

environmentName != null
  -> ToolWorker -> RemoteTool -> transport -> Daemon -> Tool
```

Tool permission 在任何外部 I/O 前完成：

```text
QUEUED + PENDING
  -> ALLOW -> persist ALLOWED -> external I/O
  -> ASK   -> WAITING_INTERACTION + OPEN Interaction
  -> DENY  -> FAILED + DENIED
```

批准只恢复原 Invocation 的 `QUEUED + ALLOWED`，继续使用原 binding；不重新解析 Agent 或 Environment。

## 8. 写者边界

| 写者 | 可写事实 | 不写 |
| --- | --- | --- |
| `ThreadCommandCoordinator` / command transaction | Session、ROOT、Thread 创建；非空 head CAS；Input；Stop | 执行中的 Entry/head 语义推进 |
| `ThreadReconciler` | Input apply、Entry/head、Model/Tool materialization、Usage apply | Provider/Tool 外部 I/O |
| `ModelWorker` | Model Invocation lease、delta、terminal | Entry/head |
| `ToolWorker` | Tool Invocation lease、partial、terminal | Entry/head |
| Interaction transaction | Interaction 与 owner activation/runnable | 越权 Entry |

Model terminal 与 Thread `runnable=true` 同事务；Reconciler 再在同一事务中写 Assistant Entry、Usage、ToolInvocation 与 head。

## 9. Activation

```text
durable mutation
  -> execution activation
  -> PostgreSQL NOTIFY
  -> listener
  -> dispatcher wake
  -> lock due activation
  -> dispatch target identity
  -> Reconciler / ModelWorker / ToolWorker
```

Dispatcher 每次重新读取 durable facts 与当前 READY Environment snapshot。通知可重复、乱序或丢失；启动/重连 wake 与 nearest-due timer 提供最终收敛。

## 10. Stop、fencing 与 realtime

Stop 通过 `expectedExecutionEpoch` 锁定当前 Thread，按 head response debt 写安全的 `ASSISTANT_ABORTED` 或 `ASSISTANT_ERROR(CANCELLED)`，取消可取消事实并递增 epoch。head 重定位使用同一 epoch CAS。旧 epoch 的 callback 因 token/epoch 不匹配而不能提交。

客户端先读取 Thread snapshot，再订阅 revision SSE。Redis `realtime` 只提供 text/thinking overlay；revision/resync 只触发 snapshot invalidate。

相关文档：

- [harness-runtime-contracts.md](harness-runtime-contracts.md)
- [harness-storage-runtime.md](harness-storage-runtime.md)
- [harness-capability-wiring.md](harness-capability-wiring.md)
- [environment-daemon-gateway.md](environment-daemon-gateway.md)
