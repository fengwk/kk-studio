# Harness PostgreSQL 与 Redis 设计

## 1. 数据职责

```text
PostgreSQL
  -> Entry / Session / Thread / Input / Invocation / Interaction / Usage / Artifact / Goal
  -> retry policy / realtime policy
  -> harness_execution_target durable activation queue

Redis Streams
  -> 有界、可丢失的 realtime projection
```

PostgreSQL 是唯一 durable truth。Redis 重启或清空只会造成流式 overlay 缺口；客户端重新读取 Thread snapshot 即可恢复权威状态。

权威 DDL 是 [`V1__schema.sql`](../../core/src/main/resources/db/migration/V1__schema.sql)。所有 durable runtime ID 由 PostgreSQL sequence `kk_studio_id_seq` 分配，在 API 中编码为十进制字符串。Catalog 使用名称身份，不使用 bigint resource ID。

## 2. Session、Entry 与 Thread

### `harness_session`

字段为 `id`、`title`、`created_at`。Session 只组织一棵 append-only Entry Tree。Chat-scoped Thread 创建事务先写 Session，再写唯一 ROOT，最后写入 head 指向 ROOT 的 Thread。

### `harness_entry`

字段为 `id`、`session_id`、`parent_entry_id`、`entry_type`、`payload`、`created_at`。

- `ROOT` 无 parent；其余 Entry 必须有同 Session parent。
- 每个 Session 只有一个 ROOT。
- Entry 通过 recursive CTE 读取 root-to-head path。
- payload 由 `RuntimeEntryPayloadJsonCodec` 严格编解码。

允许的 `entry_type`：

```text
ROOT
MESSAGE
CUSTOM_MESSAGE
ASSISTANT_ERROR
ASSISTANT_ABORTED
```

### `harness_thread`

字段为 `id`、非空 `head_entry_id`、`input_sequence`、`runnable`、`execution_epoch`、`revision`、processor lease 与时间戳。Thread 行不保存 Chat 设置、Agent、Model、Variant、Tool 或 Skill 投影；当前 Session 由 head Entry 派生。

`PUT /api/ai/runtime/threads/{threadId}/head` 只能写入非空 Entry 引用。命令先锁 Thread、校验静止条件和 `expectedExecutionEpoch`，再递增 epoch 并清理 processor lease 与 Thread target。

## 3. Thread Input

`harness_thread_input` 保存 `thread_id`、sequence、`input_type`、payload、幂等键、状态和时间。

允许的 `input_type`：

```text
USER_MESSAGE
CUSTOM_MESSAGE
```

enqueue 在同一事务内分配 sequence、写 Input、设置 `runnable=true` 并确保 Thread execution target。Input payload 中的 `TurnSettings` 只有：

```text
agentName
environmentName
yoloEnabled
```

它是名称引用，不展开当前 Catalog 定义。USER/CUSTOM Input 被 harvest 后，对应 Entry 继续保留同一份 TurnSettings。

## 4. Invocation 表

### `harness_model_invocation`

保存 source head、execution epoch、完整 `ModelInvocationRequest` JSON、状态、attempt、worker lease、deadline、activity、result/error、`applied_at` 与安全流快照。

唯一键为 `(thread_id, source_head_entry_id, execution_epoch)`。安全流快照只包含 text/thinking 的 attempt-local 累积；新的 retry attempt 在 CAS 中清空它，防止不同 attempt 的 partial 混入 Provider 上下文。

### `harness_tool_invocation`

保存 Assistant Entry、所属 Model Invocation、ordinal、tool call id、descriptor/arguments、`environment_name`、权限状态、`yolo_enabled`、状态、lease、retry、result/error 与 `applied_at`。

`environment_name` 是 ToolBinding 的冻结目标：为空时由本地 Tool 执行，非空时经 RemoteTool 发往指定 READY Environment。发送前目标不可用写 `FAILED`；发送结果不确定写 `UNKNOWN`，两者都由 durable facts 记录。

### `harness_interaction`

保存 Tool permission request/response、`OPEN/RESOLVED`、version 与时间。每个 Tool 最多一个 OPEN Interaction。

## 5. Durable activation

