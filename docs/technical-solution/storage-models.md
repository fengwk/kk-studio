# 存储模型

Harness 的关系数据库是执行和恢复的唯一事实源。H2 与 MySQL schema 均维护在 `core/src/main/resources/`；测试 schema 位于 `core/src/test/resources/`。S3 只保存 ComfyUI 对象和浏览器直传对象，不承担 Harness 事务状态。

## 资源文件

| 文件 | 用途 |
| --- | --- |
| `schema-h2.sql` / `schema-mysql.sql` | H2 与 MySQL 的完整表结构 |
| `data-h2.sql` | 本地 H2 最小 seed |
| `data-minimax-h2.sql` | MiniMax H2 开发 seed |
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

这些资源不带 Tenant、Workspace membership 或 ACL。资源 `version` 支持并发修改检测；一次 Run 使用解析后的 Agent Snapshot 与 Environment binding，不回看可变资源配置。

## Harness Session 与 Run

| 表 | 职责 | 关键字段/索引 |
| --- | --- | --- |
| `harness_session` | 根/子 Session 树和执行指针 | `root_session_id`、`parent_session_id`、`leaf_entry_id`、`active_run_id`、`yolo_enabled` |
| `harness_session_entry` | 完整语义历史 | `session_id`、`parent_entry_id`、`run_id`、`entry_type`、`payload_json` |
| `harness_run` | durable Run 与 claim lease | `session_id`、`trigger_entry_id`、`status`、`event_sequence`、`lease_*`、`cancel_requested_at` |
| `harness_run_event` | Run 内线性 event journal | `(run_id, sequence)` 唯一 |

`harness_session_entry.payload_json` 是严格 Session Entry JSON 边界，保存消息、冻结 Snapshot 和 compaction 语义。`harness_run_event.payload_json` 保存带 schema version 的实时进度和状态。Session Entry 是完整历史基线，Run Event 是运行中覆盖层。

`harness_run` 的 claim 查询使用 `(status, next_attempt_at, lease_until, id)` 索引；worker restart 后可重新 claim 已过期 lease。根/子关系通过 `root_session_id` 和 `parent_session_id` 保持，根 Session 的 `yolo_enabled` 是共享策略事实。

## Tool、Task 与 Control

| 表 | 职责 | 关键约束 |
| --- | --- | --- |
| `tool_invocation` | Tool 权限、目标、lease、结果与终态 | `(run_id, tool_call_id)` 和 `(assistant_entry_id, ordinal)` 唯一 |
| `tool_artifact` | 全局不可变 Tool 输出 bytes | content、media type、size、SHA-256 一起持久化 |
| `harness_subagent_task` | parent invocation 到 child Session/Run 的 durable relation | `parent_invocation_id` 主键 |
| `harness_run_control_message` | steer / follow-up 的消费记录 | pending control 查询和 Session 查询索引 |

`tool_invocation` 冻结 Tool name/version、target、Environment ID、interceptor 后 arguments、权限策略和 deadline。Environment worker 使用 `(environment_id, status, deadline_at, id)` 的受限索引；连接断开不改变 Invocation 的 durable 终态。Artifact bytes 不存入 Session payload，Session/Tool Result 只保存 artifact reference。

Subagent task 的 child Session、child Run、working-copy policy/revision 和终态 report 都可由任务表读取。Root Activity 由持久 Session/Run/Task/Invocation/Control 事实查询构建，不单独维护内存 EventBus。

## Usage 与成本

`model_usage_record` 对每个 Assistant Entry 只保存一条不可变账本记录。它冻结 provider/model、Prompt Cache 策略和命中、token 明细、stop reason、价格版本与成本分项；`assistant_entry_id` 和 `(run_id, attempt, turn_index)` 都有唯一约束。Session 用量 API 只聚合账本，不从当前模型配置重算历史成本。

## 兼容资源表

Harness 运行时与前端 Timeline 只使用 `harness_*`、`tool_*`、`model_usage_record` 和 `tool_environment` 表。Provider / Model / AgentDefinition 资源使用 `agent_provider`、`agent_model`、`agent_definition` 表。

## 事务与删除

- Harness 持久写入遵守 `Run -> Session -> Root -> Invocation/Task/Control` 锁顺序。
- Assistant Entry、Usage Record 和对应 Run Event 原子写入。
- Environment 删除由服务层在存在 Tool Invocation 引用时拒绝，避免删除仍可恢复的 execution binding。
- ComfyUI job 是远端系统事实，数据库只保存工作流定义；输入和输出对象使用固定 bucket 的预签名边界。
