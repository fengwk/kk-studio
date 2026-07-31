# Harness PostgreSQL 与 Redis 设计

## 1. 数据职责

```text
PostgreSQL
  -> 唯一 durable truth
  -> Entry / Session / Thread / Input / Invocation / Interaction / Usage / Artifact / Goal / RetryPolicy
  -> harness_execution_target：唯一 durable activation queue

Redis Streams
  -> 可丢失、有界、短期可重放的 realtime projection
```

Redis 重启、清空或网络隔离不会丢失 PostgreSQL 中的用户 Input、Entry/head、Invocation terminal 等 durable facts；Redis 只影响 realtime tail。`harness_execution_target` 行与其 PostgreSQL `NOTIFY` trigger 负责 activation：通知可丢失，但 listener 启动/重连 wake 与 nearest-due timer 均会重新读取 durable target。

## 2. PostgreSQL-only

项目只保留 PostgreSQL schema、driver、seed 与集成测试。所有持久化集成测试运行在真实 PostgreSQL 上。

权威 DDL：[`V1__schema.sql`](../../core/src/main/resources/db/migration/V1__schema.sql)，由 Flyway 执行。

## 3. ID 生成

Durable entity id 使用 PostgreSQL sequence `kk_studio_id_seq`，由 Store/IdGenerator 端口通过 `nextval` 分配。API 将 bigint 序列化为十进制字符串。Redis Stream id 仅作 realtime cursor。

## 4. 最小表模型

### 4.1 `harness_session`

`id`、`title`、`created_at`（`timestamptz(3)`）。Session 只组织一份 append-only Entry Tree，不持有 Thread 也不持有 Thread 归属。Session 由 Thread `bootstrapThread` 内部私有 helper 与 ROOT / `RUNTIME_CONFIG` Entry 一起原子创建。Session DTO `updateTime` 为 derived observable：列表查询按 `max(entry.created_at)` 派生，没有 stored `updated_at` 列。

### 4.2 `harness_entry`

`id`、`session_id`、`parent_entry_id`、`entry_type`、`payload` jsonb、`created_at`。

- unique `(session_id, id)`
- parent 必须同 Session；ROOT 无 parent
- `RuntimeEntryPayloadJsonCodec` 严格编解码
- 路径用 recursive CTE，禁止应用层逐节点 SELECT

### 4.3 `harness_thread`

`id`、可空 `head_entry_id`、`input_sequence`、`runnable`、`execution_epoch`、`processor_token`/`processor_until`、时间戳。

`head_entry_id` 是指向全局 Entry id 空间的单列 FK，可空（UNBOUND）；跨 Session 归属由 Thread command 在上游校验。Thread 不保存 `session_id`：当前 Session 由 head Entry 派生。

也不保存 Agent/Model/Variant、YOLO、retry、waiting reason 或流式状态。

### 4.4 `harness_thread_input`

`id`、`thread_id`、`sequence`、`input_type`、`payload`、幂等键、`status`、时间。

唯一：`(thread_id, sequence)`、幂等键。enqueue、sequence 分配与 `runnable=true` 同事务；UNBOUND Thread 拒绝入队。

### 4.5 `harness_model_invocation`

source head、execution epoch、完整 `ProviderRequest` snapshot、状态、worker lease、deadline/activity、retry、terminal response/error、`applied_at`。

唯一 `(thread_id, source_head_entry_id, execution_epoch)`。

`safe_stream_snapshot` jsonb 仅在 RUNNING/RETRY_WAIT/终态下保存 text/thinking 累积；每次 text/thinking SSE delta 之前 `recordSafeStreamSnapshot` 必须以 Thread + Invocation 锁 fenced 写入。新 retry attempt 在 CAS 中由 `safe_stream_snapshot = null` 重置，确保旧 attempt 的 partial 不会跨 attempt 污染下一轮的 Provider 上下文。`/stop` 通过 `(threadId, executionEpoch, sourceHeadEntryId)` 加 `safe_stream_snapshot is not null AND applied_at is null` 的 head-scoped 谓词读取该列，包括已经 `SUCCEEDED` 但 `applied_at is null` 的窗口，使得 partial 输出不被 stop 丢失。

