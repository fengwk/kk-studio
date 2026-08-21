# E2E 回归

本文描述当前 Node API 矩阵的 case、开关、API 验证方式和报告位置。事实源命令是：

```bash
node scripts/e2e/run-matrix.mjs --list
```

当前注册 **75** 个 API case；标准入口默认执行免费的 **L1 63** 个 case（其中
`canvas.api_version_contract` 免费验证 Canvas UUID/version/patch/changes 契约）。Canvas
Resource 直读预签名与全局 Blob 存储 contract 需要 backend 已启用 S3，并通过
`--with-canvas-storage` 显式执行；免费 fake Function 完整链路还需通过
`--with-canvas-function` 显式开启 fake
model 并重启 backend；L2/L3/L4 需要显式打开真实 Provider、分支或 Environment Tool 开关。分支（L3）现在通过 ENTRY target 在同 Session 历史 Entry 下开新 Thread 表达。UI E2E
由 `scripts/e2e.sh --ui` 另行附加，默认注册 37 个免费 UI case；`--with-tools --ui` 再追加 1 个 Environment/Workspace 创建 case。这些 UI case 不计入 75 个 Node API case。

## 1. 入口与开关

```bash
./scripts/e2e.sh                         # 默认 L1，免费
./scripts/e2e.sh --rebuild               # Java 21 clean package 后启动服务
./scripts/e2e.sh --real                  # L2 真实 MiniMax
./scripts/e2e.sh --real --with-branch    # L3 同 Session 分支
./scripts/e2e.sh --with-tools            # L4 Environment projection
./scripts/e2e.sh --with-canvas-storage   # Canvas Resource 直读预签名 / 全局 Blob 存储边界（需 S3 配置）
./scripts/e2e.sh --with-canvas-function  # 免费 fake Function（隐含 storage + rebuild）
./scripts/e2e.sh --real --with-tools     # L4 真实 Tool turn
./scripts/e2e.sh --ui                    # Playwright UI E2E
./scripts/e2e.sh --list
./scripts/e2e.sh --docs

npm --prefix frontend run e2e
npm --prefix frontend run e2e:ui
npm --prefix frontend run e2e:matrix
npm --prefix frontend run e2e:list
npm --prefix frontend run e2e:docs

node scripts/e2e/ui-smoke.mjs \
  --base-url http://127.0.0.1:5173 \
  --backend-url http://127.0.0.1:18081 \
  --report-dir reports/e2e/ui-standalone \
  --only ui.chat.composer.history_order_boundaries
```

`--with-branch` 自动启用 `--real`。Node runner 还支持：

```text
--base-url URL
--frontend-url URL
--daemon-env NAME
--real
--with-tools
--with-branch
--with-canvas-storage
--with-canvas-function
--only CASE_ID
--level L1|L2|L3|L4
--list
--docs
--report-root DIR
--no-report
```

默认 backend URL 是 `http://127.0.0.1:18081`，默认 frontend URL 由 `scripts/e2e.sh` 传入 `http://127.0.0.1:5173`。真实 Provider 使用 `TEST_MINIMAX_BASE_URL` 与 `TEST_MINIMAX_API_KEY`；所有付费 API/UI case 都在执行前硬校验 Provider/Model 为 `minimax/MiniMax-M2.7`，不得静默切换到其它付费 Provider。

本地完整免费 Canvas 回归使用 `deploy/test` 提供 PostgreSQL、Redis 与 MinIO，只连接本机
Redis/S3-compatible endpoint；`s3Enabled` 由 canvas-test Flyway seed（`db/seed/canvas-test`
`V3__canvas_test_system_settings.sql`）写入 SystemSettings：

```bash
docker compose -f deploy/test/compose.yaml down --volumes --remove-orphans
docker compose -f deploy/test/compose.yaml up -d --wait

env \
  JAVA_HOME_21="$JAVA_HOME_21" \
  KK_STUDIO_DB_URL=jdbc:postgresql://127.0.0.1:15432/canvas_test \
  KK_STUDIO_DB_USER=canvas_test \
  KK_STUDIO_DB_PASSWORD=canvas_test_only \
  KK_STUDIO_REDIS_URL=redis://127.0.0.1:16379 \
  KK_STUDIO_STORAGE_S3_ENDPOINT=http://127.0.0.1:19000 \
  KK_STUDIO_STORAGE_S3_PUBLIC_ENDPOINT=http://127.0.0.1:19000 \
  KK_STUDIO_STORAGE_S3_REGION=us-east-1 \
  KK_STUDIO_STORAGE_S3_BUCKET=canvas-test \
  KK_STUDIO_STORAGE_S3_ACCESS_KEY=canvas-test \
  KK_STUDIO_STORAGE_S3_SECRET_KEY=canvas-test-only \
  ./scripts/e2e.sh --with-canvas-function --ui

docker compose -f deploy/test/compose.yaml down --volumes --remove-orphans
```

该命令选择 67 个免费 API case 和 37 个免费 UI case，不启用真实 Provider、Tool 或
Branch。`--with-canvas-function` 自动启用 fake Function、Canvas storage 与 backend
rebuild；不得为这条回归追加 `--real`。

## 2. 分层

| 层级 | 开关 | 成本 | 覆盖 |
| --- | --- | --- | --- |
| L1 | 默认 | 免费 | Catalog/Chat CRUD（含 Agent subagents 引用校验与删除保护）、SystemSettings 完整聚合/CAS、内部工具目录、owner-aware command-batches（NEW_SESSION/ENTRY/THREAD）、Session/Thread 查询、命令 batch 严格 wire、CAS/幂等、IDLE stop no-op、模型 transient attempt retry 可见性、i18n 与 proxy |
| L2 | `--real` | MiniMax | 文本轮次 durable 边界、真实 task 委派 durable 子 Thread、运行中原子 batch 合并收割、stop partial + REPLAYED + continue |
| L3 | `--real --with-branch` | MiniMax | ENTRY target 在同 Session 历史 assistant Entry 下开新 Thread 并继续分支 turn |
| L4 | `--with-tools` 或 `--real --with-tools` | Daemon / MiniMax | READY Environment、ToolCatalog、非 YOLO approval 流、Resource 外部化与同源下载验证 |

默认 L1 不启动真实 Provider，也不执行 Tool 外部副作用。

Canvas OpenCLI adapters 的付费开关不属于 Node 默认矩阵。`deploy/test/run.sh --with-app`
将 app 只连接到容器内 fake Hub，免费覆盖 GPT Image 与 Seedance 的 execute/poll/download/
materialize 闭环。真实 Seedance 只允许单独执行：

```bash
RUN_REAL_SEEDANCE_PREPARE_SMOKE=1 \
SEEDANCE_WORKSPACE_ID=... \
  ./scripts/seedance-prepare-smoke.sh --confirm-prepare-only
```

该脚本硬编码 `seedance2.0fast + duration=4 + submit=0`，直接调用 Hub，不通过
FunctionRun，不下载或导入视频。它只准备页面，不触发生成；GPT Image 与 Seedance
`submit=1` 均可能产生费用，不进入自动回归。

