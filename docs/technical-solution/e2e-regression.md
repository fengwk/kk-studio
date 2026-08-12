# E2E 回归

本文描述当前 Node API 矩阵的 case、开关、API 验证方式和报告位置。事实源命令是：

```bash
node scripts/e2e/run-matrix.mjs --list
```

当前注册 **67** 个 API case；标准入口默认执行免费的 **L1 58** 个 case（其中
`canvas.api_version_contract` 免费验证 Canvas UUID/version/patch/changes 契约）。Canvas
Resource 直读预签名与全局 Blob 存储 contract 需要 backend 已启用 S3，并通过
`--with-canvas-storage` 显式执行；免费 fake Function 完整链路还需通过
`--with-canvas-function` 显式开启 fake
model 并重启 backend；L2/L3/L4 需要显式打开真实 Provider、分支或 Environment Tool 开关。UI smoke
由 `scripts/e2e.sh --ui` 另行附加，默认注册 15 个免费 UI case，不计入这 67 个 Node API case。

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
--with-canvas-storage
--with-canvas-function
--only CASE_ID
--level L1|L2|L3|L4
--list
--docs
--report-root DIR
--no-report
```

默认 backend URL 是 `http://127.0.0.1:18081`，默认 frontend URL 由 `scripts/e2e.sh` 传入 `http://127.0.0.1:5173`。真实 Provider 使用 `TEST_MINIMAX_BASE_URL` 与 `TEST_MINIMAX_API_KEY`，默认测试模型是 `minimax/MiniMax-M2.7`。

本地完整免费 Canvas 回归使用 `deploy/test` 提供 PostgreSQL 与 MinIO，只连接本机
S3-compatible endpoint：

```bash
docker compose -f deploy/test/compose.yaml up -d --wait

env \
  JAVA_HOME_21="$JAVA_HOME_21" \
  KK_STUDIO_DB_URL=jdbc:postgresql://127.0.0.1:15432/canvas_test \
  KK_STUDIO_DB_USER=canvas_test \
  KK_STUDIO_DB_PASSWORD=canvas_test_only \
  KK_STUDIO_STORAGE_S3_ENABLED=true \
  KK_STUDIO_STORAGE_S3_ENDPOINT=http://127.0.0.1:19000 \
  KK_STUDIO_STORAGE_S3_PUBLIC_ENDPOINT=http://127.0.0.1:19000 \
  KK_STUDIO_STORAGE_S3_REGION=us-east-1 \
  KK_STUDIO_STORAGE_S3_BUCKET=canvas-test \
  KK_STUDIO_STORAGE_S3_ACCESS_KEY=canvas-test \
  KK_STUDIO_STORAGE_S3_SECRET_KEY=canvas-test-only \
  MANAGEMENT_HEALTH_REDIS_ENABLED=false \
  ./scripts/e2e.sh --with-canvas-function --ui

docker compose -f deploy/test/compose.yaml down --volumes --remove-orphans
```

该命令选择 60 个免费 API case 和 15 个免费 UI case，不启用真实 Provider、Tool 或
Branch。`--with-canvas-function` 自动启用 fake Function、Canvas storage 与 backend
rebuild；不得为这条回归追加 `--real`。

## 2. 分层

| 层级 | 开关 | 成本 | 覆盖 |
| --- | --- | --- | --- |
| L1 | 默认 | 免费 | Catalog/Chat CRUD（含 Agent subagents 引用校验与删除保护）、内部工具目录、Chat-scoped Thread、命令 batch 严格 wire、CAS/幂等、head move、IDLE stop no-op、i18n 与 proxy |
| L2 | `--real` | MiniMax | 文本轮次 durable 边界、真实 task 委派 durable 子 Thread、运行中原子 batch 合并收割、stop partial + REPLAYED + continue |
| L3 | `--real --with-branch` | MiniMax | 同一 Thread 同 Session move head 回退到历史 Entry 后继续 |
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

### L1（注册 60，默认 58）

