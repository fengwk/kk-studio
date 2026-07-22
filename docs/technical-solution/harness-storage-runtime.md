# Harness PostgreSQL 与 Redis 设计

## 1. 数据职责

```text
PostgreSQL
  -> 唯一 durable truth
  -> Entry / Thread / Input / Invocation / Interaction / Usage / Artifact

Redis Pub/Sub
  -> 可丢失的低延迟 wake hint

Redis Streams
  -> 可丢失、有界、短期可重放的 realtime projection
```

Redis 重启、清空或网络隔离后，只允许出现实时体验下降，不允许出现：

- 丢失用户 Input；
- 丢失 Entry/head；
- 重复推进 Thread；
- 丢失 Invocation terminal；
- 永久 WAITING；
- ID 冲突。

## 2. PostgreSQL-only

项目只保留 PostgreSQL schema、driver、seed 和集成测试。删除：

- MySQL driver 与 schema；
- H2 driver、schema 和 compatibility mode；
- MySQL migration 目录；
- MySQL/H2 专用 profile 和初始化 SQL。

所有持久化集成测试运行在真实 PostgreSQL 容器上，避免方言模拟掩盖锁、CTE、JSONB、`RETURNING` 和 `SKIP LOCKED` 差异。

## 3. ID 生成

Durable entity id 使用 PostgreSQL sequence/bigint，由 Store/IdGenerator 端口通过 `nextval` 分配。

该选择保证：

- 多实例无需 Snowflake worker id；
- Redis 数据丢失不会产生主键碰撞；
- 在 Model/Tool 调用前可预分配 id；
- API 仍可将 bigint 序列化为字符串，避免 JavaScript 精度问题。

Redis Stream 使用 Redis 自身 stream id，仅作为 realtime cursor，不进入业务主键空间。

## 4. 最小表模型

以下字段是目标职责，不表示必须逐字使用同名 DDL；实现时不得增加重复投影字段。

### 4.1 `harness_session`

| 字段 | 语义 |
| --- | --- |
| `id` | Session id |
| `title` | 用户可见标题 |
| `main_thread_id` | Main Thread |
| `parent_session_id` | Child Session 的父 Session，可空 |
| `parent_invocation_id` | 创建该 Child Session 的 `task` ToolInvocation，可空且非空时唯一 |
| `created_at` / `updated_at` | 时间 |

`root_session_id` 与 `depth` 默认不存储，通过 recursive CTE 查询；只有实际性能证据证明必要时才增加投影。

### 4.2 `harness_entry`

| 字段 | 语义 |
| --- | --- |
| `id` | Entry id |
| `session_id` | 所属 Session |
| `parent_entry_id` | 单父关系，可空 |
| `entry_type` | 语义类型 |
| `payload` | `jsonb` typed payload |
| `created_at` | 创建时间 |

索引：

- primary key `(id)`；
- unique `(session_id, id)`；
- `(session_id, parent_entry_id)`。

使用复合外键 `(session_id, parent_entry_id) -> (session_id, id)` 约束 parent 必须属于同一 Session，并禁止修改已存在 Entry 的 parent/payload。正常写路径只追加 Entry，因此不会创建环；仍保留最大递归深度保护以防人工数据损坏。

完整路径使用一次 recursive CTE，从 leaf 沿 parent 回溯后按 depth 反转。禁止应用层逐节点 SELECT。

Context 查询首先定位最近有效 Compaction，再只加载 Compaction 所需 retained range 与其后的 Entry，不读取已被压缩淘汰的祖先 payload。

### 4.3 `harness_thread`

| 字段 | 语义 |
| --- | --- |
| `id` | Thread id |
| `session_id` | 所属 Session |
| `head_entry_id` | 当前 branch cursor |
| `input_sequence` | 下一个 mailbox sequence 的事务基准 |
| `runnable` | PostgreSQL durable wake fact |
| `execution_epoch` | Stop/cancel generation fencing |
| `processor_token` / `processor_until` | 短 activation lease |
| `created_at` / `updated_at` | 时间 |

不保存 Agent/Model/Variant、YOLO、retry、waiting reason 或流式状态。

### 4.4 `harness_thread_input`