### 4.6 `harness_tool_invocation`

Assistant Entry、ordinal、ToolCall、descriptor/arguments、`PLATFORM/ENVIRONMENT`、execution epoch、worker lease、deadline/retry、terminal result/error、`applied_at`、`permission_state` 与冻结的 `yolo_enabled`。

唯一 `(thread_id, assistant_entry_id, execution_epoch, ordinal)`。

权限状态机是 Tool 执行事实的一部分：

- 新行是 `QUEUED + PENDING`；`yolo_enabled` 从生成该 ToolCall 的冻结 RuntimeConfig 复制。
- `ALLOW` 先在同一 Tool worker lease 内持久化最终 descriptor/arguments 与 `ALLOWED`，然后才允许任何外部 Tool I/O。
- `ASK` 原子写入最终计划、`WAITING_INTERACTION + ASKED`、一个以 `tool_invocation_id` 关联该 Tool 的 OPEN Interaction，并 park 该 Tool target。
- 用户批准把行转为 `QUEUED + ALLOWED` 并仅经原 route FIFO gate 重新启用 target；拒绝转为 `FAILED + DENIED`，删除 Tool target、唤醒 Thread 并激活下一个环境 route head。

Model/Tool Invocation 与 `harness_model_usage` 的 Thread 归属都是单列 `thread_id` FK。`harness_model_invocation` 不持有 `session_id`，`source_head_entry_id` 单列 FK 到 `harness_entry(id)`；`harness_tool_invocation` 与 `harness_model_usage` 的 `session_id` 仍作为约束载体，保证所引用的 Entry 与其属于同一 Session。

### 4.7 `harness_interaction`

`tool_invocation_id`、request/response jsonb、`OPEN/RESOLVED` 状态与 version；每个 Tool 最多一个 OPEN 行。

### 4.8 其他 durable 表

| 表 | 职责 |
| --- | --- |
| `harness_retry_policy` | 全局自动重试策略（id=1 单行；不持久化时间戳） |
| `harness_realtime_stream_policy` | 全局 Redis realtime Stream 容量策略（id=1 单行；`max_length > 0`；默认 5000） |
| `harness_thread_goal` | Thread goal |
| `harness_artifact` | 不可变 Tool 输出 |
| `harness_model_usage` | Assistant 用量账本（持久化 token/pricing/created_at；`ModelCost` 由 `ModelCost.calculate(pricing, usage)` 重建） |

## 5. Runnable 与 activation

任何能推进 Thread Reconciler 的事务写：

```sql
update harness_thread
set runnable = true,
    updated_at = current_timestamp
where id = :thread_id;
```

必须与对应领域事实同事务（Input insert、Model/Tool terminal、Interaction resolution）。

Model/Tool retry 到期只 dispatch 对应 Invocation，terminal 前不必激活 Thread。

`harness_execution_target` 是唯一 durable activation queue：每个 `(target_kind, target_id)` 只有一行，
`dispatch_enabled` 是显式 dispatch gate。`lock` 与 `findAll` 可见 disabled parked row，但 `lockDue`、due
扫描和 nearest-due timer 只读取 enabled row。PLATFORM target 通过 `schedule` 正常启用；ENVIRONMENT
Tool target 先通过 `park` 写入 disabled row，完成同一批 materialization 后按 route 激活 oldest queued
head。route FIFO 顺序固定为 joined Tool invocation 的 `created_at, assistant_entry_id, ordinal, id`；
RUNNING、RETRY_WAIT 或 WAITING_INTERACTION head 都阻塞后续 sibling。Tool terminal 删除当前
target、调度 owning Thread 后，在同一事务中激活下一 route head。启用插入、enable transition 和
enabled row 的严格提前会触发 PostgreSQL NOTIFY；parked/disabled row rewrite 与 lease extension 不会。

Thread claim 条件：`runnable=true`、`head_entry_id is not null` 且 processor lease 为空/过期。claim 写新 token/until，不递增 `execution_epoch`。成功 suspend/create ModelInvocation/quiesce 时清 lease 并 `runnable=false`；异常 release 保留 `runnable=true`。

