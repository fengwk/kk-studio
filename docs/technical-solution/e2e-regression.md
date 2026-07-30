# E2E 回归

本文描述 `kk-studio` 当前生效的端到端自动化回归：矩阵分层、入口、报告目录、用例清单与维护方式。

> `e2e` profile 通过 Flyway 对空 PostgreSQL 数据库执行 [`V1__schema.sql`](../../core/src/main/resources/db/migration/V1__schema.sql) 和 [`V2__e2e_seed.sql`](../../core/src/main/resources/db/seed/e2e/V2__e2e_seed.sql)。不再使用 H2/MySQL 作为 durable 存储；启动 e2e 前需提供可写空库（可用环境变量 `KK_STUDIO_DB_URL` / `KK_STUDIO_DB_USER` / `KK_STUDIO_DB_PASSWORD`）。`flyway_schema_history` 校验并记录已执行版本。

## 边界

- **已覆盖**：API 契约、资源 CRUD、Model/Agent 配置校验矩阵、Thread 生命周期与 head 重定位（bootstrap / rebind / unbind / stop / epoch fencing）、usage 语义；可选真模型/分支/tool。
- **未覆盖（默认）**：浏览器点击 UI、Canvas/ComfyUI 全流程、配置笛卡尔全组合穷举、SSE 多 Pane 视觉。
- 真模型与 tool/branch 默认关闭，需显式参数。

## 入口

```bash
./scripts/e2e.sh                         # 默认完整 L1 矩阵（免费）
./scripts/e2e.sh --rebuild               # 重打包并重启服务
./scripts/e2e.sh --real                  # + 真 MiniMax 文本（需 TEST_MINIMAX_*）
./scripts/e2e.sh --real --with-branch
./scripts/e2e.sh --real --with-tools
./scripts/e2e.sh --ui                 # + Playwright UI smoke（截图进报告）
./scripts/e2e.sh --list
./scripts/e2e.sh --docs

npm --prefix frontend run e2e
npm --prefix frontend run e2e:ui
npm --prefix frontend run e2e:matrix
npm --prefix frontend run e2e:list
```

执行 `--rebuild` / `--real` 时，E2E runner 在 backend ready 后调用唯一的 Python API
同步器。app 和 Compose 不读取真实 Provider 凭证，密钥不会写入仓库或报告。

| Provider | 协议 | Base URL | API Key |
| --- | --- | --- | --- |
| MiniMax | OpenAI Responses | `TEST_MINIMAX_BASE_URL` | `TEST_MINIMAX_API_KEY` |

当前默认 E2E Agent 固定使用 `minimax/MiniMax-M2.7`，因此 `--real` 必须同时提供
`TEST_MINIMAX_BASE_URL` 和 `TEST_MINIMAX_API_KEY`。这是唯一真实 credential 输入；同步器
仅更新确定性 MiniMax Provider `id=1`，并将 Base URL 去尾斜杠后补为 `/v1`。

E2E seed 固定包含 Pi 0.82.1 有效运行时中的 19 个模型：

| Provider | 模型 |
| --- | --- |
| MiniMax | `MiniMax-M2.7`、`MiniMax-M3` |
| OpenAI | `gpt-5.4`、`gpt-5.5`、`gpt-5.6-luna`、`gpt-5.6-sol`、`gpt-5.6-terra` |
| xAI | `grok-4.5` |
| DeepSeek | `deepseek-v4-flash`、`deepseek-v4-pro` |
| Google | `gemini-3.5-flash`、`gemini-3.6-flash`、`gemini-3.1-pro-preview` |
| Anthropic | `claude-sonnet-4-6`、`claude-opus-4-6`、`claude-sonnet-5`、`claude-opus-5`、`claude-fable-5` |
| ZAI | `glm-5.2` |

模型名称、上下文与输出上限、输入模态、基础价格及 Pi 支持的 thinking levels
由快照整体校验。OpenAI 分档阈值等于当前 272000 context 上限，seed 使用可达区间的
基础价格；Anthropic 1 小时缓存写价格按 Pi 规则使用 `2 * input`。默认 Agent 仍绑定
`minimax/MiniMax-M2.7` 的 `high` variant。

## 实现结构

