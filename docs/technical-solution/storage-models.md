# 存储模型

关系数据库是 Harness 执行恢复与 Canvas 最小持久化的事实源。H2 与 MySQL schema 均维护在 `core/src/main/resources/`；测试 schema 位于 `core/src/test/resources/`。S3 只保存 ComfyUI 对象和浏览器直传对象，不承担 Harness 或 Canvas 事务状态。

## 资源文件

| 文件 | 用途 |
| --- | --- |
| `schema-h2.sql` / `schema-mysql.sql` | H2 与 MySQL 的完整表结构 |
| `data-dev.sql` | `dev` profile：内存 H2 + stub provider 最小 seed |
| `data-e2e.sql` | `e2e` profile：内存 H2 + 真模型 seed（默认 MiniMax，可替换） |
| `data-mysql.sql` | MySQL 最小 seed |
| `src/test/resources/schema-h2.sql` | Core 集成测试 schema |

## 全局资源

| 表 | 职责 | 关键约束 |
| --- | --- | --- |
| `agent_provider` | Provider 连接和配置 | `name` 唯一 |
| `agent_model` | 模型能力和配置 | `name` 唯一 |
| `agent_definition` | Agent 定义、默认模型和 config | `name` 唯一 |
| `tool_environment` | 全局 Environment daemon registry | `name` 唯一；capability/last-seen 由 daemon 更新 |
| `comfyui_workflow_api` | ComfyUI 工作流卡片 | `api_name` 唯一 |

这些资源不带 Tenant、Workspace membership 或 ACL。资源 `version` 支持并发修改检测；一次 Turn 使用 Thread 冻结 runtime config 与解析后的 binding，不回看可变资源配置。

## Canvas 最小持久化

| 表 | 职责 | 关键约束 |
| --- | --- | --- |
| `canvas_document` | Canvas 身份、title、schema version、revision、lifecycle 与默认 viewport | 索引 `(workspace_id, gmt_modified)` |
| `canvas_node` | RESOURCE/FUNCTION/GROUP 节点、transform、表现数据与 revision | 按 `(canvas_id, gmt_deleted)` 查询；节点使用 `gmt_deleted` 软删除 |
| `canvas_link` | source Resource 对 target 的可见性 Link | 唯一 `(canvas_id, source_node_id, target_node_id)` |
| `canvas_command` | 客户端幂等命令与 revision 结果 | 唯一 `(workspace_id, command_id)`；保存 `request_hash` 检测幂等冲突 |

当前 `DurableCanvasService` 支持创建 Canvas，以及 `create_text_node`、`create_generate_text_node`、`create_link`、`move_nodes`、`delete_node` 五种命令。命令以 `baseRevision` 做乐观并发控制；相同 `commandId + requestHash` 不重复应用并返回当前持久 snapshot，不同 hash 返回 `IDEMPOTENCY_CONFLICT`。

`ResourceReference`、Resource/ResourceVersion、FunctionRun、Workflow 及其 worker 尚未进入当前 schema；完整目标契约见 [infinite-canvas-implementation-design.md](infinite-canvas-implementation-design.md)。

## Session、Thread 与 Event

| 表 | 职责 | 关键字段 / 索引 |
| --- | --- | --- |
| `harness_session` | Session tree 容器（根/子） | `root_session_id`、`parent_session_id`、`parent_invocation_id`、`depth`；索引 `idx_harness_session_root (root_session_id)` |
| `harness_session_entry` | append-only 语义历史 | `session_id`、`parent_entry_id`、`entry_type`、`payload_json`；索引 `(session_id, id)`、`parent_entry_id` |
| `harness_thread` | durable 用户面板 / tree cursor | `session_id`、`head_entry_id`、`agent_definition_id`、`runtime_config_json`、`yolo_enabled`、`input_sequence`、`processor_token`、`processor_until`、`version`；索引 `(session_id, id)` |
| `harness_thread_input` | 有序 mailbox | `thread_id`、`sequence`、`input_type`、`payload_json`、`client_message_id`、`applied_entry_id`、`applied_at`；唯一 `(thread_id, sequence)`、`(thread_id, client_message_id)`；索引 `(thread_id, applied_entry_id, sequence)` |
| `harness_thread_event` | Thread event journal | `id`（全局 SSE cursor）、`thread_id`、`subject_entry_id`、`event_type`、`payload_json`；索引 `(thread_id, id)` |

