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

权威 DDL 有两份 byte-identical 的副本：`core` 的 [`V1__schema.sql`](../../core/src/main/resources/db/migration/V1__schema.sql)（含七张表）与 `harness-runtime-spring` 的 [`harness-runtime-schema.sql`](../../harness/runtime-spring/src/main/resources/fun/fengwk/kkstudio/harness/runtime/spring/postgresql/harness-runtime-schema.sql)；`CoreHarnessArchitectureTest` 逐字节校验两者一致。Schema 采用 clean-slate rebuild，不维护兼容迁移；不存在 `agent_thread_goal`，Goal 状态复用 `harness_entry` 的插件 CUSTOM payload。所有 durable 实体 id 由注入的 `Supplier<UUID>` 生成（生产：`UUID::randomUUID`），API 中编码为 canonical UUID string；HTTP DTO 的 Java `long`/`Long` 统一编码为 canonical decimal string，数据库仍保留 bigint。

## 2. `harness_session` / `harness_entry`

`harness_session`：`id`、`created_at`。Session 只组织一棵 append-only Entry Tree。

`harness_entry`：`id`、`session_id`、`parent_entry_id`、`entry_type`、`payload`、`created_at`。

- `(session_id, id)` 唯一；`ROOT` 无 parent，其余 Entry 必须有同 Session parent；每 Session 唯一 ROOT（partial unique index）。
- 允许的 `entry_type`（check 约束）：

```text
ROOT, TURN_START, MESSAGE, CUSTOM, MODEL_ATTEMPT_FAILURE, CUSTOM_MESSAGE,
ASSISTANT_ERROR, ASSISTANT_ABORTED, COMPACTION, TURN_END
```

- payload 由 `HistoryEntryPayloadJsonCodec` 严格编解码；root-to-head path 由 recursive CTE 读取。

## 3. `harness_thread`

| 列 | 约束 |
| --- | --- |
| `id` | UUID 主键 |
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
| `command_type` | 六类 check 约束（USER_MESSAGE/CUSTOM_MESSAGE/SET_ENVIRONMENT/SET_AGENT/SET_MODEL/SET_ACTIVE_TOOLS）；YOLO 由 Thread 行直接控制 |
| `payload` | JSON object |
| `client_command_id` | UUID；`(thread_id, client_command_id)` 唯一幂等键 |
| `request_hash` | raw 命令的 canonical SHA-256，64 位小写 hex；exact replay 必须匹配 |
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
| `request` | 完整冻结 `ModelInvocationRequest` JSON（route/provider/tools/skills/subagentBindings/YOLO/contextWindow/可空 compaction metadata；Provider/Model 只按名称引用） |
| `status` | READY/DISPATCHING/RUNNING/SUCCEEDED/FAILED/CANCELLED/UNKNOWN |
| `attempt` | `>= 0` |
| `stream_checkpoint` | 当前 attempt 的单调安全 checkpoint；text/thinking 归一化为非 null，至少一侧非空且纯空白合法；retry 时归档后清空，防止跨 attempt partial 混入 |
| `failed_attempts` | `NOT NULL` JSON array（可为空数组）；由 retry policy 驱动的 append-only `TRANSIENT` 失败前缀，无条数上限 |
| `result` / `error` / `result_entry_id` | `result` 与 `error` 互斥；`result_entry_id` 在**本表内**唯一（partial unique index），terminal 且已挂 Entry 不再 apply |

`failed_attempts` 的每项固定为 `{attempt,sequence,text,thinking,error,failedAt,retryAt}`：attempt 必须连续 `1..N`、不超过 invocation attempt，时间不得倒退，`retryAt >= failedAt`。`RUNNING -> READY` 每次只追加当前 attempt 一项并清除 checkpoint；active snapshot 直接投影该数组。最终 apply/Stop 在同一 EntryPath 上先按序写 `MODEL_ATTEMPT_FAILURE`（`Entry.createdAt=failedAt`），再写唯一 Assistant 结果；挂 `result_entry_id` 时 invocation 同步清空已物化的 checkpoint/failed_attempts。Compaction invocation 不物化这类 UI 审计 Entry。

