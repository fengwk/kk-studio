# 存储模型

PostgreSQL 是 Harness 执行、Chat、Catalog 与 Canvas 的 durable truth。权威 DDL 是 [`V1__schema.sql`](../../core/src/main/resources/db/migration/V1__schema.sql)（Flyway 执行；其中 Harness 部分与 `harness-runtime-spring` 的 `harness-runtime-schema.sql` byte-identical）。Redis 只保存 realtime overlay；S3 保存 ComfyUI 与浏览器直传对象。

## 1. Catalog 表

| 表 | 身份与关键约束 |
| --- | --- |
| `agent_provider` | `name` 主键；当前行的 `provider_type`/`base_url`/`credential`/`config` 是唯一连接事实；`version` 是 CRUD CAS token |
| `agent_model` | `(provider_name, name)` 复合主键；`provider_name` 外键到 `agent_provider(name)`；`config` 保存结构化 Model 配置 |
| `agent_definition` | `name` 主键；`(model_provider_name, model_name)` 外键到 Model；`variant` 是可选 variant 名称 |

Catalog 只有上述三张名称资源表。三张表不使用 bigint resource ID，均以名称（Provider/Agent 为 `name`，Model 为 `(providerName, name)`）作为身份与全部引用；`version` 是当前行的 CRUD 乐观锁（CAS）token。删除是带 `expectedVersion` CAS 的硬删除（物理删行）：删除后同名立即可重建，重建行 `version` 从 0 重新开始；记录存续期间名称不可修改。Provider/Model 删除分别与 active Model/Agent 检查及父行锁配合，避免并发创建产生 active orphan。Model 的公开引用为 `providerName/modelName`，API 解析只在第一个 `/` 切分。结构化 Model config 包含 limit、abilities、pricing、`defaultVariant` 与 `variants`；Agent config 只保存 tools/skills 名称集合。

## 2. ComfyUI Workflow API

| 表 | 字段与职责 |
| --- | --- |
| `comfyui_workflow_api` | bigint `id`、唯一 `api_name`、名称/描述、workflow/input bindings JSON、default selector、enabled、version 与时间 |

该表只保存工作流 API 定义；ComfyUI 产物与浏览器直传对象位于对象存储，不进入 Harness Runtime 表。

## 3. Chat 与关系

| 表 | 字段与职责 |
| --- | --- |
| `chat` | `id`、`title`、`agent_name`、`yolo_enabled`、`version`、时间；两项名称/开关是 Chat 唯一可见发送设置 |
| `chat_thread` | `(chat_id, thread_id)` 主键，表示 Chat 与 Thread 的历史多对多关系 |

Chat 的 `agent_name` 与 `yolo_enabled` 不复制到 Thread；Thread 的 branch 设置来自 head Entry 的 `BranchSettings` 快照。

## 4. Harness 表（精确 7 张）

| 表 | 关键字段与约束 |
| --- | --- |
| `harness_session` | `id`、`title`、`created_at`；只作为 Entry Tree 容器 |
| `harness_entry` | `id`、`session_id`、`parent_entry_id`、`entry_type`、`payload`、`created_at`；ROOT 无 parent，其余 Entry 有 parent；每 Session 唯一 ROOT |
| `harness_thread` | `id`、非空 `head_entry_id`、`yolo_enabled`、`next_command_sequence`（≥1）、`revision`（≥0）、时间 |
| `harness_thread_command` | `thread_id`、`sequence`、`command_type`、`payload`、`client_command_id`、`consumed_turn_start_entry_id`、`cancelled_at` |
| `harness_model_invocation` | `thread_id`、`turn_start_entry_id`、`basis_head_entry_id`、`request`、status、attempt、`stream_checkpoint`、`result`/`error`/`result_entry_id` |
| `harness_tool_invocation` | `model_invocation_id`、`assistant_entry_id`、`ordinal`、`request`、status、attempt、`approval`、`result`/`error`/`result_entry_id` |
| `harness_work` | `(target_type, target_id)`、`available_at`、`wake_version`、`lease_token`、`lease_until` |

