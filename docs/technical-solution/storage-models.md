# 存储模型

PostgreSQL 是 Harness 执行恢复与 Canvas 最小持久化的事实源。权威 DDL 为 `core/src/main/resources/schema-postgresql.sql`。S3 只保存 ComfyUI 对象和浏览器直传对象，不承担 Harness 或 Canvas 事务状态。Redis 不保存 durable truth。

## 资源文件

| 文件 | 用途 |
| --- | --- |
| `schema-postgresql.sql` | PostgreSQL 完整表结构 |
| `data-dev-postgresql.sql` | `dev` profile 最小 seed |
| `data-e2e-postgresql.sql` | `e2e` profile seed |

## 全局资源与实时注册表

| 表 / 资源 | 职责 | 关键约束 |
| --- | --- | --- |
| `agent_provider` | Provider 连接和配置 | `name` 唯一 |
| `agent_model` | 模型能力和配置 | `(provider_id, name)` 唯一 |
| `agent_definition` | Agent 定义、默认模型和 config | `name` 唯一 |
| live Environment registry（非表） | 当前 Daemon 的内存 Environment registry | `environmentName` 唯一；断开即移除 |
| `comfyui_workflow_api` | ComfyUI 工作流卡片 | `api_name` 唯一 |

这些资源不带 Tenant、Workspace membership 或 ACL。运行配置以 Entry 中的完整 `RUNTIME_CONFIG` 快照为执行真源，不依赖 live Definition 补齐历史。

## Canvas 当前持久化

| 表 | 职责 | 关键约束 |
| --- | --- | --- |
| `canvas_document` | Canvas 身份、title、revision、默认 viewport | 索引 `(updated_at, id)`；`title` 非空、`revision >= 0`、`id > 0` |
| `canvas_node` | RESOURCE / FUNCTION 节点；硬删除 | 唯一 `(canvas_id, id)`；`node_type`/`name` 非空、`width`/`height > 0`；FK 复合 `(canvas_id, source_node_id)` / `(canvas_id, target_node_id)` |
| `canvas_link` | 同 Canvas 的可见性边；节点删除时级联清理 | 唯一 `(canvas_id, source_node_id, target_node_id)`；FK `ON DELETE CASCADE`；`source_node_id <> target_node_id` |
| `canvas_command_dedup` | 客户端幂等去重事实；`request_hash` 由服务端基于 `commandsJson` 计算 SHA-256 | 主键 `(canvas_id, command_id)`；`request_hash` 为 64 位十六进制字符串 |

当前 `DurableCanvasService` 支持创建 Canvas，以及 `create_text_node`、`create_generate_text_node`、`create_link`、`move_nodes`、`delete_node` 五个命令。命令以 `baseRevision` 做乐观并发控制，按 `(canvas_id, command_id, sha256(commandsJson))` 做幂等。`canvas_command_dedup` 是纯去重事实，不保存 payload/result。

FUNCTION 节点当前唯一可持久化实例是 `system.generate-text` v1。

## Chat 与 Harness durable 表

| 表 | 职责 | 关键字段 / 约束 |
| --- | --- | --- |
| `chat` | 持久 Chat 集合与默认 Agent | `default_agent_id` 可空；不关联 Session/Thread |
| `harness_session` | Entry Tree 容器 | `id`、`title`、`created_at`；仅与 append-only Entry Tree 关联，存储上不持有 Thread |
| `harness_entry` | append-only 语义历史 | `session_id`、`parent_entry_id`、`entry_type`、`payload` jsonb；类型为 `ROOT/RUNTIME_CONFIG/MESSAGE/CUSTOM_MESSAGE/ASSISTANT_ERROR/ASSISTANT_ABORTED`（`ASSISTANT_ABORTED` 仅承载安全 text/thinking） |
| `harness_thread` | 可复用 durable runtime process | 可空 `head_entry_id`（单列 FK）、`input_sequence`、`runnable`、`execution_epoch`、`processor_token`/`processor_until` |
| `harness_thread_input` | 有序 mailbox | `(thread_id, sequence)` 与幂等键唯一；`QUEUED/APPLIED/CANCELLED` |
| `harness_model_invocation` | 冻结 Provider 调用 | `(thread_id, source_head_entry_id, execution_epoch)` 唯一；request/result jsonb；`source_head_entry_id` 单列 FK 到 `harness_entry(id)`；`safe_stream_snapshot` jsonb 仅 text/thinking，fenced-write 在每次 SSE 发布前，retry-attempt CAS 中重置 |
| `harness_tool_invocation` | Tool 执行事实 | `(thread_id, assistant_entry_id, execution_epoch, ordinal)` 唯一；`PLATFORM/ENVIRONMENT` |
| `harness_interaction` | 通用交互事实 | open owner 唯一约束 |
| `harness_retry_policy` | 全局自动重试策略 | 单行策略 |
| `harness_realtime_stream_policy` | 全局 Redis realtime Stream 容量策略 | 单行 `max_length`；默认 5000 |
| `harness_thread_goal` | Thread 当前 goal | 主键 `thread_id` |
| `harness_artifact` | 全局不可变 Tool 输出 | content bytea、media type、size、SHA-256 |
| `harness_model_usage` | Assistant 用量账本 | 唯一 `assistant_entry_id` |

