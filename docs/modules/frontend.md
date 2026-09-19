# Frontend 模块

[`frontend/`](../../frontend) 是独立的 React/Vite/TypeScript 工程：它把后端 durable
Snapshot 与有损 realtime 事件还原成可恢复的浏览器工作台。开发期由 Vite 提供页面并把
`/api` 代理到后端；发布期由 Maven `distribution` profile 构建后嵌入 Web Fat JAR。
入口、脚本和工具链见 [frontend/package.json](../../frontend/package.json) 和
[vite.config.ts](../../frontend/vite.config.ts)。

## 浏览器侧事实与同步契约

浏览器侧只有两类事实：来自 REST 的权威 Snapshot，以及浏览器本地 draft（Pane 布局、
未发送输入、上传进度）。WebSocket 事件只负责唤醒对账，或提供可丢失的显示 overlay。

- Extension 是编译期受信任的 React module：host 只接受 `TrustedReactExtension`，
  运行时不会下载、执行或评估远端脚本。
- UI 不把 PostgreSQL 行、S3 bucket/key、Daemon 本机绝对路径或 provider credential
  变成持久事实；提交到 API 的只有 canonical id、cursor 和业务字段。
- 旧 Snapshot、旧 Patch 和旧 version event 不能覆盖较新的本地状态；迟到 delta 不能
  复活已终态的 invocation。
- 网络结果不确定时保留 exact replay；明确的 `409` 交给 ConflictPresenter，不自动
  重放具有业务语义的命令。

## 应用组装与路由

[main.tsx](../../frontend/src/main.tsx) 在 `StrictMode` 下挂载
[AppProviders](../../frontend/src/app/providers.tsx)，provider 顺序是：

```text
QueryClientProvider
└─ ExtensionHostProvider
   └─ BrowserPreferencesProvider
      └─ ApplicationEventProvider
         └─ BrowserRouter
            └─ AppRouter
```

Query 默认 `retry: false`、`refetchOnWindowFocus: false`；ExtensionHost 在 provider
生命周期内只创建一次，ApplicationEventProvider 持有共享的 application-event manager。

[AppRouter](../../frontend/src/app/router.tsx) 把 `/` replace 到 `/chats`，其余路径
交给 [WorkbenchShell](../../frontend/src/platform/workbench/WorkbenchShell.tsx)。
WorkbenchShell 的顺序是 `AppShell`、`header` slot、动态 `StudioRoutes` 和 `status`
slot，每个 Page contribution 都包裹 `OverlayHost`。

[AppShell](../../frontend/src/platform/shell/AppShell.tsx) 拥有 topbar、主导航
（AI/Projects/Canvas/Tools/Settings）、locale 选择器和全局 Escape 优先级：合法
`/chats/:chatId` 与 canonical UUID 的 `/canvas/:canvasId` 使用 immersive shell 并
隐藏 topbar；Escape 只在没有 blocking modal、焦点不在可编辑控件、内层 menu 未展开
时才关闭导航抽屉。

[createApplicationExtensionHost](../../frontend/src/app/extension-host.ts) 注册五个
内置 extension：

| extension | 页面 | 其他 contribution |
| --- | --- | --- |
| `builtin.ai` | `/chats`、`/chats/:chatId`、`/agents`、`/models`、`/providers`、`/environments`、`/mcp-servers` | AI navigation、创建/编辑/删除 dialog、`task` tool renderer |
| `builtin.projects` | `/projects`、`/projects/:projectId` | 全局 Project invalidation overlay |
| `builtin.canvas` | `/canvas`、`/canvas/:canvasId` | lazy 加载 Canvas feature |
| `builtin.comfyui` | `/comfyui` | workflow editor/delete dialog |
| `builtin.settings` | `/settings` | lazy 加载 Settings feature |

