# Harness PostgreSQL 与 Redis 设计

## 1. 数据职责

```text
PostgreSQL
  -> harness_session / harness_entry / harness_thread
  -> harness_thread_command / harness_model_invocation / harness_tool_invocation
  -> harness_work（唯一调度 mailbox）

Redis Streams
  -> 有界、可丢失的 realtime overlay（MODEL_DELTA / TOOL_PARTIAL）
```

PostgreSQL 是唯一 durable truth。Redis 重启或清空只会造成流式 overlay 缺口；客户端重新读取 Thread snapshot 即可恢复权威状态。

权威 DDL 有两份 byte-identical 的副本：`core` 的 [`V1__schema.sql`](../../core/src/main/resources/db/migration/V1__schema.sql)（含 `harness_runtime_id_seq` 与七张表）与 `harness-runtime-spring` 的 [`harness-runtime-schema.sql`](../../harness/runtime-spring/src/main/resources/fun/fengwk/kkstudio/harness/runtime/spring/postgresql/harness-runtime-schema.sql)；`CoreHarnessArchitectureTest` 逐字节校验两者一致。所有 durable runtime ID 由 sequence 分配，在 API 中编码为十进制字符串。

## 2. `harness_session` / `harness_entry`

`harness_session`：`id`、`title`、`created_at`。Session 只组织一棵 append-only Entry Tree。

`harness_entry`：`id`、`session_id`、`parent_entry_id`、`entry_type`、`payload`、`created_at`。

- `(session_id, id)` 唯一；`ROOT` 无 parent，其余 Entry 必须有同 Session parent；每 Session 唯一 ROOT（partial unique index）。
- 允许的 `entry_type`（check 约束）：

```text
ROOT, TURN_START, MESSAGE, CUSTOM_MESSAGE, ASSISTANT_ERROR, ASSISTANT_ABORTED, TURN_END
```

- payload 由 `HistoryEntryPayloadJsonCodec` 严格编解码；root-to-head path 由 recursive CTE 读取。

## 3. `harness_thread`

| 列 | 约束 |
| --- | --- |
| `id` | 正 |
| `head_entry_id` | 非空，FK 到 `harness_entry` |
| `yolo_enabled` | Thread 运行时策略 |
| `next_command_sequence` | `>= 1`；创建即 1 |
| `revision` | `>= 0`；可见变化恰好 +1 |
| `created_at` / `updated_at` | `updated_at >= created_at` |

Thread 行不保存 Session/Environment/status/epoch/lease/runnable；全部由 head Entry 分支派生或由 snapshot 投影计算。每次写前 `ThreadState.validateTransition` 校验。

## 4. `harness_thread_command`

| 列 | 约束 |
| --- | --- |
| `thread_id` / `sequence` | `(thread_id, sequence)` 唯一；`sequence > 0` |
| `command_type` | 八类 check 约束（USER_MESSAGE/CUSTOM_MESSAGE/SET_ENVIRONMENT/SET_AGENT/SET_MODEL/SET_THINKING_LEVEL/SET_ACTIVE_TOOLS/SET_YOLO） |
| `payload` | JSON object |
| `client_command_id` | `(thread_id, client_command_id)` 唯一幂等键，≤128 字符 |
| `consumed_turn_start_entry_id` | null 或 FK 到 Entry；与 `cancelled_at` 互斥（terminal check） |
| `cancelled_at` | null 或 `>= created_at` |

queued 查询用 partial index：`consumed_turn_start_entry_id is null and cancelled_at is null`。

fresh enqueue 事务：锁 Thread → 双 CAS（head/sequence）→ 可选 SET_ENVIRONMENT quiescence admission → 预留 sequence（`next_command_sequence += N`，revision +1）→ 写入全部命令 → 更新 THREAD Work。Ordered command-set replay 在这些 cursor/admission 检查之前返回既有行。

## 5. Invocation 表

### `harness_model_invocation`

| 列 | 语义 |
| --- | --- |
| `thread_id` / `turn_start_entry_id` | `(thread_id, turn_start_entry_id)` 唯一 |
| `basis_head_entry_id` | 创建时的 head |
| `request` | 完整冻结 `ModelInvocationRequest` JSON（route/provider/tools/skills/YOLO） |
| `status` | READY/DISPATCHING/RUNNING/SUCCEEDED/FAILED/CANCELLED/UNKNOWN |
| `attempt` | `>= 0` |
| `stream_checkpoint` | attempt-local 单调 checkpoint；新 attempt 清空，防止跨 attempt partial 混入 |
| `result` / `error` / `result_entry_id` | `result` 与 `error` 互斥；`result_entry_id` 在**本表内**唯一（partial unique index），terminal 且已挂 Entry 不再 apply |

### `harness_tool_invocation`

| 列 | 语义 |
| --- | --- |
| `model_invocation_id` / `assistant_entry_id` / `ordinal` | `(assistant_entry_id, ordinal)` 唯一；siblings 按 ordinal 连续 |
| `request` | 冻结 binding（descriptor/type/route）+ arguments |
| `status` | WAITING_APPROVAL/READY/DISPATCHING/RUNNING/SUCCEEDED/FAILED/CANCELLED/UNKNOWN |
| `approval` | durable `{decision: ALLOWED|DENIED, decisionId, decidedAt, ...}` |
| `result` / `error` / `result_entry_id` | 同 Model 表约束 |

## 6. `harness_work` 与调度协议

`harness_work` 以 `(target_type, target_id)` 为主键（`target_type` 仅 THREAD/MODEL/TOOL）：