```text
seed.structured_model_config
seed.agent_and_provider
catalog.internal_tools_hidden
thread.chat_scoped_create_atomic
thread.branch_settings_projection
thread.user_message_strict_wire
thread.custom_message_strict_wire
thread.command_idempotent_replay
thread.stale_command_cas_rejected
thread.rebind_same_session
thread.rebind_cross_session_rejected
thread.stop_idle_noop
thread.branch_settings_diff_commands
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
crud.chat.thread_association_list
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
canvas.storage_upload_contract
canvas.function_fake_runtime
chat.attachment_upload_contract
canvas.api_version_contract
```

L1 的关键语义断言：

- Provider/Agent name 与 Model `(providerName,name)` 创建、更新、硬删除、同名重建；记录存续期间名称不可修改，DELETE 带 `expectedVersion` 硬删除后列表不再出现，同名立即可重建且 `version` 从 `"0"` 重新开始并读取到新数据（删除前数据不残留）；
- Agent config 的 `tools`/`skills`/`subagents` 三个列表**必填**（缺失 400），元素去重、非空白短名；`tools` 只允许可选目录中的名称（未知 400），`subagents` 是 Agent 名称 allowlist——引用锁定（被引用的 Agent 不可删除，DELETE 409；移除引用后即可硬删除），未知引用 404；
- `GET /api/ai/catalog/tools` 只返回可选 Platform/Environment 目录：`load_skill`/`task` 两个内部 Platform Tool 绝不出现；Goal 插件 `create_goal/get_goal/update_goal` 必须作为可选 ToolCatalog 能力通过 Agent config 校验；
- Chat CRUD 仅持久化 `agentName`、`yoloEnabled` 与可选默认 `environmentName`（可为 null）；先建 Thread 再更新 Chat 后 reread 同一 Thread，branchSettings 逐字段不变；
- Chat-scoped Thread create body 携带完整 `branchSettings`，201 返回 `HarnessThreadSnapshotDTO`；`title` 可空（null 保持 null）；
- Thread/snapshot 的 `threadId`、`sessionId`、`headEntryId` 均为 canonical UUID string；`nextCommandSequence`、`revision` 为 strict decimal string；`nextCommandSequence` 从 **1** 开始；
- snapshot 结构固定为 `thread`、`entries`（当前 root→head 路径）、`queuedCommands`、`modelInvocation|null`（只暴露 active invocation）、`toolInvocations`（只暴露 classifier-applicable active siblings）；
- 命令 batch 携带 `expectedHeadEntryId` + `expectedNextCommandSequence` CAS cursor；stale cursor 409 且 Thread 状态（sequence/revision/head）逐字段不变；
- `USER_MESSAGE` 只接受一个非空有序 `contents(TEXT/ATTACHMENT)` 列表：`TEXT(text)` 与 `ATTACHMENT(uploadId)`（READY upload 的 canonical UUID string，入队事务内原子消费并删除 upload 行）；`text`/`content` 文本 shorthand 已移除（`text` 为未知字段、`content` 对 USER_MESSAGE 禁用）；`IMAGE/AUDIO/VIDEO` 内容类型、未知/多余字段与非 canonical uploadId 返回 400；`CUSTOM_MESSAGE` 仍使用 `content` 且 role 仅 `SYSTEM|USER`（SYSTEM+USER 同一原子 batch 顺序与 payload 稳定）；
- 命令响应携带 `requestHash`（raw 命令 canonical SHA-256，64 位小写 hex）与 `sequence`；同 `clientCommandId` + 同 hash 整批重放幂等返回既有命令且不二次消费 upload；部分重放 409；replay/400 不依赖异步消费时序；
- `SET_ENVIRONMENT/SET_AGENT/SET_MODEL/SET_ACTIVE_TOOLS/SET_YOLO` 五类命令按前端固定顺序与 `USER_MESSAGE` 一个原子 batch 入队；case 使用 canonical 但不存在的 Agent，使 Resolver 在调用 Provider 前确定性 `PLANNING_FAILED`。消费后 `branchSettings`/`yoloEnabled` 精确投影、queue 清空，durable `ASSISTANT_ERROR` 与最终 `TURN_END(FAILED, continueModel=false)` 收敛到 IDLE，USER MESSAGE 自身不算消费证据；
- `PUT /head` body `{targetEntryId,expectedRevision}`：同 target 在 revision 校验前 no-op（即使 stale 也不 bump）；非同 target stale revision 409；跨 Session target 409；
- `POST /stop` body `{stopRequestId,expectedRevision}`：IDLE 无 queued 时 status=IDLE、无 stopped TURN_END、revision 不变；IDLE stop 不写持久 marker，同 `stopRequestId` 再次调用仍是 IDLE no-op（不是 REPLAYED）；stale revision 409；真实 STOPPED/REPLAYED 语义由 L2 覆盖；
- 未知 Thread snapshot 404；
- `Accept-Language` 验证错误 message/title 本地化而稳定字段不变。
- `canvas.api_version_contract` 免费 L1：create/list/get/commands 使用 canonical UUID id 与
  long `version`（`graphRevision` 字段不存在）；`expectedVersion` CAS stale 409；
  同 `commandId` 精确回放返回当前版本的确定性空 patch（version 不前进），同 id 不同内容
  409；`changes?afterVersion=0` 恒为权威 snapshot，尾部版本返回连续 patches 或同样
  回退 snapshot；深删除后画布 404。