[ExtensionHost](../../frontend/src/platform/extensions/ExtensionHost.ts) 提供 `pages`、
`navigation`、`panels`、`widgets`、`inspectors`、`commands`、`statuses`、`dialogs`、
`overlays` 和 `toolRenderers` 十个 registry。同一 contribution id 的候选按 `priority`
降序、注册顺序升序选择，卸载高优先级候选后低优先级候选接管；重复 extension id、非法
contribution id 和非法 page path 在注册时被拒绝。
[WorkbenchSlots](../../frontend/src/platform/workbench/WorkbenchSlots.tsx) 只把
registry 渲染到 slot，`OverlayHost` 统一渲染 dialogs 和 overlays。`toolRenderers`
的 `id` 必须与后端冻结的 `rendererKey` 一致；`task` renderer 缺失时 MessageList
使用默认 renderer。

[`src/shared`](../../frontend/src/shared) 不得依赖 `@/features`（ESLint 强制），feature
之间只通过 ExtensionHost 和 shared 协作。Thread panel 是可移植 presentation：只依赖
[thread-timeline-types](../../frontend/src/features/ai/runtime/thread-timeline-types.ts)
和 panel 内部组件，API、React Query、realtime、Canvas 与 controller 都留在宿主层。

## API 与 contract 边界

[client.ts](../../frontend/src/shared/api/client.ts) 以 `/api` 为 base URL，Axios
timeout 为 `60000ms`，请求注入当前 `Accept-Language`，成功时把 `ResultEnvelope`
解包为 `data`。`ApiError` 保留 HTTP status、code 和 errors；`isConflictError` /
`isNotFoundError` 只按 status 判定，`isConflictReason` 额外要求 `errors.reason`
精确匹配，只有精确匹配时才允许按 reason 恢复。

contract 按 HTTP 边界分组，全部是严格 wire 类型：

| 文件 | 内容 |
| --- | --- |
| [base.ts](../../frontend/src/shared/api/contracts/base.ts) | `ResultEnvelope`、分页、时间、canonical decimal、`CatalogVersion`、`CanvasVersion` |
| [ai-runtime.ts](../../frontend/src/shared/api/contracts/ai-runtime.ts) | Session/Entry/Thread、branch settings、command batch、stop/approval、model/tool invocation、Snapshot |
| [ai-catalog.ts](../../frontend/src/shared/api/contracts/ai-catalog.ts) | Provider、Model、Agent、Tool catalog 与 structured config |
| [ai-chat.ts](../../frontend/src/shared/api/contracts/ai-chat.ts) | Chat 资源 |
| [ai-environment.ts](../../frontend/src/shared/api/contracts/ai-environment.ts) | Environment Card/live capability、Skill 来源、持久 inventory 与异步操作 |
| [ai-mcp.ts](../../frontend/src/shared/api/contracts/ai-mcp.ts) | MCP Server 安全投影与显式配置 |
| [studio.ts](../../frontend/src/shared/api/contracts/studio.ts) | Canvas document、node/resource/group/link、Snapshot、Patch、version event、typed command |
| [storage.ts](../../frontend/src/shared/api/contracts/storage.ts) | PENDING/READY upload、presigned PUT、render-time presigned URL |
| [comfyui.ts](../../frontend/src/shared/api/contracts/comfyui.ts) | Workflow、input binding、run、job、cancel |
| [system-settings.ts](../../frontend/src/shared/api/contracts/system-settings.ts) | schema sections/field types、permission、model selection、apply timing |

service 只做路由映射与严格解码：

| service | 路由范围 |
| --- | --- |
| [agent-service.ts](../../frontend/src/shared/api/agent-service.ts) | `/ai/catalog/providers|models|agents|tools`，删除使用 `expectedVersion` CAS |
| [chat-service.ts](../../frontend/src/shared/api/chat-service.ts) | `/ai/chats` 与 owner Session 查询 |
| [mcp-server-service.ts](../../frontend/src/shared/api/mcp-server-service.ts) | `/ai/mcp-servers` CRUD、显式配置查询与 discover |
| [environment-service.ts](../../frontend/src/shared/api/environment-service.ts) | `/harness/environments` Card、token、Skill 来源、inventory 与 operation |
| [harness-service.ts](../../frontend/src/shared/api/harness-service.ts) | `/harness/command-batches|sessions|threads` |
| [studio-service.ts](../../frontend/src/shared/api/studio-service.ts) | `/canvases`、Canvas resource 与 Function Run；自带 `canvasRequest`、AbortSignal 与 strict envelope |
| [storage-service.ts](../../frontend/src/shared/api/storage-service.ts) | upload 生命周期与 blob presigned URL |
| [comfyui-service.ts](../../frontend/src/shared/api/comfyui-service.ts) | workflow/run 与 `blobId` 文件输入 |
| [system-settings-service.ts](../../frontend/src/shared/api/system-settings-service.ts) | `/settings` 聚合 GET/PUT 与 schema |
| [projects-api.ts](../../frontend/src/features/projects/projects-api.ts) | `/projects`、`/issues`；Project 的 DTO 与 codec 就近放在 feature 内 |