| 路径 | 职责 |
| --- | --- |
| `scripts/e2e.sh` | 环境准备 + 调用矩阵 |
| `scripts/e2e/lib.sh` | backend/frontend/daemon 启停、MiniMax credential 同步（依赖 Python 同步器） |
| `scripts/e2e/sync_provider_credentials.py` | 唯一 MiniMax credential 同步来源：通过后端 API 更新 seed Provider `id=1`；可独立执行与单元测试 |
| `scripts/e2e/run-matrix.mjs` | Node API 矩阵 runner、报告输出 |
| `scripts/e2e/ui-smoke.mjs` | Playwright UI smoke（L5） |
| `scripts/e2e/lib/http.mjs` | fetch/断言 |
| `scripts/e2e/lib/fixtures.mjs` | 合法体与配置矩阵行 |
| `scripts/e2e/lib/harness.mjs` | 共享 Thread 步骤：创建/引导/重绑、Thread/Session 读取、等待静止，以及先连接 SSE 再等待严格模型文本 delta 的 stop 时机 helper |
| `scripts/e2e/lib/registry.mjs` | case 注册表 |
| `scripts/e2e/cases/*.mjs` | API 用例 |
| `core/src/test/resources/.../pi-model-catalog.json` | Pi 0.82.1 有效模型目录快照 |
| `reports/e2e/` | 报告与产物（gitignore） |

## 报告

```text
reports/e2e/<runId>/
  report.md
  summary.json
  cases/<id>.json
  artifacts/<id>/
  logs/
reports/e2e/latest/report.md
reports/e2e/LATEST_RUN.txt
```

判读顺序：`latest/report.md` → `summary.json` → `cases/` / `artifacts/` → `logs/`。

## 矩阵分层

| 层级 | 开关 | 成本 | 覆盖 |
| --- | --- | --- | --- |
| L1 | 默认 | 免费 | seed 契约、CRUD、配置校验、Thread 生命周期与 head 重定位、Thread snapshot 404、proxy |
| L2 | `--real` | `minimax/MiniMax-M2.7` | 文本轮次 + usage 入账；流式 `/stop` 持久化 partial assistant barrier，并在其后继续 follow-up |
| L3 | `--real --with-branch` | `minimax/MiniMax-M2.7` | rebind 到历史 Entry 后的分支路径 usage |
| L4 | `--with-tools` / `--real --with-tools` | daemon / `minimax/MiniMax-M2.7` | Environment READY、tool invocation |
| L5 | `--ui` | 本地浏览器 | 页面可达、列表渲染、打开新建模态、无致命 pageerror；截图入报告 |

API 矩阵注册 **58** 条（以 `./scripts/e2e.sh --list` 为准）。默认执行全部免费 L1（**53** 条）。`--ui` 额外 **14** 条 UI smoke（`--real` 时再 +1 真实首发）。

## L1 用例清单

### Seed / 契约 / 编排

| Case | 断言 |
| --- | --- |
| `seed.structured_model_config` | 19 个模型完整匹配 Pi 快照；xAI 仅 `grok-4.5`；禁止旧 JSON 字段 |
| `seed.agent_and_provider` | seed agent；七个 Provider 及协议映射 |
| `thread.blank_first_send_order` | 首发顺序 `createThread -> bootstrap -> USER_MESSAGE`；bootstrap 的 `RUNTIME_CONFIG` 先在路径上，mailbox 只有 USER_MESSAGE 且被 APPLIED |
| `thread_snapshot.unknown_thread_404` | 未知 Thread snapshot 404 |
| `frontend.proxy_model_contract` | 5173 代理契约 |
| `thread.commands_model_yolo` | SET_MODEL + SET_YOLO 应用 |
| `thread.commands_model_invalid_variant_rejected` | 非法 Variant 在 SET_MODEL 入队前拒绝 |
| `harness.retry_policy_round_trip` | GET original → PUT 合法差异策略 → GET 四字段一致；finally 恢复 original |
| `harness.realtime_stream_policy_round_trip` | GET original → PUT 合法差异 `maxLength` → GET 一致；finally 恢复 original |

### Thread 生命周期与 head 重定位

| Case | 断言 |
| --- | --- |
| `thread.unbound_create` | `POST /api/ai/runtime/threads` 无 body => 201；`status=UNBOUND`，`headEntryId`/`sessionId` 为空，`executionEpoch=0`，无路径 Entry，且出现在 `GET /api/ai/runtime/threads` |
| `thread.unbound_message_rejected` | UNBOUND Thread 入队消息 => 409 thread is unbound；不写入 Input |
| `thread.bootstrap_binds_session` | `POST /api/ai/runtime/threads/{id}/bootstrap` => 201 `{session, thread}`；复用同一 Thread，epoch+1，head 指向 `RUNTIME_CONFIG`，Session 由 head Entry 派生 |
| `thread.stale_epoch_rejected` | message 与 `PUT /head` 携带过期 `expectedExecutionEpoch` => 409 stale execution epoch；head、epoch 与 mailbox 均不变 |
| `thread.rebind_same_session` | `PUT /head` 指向同 Session 的 ROOT => head 更新、epoch+1、`sessionId` 不变，路径 Entries 跟随新 head |
| `thread.rebind_cross_session` | `PUT /head` 指向另一 Session 的 Entry => `threadId` 不变，派生 `sessionId` 切换 |
| `thread.unbind_head` | `headEntryId=null` => `status=UNBOUND`、`sessionId`/`headEntryId` 为空、无路径 Entry；未知 Entry => 404 |
| `thread.stop_then_rebind` | 免费覆盖 stop 的 epoch/cancel/rebind：递增 epoch、取消 queued Input 与 OPEN Interaction，随后 `PUT /head` 成功；不生成真实流式 partial |