`harness_entry.entry_type` 只允许：

```text
ROOT, TURN_START, MESSAGE, CUSTOM_MESSAGE, ASSISTANT_ERROR, ASSISTANT_ABORTED, TURN_END
```

`harness_thread_command.command_type` 只允许：

```text
USER_MESSAGE, CUSTOM_MESSAGE, SET_ENVIRONMENT, SET_AGENT, SET_MODEL,
SET_THINKING_LEVEL, SET_ACTIVE_TOOLS, SET_YOLO
```

Chat-scoped Thread 创建事务按 Session → ROOT（完整 `BranchSettings`）→ Thread 的顺序写入，Thread head 直接指向 ROOT（`nextCommandSequence=1`、`revision=0`）。`harness_work` 是唯一调度 mailbox（见 [harness-storage-runtime.md](harness-storage-runtime.md)）。

### `agent_thread_goal`（Core application-owned）

```text
thread_id      # PK，FK -> harness_thread(id)
objective      # 非空（btrim 后长度 > 0）
token_budget   # 可选，> 0
status         # active | complete | blocked
reason         # active 时 null；complete/blocked 时非空
created_at / updated_at
```

这是 **Core application-owned 的 Goal 表**：没有 `harness_` 前缀，**不是 Harness Runtime 的第 8 张表**（runtime-spring schema 不含它；`harness_runtime_id_seq` 不为其分配 ID）。Goal 产品能力通过 selectable Platform tools `create_goal` / `get_goal` / `update_goal`（`DatabaseGoalStore` 实现 `GoalStore`，由 `RuntimeToolsConfiguration` 条件装配）暴露给 Agent。

**不存在的表**：没有 dead-letter、interaction、usage ledger、artifact、global settings、input（独立表）、execution activation 等 Harness 辅助表。Harness V1 与 runtime-spring schema byte-identical（由 `CoreHarnessArchitectureTest` 校验）。

## 5. Invocation 冻结事实

`ModelInvocationRequest` 的 `environmentId`（route）、exact `providerRequest`、`toolBindings`（descriptor/type/route）与 `skillBindings`、`yoloEnabled` 是同一份冻结事实，JSON 存储在 `harness_model_invocation.request`。`ModelDescriptor` 只含 `providerName`/`modelName`/`tools`/`reasoning`/`pricing` 五个字段：Provider 连接事实与 cache capability 在每次 Model attempt 由 Core 按 `providerName` 读取当前 `agent_provider` 行解析（见 [harness-capability-wiring.md](harness-capability-wiring.md)）。Retry 只改变 invocation attempt 与调度时间，重放同一份 request；`ToolProcessor` 使用 request 中的原 binding，不重新选择 Environment。Provider 返回冻结 request 中不可见的 Tool 时，Model Invocation 终结失败并由 Agent Loop 写入 `ASSISTANT_ERROR`，不物化 ToolInvocation。

## 6. Canvas

| 表 | 职责 |
| --- | --- |
| `canvas_document` | Canvas 身份、标题、revision、viewport |
| `canvas_node` | RESOURCE/FUNCTION 节点，硬删除 |
| `canvas_link` | 同 Canvas 的可见性边，节点删除级联 |
| `canvas_command_dedup` | `(canvas_id, command_id)` 幂等事实与 request hash |

当前 `DurableCanvasService` 支持创建 Canvas、创建文本/生成文本节点、创建 link、移动节点和删除节点。

## 7. 事务不变量

- Thread、Command、ModelInvocation、ToolInvocation siblings、Work 的锁序由 [harness-runtime-contracts.md](harness-runtime-contracts.md) 统一定义（Thread → Commands → Model → Tool siblings → Work）。
- 每次可见 Thread 变化 `revision` 恰好 +1；`next_command_sequence` 不回退。
- 命令 batch 的 sequence 预留、命令行写入与 THREAD Work wake 在同一事务。
- terminal apply（Entry + head + Invocation 挂 resultEntryId）与后续 Work 请求在同一事务。
- Stop 的 replay 查找在 revision CAS 之前；approval 的 replay 不 bump revision。