所有跨 HTTP 的 entity id 都是 canonical UUID string；Java `long` 游标在 wire 上保持
canonical 非负十进制 string，Canvas version 只比较字符串长度和字典序，不转成
JavaScript number。codec 严格校验 canonical UUID、decimal、枚举、nullability 和嵌套
shape，不把宽松 cast 当成 wire contract。

## Snapshot、realtime 与恢复

Thread 与 Canvas 都先读取权威 Snapshot，再按 resource 订阅 `/api/events/v1`：

```mermaid
sequenceDiagram
  participant Q as Snapshot query
  participant M as ApplicationEventManager
  participant W as /api/events/v1
  participant O as Realtime overlay
  participant UI as Thread timeline

  Q->>UI: durable entries + invocation snapshot
  M->>W: subscribe {kind: thread, id}
  W-->>M: subscribed(cursor)
  M-->>Q: invalidate snapshot
  W-->>M: version / resync / error
  M-->>Q: invalidate snapshot
  W-->>M: realtime MODEL_DELTA / TOOL_PARTIAL
  M-->>O: strict parse + sequence reducer
  O-->>UI: transient streaming/task display
  Q-->>UI: durable entry arrives
  UI->>O: terminal fence and overlay retirement
```

- `subscribed`（首次与每次重连）、`version`、`resync` 和资源级 `error` 都触发
  Snapshot 对账；`heartbeat` 只做连接保活。version/resync 触发的快照刷新不会丢弃
  已在 refs 中累积的未决 delta。
- Thread `version` 是结构/控制状态的 durable 提示，不是 Snapshot ETag：Model
  checkpoint 可在同一 version 内推进，因此同 version 的权威回读仍参与对账。
- `realtime` 没有 cursor，只承载 `MODEL_DELTA`/`TOOL_PARTIAL`。MODEL delta 只接受
  `TEXT_DELTA`、`THINKING_DELTA`、`TOOL_CALL_DELTA`，按连续 sequence 追加；tool
  partial 按 `thread:invocation:attempt` 做有界精确去重。
- delta 即时归约进 refs，使 sequence 连续性、缺口检测和去重不依赖 React 刷新时机；
  模型/工具 overlay 的发布合并到单个 `requestAnimationFrame`，每帧发布当前累积内容，
  不做字符级缓动或打字机延时。
- invocation 出现 `resultJson`、`errorJson` 或 `resultEntryId` 后建立 terminal fence，
  迟到 delta/partial 丢弃；Snapshot 的 `modelAttemptFailures` 已记录同一
  `modelInvocationId + attempt` 时，对应迟到 `MODEL_DELTA` 也丢弃并提前结束该 attempt
  的 gap recovery。持久化终态与失败审计优先于任何较新的 transient overlay。
- MODEL sequence 出现 gap 时启动单飞 recovery：重新读取 Snapshot，退避 `200ms` 到
  `2000ms`，最多 `8` 次；即使 Thread version 未变化也读取并合并 durable checkpoint，
  不用事件填补内容。

[thread-events.ts](../../frontend/src/features/ai/runtime/thread-events.ts) 把每个
durable Entry 投影为恰好一条记录，把 active model/tool invocation 和 attempt failure
作为锚定其 Entry 之后的 synthetic record；状态为 `pending`、`running`、`completed`、
`failed`、`stopped`。

运行控制面同样以 Snapshot 为对账依据：

- Stop 请求是 `{stopRequestId, expectedVersion}`。同一个 `stopRequestId` 用于不确定
  失败的 exact replay，成功结果把 `cancelledUserMessages` 按 sequence 前置回
  Composer，`IDLE` 是 no-op。
