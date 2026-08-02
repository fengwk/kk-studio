# E2E 回归

本文描述当前 Node API 矩阵的 case、开关、API 验证方式和报告位置。事实源命令是：

```bash
node scripts/e2e/run-matrix.mjs --list
```

当前注册 **57** 个 API case；标准入口默认执行免费的 **L1 51** 个 case。L2/L3/L4 需要显式打开真实 Provider、分支或 Environment Tool 开关。UI smoke 由 `scripts/e2e.sh --ui` 另行附加，不计入这 57 个 Node API case。

## 1. 入口与开关

```bash
./scripts/e2e.sh                         # 默认 L1，免费
./scripts/e2e.sh --rebuild               # Java 21 clean package 后启动服务
./scripts/e2e.sh --real                  # L2 真实 MiniMax
./scripts/e2e.sh --real --with-branch    # L3 分支路径
./scripts/e2e.sh --with-tools             # L4 Environment projection
./scripts/e2e.sh --real --with-tools      # L4 真实 Tool turn
./scripts/e2e.sh --ui                    # Playwright UI smoke
./scripts/e2e.sh --list
./scripts/e2e.sh --docs

npm --prefix frontend run e2e
npm --prefix frontend run e2e:ui
npm --prefix frontend run e2e:matrix
npm --prefix frontend run e2e:list
npm --prefix frontend run e2e:docs
```

`--with-branch` 自动启用 `--real`。Node runner 还支持：

```text
--base-url URL
--frontend-url URL
--daemon-env NAME
--real
--with-tools
--with-branch
--only CASE_ID
--level L1|L2|L3|L4
--list
--docs
--report-root DIR
--no-report
```

默认 backend URL 是 `http://127.0.0.1:18081`，默认 frontend URL 由 `scripts/e2e.sh` 传入 `http://127.0.0.1:5173`。真实 Provider 使用 `TEST_MINIMAX_BASE_URL` 与 `TEST_MINIMAX_API_KEY`，默认测试模型是 `minimax/MiniMax-M2.7`。

## 2. 分层

| 层级 | 开关 | 成本 | 覆盖 |
| --- | --- | --- | --- |
| L1 | 默认 | 免费 | Catalog/Chat CRUD、名称身份、逐消息设置、Thread/Session、head、planning failure、policy、i18n 与 proxy |
| L2 | `--real` | MiniMax | 文本轮次、queued batch、stop partial、Usage |
| L3 | `--real --with-branch` | MiniMax | 历史 Entry 路径切换后的 Usage |
| L4 | `--with-tools` 或 `--real --with-tools` | Daemon / MiniMax | READY Environment、ToolCatalog、ToolInvocation |

默认 L1 不启动真实 Provider，也不执行 Tool 外部副作用。

## 3. 当前注册 case

下面的 ID 与 `node scripts/e2e/run-matrix.mjs --list` 一致。

### L1（50）

```text
seed.structured_model_config
seed.agent_and_provider
thread.chat_scoped_create_atomic
thread.stale_epoch_rejected
thread.rebind_same_session
thread.rebind_cross_session
thread.stop_then_rebind
thread.turn_settings_wysiwyg_failure
thread.custom_message_turn_settings
thread_snapshot.unknown_thread_404
frontend.proxy_model_contract
harness.retry_policy_round_trip
harness.realtime_stream_policy_round_trip
crud.provider.invalid_name
crud.provider.invalid_missing_type
crud.model.invalid_update_config
crud.agent.invalid_name
crud.agent.invalid_variant
crud.provider.lifecycle
crud.model.lifecycle
crud.agent.lifecycle
crud.chat.invalid_agent_name
crud.chat.visible_settings
crud.model.delete_unknown_rejected
crud.chat.lifecycle
crud.chat.thread_association_pagination
i18n.error_response_accept_language
matrix.model.setup_provider
config.model.valid.minimal
config.model.valid.reasoning_variants
config.model.valid.sampling_fields
config.model.invalid.defaultVariant_mismatch
config.model.invalid.empty_variants
config.model.invalid.context_non_positive
config.model.invalid.output_gt_context
config.model.invalid.blank_currency
config.model.invalid.empty_modalities
config.model.invalid.duplicate_variant_id
config.model.invalid.missing_config
config.model.invalid.variant_blank_id
config.model.invalid.negative_temperature
matrix.model.teardown_provider
matrix.agent.setup_model
config.agent.valid.empty_lists
config.agent.invalid.missing_tools
config.agent.invalid.missing_skills
config.agent.invalid.unknown_tool
config.agent.invalid.duplicate_skill
config.agent.invalid.unknown_field_rejected
matrix.agent.teardown_model
```