### `harness_tool_invocation`

| 列 | 语义 |
| --- | --- |
| `model_invocation_id` / `assistant_entry_id` / `ordinal` | `(assistant_entry_id, ordinal)` 唯一；siblings 按 ordinal 连续 |
| `request` | 冻结 binding（descriptor/type/route/plugin provenance/state accesses）+ arguments |
| `status` | WAITING_APPROVAL/READY/DISPATCHING/RUNNING/SUCCEEDED/FAILED/CANCELLED/UNKNOWN |
| `approval` | durable `{decision: ALLOWED|DENIED, decisionId, decidedAt, ...}` |
| `effects` | 非空 JSON object；有序 `ToolEffectBatch`，仅 `SUCCEEDED` 可非空，terminal immutable |
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
  "threadId": "00000000-0000-0000-0000-000000000001",
  "subjectKind": "MODEL_INVOCATION",
  "subjectId": "00000000-0000-0000-0000-000000000010",
  "attempt": 1,
  "type": "MODEL_DELTA",
  "payload": { "kind": "TEXT_DELTA", "text": "..." }
}
```

- realtime event 没有业务恢复语义，也不用于审计；`RealtimeEventSink.append` 失败不改变 durable terminal。
- `TOOL_PARTIAL` 除普通工具进度外，还承载 task 委派的**完整 JSON 快照心跳**（`details.kind=task.status`，约 1s 一次）：它不是 delta，顶层状态可携带扁平 `descendants` 活动子树 relay，前端按规范化快照整帧替换/语义去重；心跳丢失只影响实时展示，恢复仍来自子 Thread 的 durable snapshot。
- Stream 长度受配置策略（max length）约束，下一次写入时应用。
- 浏览器订阅经应用事件通道（`/api/events/v1`）：`revision`/`version` 事件携带 PostgreSQL durable cursor，Redis stream id 不暴露为 wire cursor；重连重订阅后重新读取 snapshot，再从 live edge 接收新 delta。
- Model delta 对应的安全 `stream_checkpoint` 由 Processor **先**持久化，commit 后才 best-effort 发布 Redis delta；terminal `resultJson`/`errorJson` 本身是 durable 边界，不依赖 terminal overlay 事件，客户端据此覆盖并最终移除流式投影。

## 8. 事务与锁序

`HarnessStore.Transaction` 提供 `nextId`、`lockThread`、`lockWork`、`loadEntryPath`、`loadQueuedCommands`、`updateThread`、`updateCommands`、`insertEntry`、`insertModelInvocation`、`insertToolInvocation`、`updateModelInvocation`、`updateToolInvocation`、`claimNextWork` 等；锁序固定：

```text
Thread -> Commands -> ModelInvocation -> ToolInvocation siblings -> Work
```

- 每个 `HarnessRuntime` 方法恰好一个事务；snapshot 单事务一致读取。
- Tool success 的 `result + effects + SUCCEEDED` 由同一次 `updateToolInvocations` 原子提交；effects 校验必须早于 Resource externalize 与该 durable update。
- Model terminal attach 的 `updateModelInvocation` 必须通过 `ModelAttemptMaterialization`：结果 path 精确包含全部且仅包含该 invocation 的失败 attempt，payload/error/createdAt/retryAt 与 stored `failed_attempts` 逐项一致；terminal `ASSISTANT_ERROR.attempt` 或 direct Stop barrier 必须与 stored terminal/checkpoint 精确一致。InMemory 与 PostgreSQL Store 共用同一校验器。
- Tool terminal apply 只经 `ToolOutcomeAppender`：按 effects 顺序追加 CUSTOM，再追加 Tool Result、推进 head 并把 `result_entry_id` 指向 Tool Result；正常 apply 与 Stop 共用该实现。
- Stop 与未决 Approval 决策把 mutation 时间戳 clamp 到最新锁定 durable fact，容忍本地时钟回拨与节点间 skew；对应 Work
  request/lease 仍使用未抬升的本地调度时钟。
- 丢失/过期 lease 的 callback 通过 token/attempt/terminal CAS 拒绝，绝不产生带 durable mutation 的 LOST 提交。

## 9. 故障语义

| 故障 | durable 处理 |
| --- | --- |
| PostgreSQL NOTIFY 丢失 | periodic poll / 重连 wake / lease 到期重新 claim |
| Redis Stream 丢失 | 客户端重新获取 snapshot |
| Runtime 进程退出 | 已提交 Entry/Command/Invocation/Work 保留；lease 到期后重新 claim |
| Provider/Tool 结果不确定 | Invocation 收敛 `UNKNOWN`，不重放不确定副作用 |
| Provider `TRANSIENT` 且 retry budget 允许 | 原子追加当前 attempt partial/error 到 `failed_attempts`，清 checkpoint，转 READY 并按 `retry_at` reschedule；不改 frozen Provider request |
| Provider context overflow | 写 FAILED turn；最多启动一次 durable OVERFLOW compaction + immediate CONTINUATION，retry 再 overflow 时停止 |
| terminal/Stop 物化失败 attempt 不一致 | Store 拒绝整个 attach 事务；不得清空 invocation audit 或推进 head |
| terminal callback 重复 | invocation token/attempt/terminal CAS；terminal result/effects 不可变 |
| 插件 sibling stale snapshot | materialize 时按 `(pluginId, customType)` 的 frozen READ/WRITE 声明机械写入 `SIBLING_STATE_CONFLICT` FAILED，不 dispatch |
| Stop/head 与 terminal 并发 | revision CAS 与 claim ownership fencing |

## 10. Resource store

- `harness-runtime-spring` 提供瞬时 `LocalFileResourceStore`：`ResourceStore.reference` 无副作用地计划 canonical `file:///` `ResourceRef`，`put` 返回精确相同引用；`harness-daemon` 的 coding 工具使用同构 store。
- `ToolResultExternalizer` 位于 `CoreToolGateway` callback bridge。插件 intents 先完整校验为 effects；Text/Json UTF-8 ≤ 8KB 保持 inline，超过阈值或 Binary 才按 `reference -> put -> exact ref check` 转为瞬时 `ResourceRef`，随后 `ToolProcessor` 原子持久化 terminal `result + effects + SUCCEEDED`。
- Tool outcome Entry 插入前，`ToolOutcomeAppender` 在同一 Store 事务调用
  `GlobalStorageToolResultHistoryMaterializer`：有界读取 data/file/http/https/s3 内容，摄入
  `storage_blob`，写入 `resource(blobId,name,preview)`，并通过
  `harness_session_blob_ref` 为 Session 持有 Blob。没有 materializer 时，含 Resource 的成功结果
  fail closed，瞬时 URI 绝不进入 durable message。
- Provider attempt 从 `storage_blob` 读取权威媒体事实：支持的图片从原始 Blob 受限读取（30 MiB）并生成 attempt-only `data:<mediaType>;base64,...`，避免远端 Provider 访问本地/私网预签名 URL；audio/video 暂使用新鲜预签名 URL。Provider adapter 把 source 统一作为 URI 交给 SDK 的 URI 重载，禁止误走 raw Base64 重载。durable invocation request 只保存 `blobId/name/preview`。前端 durable 渲染走 `/api/storage/blobs/{blobId}/presigned-original|presigned-preview`；`GET /api/ai/runtime/resources/{sha256}` 只保留给瞬时/Invocation file/s3 引用兼容。

## 11. 验证入口

Runtime 单元测试覆盖状态机、classifier、Stop replay、两阶段激活、FIFO 指纹与 fencing；PostgreSQL 集成测试覆盖锁序、CAS、terminal apply、Work claim/wake 与 schema byte-identity；Redis 集成测试覆盖 Stream cursor 与事件通道订阅。E2E 入口见 [e2e-regression.md](e2e-regression.md)。