- Tool approval 使用 `{decision, decisionId, actor: "web", reason: null}`；同一
  Thread、invocation、decision 的重试复用 decision id，ALLOW/DENY 切换生成新 id。
- `task.status` 是完整 heartbeat 而不是 delta，状态为 `queued`、`running_model`、
  `running_tool`、`waiting_approval`；同一子 Thread 的 heartbeat 替换旧帧。

## 变更提交与上传

Chat Pane 有两个正交维度。布局是 `single`、`split-2`、`split-3`、`grid-4`、
`grid-6`、`grid-8`，按 Chat id 保存在 `kk-studio.chat-pane.<chatId>`；target 三态是：

| Pane target | 入口 | 本地事实 | 发送结果 |
| --- | --- | --- | --- |
| `NEW_SESSION_DRAFT` | 新建 Pane/Chat，尚无 Session | `BranchDraft`、ordered Composer parts、未发送 settings | 原子 `NEW_SESSION`，成功后绑定新 Thread；Session 默认名由服务端从首条用户文本派生，root Thread 名固定 `main` |
| `NEW_THREAD_DRAFT` | `/tree` 选择同一 Session 的历史 Entry | `sessionId + startEntryId`、BranchDraft、Composer parts | 原子 `NEW_THREAD` 建立分支，Thread 名固定 `branch-<threadId 前 8 位>` |
| `BOUND_THREAD` | 已加载 Thread snapshot | Thread `branchSettings`、head、version、next command sequence | `THREAD` target 携带精确 cursor，batch 进入 mailbox |

`PendingAcceptance` 按 owner 与 pane id 写入 localStorage，包含 frozen request、
BranchDraft、Composer parts、generation 和 `unknownOutcome`；存在 pending operation 时
拒绝切换 target，generation 与 target identity 防止旧请求覆盖新 Pane。

`buildAcceptanceRequest` 在网络请求前冻结整批数据：

- `NEW_SESSION` 只发送 `USER_MESSAGE`，完整 BranchDraft 写进 `rootSettings`；
- `NEW_THREAD_DRAFT` 和 `BOUND_THREAD` 在 `USER_MESSAGE` 前按固定顺序追加
  `SET_AGENT`、`SET_MODEL`、`SET_ENVIRONMENT` 的 diff（`environmentName` 可空，null 表示清除）；
  Agent 工具选择由最新 Agent definition 的
  `config.tools` 决定，不生成 branch tool command；
- `BOUND_THREAD` 的 target 携带 `expectedHeadEntryId` 和
  `expectedNextCommandSequence`；YOLO 是 `PUT /yolo` 的直接控制面，不进入 mailbox；
- 每条 command 带 UUID `idempotencyKey`，与 raw command canonical SHA-256 一起构成
  服务端 ordered replay 幂等键。

失败分类决定恢复方式：网络或其他不确定失败保留 exact replay（相同 id、payload、
顺序和 cursor）；明确 `409` 表示 batch 未被接受，保留 command id 与 payload，
只在 `STALE_COMMAND_CURSOR` 且仍是同一 branch 的纯 message batch 时读取最新 Snapshot
并有限重试（最多两次）；其他情况保留本地 Composer parts，不自动重放语义命令。

Canvas 侧由 [CanvasCommandQueue](../../frontend/src/features/canvas/command-queue.ts)
串行化浏览器操作，每批以当前 Snapshot 的 `expectedVersion` 和 UUID `idempotencyKey`
提交，响应是 graph patch：

- `applyEntityPatch` 先要求 `patch.version > snapshot.version` 且
  `patch.baseVersion === snapshot.version`，再分别 upsert/remove nodes、groups、links；
- `version <= current` 是重复/过期 patch，直接忽略；baseVersion 不连续是 gap，读取
  全量 Snapshot；
- HTTP `409` 先读取最新 Snapshot，再抛出 `CanvasCommandConflictError`，queue 不自动
  重放；`replaceSnapshot` 拒绝同一 Canvas 的 version 回退，Canvas version 全程使用
  canonical decimal string；
- 节点 transform 由 [transform-batch.ts](../../frontend/src/features/canvas/transform-batch.ts)
  以 `180ms` debounce 聚合，并以 epoch 归属在途请求；