Chat 不保存 Pane；Pane 仅在浏览器 localStorage。Session **不**保存 Thread、Branch 或完整 Agent 配置副本。Thread 行不保存 `session_id`，也不保存 Agent/model 当前列：当前 Session 由 `head_entry_id` 派生，配置以 `RUNTIME_CONFIG` Entry 为权威。

Model/Tool Invocation 与 Usage 的 Thread 归属均为单列 `thread_id` FK。`harness_model_invocation.source_head_entry_id` 单列 FK 到 `harness_entry(id)`，thread↔session 一致性由上游 Thread 命令通过 head Entry 维护；`harness_tool_invocation` 与 `harness_model_usage` 的 `session_id` 仍作为约束载体，保证所引用的 Entry 与账本/调用属于同一 Session。head 重定位通过递增 `execution_epoch` 隔离旧代际，因此 tool invocation 的来源唯一键含 `execution_epoch`。

`/stop` 在 Thread 行锁内原子完成：判定当前 head 是否仍有未终结 response debt（沿用 Reconciler 的 `ModelInvocationPlanner`，旧代际的 invocation 不参与）。若存在 response debt 且当前 head 存在 matching `safe_stream_snapshot is not null AND applied_at is null` 行，则追加 `ASSISTANT_ABORTED`（仅含 text/thinking）并把 head 重新指向它；否则追加 `ASSISTANT_ERROR(CANCELLED)` barrier。Stop 不修改已 RUNNING/SUCCEEDED 的 invocation 行——它只是一道 epoch fence，旧 generation 的 `completeSuccess/completeFailure/completeCancelled` 在新 epoch 下都会被 CAS 拒绝。Tool/empty partial 不会让 stop 物化 `ToolInvocation` 或写入含 tool fragment 的 aborted entry。

## Usage 与成本

`harness_model_usage` 对每个 Assistant Entry 只保存一条不可变账本，唯一键为 `assistant_entry_id`。Thread 归属是单列 `thread_id` FK；`session_id` 与 `assistant_entry_id` 组成指向 `harness_entry(session_id, id)` 的复合 FK，作为约束载体保证账本与其 Assistant Entry 属于同一 Session。账本仅持久化不可变的 token 明细、stop reason、provider/model 标识、Prompt Cache 策略与命中、以及 `pricing_*` 价格快照；成本 `ModelCost` **不在物理层持久化** —— 读取账本时由 `ModelCost.calculate(pricing, usage)` 重建。`created_at` 是审计事实，由 Reconciler 显式写入。

## 事务与删除

- 锁序：Thread → owning Invocation → Interaction → Entry/Input append。
- Assistant Entry 与 Usage Record 原子写入；`assistant_entry_id` 唯一冲突回滚整事务。
- Harvest 按 TURN_BOUNDARY 应用 Input；每条 Input 仅能从 `QUEUED` 成功迁移一次。
- Daemon 断开只移除对应内存 Environment；历史 `harness_tool_invocation.environment_name` 保留冻结路由名。
- ComfyUI job 是远端系统事实；数据库只保存工作流定义；对象走固定 bucket 预签名边界。