- `canvas.storage_upload_contract` 仅在 `--with-canvas-storage` 下执行：backend 必须启用
  S3 配置；通用 S3 预签名端点不能签名 `blobs/` 命名空间 key；全局
  upload reserve（`sha256` 必填）→ 浏览器直传 PUT → complete 绑定 READY blob，
  `CREATE_RESOURCE_NODE` 在同一事务消费上传后 Resource DTO 携带 `blobId`/`kind`/
  `mediaType`/`sizeBytes`；`download-url`/`preview-url` 只暴露
  method/url/headers/expiresAt（不暴露 bucket/key），download 字节与上传一致；
  TEXT 资源 URL 400、未知 resource/canvas 404；深删除画布后不可达。
- `canvas.function_fake_runtime` 仅在 `--with-canvas-function` 下执行：显式注册
  `fake-image`，验证 Function node 创建（客户端 UUID nodeId）、start/poll、成功 Resource
  原子替换、`320×260 @ (100,100)` transform 精确回读、`document.version` 按命令与 Run
  状态前进、公开 run DTO 无 `stateJson`，以及 preview signed GET 的 WEBP 字节。
- `chat.attachment_upload_contract` 仅在 `--with-canvas-storage` 下执行：通用存储
  reserve -> 真实 presigned PUT -> complete -> USER_MESSAGE `ATTACHMENT(uploadId)`
  原子消费；入队响应 `requestHash` 为 64 位小写 hex、durable payload 为
  `resource(blobId/name)`；整批重放幂等不二次消费；已消费/未 READY upload 与
  `IMAGE/AUDIO/VIDEO` 内容类型确定性 400。

### L2/L3/L4（7）

```text
real.text_turn
real.task_delegation
real.queued_command_batch
real.stop_partial_continue
branch.same_session_move_head
daemon.ready
tool.read_turn
```