Session **不**保存 leaf、active 执行指针或 YOLO。Branch 不单独建表。`payload_json` 是严格 Session Entry JSON 边界。ThreadEvent `payload_json` 含 schema version 的实时进度；Entry 是完整语义基线，Event 是可观测覆盖层。

`processor_token` / `processor_until` 是跨节点单飞租约；`version` 为乐观版本。

## Tool、Task 与 Artifact

| 表 | 职责 | 关键约束 |
| --- | --- | --- |
| `tool_invocation` | Tool 权限、目标、lease、结果与终态 | 唯一 `(thread_id, tool_call_id)`、`(assistant_entry_id, ordinal)`；索引 `(thread_id, status, ordinal)`、`(target_type, status, deadline_at, id)`、`(environment_id, status, deadline_at, id)` |
| `tool_artifact` | 全局不可变 Tool 输出 bytes | content、media type、size、SHA-256 |
| `harness_subagent_task` | parent invocation → child Session/Thread | 主键 `parent_invocation_id`；唯一 `child_thread_id`；索引 `(parent_session_id, status)` |

`tool_invocation` 冻结 tool name/version、target、environment_id、arguments、permission、`side_effect`、deadline。Lease recovery 使用冻结 `side_effect`。Artifact bytes 不进入 Session payload；Tool Result 只保存 artifact reference。

Subagent task 字段：`parent_session_id`、`parent_thread_id`、`child_session_id`、`child_thread_id`、`target_agent`、`working_copy_policy` / `working_copy_revision`、`max_turns`、`status`、`report_json`。

Root Activity 由持久 ThreadEvent 等事实查询构建，无独立 activity 表。

## Usage 与成本

`model_usage_record` 对每个 Assistant Entry 只保存一条不可变账本：

| 归属键 | 说明 |
| --- | --- |
| `session_id` | 所属 Session |
| `thread_id` | 所属 Thread |
| `assistant_entry_id` | **唯一**；产生账本的 Assistant Entry |

另冻结 provider/model、Prompt Cache 策略与命中、token 明细、stop reason、价格版本与成本分项。

约束与索引：

```text
primary key (id)
unique (assistant_entry_id)          -- uk_model_usage_record_assistant_entry
index (thread_id, id)                -- idx_model_usage_record_thread
index (session_id, id)               -- idx_model_usage_record_session
index (model_resource_id, id)        -- idx_model_usage_record_model
```

H2：`numeric(32,12)` / `clob` / `timestamp(3)`；MySQL：`decimal(32,12)` / `longtext` / `datetime(3)`，InnoDB utf8mb4。

## 兼容资源表

Harness 运行时与前端 Timeline 使用 `harness_*`、`tool_*`、`model_usage_record` 和 `tool_environment`。Provider / Model / AgentDefinition 使用 `agent_provider`、`agent_model`、`agent_definition`。

## 事务与删除

- 锁序：非锁 peek → **Thread** → Invocation / Task。
- ThreadEvent `id` 为全局 Snowflake cursor；分配与插入在写路径中完成。
- Assistant Entry 与 Usage Record 原子写入；`assistant_entry_id` 唯一冲突回滚整事务。
- Input `markApplied` 仅成功一次。
- Environment 删除在存在 Tool Invocation 引用时由服务层拒绝。
- ComfyUI job 是远端系统事实；数据库只保存工作流定义；对象走固定 bucket 预签名边界。