quiesce 前锁 Thread 并 recheck：queued Input、terminal-unapplied Invocation、blocker、response debt。

Stop 与 head 重定位（bootstrap / rebind / unbind）是仅有的两条递增 `execution_epoch` 的路径，均先锁 Thread 行并 CAS `expectedExecutionEpoch`：

```sql
update harness_thread
set head_entry_id = :head_entry_id,
    execution_epoch = :expected_epoch + 1,
    processor_token = null,
    processor_until = null,
    runnable = false,
    updated_at = greatest(updated_at, :now)
where id = :thread_id
  and execution_epoch = :expected_epoch;
```

head 重定位额外要求 Thread 逻辑静止：无有效 processor lease、`runnable=false`、无 QUEUED Input、当前 epoch 无非终态 Model/Tool Invocation、无相关 OPEN Interaction。Stop 会取消 queued Input、可安全取消的 Invocation 与该 Thread 的 OPEN Interaction，因此逻辑 stop 之后即满足上述条件。epoch 递增后，旧代际 worker 的 terminal 写入均因 epoch fencing 失败。

`/stop` 还要求沿用 Reconciler 的 `ModelInvocationPlanner` 判定当前 head 是否仍有未终结 response debt；若存在 response debt，原子追加 `ASSISTANT_ABORTED`（仅 text/thinking，head-scoped `findSafeStreamSnapshotByHead(threadId, epoch, sourceHeadEntryId)` 拿到的快照）或 `ASSISTANT_ERROR(CANCELLED)` barrier 并 fence epoch。Stop 自身只是一道 epoch fence，不修改已 RUNNING/SUCCEEDED 的 invocation 行；tool/空 partial 不物化 `ToolInvocation` 或 tool fragment。

## 6. PostgreSQL execution-target activation

`harness_execution_target` 是唯一调度事实源。enabled target 的 insert、enable transition 与严格提前 reschedule 由 schema trigger 在事务提交时投递 PostgreSQL `NOTIFY harness_execution_target`；通知本身不是队列，也不承载 target JSON。

生产链路：

```text
durable target mutation
  -> PostgreSQL NOTIFY
  -> PostgresqlExecutionTargetListener
  -> PostgresqlExecutionTargetDispatcher.wake()
  -> due enabled target scan
  -> ThreadReconciler.activate / ModelWorker.dispatch / ToolWorker.dispatch
```

dispatcher 在启动、listener 连接或重连、收到 `NOTIFY`、Environment READY 以及 nearest-due timer 时 coalesced wake。每个 handler 重新锁定自己的 target 与领域事实；重复 wake、过期 snapshot 或丢失通知均不改变正确性。ENVIRONMENT eligibility 只读取 `LiveEnvironmentRegistry.listReady()` 的当前快照，READY 回调只调用 `dispatcher.wake()`。

## 7. Redis Streams realtime projection

流式 Model delta 与 Tool partial 写入 bounded Redis Stream。

```json
{
  "threadId": "...",
  "subjectKind": "MODEL_INVOCATION",
  "subjectId": "...",
  "attempt": 1,
  "type": "MODEL_DELTA",
  "payload": {
    "kind": "TEXT_DELTA",
    "text": "..."
  },
  "createdAt": "..."
}
```

约束：

- payload discriminator：`TEXT_DELTA` / `THINKING_DELTA` / `TOOL_CALL_DELTA`
- 可重试 Invocation 必须携带 `attempt`
- 每 Thread 独立 Stream，`XADD` 带 exact `MAXLEN`；长度从全局 `harness_realtime_stream_policy.max_length` 读取
- 策略保存后，本实例的既有与新建 Stream 都在下一次 `XADD` 使用新长度；其他实例最多在一秒缓存刷新后生效。调小在该次写入裁剪，调大不恢复已裁剪 event；空闲 Stream 不主动扫描或裁剪
- Event 不用于状态恢复或业务审计
- Redis 丢失后允许 in-flight 动画缺口；最终 Entry/Invocation 不受影响