| Case | 开关 | 重点验证 |
| --- | --- | --- |
| `real.text_turn` | `--real` | 真实 Provider 文本轮次；durable `TURN_START -> USER -> assistant MESSAGE -> TURN_END(COMPLETED)` 边界（TurnPlanBuilder 先追加 TURN_START 再追加 USER/CUSTOM）；IDLE 后 `modelInvocation=null`（快照无 `modelInvocations[]` 历史列表） |
| `real.task_delegation` | `--real` | 父 Agent config 携带 `subagents=[child]` 且 branch `activeTools` 含 `task`：真实 Model 调用内部 `task`，创建 durable 子 Thread（ROOT 携带 `subagentContext{parentThreadId,rootThreadId,taskInvocationId,depth=2}`）；最终 TOOL MESSAGE 冻结 `rendererKey=task` 与 `<task id state>` envelope，`id` 即子 ThreadId |
| `real.queued_command_batch` | `--real` | 运行中用最新 cursor 一次原子 batch 入队两条 USER_MESSAGE（sequence 连续）；下一 turn 收割为两个 USER entry + 一个 assistant |
| `real.stop_partial_continue` | `--real` | 流式 stop => `STOPPED`/revision+1/`stoppedTurnEndEntryId`；同 `stopRequestId` + 原 revision exact replay => `REPLAYED` 且不重复 bump；后续轮次在 ASSISTANT_ABORTED barrier 后 |
| `branch.same_session_move_head` | `--real --with-branch` | 同一 Thread 从 TURN_END head 回退到该 Session 内历史 assistant Entry；sessionId 不变、revision+1、root-to-head 路径切换并继续 |
| `daemon.ready` | `--with-tools` | READY Environment、canonical 路由名称与固定十一个 Tool（9 coding + 2 MCP 桥接）；skills + mcpServers 摘要形状；公共查询不泄露 READY operatingSystem/timeZone/note metadata |
| `tool.read_turn` | `--real --with-tools` | yolo=false：`TOOL_WAITING_APPROVAL` 下冻结 `environmentName`；输入 `ALLOW`、durable decision 为 `ALLOWED`（decisionId 幂等 replay 保留 decidedAt）；`>8KB` fixture 经 externalizer 外部化为 Resource；durable TOOL MESSAGE 的 `tool_result.contents` 携带 canonical `file:` URI（uri/mediaType/size/sha256）；按内容身份请求 `GET /api/ai/runtime/resources/{sha256}?mediaType&size&name` 下载并验证返回字节数、mediaType、`X-Content-Type-Options: nosniff` 与 sha256 一致 |

### L5 UI（默认 15，`--real` 追加 1）

`scripts/e2e.sh --ui` 的免费 UI smoke 包含：

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
```

其中 `ui.canvas.page_loads` 验证 `/canvas` library 保留全局顶栏，点击或创建后进入
canonical UUID 形式 `/canvas/:canvasId`：编辑器无全局顶栏且挂 `canvas-immersive` class；Chat 面板默认收起且底部不渲染
composer，点击 header「Chat / 对话」toggle 展开/收起；默认面板宽于 360px，左边缘拖拽调宽后 zoom
保持不变；add launcher（左侧中部功能轨）仍可打开菜单，V/H 按钮不展示，缩放控制位于左下；首屏 fit
产生的动态缩放值位于合法区间，Chat 面板展开/收起不改变该缩放值，点击缩放值可重置为 `100%`
且刷新后恢复该持久化视口，浏览器 back 返回 library、forward 再进入编辑器；
Function 生成的付费路径不进入默认 UI smoke，前端组件测试使用 fake Function runtime
隔离。
`--real` 额外执行 `ui.chat.blank_first_send_real`。Headless 模式只对 Chromium 子进程移除
宿主 `DISPLAY` 与 `WAYLAND_DISPLAY`，避免混合桌面环境导致 compositor 停帧；`--headed`
保留宿主显示环境。

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
GET /api/ai/chat/{chatId}/threads            -> HarnessThreadDTO[]（关联时间新到旧）
POST /api/ai/chat/{chatId}/threads           -> 201 HarnessThreadSnapshotDTO
PUT /api/ai/chat/{chatId}/threads/{threadId} -> 幂等关联

GET  /api/ai/runtime/threads/{threadId}/snapshot
POST /api/ai/runtime/threads/{threadId}/commands          -> 202
PUT  /api/ai/runtime/threads/{threadId}/head
POST /api/ai/runtime/threads/{threadId}/stop
POST /api/ai/runtime/threads/{threadId}/tool-invocations/{toolInvocationId}/approval
GET  /api/ai/runtime/threads/{threadId}/events/stream?afterRevision={revision}

GET  /api/ai/runtime/resources/{sha256}?mediaType=&size=&name=  -> 同源 managed Resource 下载（attachment + nosniff）

POST /api/storage/uploads                       -> 通用存储 reserve（PENDING + presigned PUT）
POST /api/storage/uploads/{uploadId}/complete   -> READY（blobId；供 ATTACHMENT 消费）
DELETE /api/storage/uploads/{uploadId}
GET  /api/storage/blobs/{blobId}/presigned-original|preview
```