- [canvas-version-events.ts](../../frontend/src/features/canvas/canvas-version-events.ts)
  订阅 `{kind: "canvas", id}`，首次 `subscribed`、重连、`resync` 和 `error` 都读取
  权威 Snapshot，`version` 只有严格大于本地版本时才触发读取。

上传走分阶段协议，任何中途切换都作废整批：

```mermaid
sequenceDiagram
  participant F as File
  participant W as SHA-256 Worker
  participant API as Storage API
  participant C as Canvas command queue

  F->>W: hash + media descriptor
  W-->>API: reserve(filename, mediaType, sizeBytes, sha256)
  alt PENDING
    API-->>F: presigned PUT with filtered headers
  else READY
    API-->>API: content already exists, skip upload
  end
  API-->>API: complete(uploadId)
  API-->>C: CREATE_RESOURCE_NODE(uploadId)
```

[canvas-upload.ts](../../frontend/src/features/canvas/canvas-upload.ts) 与 Composer
附件共用同一套顺序：Web Worker SHA-256、`reserveUpload`、PENDING 直传或 READY 跳过、
`completeUpload`、最后提交消费 upload 的命令。每个 await 后校验 batch epoch；切换
Canvas 或 unmount 会作废整批并清理 progress；alias 在 `finally` 释放；已 complete 的
upload handle 不主动删除，由服务端过期策略回收。

Storage contract 不暴露 bucket/key：直传只发送签名响应允许的浏览器安全 headers，
upload handle 只能按 upload id 删除，blob original/preview URL 只在资源实际渲染或下载
时请求，并严格校验 `url`、mediaType 与 safe integer `sizeBytes`。

## Feature 主线

### AI

[`features/ai`](../../frontend/src/features/ai) 分成 catalog、chat、composer、environment、
mcp、runtime 六个子目录。Catalog 页面按 structured config 渲染 Provider/Model/Agent；
Agent 的 `tools`、`subagents` 用 catalog candidate 校验（candidate 身份就是模型可见 name），`skills` 用显式
`{sourceId, name}` 引用并从绑定 Environment 的持久 inventory 构建候选，切换或解绑
Environment 会清空选择。Chat 只持久化 title、agentName 与 YOLO 开关，Environment
归属完全由 Agent definition 的 `environmentId` 决定。

Environment 卡片只展示 capability 的 canonical `id`，管理弹窗提供 Skill 来源 CAS CRUD、
持久 inventory 与带显式 `timeoutMillis` 的 refresh/install/update，操作只在
`PENDING/RUNNING` 时每 2 秒轮询并在终态刷新；只有 `PENDING` 可取消。MCP 卡片只消费
不含连接细节的安全投影，完整配置仅在打开编辑弹窗时经 `no-store` 端点读取，并用递增
generation fence 防止关闭/重开时的迟到响应覆盖当前弹窗；更新或发现进行中时所有 JSON
mutation 控件禁用。

### Projects

[ProjectsPage](../../frontend/src/features/projects/ProjectsPage.tsx) 承载 Project 列表
与 create/edit/archive/delete；[ProjectDetailPage](../../frontend/src/features/projects/ProjectDetailPage.tsx)
只消费一个 `ProjectSnapshotDTO`（Project、未归档 Issues、依赖、blocked、当前/最近 Run、
Coordinator Session/Thread）。[IssueBoard](../../frontend/src/features/projects/components/IssueBoard.tsx)
固定六列 backlog、待办、执行中、等待人类、审核、完成，已取消的 Issue 单独成道；
IssueDetailModal 用 Snapshot 中的 decimal version 做 CAS，成功后重读 Snapshot。
[CoordinatorConversation](../../frontend/src/features/projects/components/CoordinatorConversation.tsx)
的首条 command 可在尚无 Session/Thread 时发送，会话历史来自 Harness Thread Snapshot，
Project feature 不复制 Harness durable state。

[useProjectsInvalidation](../../frontend/src/features/projects/useProjectsInvalidation.ts)
由 ExtensionHost overlay 桥接：订阅 `{kind: "projects"}`，每个 `subscribed` ack（含首连
与重连）和资源级 `error` 都触发权威回读；它不维护本地事件日志，也不在本地 mutation
后伪造 WebSocket event。