## 3. 当前注册 case

下面的 ID 与 `node scripts/e2e/run-matrix.mjs --list` 一致。

### L1（注册 67，默认 63）

```text
seed.structured_model_config
seed.agent_and_provider
catalog.internal_tools_hidden
thread.new_session_submission_atomic
thread.branch_settings_projection
thread.user_message_strict_wire
thread.product_http_rejects_custom_message
thread.command_idempotent_replay
thread.stale_command_cas_rejected
thread.entry_materialization_same_session
thread.session_entry_tree
thread.entry_cross_session_rejected
thread.stop_idle_noop
thread.branch_settings_diff_commands
thread.yolo_direct_update
thread_snapshot.unknown_thread_404
frontend.proxy_model_contract
crud.provider.invalid_name
crud.provider.invalid_missing_type
crud.model.invalid_update_config
crud.agent.invalid_name
crud.agent.invalid_variant
crud.provider.lifecycle
crud.model.lifecycle
crud.agent.lifecycle
crud.agent.subagent_reference_lifecycle
crud.chat.invalid_agent_name
crud.chat.thread_branch_settings_independent
crud.model.delete_unknown_rejected
crud.chat.lifecycle
crud.chat.session_ownership_list
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
config.agent.valid.goal_plugin_tools
config.agent.invalid.missing_tools
config.agent.invalid.missing_skills
config.agent.invalid.missing_subagents
config.agent.invalid.unknown_tool
config.agent.invalid.duplicate_skill
config.agent.invalid.duplicate_subagent
config.agent.invalid.unknown_subagent
config.agent.invalid.unknown_field_rejected
matrix.agent.teardown_model
settings.system_contract_cas
model.attempt_failure_visibility
events.heartbeat_keepalive
canvas.storage_upload_contract
canvas.function_fake_runtime
chat.attachment_upload_contract
chat.attachment_inline_image_latest_agent_tools
canvas.api_version_contract
```

L1 的关键语义断言：

- Provider/Agent name 与 Model `(providerName,name)` 创建、更新、硬删除、同名重建；记录存续期间名称不可修改，DELETE 带 `expectedVersion` 硬删除后列表不再出现，同名立即可重建且 `version` 从 `"0"` 重新开始并读取到新数据（删除前数据不残留）；
- Agent config 的 `tools`/`skills`/`subagents` 三个列表**必填**（缺失 400），元素去重、非空白短名；`tools` 只允许可选目录中的名称（未知 400），`subagents` 是 Agent 名称 allowlist——引用锁定（被引用的 Agent 不可删除，DELETE 409；移除引用后即可硬删除），未知引用 404；
- `GET /api/ai/catalog/tools` 只返回可选 Platform/Environment 目录：`load_skill`/`task` 两个内部 Platform Tool 绝不出现；Goal 插件 `create_goal/get_goal/update_goal` 必须作为可选 ToolCatalog 能力通过 Agent config 校验；
- Chat CRUD 仅持久化 `agentName`、`yoloEnabled` 与可选默认 `EnvironmentBinding{name, workspacePath}`（可为 null，两字段同存同空）；先建 Thread 再更新 Chat 后 reread 同一 Thread，branchSettings 逐字段不变；
- NEW_SESSION materialization body 携带完整 `rootSettings`（同构于原 `branchSettings`），202 返回 `HarnessAcceptedCommandsDTO{session,rootEntry,thread,acceptedCommands,replayed}`；`replayed=false` 表示全新接受；
- Thread/snapshot 的 `threadId`、`sessionId`、`headEntryId` 均为 canonical UUID string；`nextCommandSequence`、`version` 为 strict decimal string；`nextCommandSequence` 从 **1** 开始；
- snapshot 结构固定为 `thread`、`entries`（当前 root→head 路径）、`queuedCommands`、`modelInvocation|null`（只暴露 active invocation）、`toolInvocations`（只暴露 classifier-applicable active siblings）、`modelAttemptFailures`（只暴露当前 active Model 尚未物化的失败 attempt；item 为 `modelInvocationId/turnStartEntryId/basisHeadEntryId/attempt/sequence/text/thinking/errorCode/errorMessage/failedAt/retryAt`，其中 `sequence` 是 HTTP decimal string）；
- THREAD target 携带 `expectedHeadEntryId` + `expectedNextCommandSequence` CAS cursor；stale cursor 409 的统一信封携带 `errors.reason=STALE_COMMAND_CURSOR`，且 Thread 状态（sequence/version/head）逐字段不变；
- `USER_MESSAGE` 只接受一个非空有序 `contents(TEXT/ATTACHMENT/RESOURCE)` 列表：`TEXT(text)`；`ATTACHMENT(uploadId)`（READY upload 的 canonical UUID string，入队事务内原子消费并删除 upload 行）；`RESOURCE(blobId,name,preview?)`（只复用目标 Session 已有 blob ref，不重复 retain，新/跨 Session 拒绝并回滚）。`text`/`content` 文本 shorthand 已移除；`IMAGE/AUDIO/VIDEO`、未知/多余字段与非 canonical id 返回 400；`CUSTOM_MESSAGE` 在产品 HTTP 面确定性 400；
- 命令响应携带 `requestHash`（raw 命令 canonical SHA-256，64 位小写 hex）与 `sequence`；同 `clientCommandId` + 同 hash 整批重放幂等返回既有命令且不二次消费 upload；部分重放 409；replay/400 不依赖异步消费时序；
- `SET_ENVIRONMENT/SET_AGENT/SET_MODEL/SET_ACTIVE_TOOLS` 四类命令按前端固定顺序与 `USER_MESSAGE` 一个原子 batch 入队；case 使用 canonical 但不存在的 Agent，使 Resolver 在调用 Provider 前确定性 `PLANNING_FAILED`。消费后 `branchSettings` 精确投影、queue 清空，durable `ASSISTANT_ERROR` 与最终 `TURN_END(FAILED, continueModel=false)` 收敛到 IDLE，USER MESSAGE 自身不算消费证据；yolo 走直接控制面（`PUT /yolo`）绝不进入 mailbox；
- `PUT /yolo` body `{expectedVersion,yoloEnabled}`：同值请求在任何 version CAS 之前 no-op 成功（过期 expectedVersion 不冲突、version 零触碰）；值变化时 version 精确 +1 并返回权威 Thread，过期 version 409 `STALE_VERSION`；不创建 Command/Entry/Work；
- ENTRY target 在既有 Session 既有 Entry 下开新 Thread：sessionId 不变、accepted command sequence=1、quiescent 后分支 Thread 的 root→head 路径必须包含 startEntry 与分支 USER、rootEntry 不变；`startEntryId` 不存在 404、跨 Session 400；原 Thread 的 head/version/nextCommandSequence 逐字段不变；
- `POST /stop` body `{stopRequestId,expectedVersion}`：IDLE 无 queued 时 status=IDLE、无 stopped TURN_END、version 不变；IDLE stop 不写持久 marker，同 `stopRequestId` 再次调用仍是 IDLE no-op（不是 REPLAYED）；stale version 409；真实 STOPPED/REPLAYED 语义由 L2 覆盖；
- 未知 Thread snapshot 404；
- `model.attempt_failure_visibility` 免费 L1：先通过 `/api/events/v1` 建立 Thread 订阅并收到 `subscribed` ack；case 内 `node:http` OpenAI-compatible SSE mock 的首次 Provider attempt 随后流出确定性 text partial，再通过断连触发 LangChain4j `TRANSIENT`。事件通道必须观测到该 partial；活跃期轮询同一快照精确断言 `modelAttemptFailures`（`attempt=1`、HTTP decimal-string `sequence`、text/thinking、error、合法 `failedAt/retryAt`），立即 retry 的 Provider `messages` 必须与失败前完全一致且不含失败 partial/error；quiescent 后断言 `MODEL_ATTEMPT_FAILURE` 位于成功 assistant 之前且 payload 保留 partial/error/retryAt、未物化列表清空；第二 turn 的 mock request `messages` 同样不含失败 attempt 的 partial/thinking/error，两个 turn 均 `COMPLETED`，成功 assistant 不拼接失败 partial；
- `events.heartbeat_keepalive` 使用 Node 原生 WebSocket 直连 `/api/events/v1`，不建立资源订阅，在 25 秒上限内等待严格 `{version:1,type:"heartbeat"}`；收到后连接必须仍为 OPEN。服务端所有连接共享单个 scheduler，case 不创建客户端 ping 协议或第三方 WS 依赖；
- `Accept-Language` 验证错误 message/title 本地化而稳定字段不变。
- `settings.system_contract_cas` 免费 L1：GET 必须返回六个完整 section，`version` 与 Long 字段为 canonical decimal string，已删除的无消费者字段不得残留；完整聚合 PUT 以 `expectedVersion` CAS 推进版本并回读新值；权限 tool name 首尾空白返回 400，stale version 返回 409，两类失败都不得推进版本；`finally` 以最新版本重试恢复原值并再次回读断言，不能污染后续 case。
- `canvas.api_version_contract` 免费 L1：create/list/get/commands 使用 canonical UUID id 与
  十进制字符串 graph `version`（数据库 `canvas_document.version` 仍是 bigint，wire 是 canonical
  非负十进制字符串，`graphVersion` 字段不存在）；`expectedVersion` CAS stale 409；
  同 `commandId` 精确回放返回当前版本的确定性空 patch（version 不前进），同 id 不同内容
  409；`changes?afterVersion=0` 在缓存完整时返回连续 `0→1` patch，缓存缺失/gap 时回退
  权威 snapshot，尾部版本返回空 delta；`RENAME_GROUP` 更新标题只发 group UPSERT patch
  （成员关系与几何不变），空白 title 与未知 group 400；`UNGROUP` 接受当前成员的非空
  子集，子集解绑只 upsert 指定 Node 为 `groupId=null`，Group 与其余成员保留；深删除后
  画布 404。