Chat-scoped Thread create body（完整 branch draft；`title` nullable）：

```json
{
  "title": null,
  "branchSettings": {
    "environmentName": null,
    "agentName": "default-assistant",
    "model": { "providerName": "minimax", "modelName": "MiniMax-M2.7", "variant": "default" },
    "activeTools": []
  },
  "yoloEnabled": false
}
```

命令 batch body（CAS cursor + ordered commands；`clientCommandId` 稳定；创建后 `expectedNextCommandSequence` 为 `"1"`）：

```json
{
  "expectedHeadEntryId": "1",
  "expectedNextCommandSequence": "1",
  "commands": [
    {
      "type": "USER_MESSAGE",
      "clientCommandId": "...",
      "contents": [{ "type": "TEXT", "text": "..." }]
    }
  ]
}
```

`USER_MESSAGE` 必须且只能携带一个非空有序 `contents` 列表，元素只允许 `TEXT(text)` 与 `ATTACHMENT(uploadId)`——`uploadId` 是通用存储 reserve/complete 得到的 READY upload（canonical UUID string），入队事务内原子消费：锁定 upload 行 -> 以权威文件名物化为 durable `resource(blobId/name)` -> session blob ref -> 删除已消费 upload 行；整批重放（同 `clientCommandId` + 同 hash）绝不二次消费。`text`/`content` 文本 shorthand 已移除：`text` 按未知字段拒绝，`content` 对 USER_MESSAGE 禁用；`IMAGE/AUDIO/VIDEO` 内容类型、未知/多余字段、空 `contents` 与非 canonical uploadId 一律 400。命令响应（`HarnessThreadCommandDTO`）携带 `requestHash`（raw 命令的 canonical SHA-256，64 位小写 hex）与 `sequence`（Thread 内从 1 开始的正整数）。`CUSTOM_MESSAGE` 使用 `content` 与 `role`（仅 `SYSTEM|USER`）。五类 SET 命令各自只携带目标字段：`SET_ENVIRONMENT(environmentName)`、`SET_AGENT(agentName)`、`SET_MODEL(model)`、`SET_ACTIVE_TOOLS(activeTools)`、`SET_YOLO(yoloEnabled)`，多余字段一律 400。

head move 与 stop 均为 revision CAS：

```json
{ "targetEntryId": "1", "expectedRevision": "0" }
{ "stopRequestId": "...", "expectedRevision": "0" }
```

- `PUT /head`：同 target 在 revision 校验前 no-op（即使 stale 也不 bump）；非同 target stale revision、非静止、跨 Session target、TURN_END continuation 义务均为 409；成功 revision+1；
- `POST /stop` 响应 `{status, thread, stoppedTurnEndEntryId, cancelledCommandCount}`；status 三态：
  - `STOPPED`：真实停止一个 Turn（revision+1，`stoppedTurnEndEntryId` 非空，TURN_END closeRequestId = `STOP/{threadId}/{stopRequestId}`）；
  - `REPLAYED`：同 `stopRequestId` 再次调用命中持久 TURN_END（在 revision CAS 之前，revision 不再变化，返回同一 `stoppedTurnEndEntryId`）；
  - `IDLE`：无活动 Turn（无持久 marker，`stoppedTurnEndEntryId=null`；同 `stopRequestId` 再调用仍是 IDLE）；
  - stale revision 409；