SSE adapter 事件名 `realtime`，cursor 为 Redis stream-id。

## 8. Snapshot-first 客户端恢复

1. 加载 Thread derived view
2. 加载 Entry path
3. 加载 queued Inputs
4. 加载 active/terminal-unapplied Invocations
5. 加载 open Interactions
6. 订阅 Redis-backed SSE tail（`realtime`）

cursor 失效或 Redis 重启时重复完整 snapshot，不从丢失 delta 重建权威状态。

## 9. Retry 与 timeout

Retry/timeout 是 Invocation 属性：

- `attempt`
- `next_attempt_at`
- `deadline_at`
- `last_activity_at`

PostgreSQL 时间字段是事实。已过期 `RUNNING` lease 终态 `UNKNOWN`，不得重放。

Model：

- 首次 `QUEUED -> RUNNING` 建立总 `deadline_at`；retry 不得延长
- worker heartbeat 不是 progress activity
- Provider delta 是真实 activity；terminal 不以 callback 到达时刻伪造 activity

## 10. 锁序

```text
Thread
  -> owning Invocation
  -> Interaction
  -> Entry/Input append
```

## 11. Activation runtime

`PostgresqlExecutionTargetListener` 只监听 PostgreSQL 并调用 dispatcher wake；不执行 Model 或 Tool 外部 I/O。dispatcher 对 Thread target 调用 `ThreadReconciler.activate()`；对 Model target 调用 `ModelWorker.activate()`，后者先同步 durable claim、再把 Provider I/O 交给 worker scheduler；对 Tool target 同步执行 durable claim 后由 ToolWorker 自己的 executor 承担外部 I/O。已开始执行的 heartbeat、deadline、idle 与 retry timer 仍是 worker 的局部执行控制；dispatcher 不做周期性全表扫描，只维护单个 nearest-due timer。

## 12. 故障语义

| 故障 | 处理 |
| --- | --- |
| PostgreSQL NOTIFY 遗失或 listener 重连 | durable target 保持不变；启动/重连 wake 与 nearest-due timer 重新读取 |
| Redis Streams 清空 | 客户端 snapshot reload |
| Runtime 进程退出 | 已提交 durable row 保持不变 |
| Model worker 在 Provider I/O 后失联 | RUNNING lease 过期后 `UNKNOWN` |
| QUEUED/RETRY_WAIT wake 未送达 | durable target 保持不变；后续 dispatcher wake 或 nearest-due timer 重新读取 |
| terminal callback 重复 | invocation token + terminal CAS |
| Stop / head 重定位与 terminal 并发 | execution epoch fencing |
| execution-target notify 先于 commit | PostgreSQL 仅在提交后投递 trigger 产生的 NOTIFY |
| realtime sink 失败 | 不影响 durable terminal |

## 13. 测试架构

### 13.1 Runtime domain

- 纯单元测试：状态机、TURN_INPUT_BATCH、response debt、suspend/resume、lease/fencing
- 核心路径行覆盖率目标 ≥ 90%

### 13.2 PostgreSQL Integration

- Testcontainers PostgreSQL
- recursive path、enqueue/quiesce、Stop 与 head 重定位的 epoch CAS、指定 target claim 查询

### 13.3 Redis Integration

- Streams cursor/trim、snapshot-first SSE

### 13.4 E2E

- Stub Model/Tool terminal 驱动
- Platform/Environment transport
- Interaction resolution
- 真 Provider 与 Daemon 可选矩阵

## 14. 本地运行

`deploy/local/compose.yaml` 一键启动内嵌 React 的 Spring Boot `app`、PostgreSQL 与 Redis。`app` 在 `dev` profile 通过 Flyway 执行 [`V1__schema.sql`](../../core/src/main/resources/db/migration/V1__schema.sql) 和 [`V2__dev_seed.sql`](../../core/src/main/resources/db/seed/dev/V2__dev_seed.sql)；`flyway_schema_history` 使重启成为安全 no-op。Harness Daemon 不属于默认 Compose 栈。