- `canvas.storage_upload_contract` 仅在 `--with-canvas-storage` 下执行：backend 必须启用
  S3 配置；通用 S3 预签名端点不能签名 `blobs/` 命名空间 key；全局
  upload reserve（`sha256` 必填）→ 浏览器直传 PUT → complete 绑定 READY blob，
  `CREATE_RESOURCE_NODE` 在同一事务消费上传后 Resource DTO 携带 `blobId`/`kind`/
  `mediaType`/`sizeBytes`（sizeBytes 是十进制字符串）；`download-url`/`preview-url` 只暴露
  method/url/headers/expiresAt（不暴露 bucket/key），download 字节与上传一致；
  TEXT 资源 URL 400、未知 resource/canvas 404；深删除画布后不可达。
- `canvas.function_fake_runtime` 仅在 `--with-canvas-function` 下执行：显式注册
  `fake-image`，验证 Function node 创建（客户端 UUID nodeId）、start/checkpoint/terminal、
  成功 Resource 原子替换、`320×260 @ (100,100)` transform 精确回读、`document.version`（十进制字符串）
  按命令与 Run start/checkpoint/terminal 状态前进、公开 run DTO 无 `stateJson`，以及 preview signed GET 的 WEBP 字节。
- `chat.attachment_upload_contract` 仅在 `--with-canvas-storage` 下执行：通用存储
  reserve -> 真实 presigned PUT -> complete -> USER_MESSAGE `ATTACHMENT(uploadId)`
  原子消费；入队响应 `requestHash` 为 64 位小写 hex、durable payload 为
  `resource(blobId,name,preview)`；整批重放幂等不二次消费；同 Session `RESOURCE` 可重提且
  新/跨 Session 引用整体回滚；已消费/未 READY upload 与 `IMAGE/AUDIO/VIDEO` 内容类型确定性 400。
- `chat.attachment_inline_image_latest_agent_tools` 仅在 `--with-canvas-storage` 下执行：本地
  OpenAI Responses mock 捕获真实 Provider 请求；Thread 创建后更新同名 Agent 的工具集合，下一
  turn 必须忽略历史 `branchSettings.activeTools` 并发送最新 `read/grep`；本地 MinIO 图片必须作为
  精确 `data:image/png;base64,...` source 发送，请求不得包含 `127.0.0.1`/`localhost` 预签名 URL。

### L2（4）/ L3（1）/ L4（3）

```text
real.text_turn
real.task_delegation
real.queued_command_batch
real.stop_partial_continue
branch.same_session_entry_thread
daemon.ready
daemon.directories
tool.read_turn
```