- 命令 batch 整批同 `clientCommandId` + 同 `requestHash` 重放返回既有命令（sequence/requestHash 稳定）；仅部分存在 409 `PARTIAL_COMMAND_REPLAY`。

Tool approval：

```json
{ "decision": "ALLOW", "decisionId": "...", "actor": "web", "reason": null }
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
DELETE /api/canvases/{canvasId}             -> 深删除（含绑定 Thread）
GET    /api/canvases/{canvasId}/changes?afterVersion=N -> CanvasChangesDTO
GET    /api/canvases/{canvasId}/events/stream?afterVersion=N -> SSE（'version'/'resync'）
POST   /api/canvases/{canvasId}/thread/messages -> 200 CREATED 原子首次发送
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

- 所有实体 id（canvas/node/group/resource/blob/upload/thread）都是 canonical UUID 字符串
  （shape-only，不限定 version/variant 位）；graph 版本是 long `version`
  （`canvas_document.version`），不存在 `graphRevision` 字段；
- `POST /commands` body 为 `{expectedVersion, commandId, commands[]}`：`expectedVersion`
  是精确 CAS 游标（stale 409 `VERSION_CONFLICT`）；`commandId` 是整批幂等键——同 id 同
  内容精确回放返回 `{baseVersion:version, version, [], [], []}` 空 patch，同 id 不同内容
  409 `IDEMPOTENCY_CONFLICT`；创建类命令携带客户端生成的实体 UUID
  （`CREATE_TEXT_NODE(nodeId,name,markdown,transform)`、
  `CREATE_RESOURCE_NODE(nodeId,name,uploadIds,transform)`、
  `CREATE_FUNCTION_NODE(nodeId,name,modelKey,configJson,transform)`、
  `CREATE_GROUP(groupId,title,transform,memberNodeIds)`）；
- `CanvasResourceDTO` 的 `kind` 由 API 按 blob mediaType 派生，TEXT 资源无 blob；
  `mediaType/sizeBytes/width/height/durationMs` 来自 blob 权威事实列；
- `GET /changes`：要么返回从 `afterVersion` 起连续 patches（客户端逐个应用），要么返回
  必须整体替换的权威 snapshot（初始加载/gap/缓存损坏）；未知 canvas 400；
- SSE `events/stream`：`afterVersion` 与 `Last-Event-ID` 都是非负十进制 version；
  `version` 事件只携带前进版本（客户端随后拉 changes），`resync` 事件要求整体快照；
- `POST /thread/messages` 原子首次发送：单事务创建根 Thread、入队有序
  `USER_MESSAGE contents`（`commandId` 作为 `clientCommandId` 幂等键）并绑定
  `canvas_document.thread_id`；已绑定 Thread 时原样重放；`branchSettings` 是冻结的
  provider/model/variant 三元组，与 Harness `HarnessBranchSettingsDTO` 同构；
- Resource 直读 URL 由服务端解析 Resource → blobId → blob 预签名，响应只含
  method/url/headers/expiresAt；TEXT 资源 400，未知 resource/canvas 404；全局
  storage 端点同样不暴露 bucket 与对象物理 key。

Environment 路由身份：

```text
GET /api/ai/environment            -> LiveEnvironmentDTO[]（name = canonical 路由身份 + ready 可用性标记）
WebSocket /api/ai/environment/daemon/v2
```

- Thread create / `SET_ENVIRONMENT` 的 `environmentName` 只接受 canonical bounded 小写路由名称（或 null 清除），非法名称 400；mapper 不查注册表；turn 规划时 ENVIRONMENT 工具按最新名称绑定、缺失/未 READY **不拒绝**（实际 start 时确定性 `Rejected`，durable `FAILED` ToolResult 模型可见），Agent skills 则要求最新选中 Environment live（缺失/未 READY/无名称精确拒绝）；
- daemon 由 `scripts/e2e/lib.sh` 以唯一 `--workdir "$DAEMON_ENV_ROOT"` 和显式 `--note "$DAEMON_NOTE"` 启动；`DAEMON_NOTE` 默认稳定为 `E2E daemon environment.`，可由环境变量覆盖。workdir 只作为 CodingTools 本地边界，note 只经真实 CLI/READY 进入模型上下文；`GET /api/ai/environment` 继续只返回既有投影，`daemon.ready` 显式断言不存在 `operatingSystem` / `workingDirectory` / `timeZone` / `note` 字段；
- Chat 默认值（agentName/yoloEnabled/environmentName）仅作 blank pane 初始值（environmentName 可为 null，发送前可改/清空）；Thread `branchSettings` 独立持久化，Environment route immutable；
- daemon `read` 输出超过 core externalizer 内联阈值（8KB）的 Text content 会被外部化为 Resource（`file:///` URI，携带 mediaType/size/sha256）；daemon preview 阈值默认 2000 行 / 50KB；
- managed Resource 只按内容身份（mediaType/size/sha256/name）经 `GET /api/ai/runtime/resources/{sha256}` 同源下载：响应 `attachment` + `X-Content-Type-Options: nosniff`，字节与 sha256 一致；未知/不完整身份不产生链接。

