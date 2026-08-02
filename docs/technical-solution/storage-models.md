# 存储模型

PostgreSQL 是 Harness 执行、Chat、Catalog 与 Canvas 的 durable truth。权威 DDL 是 [`V1__schema.sql`](../../core/src/main/resources/db/migration/V1__schema.sql)，由 Flyway 执行。Redis 只保存 realtime projection；S3 保存 ComfyUI 与浏览器直传对象。

## 1. Catalog 表

| 表 | 身份与关键约束 |
| --- | --- |
| `agent_provider` | `name` 主键永久保留；`deleted_at` 软删除；Provider/Agent 使用名称引用 |
| `agent_provider_revision` | `(provider_name, provider_version)` 复合主键；append-only 保存 Provider 类型、endpoint、credential 与 config |
| `agent_model` | `(provider_name, name)` 复合主键永久保留；`deleted_at` 软删除；`provider_name` 外键到 `agent_provider(name)` |
| `agent_definition` | `name` 主键永久保留；`deleted_at` 软删除；`(model_provider_name, model_name)` 外键到 Model；`variant` 是可选 variant 名称 |

Catalog 表不使用 bigint resource ID。普通 page/get/runtime 查询只返回 `deleted_at is null` 的行；删除是带版本递增的 soft-delete，原名称不能重新创建。Model 的公开引用为 `providerName/modelName`，API 解析只在第一个 `/` 切分。结构化 Model config 包含 limit、abilities、pricing、`defaultVariant` 与 `variants`；Agent config 只保存 tools/skills 名称集合。

Provider 创建写入 revision `0`；每次成功更新在同一事务写入 `expectedVersion + 1`。Provider 删除不创建新 revision，已冻结的 invocation 仍可通过旧 revision 重放。Provider/Model 删除分别与 active Model/Agent 检查及父行锁配合，避免并发创建产生 active orphan。

## 2. Chat 与关系

| 表 | 字段与职责 |
| --- | --- |
| `chat` | `id`、`title`、`agent_name`、`environment_name`、`yolo_enabled`、`version`、时间；三项名称/开关是 Chat 唯一可见发送设置 |
| `chat_thread` | `(chat_id, thread_id)` 主键，表示 Chat 与 Thread 的历史多对多关系 |

Chat 的 `agent_name`、`environment_name` 与 `yolo_enabled` 不复制到 Thread。发送请求从当前 Chat 读取这三项并写入该消息的 `TurnSettings`。

## 3. Session、Entry、Thread

| 表 | 关键字段与约束 |
| --- | --- |
| `harness_session` | `id`、`title`、`created_at`；只作为 Entry Tree 容器 |
| `harness_entry` | `id`、`session_id`、`parent_entry_id`、`entry_type`、`payload`、`created_at`；ROOT 无 parent，其余 Entry 有 parent |
| `harness_thread` | `id`、非空 `head_entry_id`、`input_sequence`、`runnable`、`execution_epoch`、`revision`、processor lease、时间 |
| `harness_thread_input` | `thread_id`、sequence、`input_type`、payload、幂等键、状态、时间 |

`harness_entry.entry_type` 只允许：

```text
ROOT
MESSAGE
CUSTOM_MESSAGE
ASSISTANT_ERROR
ASSISTANT_ABORTED
```

`harness_thread_input.input_type` 只允许：

```text
USER_MESSAGE
CUSTOM_MESSAGE
```

Chat-scoped Thread 创建事务按 Session → ROOT → Thread 的顺序写入，Thread head 直接指向 ROOT。`PUT /head` 的 `head_entry_id` 是必填非空引用，并用 `expectedExecutionEpoch` 做静止校验与 CAS fencing。

USER/CUSTOM Input 的 payload 与 harvest 后的对应 Entry 都保存：

```json
{
  "turnSettings": {
    "agentName": "agent-name",
    "environmentName": "environment-name-or-null",
    "yoloEnabled": false
  }
}
```

这份引用不展开 Agent、Model、Provider、Tool 或 Skill 定义。规划阶段再读取当前 Catalog 与 READY Environment。

## 4. Invocation 与 activation

| 表 | 关键事实 |
| --- | --- |
| `harness_model_invocation` | source head、epoch、完整 `ModelInvocationRequest`、状态、attempt、lease、deadline、result/error、`applied_at` 与安全流快照 |
| `harness_tool_invocation` | Assistant Entry、Model Invocation、ordinal、tool call、descriptor/arguments、`environment_name`、权限、YOLO、状态、lease、结果 |
| `harness_interaction` | Tool permission 的 request/response、`OPEN/RESOLVED` 与 version |
| `harness_execution_target` | Thread、Model、Tool target 的唯一 durable activation queue；`dispatch_enabled` 控制可调度性 |

`ModelInvocationRequest` 的 `providerRequest`、`toolBindings`、`skillBindings` 与 `yoloEnabled` 是同一份冻结事实。`providerRequest.model` 还冻结 `providerName` 与非负 `providerVersion`。Retry 只改变 invocation attempt 与调度时间，按该版本的 Provider revision 重放相同 request；ToolWorker 使用 request 中的原 binding。

## 5. Usage ledger

`harness_model_usage` 每个 Assistant Entry 只写一条记录，保存：

```text
provider_name
model_name
provider_type
prompt_cache_mode
prompt_cache_retention
cache_eligible
cache_affinity_key
stop_reason
usage_*_tokens
pricing_*
request_id
reported_service_tier
raw_usage
created_at
```

账本通过 `thread_id` 与 `(session_id, assistant_entry_id)` 约束归属，通过 `(provider_name, model_name, id)` 建立查询索引。`provider_name`、`model_name` 是写入时冻结的历史事实；Usage ledger 不对 Catalog 表建立外键，因此 Catalog 删除或更新不会改写历史账本。

## 6. Canvas

| 表 | 职责 |
| --- | --- |
| `canvas_document` | Canvas 身份、标题、revision、viewport |
| `canvas_node` | RESOURCE/FUNCTION 节点，硬删除 |
| `canvas_link` | 同 Canvas 的可见性边，节点删除级联 |
| `canvas_command_dedup` | `(canvas_id, command_id)` 幂等事实与 request hash |

当前 `DurableCanvasService` 支持创建 Canvas、创建文本/生成文本节点、创建 link、移动节点和删除节点。

## 7. 其他 durable 表

`harness_retry_policy`、`harness_realtime_stream_policy`、`harness_thread_goal`、`harness_artifact` 与账本共同组成 Runtime 的辅助事实。Environment registry、Daemon 连接与 Redis Stream 都不是 durable truth。

## 8. 事务不变量

- Thread、Invocation、Interaction、Entry/Input 的锁序由 Runtime 契约统一定义。
- Assistant Entry、Usage、ToolInvocation materialization 与 head 推进在 Reconciler 事务中保持原子。
- Input sequence、幂等键与 `runnable=true` 在同一事务更新。
- Model/Tool terminal 与后续 Thread activation target 在同一事务更新。
- PostgreSQL trigger 在事务提交后发送 NOTIFY；通知只负责唤醒，不承载事实。