| Case | 开关 | 重点验证 |
| --- | --- | --- |
| `real.text_turn` | `--real` | 真实 Provider 文本轮次；durable `TURN_START -> USER -> assistant MESSAGE -> TURN_END(COMPLETED)` 边界（TurnPlanBuilder 先追加 TURN_START 再追加 USER/CUSTOM）；IDLE 后 `modelInvocation=null`（快照无 `modelInvocations[]` 历史列表） |
| `real.task_delegation` | `--real` | 父 Agent config 携带 `subagents=[child]` 且 branch `activeTools` 含 `task`：真实 Model 调用内部 `task`，创建 durable 子 Thread（ROOT 携带 `subagentContext{parentThreadId,rootThreadId,taskInvocationId,depth=2}`）；最终 TOOL MESSAGE 冻结 `rendererKey=task` 与 `<task id state>` envelope，`id` 即子 ThreadId |
| `real.queued_command_batch` | `--real` | 运行中用最新 cursor 连续两个 THREAD batch 各入队一条 USER_MESSAGE（sequence 连续）；下一 turn 收割为两个 USER entry + 一个 assistant |
| `real.stop_partial_continue` | `--real` | 流式 stop => `STOPPED`/version+1/`stoppedTurnEndEntryId`；同 `stopRequestId` + 原 version exact replay => `REPLAYED` 且不重复 bump；后续轮次在 ASSISTANT_ABORTED barrier 后 |
| `branch.same_session_entry_thread` | `--real --with-branch` | ENTRY target 在 real.text_turn 的同 Session 历史 assistant Entry 下开新 Thread（不复制 Entry）；sessionId 不变、accepted command sequence=1、分支 turn 在 startEntry 之后继续产生独立 assistant；原 Thread projection 不变 |
| `daemon.ready` | `--with-tools` | READY Environment、canonical 路由名称与固定十一个 Tool（9 coding + 2 MCP 桥接）；skills + mcpServers 摘要形状；`rootPath` 存在；公共查询不泄露 READY operatingSystem/timeZone/note metadata |
| `daemon.directories` | `--with-tools` | `GET /api/ai/environments/{name}/directories` 缺省 `path="."` 浏览 root（canonical 相对 wire path；`displayPath` 等于请求 `path` 的最后一段，root 为 `'.'`，只作展示、绝不暴露 daemon 本地绝对路径；root `parentPath="."`；`truncated` 布尔；`gitBranch` 可空；entries 只含直属子目录 `{name,path}`：`name` 等于 `path` 最后一段、`path` 是请求目录的直接子路径）；显式 `path="."` 与缺省一致；`..` 段 400 `INVALID_PATH`、不存在目录 404 `NOT_FOUND`、非法环境名 400 `INVALID_ENVIRONMENT_NAME` |
| `tool.read_turn` | `--real --with-tools` | e2e seed 覆盖 `system_setting` 使 `read/write/edit/bash: ask`（`db/seed/e2e/V2__e2e_seed.sql`），本 case 验证 read；yolo=false 时在 `TOOL_WAITING_APPROVAL` 下冻结 `EnvironmentBinding{name, workspacePath}`；输入 `ALLOW`、durable decision 为 `ALLOWED`（decisionId 幂等 replay 保留 decidedAt）；`>8KB` fixture 的完整格式化 `read` 文本结果先外部化为瞬时 ResourceRef，Entry 写入前摄入全局 Blob；durable `tool_result.contents` 只携带 `resource(blobId,name,preview)`，不复制 uri/mediaType/size/sha256；经 `/api/storage/blobs/{blobId}/presigned-original` 下载并验证权威 mediaType/十进制字符串 sizeBytes 与格式化工具结果字节一致 |

### L5 UI（默认 37，`--with-tools` / `--real` 各追加 1）

`scripts/e2e.sh --ui` 的免费 UI E2E 矩阵包含：

```text
ui.i18n.language_switch
ui.chats.page_loads
ui.models.page_loads
ui.models.open_create_modal
ui.agents.page_loads
ui.providers.page_loads
ui.environments.page_loads
ui.canvas.page_loads
ui.nav.roundtrip
ui.chat.create_flow
ui.model.create_edit_delete_flow
ui.agent.create_edit_delete_flow
ui.model.validation_empty_name
ui.provider.create_edit_delete_flow
ui.chat.blank_workspace_shell
ui.chat.composer.history_order_boundaries
ui.chat.composer.duplicate_entries
ui.chat.composer.multiline_scroll
ui.chat.composer.multiline_caret_boundaries
ui.chat.composer.edit_recalled
ui.chat.composer.draft_persistence_boundaries
ui.chat.composer.palette_precedence
ui.chat.selection_panel.keyboard_mode
ui.chat.composer.escape_refocus
ui.chat.composer.submit_clears_draft
ui.chat.composer.attachment_previews
ui.chat.debug.conversation_switch
ui.chat.debug.keyboard_nav
ui.chat.debug.scroll_restore
ui.chat.shortcuts.escape_restores_focus
ui.chat.composer.settings_controls_batch
ui.chat.composer.multi_pane_settings_isolation
ui.chat.footer.readonly_facts
ui.chat.tool_card.streaming_layout_scroll
ui.chat.task_status.bound_widget
ui.settings.notifications_single_entry
ui.settings.system_contract_cas
```

其中 `ui.canvas.page_loads` 验证 `/canvas` library 保留全局顶栏，点击或创建后进入
canonical UUID 形式 `/canvas/:canvasId`：编辑器无全局顶栏且挂 `canvas-immersive` class；Chat 面板默认收起且底部不渲染
composer，点击 header「Chat / 对话」toggle 展开/收起；默认面板宽于 360px，左边缘拖拽调宽后 zoom
保持不变；add launcher（左侧中部功能轨）仍可打开菜单，V/H 按钮不展示，缩放控制位于左下；首屏 fit
产生的动态缩放值位于合法区间，Chat 面板展开/收起不改变该缩放值，点击缩放值可重置为 `100%`
且刷新后恢复该持久化视口；展开的 Canvas Draft target 使用共享双层 Composer，常驻 Permission 与
Model/Variant 控件且不制造 Footer fallback；浏览器 back 返回 library、forward 再进入编辑器；
Function 生成的付费路径不进入默认 UI E2E，前端组件测试使用 fake Function runtime
隔离。
`--with-tools` 额外执行 `ui.chat.create_environment_workspace`：Create Chat 必须先选 READY Environment，再进入目录模式并明确确认 Workspace，提交后 HTTP Chat DTO 精确保存完整 binding；不得在 Environment 点击时静默写入 `workspacePath:'.'`。`--real` 额外执行 `ui.chat.blank_first_send_real`。Headless 模式只对 Chromium 子进程移除
宿主 `DISPLAY` 与 `WAYLAND_DISPLAY`，避免混合桌面环境导致 compositor 停帧；`--headed`
保留宿主显示环境。

Composer 矩阵的维度与边界如下：