默认 L1 不覆盖 task 心跳与 UI 呈现：`task.status` 心跳、renderer 分发、TaskStatusWidget 与浏览器通知等呈现契约由前端单测覆盖，真实 task 委派只由显式 `--real` 的 `real.task_delegation` 覆盖。

SSE：`afterRevision` 与 `Last-Event-ID` 是 canonical decimal durable cursor；Redis realtime delta 无 SSE id。

## 5. MiniMax-H3 手工 smoke

MiniMax-H3 Ref2VA 不注册自动 E2E case。默认 L1、`--with-canvas-function` 和 `--real`
都不得触发 H3 Prompt Agent 或 ComfyUI `/prompt`；自动验证使用本地 Harness mock 和本地
HTTP routes 覆盖 multipart、history、streaming、恢复与 materialize。

只有明确安排高成本 smoke 时才执行以下步骤：

1. 使用隔离的 S3 bucket、Prompt Agent/Environment 和测试专用 ComfyUI；先以只读方式
   检查 ComfyUI `/object_info`、`/system_stats`，不得在准备阶段上传或提交 prompt。
2. 配置 `KK_STUDIO_CANVAS_H3_ENABLED=true`、
   `KK_STUDIO_CANVAS_H3_PROMPT_AGENT`、
   `KK_STUDIO_CANVAS_H3_PROMPT_ENVIRONMENT`、
   `KK_STUDIO_CANVAS_H3_COMFY_BASE_URL` 和可选 Bearer，重启 backend。
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
| `scripts/e2e.sh` | 环境启停、凭证同步、矩阵与 UI smoke 编排 |
| `scripts/e2e/run-matrix.mjs` | Node case 注册、筛选、执行和报告 |
| `scripts/e2e/lib/registry.mjs` | case 注册表 |
| `scripts/e2e/lib/harness.mjs` | Chat-scoped Thread、命令 batch、head/stop CAS、approval、快照轮询、SSE 等共享步骤 |
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
case 与整轮 `durationMs` 使用 Node 单调时钟计算，不受宿主 wall clock 校正影响。

## 7. 维护

1. API 字段、状态或验证变化时，同步 case 与本文件。
2. 新增或删除 case 后运行 `node scripts/e2e/run-matrix.mjs --list`，以输出的 ID 和总数更新本文件。
3. Chat/Thread 编排步骤集中在 `scripts/e2e/lib/harness.mjs`；Thread 创建携带完整 `branchSettings`，后续变更通过命令 batch 表达。
4. 真模型、Tool、分支和 UI 只通过显式开关执行；默认 L1 保持免费。
5. OpenCLI fake Hub 完整闭环由 `deploy/test/run.sh --with-app` 覆盖；真实 Seedance
   prepare-only smoke 必须同时提供确认参数和环境开关，且固定 `submit=0`。