```text
available_at     # 最早可 claim
wake_version     # > 0；每次 wake +1，fence 丢失的 wake
lease_token      # 非空白或 null
lease_until      # 与 lease_token 成对
```

- **claim**：`available_at <= now` 且 lease 已过期 → 写入新 lease token/until；claim 是 Work-only 短事务，不读业务状态。
- **wake**：`available_at = least(available_at, excluded.available_at)` + `wake_version = wake_version + 1`；幂等合并。
- **NOTIFY**：wake 提交后经 `harness_runtime_work` channel 发送通知；`PostgresqlWorkListener` 只把它合并为 dispatcher wake，通知丢失不改变正确性。
- **poll**：dispatcher 以 fixed-delay periodic poll 做 due scan，与启动/重连 wake 一起保证最终收敛；claim 成功后必须二选一（worker 接受 handoff 或立即 reschedule）。
- **heartbeat**：processor 长任务（Resolver 调用）期间 `WorkHeartbeat` 续租；lease 过期后 stale worker 的后续提交被 ownership 校验拒绝。
- **complete**：`work.complete(leaseToken, claimedWakeVersion, now)` 校验 claim 与 wake 版本后删除/降级行；reschedule 校验后改写 `available_at`（wake_version 不变或按协议递增）。

生产链路：

```text
durable mutation
  -> work wake
  -> PostgreSQL NOTIFY（提示） + periodic poll（收敛）
  -> dispatcher claim（round-robin THREAD/MODEL/TOOL）
  -> bounded handoff -> ThreadProcessor / ModelProcessor / ToolProcessor
```

## 7. Redis realtime overlay

每个 Thread 使用 bounded Redis Stream 保存 Model delta 与 Tool partial：

```json
{
  "threadId": "1",
  "subjectKind": "MODEL_INVOCATION",
  "subjectId": "1",
  "attempt": 1,
  "type": "MODEL_DELTA",
  "payload": { "kind": "TEXT_DELTA", "text": "..." }
}
```

- realtime event 没有业务恢复语义，也不用于审计；`RealtimeEventSink.append` 失败不改变 durable terminal。
- Stream 长度受配置策略（max length）约束，下一次写入时应用。
- revision SSE 帧使用 PostgreSQL durable revision；Redis stream id 不暴露为 SSE id；重连只携带 revision，重新读取 snapshot 后从 live edge 接收新 delta。
- Model delta 对应的安全 `stream_checkpoint` 由 Processor **先**持久化，commit 后才 best-effort 发布 Redis delta；terminal `resultJson`/`errorJson` 本身是 durable 边界，不依赖 terminal overlay 事件，客户端据此覆盖并最终移除流式投影。

## 8. 事务与锁序

`HarnessStore.Transaction` 提供 `nextId`、`lockThread`、`lockWork`、`loadEntryPath`、`loadQueuedCommands`、`updateThread`、`updateCommands`、`insertEntry`、`insertModelInvocation`、`insertToolInvocation`、`updateModelInvocation`、`updateToolInvocation`、`claimNextWork` 等；锁序固定：

```text
Thread -> Commands -> ModelInvocation -> ToolInvocation siblings -> Work
```

- 每个 `HarnessRuntime` 方法恰好一个事务；snapshot 单事务一致读取。
- Stop 额外把时间戳 clamp 到最新锁定 durable fact，容忍本地时钟回拨与节点间 skew。
- 丢失/过期 lease 的 callback 通过 token/attempt/terminal CAS 拒绝，绝不产生带 durable mutation 的 LOST 提交。

## 9. 故障语义

| 故障 | durable 处理 |
| --- | --- |
| PostgreSQL NOTIFY 丢失 | periodic poll / 重连 wake / lease 到期重新 claim |
| Redis Stream 丢失 | 客户端重新获取 snapshot |
| Runtime 进程退出 | 已提交 Entry/Command/Invocation/Work 保留；lease 到期后重新 claim |
| Provider/Tool 结果不确定 | Invocation 收敛 `UNKNOWN`，不重放不确定副作用 |
| terminal callback 重复 | invocation token/attempt/terminal CAS |
| Stop/head 与 terminal 并发 | revision CAS 与 claim ownership fencing |

## 10. Resource store

- `harness-runtime-spring` 提供 `LocalFileResourceStore`（`ResourceStore.reference` 无副作用地计划精确 `ResourceRef`；canonical `file:///` URI、非空 size/sha256）；`harness-daemon` 的 coding 工具使用同构实现（daemon 侧 `store` 直接落盘并返回 ref）。
- `ToolResultExternalizer`（core，位于 `CoreToolGateway` callback bridge）在持久化前把 ToolResult all-or-nothing 外部化：Text/Json 内容 UTF-8 ≤ `INLINE_RESULT_UTF8_BYTES`（8KB）保持 inline ToolContent 直接编码；超过阈值或二进制内容先经 `ResourceStore.reference` **无副作用计划**精确 `ResourceRef`，再逐项 `put` 写入，**每次 put 返回的 ref 必须与计划 ref 精确相等**（不等即存储契约违反）；随后才把已外部化的 ToolResult 交给 ToolProcessor 落库。**PostgreSQL 不存 BLOB**——durable 表只保存 ResourceRef JSON（uri/mediaType/name/size/sha256）与可选文本 preview。

## 11. 验证入口

Runtime 单元测试覆盖状态机、classifier、Stop replay、两阶段激活、FIFO 指纹与 fencing；PostgreSQL 集成测试覆盖锁序、CAS、terminal apply、Work claim/wake 与 schema byte-identity；Redis 集成测试覆盖 Stream cursor 与 snapshot-first SSE。E2E 入口见 [e2e-regression.md](e2e-regression.md)。