| Case | 数据/状态 | 关键边界 |
| --- | --- | --- |
| `ui.chat.composer.history_order_boundaries` | durable + 真实 active invocation 下的 queued + localStorage 草稿 | `ArrowUp/Down` 顺序、最老/最新端不循环、浏览历史不覆盖草稿 |
| `ui.chat.composer.duplicate_entries` | durable 与 queued 文本完全相同 | DOM 文本不变时游标仍独立前进，返回草稿需要精确步数 |
| `ui.chat.composer.multiline_scroll` | 24 行 durable 历史 | editor 必须真实 overflow，召回后 collapsed Selection 位于末尾且 `scrollTop + clientHeight >= scrollHeight` |
| `ui.chat.composer.multiline_caret_boundaries` | 三行当前草稿 + durable 历史 | 行内上下键保留浏览器原生移动；仅首行上边界召回历史 |
| `ui.chat.composer.edit_recalled` | durable 历史 + 当前草稿 | 编辑召回内容后提升为新草稿，二次导航与刷新恢复一致 |
| `ui.chat.composer.draft_persistence_boundaries` | 空 Pane | 精确空白/换行持久化、刷新恢复、纯空白清理、无历史时上键 no-op |
| `ui.chat.composer.palette_precedence` | durable 历史 + plus/slash palette | palette 打开时方向键只移动命令，不召回消息；菜单模式保留草稿，slash Escape 清理；命令表不重复渲染 Composer 查询行 |
| `ui.chat.selection_panel.keyboard_mode` | Chat-scoped Thread picker + head 回退后形成的真实分叉 Session + 两条真实 Thread | 无 modal backdrop；Composer 与 interaction panel 互斥；搜索自动聚焦；Tab 切换排序；方向键移动；Tree 面板横向铺满，并在完整 Session Tree 上验证 pi 风格 `›` 选择光标、`•` 当前路径、`├─ / │ / └─` 分叉连续性；丢弃确认明确指出未发送内容且使用 icon-only 关闭按钮，取消后恢复搜索；Esc 返回 Composer 并保留草稿 |
| `ui.chat.composer.escape_refocus` | transcript 文字选区 + 当前 Pane 草稿 | 全局 Escape 恢复当前 Pane Composer 的焦点与末尾 caret，草稿/localStorage 不变 |
| `ui.chat.composer.submit_clears_draft` | durable Thread + 新提交 | 提交后 composer/localStorage 立即清空，用户消息进入 timeline |
| `ui.chat.composer.attachment_previews` | 浏览器路由内的免费 READY upload stub + 本地 PNG/MP4/PDF | editor pill 完整显示 `[文件名]` 且不做省略；上方注册表固定 200px，长文件名单行 `…`；图片展示可点击缩略图并打开 Lightbox，视频展示首帧 preview，PDF 展示 text 类型图标；只验证草稿 UI，不发送模型请求 |
| `ui.chat.debug.conversation_switch` | durable Thread（真实 USER 条目） | `/debug` 顶部展示 10 行最新系统提示词预览，下方按当前 branch Entry 顺序展示调试列表；标签为真实 `entryType`，摘要为压缩 payload JSON；点击打开只读 pretty JSON 详情且含 `"role"` 与正文；Composer 保持挂载可编辑；再次 `/debug` 或 `+` 菜单 toggle 卸载调试视图且草稿/localStorage 不变 |
| `ui.chat.debug.keyboard_nav` | durable Thread | listbox 初始无选中；点击选中并打开详情；hover 不改选中；`↑/↓` 只在已选中时切换相邻行；`Esc` 取消选中并关闭详情 |
| `ui.chat.shortcuts.escape_restores_focus` | 空 Pane + 草稿 | `+` 菜单打开只读快捷键面板（region `键盘快捷键`）；`Esc` 关闭并恢复 Composer 焦点，草稿/localStorage 不变 |
| `ui.chat.debug.scroll_restore` | 30 条 durable Thread（两类主视图均真实 overflow；事件行紧凑布局后需更多条目） | headless 下以 DOM 属性 + 原生 `scroll` 事件向上滚动 600px；`/debug` toggle 往返后 Debug 列表 `scrollTop` 原样恢复（容差 ±2），Conversation 恢复后保持远离底部（不贴底；worktree 前端在 palette 切换期间存在一次布局漂移，精确 pixel 回放不保证）；重绑清零由单元测试覆盖 |
| `ui.chat.composer.settings_controls_batch` | 本地 hold-provider + 两 Variant Model + 活跃 Thread | 浏览器几何确认默认单行输入与控制栏组成紧凑两行布局；Permission 菜单只有 Default/YOLO；Model→Variant 两级 anchored listbox；选择 review+YOLO 后与 USER_MESSAGE 按 `SET_MODEL→USER_MESSAGE` 同批入队，YOLO 经直接控制面 `PUT /yolo` 生效并反映在 Thread 快照 |
| `ui.chat.composer.multi_pane_settings_isolation` | split-2 绑定两条真实 Thread | pane-1 YOLO 不污染 pane-2 Default；一次只存在一个 settings listbox；打开 pane-2 自动关闭 pane-1 菜单，Escape 只恢复 pane-2 Composer 焦点 |
| `ui.chat.footer.readonly_facts` | 本地 completion-provider 冻结 usage，再切换到 unavailable 长 Workspace path | Footer 中间省略安全 wire path，完整 title 不含 daemon 绝对路径；展示 usage、used/contextWindow、cache hit；缺失 usage 时按 0 展示；缺失 Git 整段省略；无 button，且不含 Agent/Model/Permission/Notification |
| `ui.chat.tool_card.streaming_layout_scroll` | 本地 streaming-provider 重复发送完整 write/edit/bash name/id 并冻结参数流；e2e profile 非 YOLO approval | 流式身份不重复拼接；edit 参数预览为 5 行尾随窗口；长 header 完整折行且绝对定位 toggle 不占宽；稳定 edit 完整展示 diff；write/edit/bash 均真实进入 approval；拒绝按钮使用 danger 红色边框；拒绝后 Tool output 不拥有纵向滚动，卡内滚轮滚动外层 transcript |
| `ui.chat.task_status.bound_widget` | 先绑定共享 Bound ChatPanel，再由本地 parent Provider 产生真实 `task` tool call，child Provider 保持运行 | 通过 `/api/events/v1` 后续 `task.status` heartbeat 渲染 TaskStatusWidget，不把 lossy realtime 误当作可重放快照；无需真实模型费用 |
| `ui.settings.notifications_single_entry` | Settings + 免费 durable Thread | `/settings` 恰好一个浏览器通知 switch；Thread panel/Footer 无 switch、button 或 `notify:*` 旧入口 |
| `ui.settings.system_contract_cas` | 真实本地 backend `/api/settings` | server tab 以 GET 权威聚合 hydration（Long 十进制字符串）；编辑后按 `expectedVersion` 完整聚合 CAS PUT，回读验证版本推进、六 section 完整与 wire 形态；外部写入推进版本后，陈旧保存必须弹出冲突说明，确认前保留 draft，点击重新加载后刷新页面并读取最新值；`finally` 中用最新版本恢复原值并回读断言，避免污染系统配置 |

durable fixture 使用不存在的 Agent，使 USER_MESSAGE 在 Provider 调用前确定性物化；queued
fixture 使用 case 内本地 hold-provider 保持真实 Model invocation 活跃，再通过真实命令 API
写入 queued USER_MESSAGE。两类 fixture 都免费、不依赖竞态、不调用真实 Provider，并在
finally 中停止 Thread、清理 Chat/Catalog 与浏览器 localStorage。每个 case 独立出报告 ID；
runner 支持重复 `--only CASE_ID` 筛选，失败自动保存 `failure.png`、
`failure-state.json`、pageerror 与 console error。

附件草稿的历史导航保护需要浏览器 `File`、上传注册表与 READY upload，不在默认免费 UI
fixture 中伪造：组件边界由 `ThreadComposer.pill.test.tsx` 与
`ThreadComposer.more.test.tsx` 覆盖，真实 reserve/PUT/complete/消费契约由显式
`--with-canvas-storage` 的 `chat.attachment_upload_contract` 覆盖。

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
- Model ref 为 `providerName/modelName`，只切第一个 `/`（model name 内可含 `/`）；
- Catalog PUT/DELETE 的 `expectedVersion` 使用十进制字符串；
- 空白名称、Provider/Agent 名称含 `/`、缺字段、非法 variant、非法 config 和未知字段返回 `400`；
- 未知名称返回 `404`，版本冲突返回 `409`；
- 三张名称资源表（agent_provider/agent_model/agent_definition）均为硬删除：删除后同名可重建，重建行的 `version` 从 `"0"` 重新开始；记录存续期间名称（Provider/Agent name、Model 的 `providerName/name`）不可修改。