L1 的关键语义断言：

- Provider/Agent name 与 Model `(providerName,name)` 创建、更新、删除；
- Model config 与 Agent tools/skills 严格校验；
- Chat 持久化 `agentName`、`environmentName`、`yoloEnabled`；
- Chat-scoped Thread 原子产生 Session、ROOT 与非空 head；
- 每条 USER/CUSTOM message 携带精确 TurnSettings；
- 缺失能力产生 `ASSISTANT_ERROR`，不产生 ModelInvocation；
- `PUT /head` 只使用非空 Entry 和当前 epoch；
- stale version/epoch、未知请求目标与 invalid DTO/请求体引用分别验证 `409`、`404`、`400`；
- retry/realtime policy 通过 GET → PUT → GET 往返验证；
- `Accept-Language` 验证错误 message/title 本地化而稳定字段不变。

### L2/L3/L4（6）

```text
real.text_turn
real.queued_input_batch
real.stop_partial_continue
branch.path_usage
daemon.ready
tool.read_turn
```

| Case | 开关 | 重点验证 |
| --- | --- | --- |
| `real.text_turn` | `--real` | 真实 Provider 文本、Assistant Entry 与 Usage |
| `real.queued_input_batch` | `--real` | 运行期间入队消息在下一轮按 batch 处理 |
| `real.stop_partial_continue` | `--real` | 安全 text/thinking partial、stop barrier 与后续轮次 |
| `branch.path_usage` | `--real --with-branch` | head 切换到历史 Entry 后的路径 Usage |
| `daemon.ready` | `--with-tools` | READY Environment 与 `GET /api/ai/environment` 的固定十个 Tool |
| `tool.read_turn` | `--real --with-tools` | Agent 选择 `read`、YOLO、原 `environmentName` binding 与 Tool 成功 |

## 4. API 契约与验证方式

### Catalog

```text
GET|POST /api/ai/catalog/providers
PUT|DELETE /api/ai/catalog/providers/{name}
GET|POST /api/ai/catalog/models
PUT|DELETE /api/ai/catalog/models?providerName={providerName}&modelName={modelName}
GET|POST /api/ai/catalog/agents
PUT|DELETE /api/ai/catalog/agents/{name}
GET /api/ai/catalog/tools
```

验证点：

- Provider/Agent response 使用 `name`，没有 bigint resource ID；
- Model response 使用 `providerName`、`name`、结构化 `config`；
- Model ref 为 `providerName/modelName`，只切第一个 `/`；
- Catalog PUT/DELETE 的 `expectedVersion` 使用十进制字符串；
- 空白名称、Provider/Agent 名称含 `/`、缺字段、非法 variant、非法 config 和未知字段返回 `400`；
- 未知名称返回 `404`，版本冲突返回 `409`。

### Chat 与 Thread

```text
GET|POST /api/ai/chat
GET|PUT|DELETE /api/ai/chat/{chatId}
GET /api/ai/chat/{chatId}/threads?sort={recent|created}&cursor={opaque}&limit={1..100}
POST /api/ai/chat/{chatId}/threads
PUT /api/ai/chat/{chatId}/threads/{threadId}

GET /api/ai/runtime/threads?sort={recent|created}&cursor={opaque}&limit={1..100}
GET /api/ai/runtime/threads/{threadId}
GET /api/ai/runtime/threads/{threadId}/snapshot
PUT /api/ai/runtime/threads/{threadId}/head
POST /api/ai/runtime/threads/{threadId}/messages
POST /api/ai/runtime/threads/{threadId}/messages/custom
POST /api/ai/runtime/threads/{threadId}/stop
GET /api/ai/runtime/threads/{threadId}/events/stream?afterRevision={revision}
```

