# 存储模型

本文描述云端内嵌 Agent Studio 当前使用的数据库结构与初始化资源。数据库只持久化已经参与管理或运行时行为的数据，不保留未消费的扩展字段。

## 资源文件

| 文件 | 用途 |
| --- | --- |
| `core/src/main/resources/schema-h2.sql` | H2 表结构，供本地运行使用 |
| `core/src/main/resources/data-h2.sql` | `local-h2` 验收 stub seed |
| `core/src/main/resources/data-minimax-h2.sql` | `minimax-h2` 开发 seed |
| `core/src/main/resources/schema-mysql.sql` | MySQL 5.7 兼容表结构 |
| `core/src/main/resources/data-mysql.sql` | MySQL 最小 seed |
| `core/src/test/resources/schema-h2.sql` | Core 测试表结构 |
| `core/src/test/resources/data-h2.sql` | Core 测试 seed |

## 表清单

| 表 | 职责 | 业务键 |
| --- | --- | --- |
| `agent_provider` | Provider 连接配置与展示信息 | `name` |
| `agent_model` | 模型与 variants | `provider_id + name` |
| `agent_definition` | Agent 定义 | `name` |
| `agent_session` | Chat session 元数据与当前事件指针 | `session_id` |
| `agent_session_event` | Session 事件树 | `event_id` |
| `agent_run` | Message submit 触发的 runtime run | `run_id` |

## `agent_provider`

| 字段 | 说明 |
| --- | --- |
| `id` | 内部主键 |
| `name` | Provider 业务名称 |
| `description` | 展示描述 |
| `provider_type` | `ProviderType` |
| `base_url` | Provider 服务地址 |
| `api_key` | Provider 访问凭据 |
| `timeout_millis` | 请求总超时 |
| `gmt_create` / `gmt_modified` | 创建、更新时间 |

运行时映射只使用 `provider_type/base_url/api_key/timeout_millis` 构造 `ProviderInfo`。

## `agent_model`

| 字段 | 说明 |
| --- | --- |
| `id` | 内部主键 |
| `provider_id` | 所属 Provider |
| `name` | 模型名称 |
| `description` | 展示描述 |
| `default_variant` | 默认 variant 名称 |
| `variants_json` | `Variant` JSON 数组 |
| `gmt_create` / `gmt_modified` | 创建、更新时间 |

`variants_json` 承载运行时支持的模型请求参数，包括 `temperature`、`maxOutputTokens`、`topP`、`topK`、`frequencyPenalty`、`presencePenalty` 和 `stopSequences`。

## `agent_definition`

| 字段 | 说明 |
| --- | --- |
| `id` | 内部主键 |
| `name` | `AgentInfo.name` |
| `description` | 展示描述 |
| `system_prompt` | `AgentInfo.systemPrompt` |
| `default_provider_id` | 默认 Provider |
| `default_model_id` | 默认 Model |
| `default_variant` | 默认 variant |
| `tools_json` | Agent 声明的工具名称 JSON 数组 |
| `gmt_create` / `gmt_modified` | 创建、更新时间 |

## `agent_session`

| 字段 | 说明 |
| --- | --- |
| `id` | 内部主键 |
| `session_id` | Session 业务 ID |
| `agent_id` | 绑定的 Agent ID |
| `agent_name` | 绑定时的 Agent 名称快照 |
| `title` | Session 标题 |
| `current_head_event_id` | 当前默认继续点；初始值为 `root` |
| `gmt_create` / `gmt_modified` | 创建、更新时间 |

系统只维护一个当前继续点，因此 `current_head_event_id` 是唯一 head 事实来源，不额外维护命名 head 表。

## `agent_session_event`

| 字段 | 说明 |
| --- | --- |
| `id` | 内部顺序主键 |
| `event_id` | Event 业务 ID |
| `session_id` | 所属 Session |
| `parent_event_id` | 父 Event ID，根节点使用 `root` |
| `run_id` | 关联 Run ID，可为空 |
| `event_type` | 事件类型，同时决定 payload 的反序列化类型 |
| `payload_json` | Payload JSON 字符串 |
| `gmt_create` / `gmt_modified` | 创建、更新时间 |

`parent_event_id` 构成事件树。默认读取从 `agent_session.current_head_event_id` 沿父链回放；显式传入 `headEventId` 时从指定事件沿父链回放。

## `agent_run`

| 字段 | 说明 |
| --- | --- |
| `id` | 内部主键 |
| `run_id` | Run 业务 ID |
| `session_id` | 所属 Session |
| `trigger_event_id` | 触发 Run 的 `user_message` Event ID |
| `status` | `queued`、`running`、`succeeded`、`failed` |
| `gmt_create` / `gmt_modified` | 创建、更新时间 |

同一 Session 同时只允许一个 `queued` 或 `running` Run。

## 聚合删除

删除 Session 时先锁定 Session 并确认不存在 active Run，然后在同一事务内删除：

1. `agent_run`
2. `agent_session_event`
3. `agent_session`

## 最小 seed

| Profile / 数据文件 | 表 | 记录 | 说明 |
| --- | --- | --- | --- |
| `local-h2` / `data-h2.sql` | `agent_provider` | `stub` | 本地验收 Provider |
| `local-h2` / `data-h2.sql` | `agent_model` | `acceptance-stub` | 本地验收 Model |
| `local-h2` / `data-h2.sql` | `agent_definition` | `default-assistant` | 本地验收 Agent |
| `minimax-h2` / `data-minimax-h2.sql` | `agent_provider` | `minimax` | 本地开发 Provider，启动脚本同步真实凭据 |
| `minimax-h2` / `data-minimax-h2.sql` | `agent_model` | `MiniMax-M2.7` | 本地开发 Model |
| `minimax-h2` / `data-minimax-h2.sql` | `agent_definition` | `default-assistant` | 本地开发 Agent |

`local-h2` 由 `LocalH2AcceptanceConfiguration` 注入 `AcceptanceStubProviderManager`，不访问外部 Provider。
