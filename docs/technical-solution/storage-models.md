# 存储模型

本文描述当前云端内嵌 agent MVP 使用的表结构和初始化资源。数据库结构与上层 DTO 以 `agent` 包的 `ProviderInfo`、`ModelInfo`、`Variant`、`AgentInfo`、session event 语义为基准。

## 资源文件

| 文件 | 用途 |
| --- | --- |
| `core/src/main/resources/schema-h2.sql` | H2 表结构，服务测试与 `local-h2` 验收使用 |
| `core/src/main/resources/data-h2.sql` | `local-h2` 验收 stub seed |
| `core/src/main/resources/data-minimax-h2.sql` | `minimax-h2` 开发 seed |
| `core/src/main/resources/schema-mysql.sql` | MySQL 5.7 兼容表结构 |
| `core/src/main/resources/data-mysql.sql` | MySQL 最小 seed |

## 表清单

| 表 | 职责 | 业务键 |
| --- | --- | --- |
| `demo` | 既有 demo API 数据 | `id` |
| `agent_provider` | Provider 连接配置与展示信息 | `provider` |
| `agent_model` | 模型元数据与 variants | `provider + name` |
| `agent_definition` | Agent 定义 | `agent_name` |
| `agent_session` | Chat session metadata 与当前 head 指针 | `session_id` |
| `agent_session_head` | Session branch head | `head_id`，`session_id + head_name` |
| `agent_session_event` | Session branch event log | `event_id` |
| `agent_run` | Message submit 触发的 runtime run | `run_id` |

## `agent_provider`

| 字段 | 说明 |
| --- | --- |
| `provider` | provider 业务名 |
| `name` | 展示名称 |
| `description` | 展示描述 |
| `provider_type` | `ProviderType` |
| `base_url` | provider 服务地址 |
| `api_key` | provider 访问凭据 |
| `timeout_millis` | 请求总超时 |
| `stream_idle_timeout_millis` | 流式空闲超时 |

`agent_provider` 映射到 `ProviderInfo` 时只使用 `provider_type/base_url/api_key/timeout_millis/stream_idle_timeout_millis`。`provider/name/description` 只属于上层管理与展示。

## `agent_model`

| 字段 | 说明 |
| --- | --- |
| `provider` | 所属 provider |
| `name` | 模型名 |
| `display_name` | 展示名 |
| `description` | 描述 |
| `capabilities_json` | 能力元数据 JSON 对象 |
| `limit_json` | 限制元数据 JSON 对象 |
| `pricing_json` | 价格元数据 JSON 对象 |
| `default_variant` | 默认 variant |
| `variants_json` | `Variant` JSON 数组 |

`variants_json` 承载模型请求参数，例如 `temperature`、`maxOutputTokens`、`topP`、`providerOptions`。

## `agent_definition`

| 字段 | 说明 |
| --- | --- |
| `agent_name` | `AgentInfo.name` |
| `name` | 展示名称 |
| `description` | 展示描述 |
| `system_prompt` | `AgentInfo.systemPrompt` |
| `default_provider` | 默认 provider |
| `default_model` | 默认 model |
| `default_variant` | 默认 variant |
| `tools_json` | 工具名 JSON 数组 |
| `subagents_json` | 子 agent 名 JSON 数组 |
| `skills_json` | skill 名 JSON 数组 |

## `agent_session`

| 字段 | 说明 |
| --- | --- |
| `session_id` | session 业务 id |
| `agent_name` | 绑定的 agent |
| `title` | session 标题 |
| `status` | session 状态 |
| `current_head_event_id` | 当前默认 head event id |

## `agent_session_head`

| 字段 | 说明 |
| --- | --- |
| `head_id` | head 业务 id |
| `session_id` | session 业务 id |
| `head_name` | head 名称，默认值为 `default` |
| `head_event_id` | 当前 head 指向的 event id |

## `agent_session_event`

| 字段 | 说明 |
| --- | --- |
| `event_id` | event 业务 id |
| `session_id` | session 业务 id |
| `parent_event_id` | 父 event id |
| `run_id` | 关联 run id，可为空 |
| `event_type` | 事件类型 |
| `payload_type` | payload 类型 |
| `payload_json` | payload JSON 字符串 |

前端直接消费 event log，并在 `frontend/src/features/ai/session-events.ts` 聚合为聊天消息。

## `agent_run`

| 字段 | 说明 |
| --- | --- |
| `run_id` | run 业务 id |
| `session_id` | session 业务 id |
| `trigger_event_id` | 触发 run 的 event id |
| `status` | `queued`、`running`、`succeeded`、`failed` |

## 最小 seed

| Profile / 数据文件 | 表 | 记录 | 说明 |
| --- | --- | --- | --- |
| `local-h2` / `data-h2.sql` | `agent_provider` | `stub` | 本地验收 provider |
| `local-h2` / `data-h2.sql` | `agent_model` | `stub / acceptance-stub` | 本地验收 model，默认 variant 为 `default` |
| `local-h2` / `data-h2.sql` | `agent_definition` | `default-assistant` | 本地验收 agent |
| `minimax-h2` / `data-minimax-h2.sql` | `agent_provider` | `minimax` | 本地开发 provider，启动脚本会同步真实连接配置 |
| `minimax-h2` / `data-minimax-h2.sql` | `agent_model` | `minimax / MiniMax-M2.7` | 本地开发 model，默认 variant 为 `default` |
| `minimax-h2` / `data-minimax-h2.sql` | `agent_definition` | `default-assistant` | 本地开发 agent |

`local-h2` 下由 `LocalH2AcceptanceConfiguration` 注入 `AcceptanceStubProviderManager`，不访问外部 provider。