### CRUD

| Case | 操作 |
| --- | --- |
| `crud.provider.invalid_blank_base_url_type_ok_name_only_fails` | 空白 name 在创建校验被拒 |
| `crud.provider.invalid_missing_type` | 缺 providerType => 400 |
| `crud.model.invalid_update_config` | 带创建时版本的非法 PUT 被拒绝且原配置保留 |
| `crud.agent.invalid_blank_name` | 空白 name => 400 |
| `crud.agent.invalid_variant` | Variant 不属于所选 Model => 400 |
| `crud.provider.lifecycle` | create/list/update/delete；PUT 与 DELETE 均回显响应版本 |
| `crud.model.lifecycle` | create/update/delete model（临时 provider；PUT/DELETE 使用版本） |
| `crud.agent.lifecycle` | create/update/delete agent（PUT/DELETE 使用版本） |
| `crud.chat.invalid_agent_id` | 非正整数字符串 defaultAgentId => 400 |
| `crud.model.delete_unknown_rejected` | 删除不存在 Model（`expectedVersion=0`）=> 404 |
| `crud.chat.lifecycle` | create/update/delete chat；PUT/DELETE 使用版本；空白 title 更新拒绝；删后 404。Chat 不持有 Session |

### Model config 矩阵

由 `fixtures.modelConfigMatrix()` 展开为独立 case：

| Case 后缀 | 期望 |
| --- | --- |
| `valid.minimal` | 成功 |
| `valid.reasoning_variants` | 成功 |
| `valid.sampling_fields` | 成功 |
| `invalid.defaultVariant_mismatch` | 400 |
| `invalid.empty_variants` | 400 |
| `invalid.context_non_positive` | 400 |
| `invalid.output_gt_context` | 400 |
| `invalid.blank_currency` | 400 |
| `invalid.empty_modalities` | 400 |
| `invalid.duplicate_variant_id` | 400 |
| `invalid.missing_config` | 400 |
| `invalid.variant_blank_id` | 400 |
| `invalid.negative_temperature` | 400 |

### Agent config 矩阵

| Case 后缀 | 期望 |
| --- | --- |
| `valid.empty_lists` | 成功（空 `tools`/`skills`） |
| `invalid.missing_tools` | 400（`tools` 必填） |
| `invalid.missing_skills` | 400（`skills` 必填） |
| `invalid.unready_environment` | 400（`environmentName` 不是 READY 环境） |
| `invalid.unknown_tool` | 400 unknown agent tool |
| `invalid.duplicate_skill` | 400 |
| `invalid.blank_environment_name` | 400 environmentName blank |
| `invalid.unknown_field_rejected` | 400（未知字段被严格 codec 拒绝） |

另有 setup/teardown case 管理临时 provider/model。

## 可选真实链路

| Case | 开关 | 断言 |
| --- | --- | --- |
| `real.text_turn` | `--real` | 真实 Provider 文本轮次成功并记账 |
| `real.stop_partial_continue` | `--real` | 首个非空文本 delta 后 stop；durable `ASSISTANT_ABORTED` 仅含安全 text/thinking；follow-up 位于 barrier 后，旧 debt 不重派 |
| `branch.path_usage` | `--real --with-branch` | 另一条 Thread rebind 到历史 assistant Entry 后再发一轮；session 去重 vs thread 可重复计共享前缀 |
| `daemon.ready` | `--with-tools` | Daemon Environment READY |
| `tool.read_turn` | `--real --with-tools` | YOLO 下 tool invocation |

## UI smoke（L5）