| 字段 | 语义 |
| --- | --- |
| `id` | Input id |
| `thread_id` / `sequence` | Thread 内严格顺序 |
| `input_type` | 命令类型 |
| `payload` | `jsonb` |
| `idempotency_key` | 客户端幂等键 |
| `status` | `QUEUED/APPLIED/CANCELLED` |
| `created_at` / `applied_at` | 时间 |

唯一约束：

- `(thread_id, sequence)`；
- `(thread_id, idempotency_key)`。

enqueue、sequence 分配、`runnable=true` 在锁定 Thread 的同一事务内完成。

### 4.5 `harness_model_invocation`

保存 source head、execution epoch、request snapshot、状态、worker lease、deadline/activity、retry、terminal response/error 和 applied 标志。

Model response 在 Reconciler 物化为 Entry 后可以按 retention 清理大 request/response payload，但账本和必要错误摘要进入独立长期事实。

### 4.6 `harness_tool_invocation`

保存 Assistant Entry、ordinal、ToolCall、descriptor/arguments snapshot、`PLATFORM/ENVIRONMENT`、worker lease、deadline/retry、terminal result/error 和 applied 标志。

不保存 permission/approval 专用字段。

### 4.7 `harness_interaction`

保存 owner reference、handler type、request/response `jsonb`、状态、deadline/version 和时间。

Interaction 本身就是请求/响应事实，不再建立通用 wait 表。

### 4.8 Usage 与 Artifact

Usage 是不可变账本；Artifact 保存外部化内容或对象存储引用。大模型/Tool 流式 delta 不进入 PostgreSQL 长期表。

## 5. Runnable 与 activation

### 5.1 标记 runnable

任何能推进 Thread Reconciler 的事务都执行：

```sql
update harness_thread
set runnable = true,
    updated_at = current_timestamp
where id = :thread_id;
```

该更新必须与对应领域事实处于同一事务，例如：

- Input insert；
- ModelInvocation terminal；
- ToolInvocation terminal；
- Interaction resolution；
- Child Session terminal。

Model/Tool retry 到期只标记对应 Invocation dispatchable，并发送 Invocation signal；在 Invocation terminal 前不需要激活 Thread。

### 5.2 claim

Worker 使用 PostgreSQL 条件更新或 `FOR UPDATE SKIP LOCKED`：

```text
runnable=true
或 processor lease 已过期
且没有其他有效 owner
```

claim 原子写入新 token/until 并清除本次已观察的 runnable。处理期间若出现新事实，其他事务再次写 `runnable=true`。

### 5.3 quiesce

Reconciler 进入 IDLE 前锁 Thread 并重新检查：

- `runnable`；
- queued Input；
- terminal-but-unapplied Invocation；
- retry due；
- unresolved blocker。

无工作时清 processor lease；存在工作时保持 ownership 并继续 reconcile。该锁序封闭 enqueue/terminal 与 quiesce 的丢唤醒竞态。

## 6. Redis Pub/Sub wake hint

推荐统一 signal channel：

```text
kk-studio:harness:signal
```

消息只包含：

```json
{
  "targetKind": "THREAD",
  "targetId": "..."
}
```

`targetKind` 可以是 `THREAD`、`MODEL_INVOCATION` 或 `TOOL_INVOCATION`。发布必须在 PostgreSQL commit 后发生。所有实例可以订阅同一 channel；重复调度由 PostgreSQL lease/fencing 合并。

Pub/Sub 丢失不会丢工作。低频 recovery 查询 `runnable=true` 或过期 processor lease 并重新触发 activation。

高吞吐阶段若广播放大成为真实瓶颈，可以将 wake transport 替换为 Redis Streams consumer group；Kernel/Runtime 不感知该变化。

## 7. Redis Streams realtime projection

流式 Model delta、Tool partial 和轻量生命周期投影写入 bounded Redis Stream。

事件 envelope：

```json
{
  "threadId": "...",
  "subjectKind": "MODEL_INVOCATION",
  "subjectId": "...",
  "type": "MODEL_DELTA",
  "payload": {},
  "createdAt": "..."
}
```

约束：