Chat-scoped POST 的验证顺序是：

```text
create Session + ROOT + bound Thread
  -> associate Chat
  -> return HarnessThreadDTO
```

每个 message/custom message body 都包含：

```json
{
  "content": "...",
  "agentName": "...",
  "environmentName": null,
  "yoloEnabled": false,
  "clientMessageId": "...",
  "expectedExecutionEpoch": 0
}
```

服务端把三个设置保存为 compact TurnSettings。Resolver 每次 planning 读取最新 Agent、Provider、Model、Variant 与 READY Environment；缺失 Agent、Provider、Model、Variant、Environment、Tool 或 Skill 转为 `ASSISTANT_ERROR`。

L1 同时覆盖无 Environment 的两种语义：model-only/本地 Tool Agent 可使用
`environmentName=null`；Agent 配置 Environment Tool/Skill 时写入
`ENVIRONMENT_REQUIRED`，不创建 ModelInvocation，也不在错误文案中输出伪名称 `null`。

`PUT /head` body 必须含非空 `headEntryId` 与 `expectedExecutionEpoch`。静止检查失败或 epoch 过期为 `409`；未知 Entry/Thread/Session 为 `404`。snapshot 的 `revision` 是十进制 durable cursor，SSE revision 帧携带同一 cursor，Redis realtime 没有 SSE id。

### 查询、Usage 与 Environment

```text
GET /api/ai/runtime/sessions
GET /api/ai/runtime/sessions/{sessionId}
GET /api/ai/runtime/sessions/{sessionId}/entries
GET /api/ai/runtime/interactions/{id}
GET /api/ai/runtime/interactions/open
POST /api/ai/runtime/interactions/{id}/response
GET /api/ai/runtime/tool-invocations/{id}
GET /api/ai/runtime/artifacts/{id}
GET /api/ai/runtime/usage/sessions/{sessionId}
GET /api/ai/runtime/usage/models?providerName={providerName}&modelName={modelName}
GET|PUT /api/ai/runtime/settings/retry-policy
GET|PUT /api/ai/runtime/settings/realtime-stream-policy
GET /api/ai/environment
WebSocket /api/ai/environment/daemon/v1
```

Model Usage 以写入时冻结的 `provider_name`、`model_name` 查询，不对 Catalog 建 FK。查询接口使用独立的 `providerName`、`modelName` 参数，因此 Model name 中的 `/` 不依赖 encoded-slash 路由行为。

## 5. 实现结构与报告

| 路径 | 职责 |
| --- | --- |
| `scripts/e2e.sh` | 环境启停、凭证同步、矩阵与 UI smoke 编排 |
| `scripts/e2e/run-matrix.mjs` | Node case 注册、筛选、执行和报告 |
| `scripts/e2e/lib/registry.mjs` | case 注册表 |
| `scripts/e2e/lib/harness.mjs` | Chat-scoped Thread、head、snapshot、Input 等共享步骤 |
| `scripts/e2e/cases/*.mjs` | API case |
| `scripts/e2e/ui-smoke.mjs` | Playwright UI smoke |

报告目录：

```text
reports/e2e/<runId>/
  report.md
  summary.json
  cases/<caseId>.json
  artifacts/<caseId>/
  logs/
reports/e2e/latest/report.md
```

判读顺序为 `latest/report.md` → `summary.json` → case JSON/artifacts → logs。报告目录已加入 gitignore。

## 6. 维护

1. API 字段、状态或验证变化时，同步 case 与本文件。
2. 新增或删除 case 后运行 `node scripts/e2e/run-matrix.mjs --list`，以输出的 ID 和总数更新本文件。
3. Chat/Thread 编排步骤集中在 `scripts/e2e/lib/harness.mjs`，首发只调用 Chat-scoped Thread POST，再发送 message。
4. 真模型、Tool、分支和 UI 只通过显式开关执行；默认 L1 保持免费。