`harness_execution_target` 对每个 Thread、Model Invocation 和 Tool Invocation 保留唯一行：

```text
(target_kind, target_id) -> route_key, dispatch_enabled, available_at
```

- Thread 与本地 Tool target 直接 schedule 为 enabled。
- Environment Tool target 先以 route key park，完成同一批 materialization 后只 enable 该 route 的 FIFO head。
- due scan、nearest-due timer 只读取 enabled row；ownership 查询仍能看到 parked row。
- enabled insert、enable transition 或更早的 reschedule 由 PostgreSQL trigger 在 commit 后发送 NOTIFY。

生产链路：

```text
durable target mutation
  -> PostgreSQL NOTIFY
  -> PostgresqlExecutionTargetListener
  -> PostgresqlExecutionTargetDispatcher.wake()
  -> due target claim
  -> ThreadReconciler / ModelWorker / ToolWorker
```

dispatcher 在启动、数据库重连、NOTIFY、Environment READY 和 nearest-due timer 时 coalesced wake。每次 handler 都重新锁定 durable target 与领域事实；通知丢失不改变正确性。

## 6. Redis realtime

每个 Thread 使用 bounded Redis Stream 保存 Model delta 与 Tool partial：

```json
{
  "threadId": "123",
  "subjectKind": "MODEL_INVOCATION",
  "subjectId": "456",
  "attempt": 1,
  "type": "MODEL_DELTA",
  "payload": {
    "kind": "TEXT_DELTA",
    "text": "..."
  }
}
```

- realtime event 没有业务恢复语义，也不用于审计。
- Stream 长度由 `harness_realtime_stream_policy.max_length` 控制，下一次写入时应用。
- revision SSE 帧使用 PostgreSQL durable revision；Redis stream id 不暴露为 SSE id。
- 浏览器重连只携带 revision，重新读取 snapshot 后从 Redis live edge 接收新 delta。

## 7. Stop 与 head 重定位

Stop 和 head 重定位都在 Thread 行锁内校验 `expectedExecutionEpoch` 并递增 epoch。Stop 会：

1. 按当前 head 的 response debt 读取安全 text/thinking snapshot；
2. 有安全内容时追加 `ASSISTANT_ABORTED`，否则追加 `ASSISTANT_ERROR(CANCELLED)`；
3. 取消 queued Input、可安全取消的 Invocation 与 OPEN Interaction；
4. 清理 lease、更新 Thread target 并 fence 旧 epoch。

head 重定位要求 Thread 逻辑静止：没有有效 processor lease、runnable work、queued Input、当前 epoch 的非终态 Invocation 或 OPEN Interaction。成功后旧 epoch 的 terminal callback 通过 CAS 被拒绝。

## 8. Retry 与故障语义

Invocation 保存 `attempt`、`next_attempt_at`、`deadline_at` 与 `last_activity_at`。Retry 在同一 Model Invocation 上重新 dispatch，完整 `ModelInvocationRequest`、ToolBinding、SkillBinding 与 YOLO 不变；Provider 已开始但 ownership 丢失时收敛为 `UNKNOWN`，不重放不确定副作用。

| 故障 | durable 处理 |
| --- | --- |
| PostgreSQL NOTIFY 丢失 | 后续 wake、重连 wake 或 nearest-due timer 重新读取 target |
| Redis Stream 丢失 | 客户端重新获取 snapshot |
| Runtime 进程退出 | 已提交的 Entry、Input、Invocation 与 ledger 保留 |
| Tool 目标在发送前不可用 | Tool Invocation `FAILED` |
| Tool 发送结果不确定 | Tool Invocation `UNKNOWN` |
| terminal callback 重复 | invocation token、attempt 与 terminal CAS |
| Stop/head 与 terminal 并发 | execution epoch fencing |

## 9. 验证入口

Runtime 单元测试覆盖状态机、TURN_INPUT_BATCH、response debt、lease 与 fencing；PostgreSQL 集成测试覆盖 recursive path、atomic enqueue、terminal apply、stop 和 target claim；Redis 集成测试覆盖 Stream cursor 与 snapshot-first SSE。E2E 入口见 [e2e-regression.md](e2e-regression.md)。