- 使用 `MAXLEN ~` 或时间清理限制容量；
- terminal 后只保留短 reconnect window；
- Redis cursor 过期时客户端重新加载 PostgreSQL snapshot；
- Event 不用于 maxTurns、retry、状态恢复或业务审计；
- Redis 丢失后允许 in-flight 动画缺口，最终 Entry/Invocation 不受影响。

SSE adapter 使用阻塞读取或 Redis listener，不再每 200ms 轮询 PostgreSQL。

## 8. Snapshot-first 客户端恢复

客户端连接顺序：

1. 加载 Thread derived view；
2. 加载 Entry path；
3. 加载 queued Inputs；
4. 加载 active/terminal-unapplied Invocations；
5. 加载 open Interactions；
6. 获取 realtime stream cursor；
7. 订阅 Redis-backed SSE tail。

cursor 失效或 Redis 重启时重复完整 snapshot 流程，不尝试从已丢失的 delta 重建权威状态。

## 9. Retry 与 timeout

Retry/timeout 是 Invocation 属性，不是 Thread 状态：

- `attempt`；
- `next_attempt_at`；
- `deadline_at`；
- `last_activity_at`；
- `error_kind`。

到期调度器可以使用 Redis 提示，但 PostgreSQL 时间字段是事实。Recovery 按 PostgreSQL 查询 due Invocation。

Tool timeout 分离为：

- 可选 Interaction/approval expiry；
- queue/claim lease；
- execution total timeout；
- 可选 progress idle timeout。

lease heartbeat 不等于 progress activity。

## 10. PostgreSQL 锁序

跨领域事务统一锁序：

```text
Thread
  -> owning Invocation
  -> Interaction / Child Session
  -> Entry/Input append
```

完成方与挂起方遵循同一 Thread-first 顺序。任何偏离必须由测试证明不会产生死锁或丢唤醒。

## 11. Recovery

Recovery 是正确性兜底，不是主调度路径：

1. 扫描 `runnable=true` Thread；
2. 扫描 processor lease 过期 Thread；
3. 各 Invocation worker 扫描自己的过期 lease、due retry 和 timeout；
4. 扫描已 terminal 但尚未 applied 的 Invocation；
5. 重新发布 Redis hint 或直接 schedule 本地 activation。

Recovery SQL 不 join Interaction、Tool、Model、Child Session 来推导 runnable；完成事务负责提前写 `Thread.runnable=true`。

## 12. 故障语义

| 故障 | 处理 |
| --- | --- |
| Redis Pub/Sub 丢消息 | PostgreSQL runnable recovery |
| Redis Streams 清空 | 客户端 snapshot reload |
| Runtime 进程退出 | processor lease 过期后接管 |
| Model/Tool worker 退出 | Invocation lease 过期后重试/UNKNOWN |
| terminal callback 重复 | invocation token + terminal CAS |
| Stop 与 terminal 并发 | execution epoch fencing |
| notify 先于 commit | 禁止；只允许 afterCommit |
| 外部副作用已发生但结果未知 | 按 side-effect/idempotency policy 标记 UNKNOWN 或重试 |

## 13. 测试架构

### 13.1 Kernel

- 纯单元测试；
- 状态机、TURN_BOUNDARY、response debt、suspend/resume；
- lease/fencing 与重复 terminal；
- 核心路径行覆盖率目标不低于 90%。

### 13.2 PostgreSQL Integration

- Testcontainers PostgreSQL；
- recursive path、Compaction-aware Context；
- `FOR UPDATE SKIP LOCKED`；
- enqueue/quiesce、terminal/suspend 并发；
- Stop epoch 与 late callback；
- recovery 查询。

### 13.3 Redis Integration

- Testcontainers Redis；
- Pub/Sub wake；
- Streams cursor/trim；
- Redis restart/loss 后 PostgreSQL recovery；
- snapshot-first SSE。

### 13.4 Runtime/E2E

- Stub Model/Tool terminal 驱动；
- Platform/Environment transport contract；
- Interaction resolution；
- Child Session/task Tool；
- 多实例 claim；
- 真 Provider 与 Daemon 可选矩阵。

## 14. 本地基础设施

本地开发通过 `deploy/local/compose.yaml` 启动 PostgreSQL 与 Redis。应用配置使用环境变量，不把本地密码用于任何共享或生产环境。
