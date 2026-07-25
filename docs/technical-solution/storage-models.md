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

## Canvas 最小持久化

| 表 | 职责 | 关键约束 |
| --- | --- | --- |
| `canvas_document` | Canvas 身份、title、schema version、revision、lifecycle 与默认 viewport | 索引 `(workspace_id, updated_at)` |
| `canvas_node` | RESOURCE/FUNCTION/GROUP 节点 | 按 `(canvas_id, deleted_at)` 查询；软删除 |
| `canvas_link` | source Resource 对 target 的可见性 Link | 唯一 `(canvas_id, source_node_id, target_node_id)` |
| `canvas_command` | 客户端幂等命令与 revision 结果 | 唯一 `(workspace_id, command_id)` |

当前 `DurableCanvasService` 支持创建 Canvas，以及 `create_text_node`、`create_generate_text_node`、`create_link`、`move_nodes`、`delete_node`。命令以 `baseRevision` 做乐观并发控制。

`ResourceReference`、Resource/ResourceVersion、FunctionRun、Workflow 尚未进入当前 schema；完整目标契约见 [infinite-canvas-implementation-design.md](infinite-canvas-implementation-design.md)。

## Chat 与 Harness durable 表

| 表 | 职责 | 关键字段 / 约束 |
| --- | --- | --- |
| `chat` | 持久 Chat 集合与默认 Agent | `default_agent_id` 可空 |
| `chat_session` | Chat 到 Session 成员关联 | 索引 `session_id` |
| `harness_session` | Session 容器 | `main_thread_id`、可选 `parent_session_id`/`parent_invocation_id` |
| `harness_entry` | append-only 语义历史 | `session_id`、`parent_entry_id`、`entry_type`、`payload` jsonb；类型含 `ROOT/RUNTIME_CONFIG/MESSAGE/...` |
| `harness_thread` | durable Branch actor | `head_entry_id`、`input_sequence`、`runnable`、`execution_epoch`、`processor_token`/`processor_until` |
| `harness_thread_input` | 有序 mailbox | `(thread_id, sequence)` 与幂等键唯一；`QUEUED/APPLIED/CANCELLED` |
| `harness_model_invocation` | 冻结 Provider 调用 | source head + execution epoch 唯一；request/result jsonb |
| `harness_tool_invocation` | Tool 执行事实 | `(assistant_entry_id, ordinal)` 等唯一；`PLATFORM/ENVIRONMENT` |
| `harness_interaction` | 通用交互事实 | open owner 唯一约束 |
| `harness_retry_policy` | 全局自动重试策略 | 单行策略 |
| `harness_thread_goal` | Thread 当前 goal | 主键 `thread_id` |
| `harness_artifact` | 全局不可变 Tool 输出 | content bytea、media type、size、SHA-256 |
| `harness_model_usage` | Assistant 用量账本 | 唯一 `assistant_entry_id` |

Chat 不保存 Pane；Pane 仅在浏览器 localStorage。Session **不**保存 Branch 或完整 Agent 配置副本；`main_thread_id` 创建后稳定。Thread 行不保存 Agent/model 当前列；配置以 `RUNTIME_CONFIG` Entry 为权威。

## Root Activity

Root Activity 由 durable facts 查询合成，无独立 activity 表。

## Usage 与成本

`harness_model_usage` 对每个 Assistant Entry 只保存一条不可变账本，归属键为 `session_id` / `thread_id` / `assistant_entry_id`（唯一）。另冻结 provider/model、Prompt Cache 策略与命中、token 明细、stop reason、价格与成本分项。

## 事务与删除

- 锁序：Thread → owning Invocation → Interaction → Entry/Input append。
- Assistant Entry 与 Usage Record 原子写入；`assistant_entry_id` 唯一冲突回滚整事务。
- Harvest 按 TURN_BOUNDARY 应用 Input；每条 Input 仅能从 `QUEUED` 成功迁移一次。
- Daemon 断开只移除对应内存 Environment；历史 `harness_tool_invocation.environment_name` 保留冻结路由名。
- ComfyUI job 是远端系统事实；数据库只保存工作流定义；对象走固定 bucket 预签名边界。