### Chat 与 Thread

```text
GET|POST /api/ai/chat
GET|PUT|DELETE /api/ai/chat/{chatId}
GET /api/ai/chat/{chatId}/sessions          -> HarnessSessionSummaryDTO[]（归属时间新到旧）
GET /api/ai/canvases/{canvasId}/sessions    -> HarnessSessionSummaryDTO[]（归属时间新到旧）
GET /api/ai/runtime/sessions/{sessionId}/threads  -> HarnessThreadSummaryDTO[]
GET /api/ai/runtime/sessions/{sessionId}/entries  -> 完整不可变 Entry Tree（parentEntryId 连接父节点）

POST /api/ai/runtime/command-batches        -> 202 HarnessAcceptedCommandsDTO（唯一产品用户命令写入口）
GET  /api/ai/runtime/threads/{threadId}/snapshot
PUT  /api/ai/runtime/threads/{threadId}/yolo
POST /api/ai/runtime/threads/{threadId}/stop
POST /api/ai/runtime/threads/{threadId}/tool-invocations/{toolInvocationId}/approval

WebSocket /api/events/v1        -> 事件通道（thread/canvas 订阅，见下）

GET  /api/ai/runtime/resources/{sha256}?mediaType=&size=&name=  -> 瞬时/Invocation ResourceRef 下载

POST /api/storage/uploads                       -> 通用存储 reserve（PENDING + presigned PUT）
POST /api/storage/uploads/{uploadId}/complete   -> READY（blobId；供 ATTACHMENT 消费）
DELETE /api/storage/uploads/{uploadId}
GET  /api/storage/blobs/{blobId}/presigned-original|preview
```

唯一产品写入口 `POST /api/ai/runtime/command-batches`（202 accepted），body 为
`{owner:{type:CHAT|CANVAS,id}, target:{...}, commands:[...]}`。`target` 是 sealed 三态：

```json
{
  "owner": { "type": "CHAT", "id": "00000000-0000-0000-0000-000000000099" },
  "target": {
    "type": "NEW_SESSION",
    "sessionId": "00000000-0000-0000-0000-000000000100",
    "threadId": "00000000-0000-0000-0000-000000000101",
    "rootSettings": {
      "environment": null,
      "agentName": "default-assistant",
      "model": { "providerName": "minimax", "modelName": "MiniMax-M2.7", "variant": "default" },
      "activeTools": []
    },
    "yoloEnabled": false
  },
  "commands": [
    {
      "type": "USER_MESSAGE",
      "clientCommandId": "00000000-0000-0000-0000-000000000102",
      "contents": [{ "type": "TEXT", "text": "..." }]
    }
  ]
}
```

- `NEW_SESSION{sessionId,threadId,rootSettings,yoloEnabled}`：预分配 id，原子创建 Session + ROOT + Thread 并接受本批命令；`sessionId`/`threadId` 是 materialization replay 的查找键；
- `ENTRY{sessionId,startEntryId,threadId,yoloEnabled}`：在既有 Session 的既有 Entry 下开新 Thread（不复制 Entry，head 直接指向该 Entry）；
- `THREAD{threadId,expectedHeadEntryId,expectedNextCommandSequence}`：在既有 Thread 上继续（exact cursor CAS；`expectedNextCommandSequence` 为正十进制 string，创建后为 `"1"`）；
- 响应 `HarnessAcceptedCommandsDTO{session,rootEntry,thread,acceptedCommands,replayed}`：`replayed=true` 表示整批精确 replay（NEW_SESSION/ENTRY 按 materialization hash、THREAD 按 clientCommandId + requestHash）；
- owner 授权：NEW_SESSION 确认 owner 存在；ENTRY/THREAD 确认目标 Session 已由该 owner 持有（Chat/Canvas 归属互斥）；`CUSTOM_MESSAGE` 与未知 target/command 类型在产品 HTTP 面确定性 400。

`USER_MESSAGE` 必须且只能携带一个非空有序 `contents` 列表，元素允许 `TEXT(text)`、`ATTACHMENT(uploadId)` 与 `RESOURCE(blobId,name,preview?)`。`uploadId` 是通用存储 reserve/complete 得到的 READY upload，入队事务内原子消费：锁定 upload 行 -> 以权威文件名物化为 durable resource -> session blob ref -> 删除 upload；整批重放绝不二次消费。RESOURCE 用于 Stop 恢复后的 durable pill 重提，只允许目标 Session 已有的 blob ref，不重复 retain；新/跨 Session 引用 400 且 materialization 整体回滚。`text`/`content` shorthand、`IMAGE/AUDIO/VIDEO`、未知/多余字段、空 contents 与非 canonical id 一律 400。命令响应携带 `requestHash` 与 `sequence`；固定 SET 前缀、末尾单 USER_MESSAGE 与 YOLO 直接控制面规则保持不变。

ENTRY 分支与 stop 均通过既有写面表达：

```json
{ "target": { "type": "ENTRY", "sessionId": "...", "startEntryId": "00000000-0000-0000-0000-000000000005", "threadId": "...", "yoloEnabled": false } }
{ "stopRequestId": "00000000-0000-0000-0000-000000000201", "expectedVersion": "0" }
```

- ENTRY target 在既有 Session 的既有 Entry 下开新 Thread（head 直接指向目标 Entry，不复制 Entry），原 Thread 保持不变；`startEntryId` 不存在时返回 404，跨 Session 时返回 400；
- `POST /stop` 响应 `{status, thread, stoppedTurnEndEntryId, cancelledCommandCount, cancelledUserMessages[]}`；取消消息按 sequence 升序，元素为 `{sequence,clientCommandId,messageJson}`，用于前端恢复 TEXT/RESOURCE Composer parts；status 三态：
  - `STOPPED`：真实停止一个 Turn（version+1，`stoppedTurnEndEntryId` 非空，TURN_END closeRequestId = raw `stopRequestId`，按被关闭 TURN_START 的 `ownerThreadId` 界定 Thread 作用域）；
  - `REPLAYED`：同 `stopRequestId` 再次调用，在 Thread 锁内做 Session 级查找命中同 owner 的持久 STOPPED TURN_END（在 version CAS 之前，version 不再变化，返回同一 `stoppedTurnEndEntryId`）；另一 Thread 相同 raw id 被忽略而非冲突；
  - `IDLE`：无活动 Turn（无持久 marker，`stoppedTurnEndEntryId=null`；同 `stopRequestId` 再调用仍是 IDLE，不是 REPLAYED）；
  - stale version 409；
- 命令 batch 整批同 `clientCommandId` + 同 `requestHash` 重放返回既有命令（sequence/requestHash 稳定）；仅部分存在 409 `PARTIAL_COMMAND_REPLAY`。