| Case | 断言 |
| --- | --- |
| `ui.chats.page_loads` | `/chats` + 新建 Chat |
| `ui.models.page_loads` | `/models` 渲染全部 19 个 Pi seed 模型 + 新建 Model |
| `ui.models.open_create_modal` | 点击新建 Model |
| `ui.agents.page_loads` | `/agents` + default-assistant |
| `ui.providers.page_loads` | `/providers` |
| `ui.environments.page_loads` | `/environments` |
| `ui.nav.roundtrip` | 导航往返无 pageerror |
| `ui.chat.create_flow` | UI 创建 Chat 并出现在列表 |
| `ui.model.create_edit_delete_flow` | UI 创建/编辑/删除 Model |
| `ui.agent.create_edit_delete_flow` | UI 创建/编辑/删除 Agent |
| `ui.model.validation_empty_name` | 空名称前端校验错误展示 |
| `ui.provider.create_edit_delete_flow` | UI 创建/编辑/删除 Provider |
| `ui.chat.blank_workspace_shell` | 进入空白工作区，校验 blank pane + composer |
| `ui.chat.blank_first_send_real` | （`--real`）blank 首发真实消息；断言冻结 Agent/Model/Variant footer 与 assistant 的 `OK` 回复 |

截图：`reports/e2e/latest/artifacts/ui_*/`；汇总：`ui-report.md` / `ui-summary.json`。

## 关键契约锚点

```text
GET|POST /api/ai/catalog/providers
GET|POST /api/ai/catalog/models
GET|POST /api/ai/catalog/agents
PUT|DELETE /api/ai/catalog/{providers,models,agents}/{id}            # expectedVersion 必填十进制字符串
GET|POST /api/ai/chat
GET|PUT|DELETE /api/ai/chat/{id}                                     # PUT/DELETE 的 expectedVersion 必填
GET|POST /api/ai/runtime/threads                                    # POST 无 body => 201 UNBOUND Thread
GET /api/ai/runtime/threads/{id}
POST /api/ai/runtime/threads/{id}/bootstrap                          # => 201 {session, thread}
PUT /api/ai/runtime/threads/{id}/head                                # headEntryId 可为 null
POST /api/ai/runtime/threads/{id}/messages
POST /api/ai/runtime/threads/{id}/messages/custom
PUT /api/ai/runtime/threads/{id}/{agent,model,yolo}
POST /api/ai/runtime/threads/{id}/stop
GET /api/ai/runtime/threads/{id}/snapshot                            # unknown => 404
GET /api/ai/runtime/threads/{id}/events/stream?afterRevision={revision}
GET /api/ai/runtime/sessions
GET /api/ai/runtime/sessions/{id}
GET /api/ai/runtime/sessions/{id}/entries
GET /api/ai/runtime/interactions/{id}
GET /api/ai/runtime/interactions/open
POST /api/ai/runtime/interactions/{id}/response
GET /api/ai/runtime/tool-invocations/{id}
GET /api/ai/runtime/artifacts/{id}
GET /api/ai/runtime/usage/{sessions,models}/{id}
GET|PUT /api/ai/runtime/settings/retry-policy
GET|PUT /api/ai/runtime/settings/realtime-stream-policy
GET /api/ai/environment
WebSocket /api/ai/environment/daemon/v1
```

上述 Thread 写接口的请求体均含必填 `expectedExecutionEpoch`：epoch 过期或 Thread 非静止 => `409`，未知资源 => `404`。

Thread snapshot 的 `revision` 是十进制字符串的 durable cursor；SSE 的 `revision` 帧携带同一
durable id，`resync` 提示客户端重新加载 snapshot。Redis `realtime` 增量无 SSE id，仅用于
瞬态输出，不能替代 durable snapshot。

Catalog（Provider / Model / Agent）与 Chat 响应中的 `version` 是十进制字符串，`createTime` /
`updateTime` 是 Instant 时间戳。每次成功更新版本递增；PUT 的 `expectedVersion` 或 DELETE
查询参数过期时返回 `409 version_conflict`，资源不存在时返回 `404 resource_not_found`。

## 维护

1. 改 API/配置校验：先更新 `fixtures.mjs` 矩阵行与本文表格，再跑 `./scripts/e2e.sh`。
2. 改首发/编排：更新 `cases/seed-and-harness.mjs`；跨用例复用的 Thread 步骤放进 `lib/harness.mjs`，不在各 case 内重复拼 HTTP。
3. 前端 UI 变更：默认 E2E 不绑 selector；组件测用 Vitest。
4. 报告失败时从 `reports/e2e/latest/report.md` 开始排查。
5. 运行 jar 必须与源码一致；重启后需重新注入 provider credential。

## 非目标（避免误解“全面”）

当前自动化 **不代表**：

- 所有 UI 点击路径
- 所有配置字段笛卡尔积
- Canvas / ComfyUI / 权限弹窗全量
- 视觉回归与 Footer 像素级换行

这些可后续作为独立层扩展（Playwright smoke、更多矩阵行），但仍应写入本文件与报告。