### Canvas

[CanvasPage](../../frontend/src/features/canvas/CanvasPage.tsx) 只接受 canonical UUID
深链，其他 `canvasId` replace 回 `/canvas`；[CanvasRuntimeContext](../../frontend/src/features/canvas/CanvasRuntimeContext.tsx)
管理 query、Snapshot、controller、viewport、selection、upload 与 Thread dock。
[CanvasStage](../../frontend/src/features/canvas/CanvasStage.tsx) 承载 React Flow 与图
投影，[CanvasToolRail](../../frontend/src/features/canvas/CanvasToolRail.tsx) 与
[CanvasContextMenu](../../frontend/src/features/canvas/CanvasContextMenu.tsx) 负责
node、link、group、Function 编辑，
[CanvasGenerationPanel](../../frontend/src/features/canvas/CanvasGenerationPanel.tsx) 负责
Function 参数与 run/cancel，[nodes/](../../frontend/src/features/canvas/nodes/) 渲染
Text/Image/Video/Audio Resource node。Agent dock 复用 Bound Thread Pane 语义，不把
Thread 写进 Canvas graph。

### ComfyUI 与 Settings

[ComfyuiPage](../../frontend/src/features/comfyui/ComfyuiPage.tsx) 通过
[ComfyuiRuntime](../../frontend/src/features/comfyui/ComfyuiRuntime.tsx) 提供 workflow
list/edit 与 input binding 校验；run 有 `submit`、`refresh`、`cancel` 三种
pending operation，poll interval `1500ms`，polling 集合是 `pending`/`in_progress`/
`running`，terminal 集合覆盖 `succeeded`/`failed`/`cancelled`/`interrupted` 等写法；
文件输入先走 Storage reserve/直传/complete，再以 `blobId + filename` 提交。workflow
editor/delete 是 ExtensionHost dialog contribution。

[SettingsPage](../../frontend/src/features/settings/SettingsPage.tsx) 的 General tab
只保存浏览器偏好（`kkstudio.browser-preferences.v1`），不写入 `/api/settings`；server
tabs 完全由 `GET /api/settings/schema` 的 sections、groups、fields 和 label keys 决定。
[SystemSettingsSchemaRenderer](../../frontend/src/features/settings/SystemSettingsSchemaRenderer.tsx)
依据 `BOOLEAN`、`INTEGER`、`LONG`、`TEXT`、`ENUM`、`PERMISSION`、`MODEL_SELECTION` 选择
控件，permission 有 `allow`/`ask`/`deny`，apply timing 有 `NEXT_INVOCATION`、
`NEXT_CHAT`、`RESTART`；editor 以完整 aggregate + `expectedVersion` PUT，`409` 交给
ConflictPresenter。tablist 支持 ArrowLeft/ArrowRight/Home/End。

## 设计系统与共享层

[styles.css](../../frontend/src/styles.css) 的 `:root` 是全局 token 的唯一 owner，
Canvas 专属样式由 [canvas.css](../../frontend/src/features/canvas/canvas.css) 拥有；
新组件复用 token，不在 feature 之间复制全局 token inventory。

组件边界按职责划分：`AppShell` 只拥有 topbar、主导航、immersive route 与 Escape
优先级；Workbench 只拥有 route/slot/contribution composition；feature page 拥有
自己的 controller、query key、domain projection、业务 mutation 和 feature CSS，并通过
shared UI 传入数据与 callback；[shared/ui/console](../../frontend/src/shared/ui/console)、
[shared/ui/markdown](../../frontend/src/shared/ui/markdown)、
[shared/ui/media](../../frontend/src/shared/ui/media) 只提供通用 primitive 与内容渲染。

- [i18n](../../frontend/src/shared/i18n/index.ts) 支持 `zh-CN` 与 `en-US`，locale
  存在 `kk-studio.locale`，默认 `en-US`，`setLocale` 同步 `document.documentElement.lang`；
  catalog 按 `platform`、`ai`、`canvas`、`comfyui`、`settings`、`shared`、`shortcuts`
  分区。