Tool approval：

```json
{ "decision": "ALLOW", "decisionId": "00000000-0000-0000-0000-000000000301", "actor": "web", "reason": null }
```

- `decision` 输入仅 `ALLOW|DENY`，durable `approvalJson.decision` 编码为 `ALLOWED|DENIED`（ToolApprovalDecision 枚举）；`decisionId` 是客户端稳定幂等键，已决策后 exact replay 保留原 `decidedAt`；
- yolo=false 时工具调用进入 `TOOL_WAITING_APPROVAL`（快照 `toolInvocations` 含 `WAITING_APPROVAL` 项）；ALLOWED 恢复为 READY 并请求 Tool Work，DENIED 终止为 FAILED；
- 快照 `toolInvocations` 只暴露 classifier-applicable active siblings：IDLE 后为空，不可回查历史 invocation；工具终态结果从 durable TOOL MESSAGE entry 的嵌套 `tool_result.contents` 读取；
- INPUT turn 的 durable entry 顺序固定为 `TURN_START -> USER/CUSTOM Message -> assistant MESSAGE -> TURN_END`（TurnPlanBuilder 先追加 TURN_START 再追加消息）。

### Canvas

```text
GET    /api/canvases
POST   /api/canvases                        -> 200 CREATED CanvasDocumentDTO
GET    /api/canvases/{canvasId}             -> CanvasSnapshotDTO
POST   /api/canvases/{canvasId}/commands    -> CanvasPatchDTO
DELETE /api/canvases/{canvasId}             -> 深删除（含该 Canvas owner 的全部 owned Session 与 Thread）
GET    /api/canvases/{canvasId}/changes?afterVersion=N -> CanvasChangesDTO
GET    /api/canvas-function-models
POST   /api/canvases/{canvasId}/nodes/{nodeId}/runs
GET    /api/canvases/{canvasId}/nodes/{nodeId}/run
POST   /api/canvases/{canvasId}/nodes/{nodeId}/run/cancel
POST   /api/canvases/{canvasId}/resources/{resourceId}/download-url
POST   /api/canvases/{canvasId}/resources/{resourceId}/preview-url

POST /api/storage/uploads
POST /api/storage/uploads/{uploadId}/complete
DELETE /api/storage/uploads/{uploadId}
GET  /api/storage/blobs/{blobId}/presigned-original
GET  /api/storage/blobs/{blobId}/presigned-preview
```

- 所有实体 id（canvas/node/group/resource/blob/upload）都是 canonical UUID 字符串
  （shape-only，不限定 version/variant 位）；CanvasDocumentDTO **没有** `threadId` 字段
  （绑定关系由 owner-aware command-batches 的 Session 归属边表达）；graph 版本 wire 是
  canonical 非负十进制字符串（数据库 `canvas_document.version` 仍是 bigint/Java long 整数，
  不存在 `graphVersion` 字段）；
- `POST /commands` body 为 `{expectedVersion, commandId, commands[]}`：`expectedVersion`
  是精确 CAS 游标（十进制字符串，stale 409 `VERSION_CONFLICT`）；`commandId` 是整批幂等键——同 id 同
  内容精确回放返回 `{baseVersion:version, version, [], [], []}` 空 patch（版本为十进制字符串），
  同 id 不同内容
  409 `IDEMPOTENCY_CONFLICT`；创建类命令携带客户端生成的实体 UUID
  （`CREATE_TEXT_NODE(nodeId,name,markdown,transform)`、
  `CREATE_RESOURCE_NODE(nodeId,name,uploadIds,transform)`、
  `CREATE_FUNCTION_NODE(nodeId,name,modelKey,configJson,transform)`、
  `CREATE_GROUP(groupId,title,transform,memberNodeIds)`）；
- `CanvasResourceDTO` 的 `kind` 由 API 按 blob mediaType 派生，TEXT 资源无 blob；
  `mediaType/width/height` 来自 blob 权威事实列；`sizeBytes/durationMs` 是 Java long，
  wire 为十进制字符串或 null，前端 API adapter 归一化为内部 number|null（非负且
  Number.isSafeInteger，非法/超限 fail closed）；
- `GET /changes`：要么返回从 `afterVersion`（含 0）起连续 patches（客户端逐个应用），
  要么返回必须整体替换的权威 snapshot（缓存缺失/gap/损坏）；已处于尾部时返回空 delta，
  未知 canvas 400；
- WebSocket `/api/events/v1`：所有帧都带 `version:1`。客户端帧
  `{version:1, type:'subscribe'|'unsubscribe', resource:{kind,id}}`（kind 为 `thread`/`canvas`，
  id 为 canonical UUID）；服务端帧 `subscribed{resource,cursor}`（cursor 为 canonical 非负十进制，
   即订阅建立瞬间的 durable version）、
   `event{resource,name,data}`（name 为 `version`/`realtime`，version 事件额外带
  canonical `cursor`，data 分别为 `{version:"N"}`/`{version:"N"}`/realtime codec JSON 对象）、
  `resync{resource}`、`error{code,message[,resource]}`；ack 游标之后的事件不丢失，事件帧不先于 ack 帧；
  未知资源只回资源级 `RESOURCE_NOT_FOUND`（带 resource）并保持连接，非法帧/发送过载回
  `INVALID_FRAME`/`BACKPRESSURE` 后关闭连接；
- Canvas 首次发送与 Chat 共用唯一写入口 `POST /api/ai/runtime/command-batches`：
  owner 为 `{type:CANVAS,id}`，NEW_SESSION materialization 原子创建 Session + ROOT + Thread
  并接受首条 USER_MESSAGE；ENTRY/THREAD 继续；Canvas owner 的 Session 摘要经
  `GET /api/canvases/{canvasId}/sessions` 查询；
- Resource 直读 URL 由服务端解析 Resource → blobId → blob 预签名，响应只含
  method/url/headers/expiresAt；TEXT 资源 400，未知 resource/canvas 404；全局
  storage 端点同样不暴露 bucket 与对象物理 key。

Environment 路由身份：

```text
GET /api/ai/environment            -> LiveEnvironmentDTO[]（name = canonical 路由身份 + ready 可用性标记）
WebSocket /api/ai/environment/daemon/v2
```

