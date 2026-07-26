# Harness PostgreSQL 与 Redis 设计

## 1. 数据职责

```text
PostgreSQL
  -> 唯一 durable truth
  -> Entry / Session / Thread / Input / Invocation / Interaction / Usage / Artifact / Goal / RetryPolicy

Redis Pub/Sub
  -> 可丢失的低延迟 wake hint

Redis Streams
  -> 可丢失、有界、短期可重放的 realtime projection
```

Redis 重启、清空或网络隔离后，只允许实时体验下降，不允许丢失用户 Input、Entry/head、Invocation terminal 或永久卡死推进。

## 2. PostgreSQL-only

项目只保留 PostgreSQL schema、driver、seed 与集成测试。所有持久化集成测试运行在真实 PostgreSQL 上。

权威 DDL：`core/src/main/resources/schema-postgresql.sql`。

## 3. ID 生成

Durable entity id 使用 PostgreSQL sequence `kk_studio_id_seq`，由 Store/IdGenerator 端口通过 `nextval` 分配。API 将 bigint 序列化为十进制字符串。Redis Stream id 仅作 realtime cursor。

## 4. 最小表模型

### 4.1 `harness_session`

`id`、`title`、可选 `parent_session_id`/`parent_invocation_id`、时间戳。`parent_invocation_id` 非空时唯一。Session 只组织一份 Entry Tree，不持有 Thread。Child Session 产品工作流当前未实现。

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

### 4.6 `harness_tool_invocation`

Assistant Entry、ordinal、ToolCall、descriptor/arguments、`PLATFORM/ENVIRONMENT`、execution epoch、worker lease、deadline/retry、terminal result/error、`applied_at`。

唯一 `(thread_id, assistant_entry_id, execution_epoch, ordinal)`。

不保存 permission 专用列；approval 走 `harness_interaction`。

Model/Tool Invocation 与 `harness_model_usage` 的 Thread 归属都是单列 `thread_id` FK；同表的 `session_id` 只作为约束载体，保证所引用的 Entry 与其属于同一 Session。

### 4.7 `harness_interaction`

owner reference、handler type、request/response jsonb、状态、deadline/version。

### 4.8 其他 durable 表

| 表 | 职责 |
| --- | --- |
| `harness_retry_policy` | 全局自动重试策略 |
| `harness_thread_goal` | Thread goal |
| `harness_artifact` | 不可变 Tool 输出 |
| `harness_model_usage` | Assistant 用量账本 |

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

## 6. Redis Pub/Sub wake

推荐 channel：

```text
kk-studio:harness:signal
```

消息：

```json
{
  "targetKind": "THREAD",
  "targetId": "..."
}
```

`targetKind`：`THREAD` / `MODEL_INVOCATION` / `TOOL_INVOCATION`。发布必须 afterCommit。重复调度由 PostgreSQL lease/fencing 合并。Pub/Sub 丢失不丢工作；recovery 扫描 `runnable=true` 或过期 lease。

Runtime 不感知 wake transport 实现细节。

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
- 每 Thread 独立 Stream，`XADD` 带 exact `MAXLEN`
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

## 11. Recovery

1. 扫描 `runnable=true`、`head_entry_id is not null` 且 processor lease 为空/过期的 Thread
2. 各 Invocation worker 扫描过期 lease、due retry 与 timeout
3. 重新发布 Redis hint 或本地 schedule

Recovery SQL 不 join Interaction/Tool/Model 来推导 runnable；完成事务负责写 `runnable=true`。

## 12. 故障语义

| 故障 | 处理 |
| --- | --- |
| Redis Pub/Sub 丢消息 | PostgreSQL runnable recovery |
| Redis Streams 清空 | 客户端 snapshot reload |
| Runtime 进程退出 | processor lease 过期后接管 |
| Model worker 在 Provider I/O 后失联 | RUNNING lease 过期后 `UNKNOWN` |
| QUEUED/RETRY_WAIT signal 丢失 | due scan 重新 dispatch |
| terminal callback 重复 | invocation token + terminal CAS |
| Stop / head 重定位与 terminal 并发 | execution epoch fencing |
| notify 先于 commit | 禁止；只允许 afterCommit |
| realtime sink 失败 | 不影响 durable terminal |

## 13. 测试架构

### 13.1 Runtime domain

- 纯单元测试：状态机、TURN_BOUNDARY、response debt、suspend/resume、lease/fencing
- 核心路径行覆盖率目标 ≥ 90%

### 13.2 PostgreSQL Integration

- Testcontainers PostgreSQL
- recursive path、enqueue/quiesce、Stop 与 head 重定位的 epoch CAS、recovery 查询

### 13.3 Redis Integration

- Pub/Sub wake、Streams cursor/trim、Redis loss 后 PostgreSQL recovery、snapshot-first SSE

### 13.4 E2E

- Stub Model/Tool terminal 驱动
- Platform/Environment transport
- Interaction resolution
- 真 Provider 与 Daemon 可选矩阵

## 14. 本地运行

`deploy/local/compose.yaml` 一键启动内嵌 React 的 Spring Boot `app`、PostgreSQL 与 Redis。PostgreSQL 只在空命名卷首次创建时执行 `schema-postgresql.sql` 与 `data-dev-postgresql.sql`；`app` 设置 `SPRING_SQL_INIT_MODE=never`，重启不重复初始化 schema。Harness Daemon 不属于默认 Compose 栈。