- [ConflictPresenter](../../frontend/src/shared/conflict/ConflictPresenter.tsx) 统一
  展示 `409` 的 `errors.reason`/`code` 与 detail；controller 成功后 invalidate 目标
  query，冲突时保留 refresh/retry/close。
- [shortcuts](../../frontend/src/shared/shortcuts/shortcut-catalog.ts) 只列出已实现
  的 Application、Thread、Debug、Canvas 快捷键；modal、alertdialog 和 lightbox 通过
  [blocking-overlay.ts](../../frontend/src/shared/ui/blocking-overlay.ts) 优先消费
  Escape 与全局快捷键。

## 测试

| 层级 | 覆盖 |
| --- | --- |
| [Bootstrap](../../frontend/src/app/)、[platform](../../frontend/src/platform/) | App redirect、AppShell immersive route 与 Escape、ExtensionHost registry、Workbench slot |
| [AI](../../frontend/src/features/ai/) | catalog form/normalizer、Pane target/layout、Composer 与附件上传、command batch、Thread timeline、snapshot/realtime、stop/approval/task |
| [Projects](../../frontend/src/features/projects/) | list/detail、CAS、Issue Board/actions、Coordinator conversation、全局 invalidation |
| [Canvas](../../frontend/src/features/canvas/__tests__/) | page/editor/stage、controller、command queue、entity patch、version events、transform batch、upload、nodes、Function run |
| [ComfyUI](../../frontend/src/features/comfyui/) | workflow 校验、page/card/panel/editor、run lifecycle、modal |
| [Settings](../../frontend/src/features/settings/) | schema renderer/validation、draft、permission、browser preference、server CAS |
| [Shared](../../frontend/src/shared/) | API client/service/codec、application-event protocol/manager、i18n、conflict、shortcuts、blocking overlay、Markdown/media |
| E2E support | [test-support/](../../frontend/src/test-support/)、[test-setup.ts](../../frontend/src/test-setup.ts) |

Vitest 使用 jsdom，[test-setup.ts](../../frontend/src/test-setup.ts) 在每个测试前清理
localStorage、固定 `zh-CN`，并为 ResizeObserver、DOMMatrix、SVG geometry、Canvas 2D、
dialog、scrollIntoView 与 React Flow layout 提供确定性 stub。测试文件按 feature 路径
与实现同目录组织，Canvas 集成测试位于
[features/canvas/\_\_tests\_\_](../../frontend/src/features/canvas/__tests__/)。
coverage threshold 是 lines/functions/branches/statements 各 `80`。

关键测试入口：

- [`App.test.tsx`](../../frontend/src/app/App.test.tsx)、
  [`platform-invalidation.integration.test.tsx`](../../frontend/src/app/platform-invalidation.integration.test.tsx)、
  [`ExtensionHost.test.ts`](../../frontend/src/platform/extensions/ExtensionHost.test.ts)。
- [`useHarnessThreadRealtime.test.tsx`](../../frontend/src/features/ai/runtime/useHarnessThreadRealtime.test.tsx)、
  [`thread-notifications.test.ts`](../../frontend/src/features/ai/runtime/thread-notifications.test.ts)、
  [`thread-events.test.ts`](../../frontend/src/features/ai/runtime/thread-events.test.ts)。
- [`useCanvasController`](../../frontend/src/features/canvas/useCanvasController.ts) 所在的
  [`features/canvas/__tests__`](../../frontend/src/features/canvas/__tests__/)、
  [`useProjectsInvalidation.test.ts`](../../frontend/src/features/projects/useProjectsInvalidation.test.ts)、
  [`system-settings-schema-renderer.test.tsx`](../../frontend/src/features/settings/system-settings-schema-renderer.test.tsx)。
- [`client.test.ts`](../../frontend/src/shared/api/client.test.ts) 与
  [`shared/app-events/__tests__`](../../frontend/src/shared/app-events/__tests__)。

构建、lint、coverage 和 E2E 入口统一见
[开发与测试](../operations/development-and-testing.md)；本模块只定义浏览器侧覆盖边界
与测试基座。

---

上级：[系统设计](../system-design.md)。相关文档：[Share](share.md)、
[Canvas Core](canvas-core.md)、[Web](web.md)、
[开发与测试](../operations/development-and-testing.md)。