- Thread create / `SET_ENVIRONMENT` 的 `environment` 必须是完整 `{name, workspacePath}` 对象（name 为 canonical bounded 小写路由名称、workspacePath 为 canonical 相对 wire 路径，`'.'` 表示 root）或 null 清除，非法形状 400；mapper 不查注册表；turn 规划时 ENVIRONMENT 工具按最新 `EnvironmentBinding` 绑定、缺失/未 READY **不拒绝**（实际 start 时确定性 `Rejected`，durable `FAILED` ToolResult 模型可见），Agent skills 则要求最新选中 Environment live（缺失/未 READY/无名称精确拒绝）；
- daemon 由 `scripts/e2e/lib.sh` 以唯一 `--environment-root "$DAEMON_ENV_ROOT"` 和显式 `--note "$DAEMON_NOTE"` 启动；`DAEMON_NOTE` 默认稳定为 `E2E daemon environment.`，可由环境变量覆盖。environment root 只作为 CodingTools 本地边界，note 只经真实 CLI/READY 进入模型上下文；`GET /api/ai/environment` 只返回既有投影加 `rootPath`（READY 的 canonical Environment Root），`daemon.ready` 显式断言 `rootPath` 存在且不存在 `operatingSystem` / `workingDirectory` / `timeZone` / `note` 字段；`daemon.directories` 覆盖 `GET /api/ai/environments/{name}/directories` 的 root 形状与 400/404 错误映射；
- Chat 默认值（agentName/yoloEnabled/environment binding）仅作 blank pane 初始值（environment 可为 null，发送前可改/清空）；Thread `branchSettings` 独立持久化，Environment route immutable；
- daemon `read` 输出超过 core externalizer 内联阈值（8KB）的 Text content 会先外部化为瞬时 ResourceRef（`file:///` URI，携带 mediaType/size/sha256）；Entry 写入前再摄入全局 Blob，durable message 为 `resource(blobId,name,preview)`；daemon preview 阈值默认 2000 行 / 50KB；
- durable Blob Resource 经 `/api/storage/blobs/{blobId}/presigned-original|presigned-preview` 渲染，原件响应提供权威 mediaType/sizeBytes；`GET /api/ai/runtime/resources/{sha256}` 只保留给瞬时/Invocation file/s3 ResourceRef 兼容，未知或不完整内容身份不产生链接。

默认 L1 API 不执行 task Tool；前端单测覆盖 `task.status` 解析、renderer 分发、TaskStatusWidget 与审批转发，免费 UI case `ui.chat.task_status.bound_widget` 使用本地 parent/child OpenAI-compatible mock 验证真实 task heartbeat 的浏览器呈现。完整 durable 子 Thread 与终态 envelope 仍由显式 `--real` 的 `real.task_delegation` 覆盖。

WebSocket `/api/events/v1`：Thread 订阅 ack cursor 是 canonical decimal durable version，Redis
 realtime delta 经 `event{name:'realtime'}` 投递；version 事件只携带 ack 之后的前进值
（`event{name,cursor,data}`，十进制字符串，客户端随后拉 snapshot/changes），`resync` 要求整体快照；连接级 `{version:1,type:'heartbeat'}` 每 20 秒保活，由 `events.heartbeat_keepalive` 覆盖。

## 5. MiniMax-H3 手工 smoke

MiniMax-H3 Ref2VA 不注册自动 E2E case。默认 L1、`--with-canvas-function` 和 `--real`
都不得触发 H3 Prompt Agent 或 ComfyUI `/prompt`；自动验证使用本地 Harness mock 和本地
HTTP routes 覆盖 multipart、history、streaming、恢复与 materialize。

只有明确安排高成本 smoke 时才执行以下步骤：

1. 使用隔离的 S3 bucket、Prompt Agent/Environment 和测试专用 ComfyUI；先以只读方式
   检查 ComfyUI `/object_info`、`/system_stats`，不得在准备阶段上传或提交 prompt。
2. 通过 `GET/PUT /api/settings`（携带 `expectedVersion`）配置
   `integrations.minimaxH3.enabled/promptAgentName/promptEnvironmentName/comfyBaseUrl` 及各等待预算；
   可选 Bearer 仍使用 `KK_STUDIO_CANVAS_H3_COMFY_BEARER_TOKEN`，随后重启 backend 使启动快照生效。
3. 创建一张满足 H3 metadata 约束的图片 Resource，将其连接到 Function Node；选择
   `minimax-h3-ref2va`、`ratio=16:9`、`duration=4`，只执行一次。
4. 验证 Prompt Thread 为 root Thread、`activeTools=[]`，SYSTEM/USER 在同一 batch，USER
   的 `<Picture 1>` 表格、文字引用和 IMAGE attachment 使用同一编号。
5. 验证 Run checkpoint 从 `H3_INITIALIZED` 收敛到 `H3_COMPLETE`，ComfyUI 上传位于
   `kk-studio/{canvasId}`，workflow 动态参数和 SaveVideo node 92 正确，最终视频流式写入
   target Resource。
6. 另建一个尚在 pending queue 的 Run 后停止，确认仅删除其 prompt，不影响 running job，
   且没有调用全局 `/interrupt`。
7. 记录 Run/Thread/promptId/targetResourceId 和人工播放结论；随后关闭 H3 开关并清理测试
   Thread、ComfyUI output 与对象存储。

本方案实现验收不执行上述 smoke；任何真实 H3 生成都必须由任务负责人单独授权。

## 6. 实现结构与报告

| 路径 | 职责 |
| --- | --- |
| `scripts/e2e.sh` | 环境启停、凭证同步、矩阵与 UI E2E 编排 |
| `scripts/e2e/run-matrix.mjs` | Node case 注册、筛选、执行和报告 |
| `scripts/e2e/lib/registry.mjs` | case 注册表 |
| `scripts/e2e/lib/harness.mjs` | owner-aware command-batches（NEW_SESSION/ENTRY/THREAD）、Session/Thread 查询、yolo/stop CAS、approval、快照轮询、事件通道订阅等共享步骤 |
| `scripts/e2e/cases/*.mjs` | API case |
| `scripts/e2e/ui-smoke.mjs` | Playwright UI E2E 编排、筛选、报告与失败留证 |
| `scripts/e2e/ui/composer-matrix.mjs` | Composer durable/queued/draft 浏览器矩阵与免费 deterministic fixture |

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
case 与整轮 `durationMs` 使用 Node 单调时钟计算，不受宿主 wall clock 校正影响。

## 7. 维护

1. API 字段、状态或验证变化时，同步 case 与本文件。
2. 新增或删除 case 后运行 `node scripts/e2e/run-matrix.mjs --list`，以输出的 ID 和总数更新本文件。
3. Chat/Thread 编排步骤集中在 `scripts/e2e/lib/harness.mjs`；所有写操作经 `acceptCommandBatch`（owner + sealed target），Thread 创建使用 NEW_SESSION materialization（完整 `rootSettings`），分支使用 ENTRY，继续使用 THREAD。
4. 真模型、Tool、分支和 UI 只通过显式开关执行；默认 L1 保持免费。
5. `model.attempt_failure_visibility` 必须继续使用 case 内本地 `node:http` SSE mock；先建立 `/api/events/v1` Thread 订阅并以 realtime partial 证明 ack 后投递，再断言活跃窗口的 `modelAttemptFailures` 与立即 retry 的原样 Provider `messages`，最后用 quiescent durable Entry 与下一 turn 的 Provider `messages` 断言完成闭环，并将 request/snapshot 写入 case artifact；不得改成真实付费 Provider 或仅 HTTP 500 的弱化路径。
6. OpenCLI fake Hub 完整闭环由 `deploy/test/run.sh --with-app` 覆盖；真实 Seedance
   prepare-only smoke 必须同时提供确认参数和环境开关，且固定 `submit=0`。
