# 前端落地设计

本文描述 `frontend/` 当前的 Chat 工作区、Pane、Thread snapshot、命令 batch、replay 身份、transcript 投影与设置页。运行时类型与持久化语义见 [harness-runtime-architecture.md](harness-runtime-architecture.md)，全局配置契约见 [system-settings.md](system-settings.md)。

## 1. 前端摘要

| 主题 | 当前实现 |
| --- | --- |
| 工程 | 独立 Vite/TypeScript 工程，发布时由 Maven 嵌入 `web` |
| 服务端状态 | React Query；`/api/settings` 使用完整聚合 GET/PUT + `expectedVersion` CAS |
| 本地状态 | `localStorage` 中按 Chat 保存的八个 Pane 槽位、全局 Locale 与浏览器偏好（`kkstudio.browser-preferences.v1`） |
| Catalog API | `/api/ai/catalog/providers`、`/models`、`/agents`、`/tools` |
| Chat API | `/api/ai/chat` |
| Runtime API | `/api/ai/runtime/threads/{threadId}` 的 `snapshot` / `system-prompt` / `compact` / `yolo` / `stop` / `tool-invocations/{id}/approval`；`/api/ai/runtime/sessions/{sessionId}` 的 `threads` / `entries`；`/api/ai/runtime/command-batches`；WebSocket `/api/events/v1` 订阅（见 [application-event-channel.md](application-event-channel.md)） |
| Realtime | REST snapshot first；应用事件 WebSocket（durable `version`/`resync` + 无 cursor 的 PostgreSQL realtime notification） |
| 浏览器路由 | `BrowserRouter`，服务端对 SPA 路径回退 `index.html` |
| 视觉规范 | [前端设计规范](../product-design/frontend-design-system.md) |

## 2. Chat defaults 与 Pane BranchDraft

Chat DTO 保存唯一的可见发送设置（`environment` 为完整 `{name, workspacePath}` 或 null，原子语义见 [environment-workspace-binding.md](environment-workspace-binding.md)）：

```ts
interface ChatDTO {
  id: string
  title: string | null
  agentName: string
  environment: { name: string; workspacePath: string } | null   // 完整 binding；null 显式发射
  yoloEnabled: boolean
  version: string
}
```

Chat 编辑器更新 `agentName`、`environment`、`yoloEnabled` 时携带 `expectedVersion`，pending 期间锁定该 Chat 的发送。Pane 本地维护完整 `BranchDraft`：

```ts
interface BranchDraft {
  environment: { name: string; workspacePath: string } | null   // 完整 binding；workspacePath 为 canonical 相对 wire 路径
  agentName: string
  model: { providerName, modelName, variant }
  activeTools: string[]
  yoloEnabled: boolean
}
```

- **Draft pane**：`frozenDraft` 是 Chat defaults 经 Catalog 首次可解析值物化的不可变副本（`initialFrozenDraft` 基线）；agent/environment/model/variant/yolo 编辑标记 dirty，后续 Chat/Catalog refetch 不静默改写。**activeTools 由 Agent 能力配置派生**（`activeToolsFromAgent`）：`config.tools` + skills 非空时的内部 `load_skill` + subagents 非空时的内部 `task`——内部工具不可直接选择，但作为派生值进入 branch settings。
- **Bound pane**：`branchState` 从 Thread snapshot 的 `branchSettings` 初始化（base）；queued SET_* 命令投影出 `effectiveBase`，首次挂载时 draft 采用该 pending target，避免刷新后反向发送设置；后续 dirty = `effectiveBase` 与用户 `draft` 不等，durable base 跟随 snapshot，用户 draft 不被覆盖。Chat 与 Canvas Bound 共用该语义。

## 3. Draft first send

draft target（`NEW_SESSION_DRAFT` / `ENTRY_DRAFT`）的提交路径：

```text
submit -> buildAcceptanceRequest（构造 FrozenCommandBatchRequest，含完整 target/request/
         branchDraft/composerParts，网络调用前冻结）
-> useAgentPaneController 写入 pane-scoped PendingAcceptance sidecar
-> agentPaneService.acceptCommandBatch（POST /api/ai/runtime/command-batches）
  -> NEW_SESSION target 原子创建 Session + ROOT（完整 BranchSettings）+ Thread
     + owner relation + Commands + Work，返回权威投影
-> 成功才把 PaneTarget 切换为 BOUND_THREAD（写入 threadId）
```

失败恢复：

- **unknown（非明确 4xx）**：保留 frozen `PendingAcceptance`（frozen request + composerParts + branchDraft）与当前编辑内容，`retryAcceptance` 以同一 frozen request exact retry（同 command id + 原始 cursors）；只收到权威成功响应才切换 `BOUND_THREAD`。
- **definite 4xx**：服务端明确未接受 batch。清 `PendingAcceptance` sidecar、prepend frozen `composerParts` 到当前草稿、保留当前 `BranchDraft`；409/404 时 invalidate/refetch 新 snapshot，下一次 submit 基于新 snapshot 构造 fresh cursors + fresh command IDs。

Create Chat 的默认 Environment 使用 `EnvironmentWorkspacePanel` 两阶段选择：先选 READY Environment，再浏览并明确确认当前 Workspace；只有确认后才保存完整 `{name, workspacePath}`，不把选择 Environment 静默折叠为 `workspacePath:'.'`。

## 4. Bound 发送：固定 diff 顺序与 CAS

每次发送由 `buildMessageBatchPlan` 构造：

```text
SET_* diff（固定顺序 SET_ENVIRONMENT -> SET_AGENT -> SET_MODEL ->
           SET_ACTIVE_TOOLS）
+ USER_MESSAGE（不携带 role）
```
YOLO 是直接控制面（`PUT /yolo`，基于 snapshot version 的 CAS）：Bound pane 选择 YOLO 时
乐观更新 draft 并立即 PUT，成功只对齐 base+draft 的 yolo、失败回滚并暴露错误；绝不进入命令 batch。

- batch 的 `expectedHeadEntryId` / `expectedNextCommandSequence` 来自最新 snapshot Thread DTO（THREAD target）。
- `USER_MESSAGE` 之外的命令携带 pane 本地 draft 的对应字段（diff 相对 `effectiveBase`，避免重发 in-flight 设置）。
- 服务端 202 返回权威 `session/rootEntry/thread/acceptedCommands/replayed`；queued 命令由 ThreadProcessor 收割，前端以 snapshot/事件通道投影。

### 共享 Attachment Pill Composer

Chat/Canvas 的 Draft 与 Bound target 共用唯一 `ThreadComposer`：

- DOM 固定分为上层输入/附件行与下层控制栏：`[+] [Default|YOLO] ... [provider/model · variant] [发送]`；空草稿输入行默认单行且文字垂直居中，内容增长后在上限内滚动；Permission 与 Model/Variant 常驻可见，Footer 不承担设置入口；
- Permission 是 anchored listbox，仅有 `Default` / `YOLO`；Model 使用 anchored 两级 listbox（provider/model → Variant），不创建 modal/backdrop；Model/Variant 选择只修改 pane-local draft，随下一条消息进入同一 SET_* batch；Permission（YOLO）选择经直接控制面立即 `PUT /yolo`（见 §4），绝不进入命令 batch；
- 草稿是 ordered `TEXT/ATTACHMENT/RESOURCE` parts；`contenteditable=false` pill 在 DOM 保存
  `data-part-id`、`data-part-type` 与 attachment/resource 对应引用字段，展示为完整 `[name]`。RESOURCE 是当前 Session 已拥有的 durable blob ref，可在 Stop 恢复后重新提交；ATTACHMENT 仍由当前页面上传注册表管理；
- 左侧 `+` 直接打开命令表，不向草稿写入 `/`；slash 输入仍复用同一命令过滤与执行状态机；
  `/upload` 由 Composer 本地消费并点击 `display:none` 的原生 file input；
- editor 收到含文件的 paste 时从 `clipboardData.files` 或 `items[].getAsFile()` 取文件并走同一上传链路；
  纯文本 paste 仍只插入 `text/plain`；
- Composer 上方的上传注册表使用固定 200px 卡片，长文件名单行省略且 `title` 保留完整名称；图片展示可点击缩略图并复用媒体 Lightbox，视频展示可点击的暂停首帧，
  其它文件按 audio/archive/spreadsheet/code/text/file 类型展示通用图标；发送前必须全部 READY，
  payload 中 attachment 的客户端 localId 才解析为服务端 uploadId，移除或卸载时释放 object URL。

### Composer interaction slot

每个 Pane 的输入区只有一个可交互槽位：

```text
Composer(active)
  -- open selector/operation -->
ThreadInteractionPanel(active) + Composer(hidden but mounted)
  -- Escape/select/cancel -->
Composer(active, focus + caret restored)
```

- Agent、Environment、Chat Session/Thread 使用统一 `SelectionPanel`；面板挂在 transcript 与
  只读 Footer 之间，不创建 backdrop，不使用 modal。
- 面板打开后搜索框立即获得焦点；普通字符直接过滤，`↑/↓` 移动高亮项，`Enter` 确认，
  `Esc` 返回 Composer。Thread picker 的控制行提供“最近更新/创建时间”，`Tab` 可循环切换。
- `/tree` 使用同一 `ThreadInteractionPanel` shell，保留记录过滤、搜索、紧凑单行树列表和确认区；
  面板按 Pane 横向铺满；行前缀对齐 pi Session Tree，使用 `›` 当前选择、`•` 当前路径以及
  `│ / ├─ / └─` 真实分叉连接符，footer 展示当前位置与键盘提示。打开时才查询
  `sessions.entries(sessionId)`，读取完整 Session Entry Tree；普通 transcript 继续使用 snapshot 的当前 root-to-head，
  历史分支不会进入 Provider 上下文。搜索框自动聚焦，方向键移动分支，Enter 确认选择 Entry
  并切换 `ENTRY_DRAFT(sessionId,startEntryId)`（零数据库写入），Esc 返回。
- Thread 切换、创建新对话或历史重定位需要丢弃草稿时，确认框必须明确区分“输入框中未发送的消息”和“尚未随消息提交的 Agent/Environment/Model/Permission 设置”，并说明目标动作，不使用含义不明的统一提示。
- 确认 Modal 使用紧凑布局与右上角 icon-only `X`；pending 时关闭、取消与确认控件全部禁用。
- Composer 与 interaction panel 在视觉、焦点和键盘事件上互斥；Composer 仅设置
  `hidden` 而不卸载，因此本地 draft、附件上传注册表与失败恢复身份不会丢失。
- interaction panel 打开时仅保留全局 Working 状态；queued 输入与 Task widgets 暂时隐藏，
  已存在的 Footer facts 继续展示。破坏性丢弃确认仍使用 alertdialog，取消后返回原 interaction panel。

### Conversation/Debug 互斥主视图与只读面板

所有 owner/target 组合都由单一 `threadCommandsForTarget(target, manualCompaction?)` 投影命令表
（`THREAD_COMMANDS` + `TARGET_COMMANDS: Record<PaneTargetKind, ThreadCommandId[]>`，
`ThreadCommandId` 为字面量联合类型，含 `debug` 与 `compact`）。`/shortcuts` 对三种 target 可用；
`BOUND_THREAD` 额外提供 `/debug` toggle（再次执行切回 conversation）、`/stop` 与 `/compact`。
Canvas 与 Chat 复用相同矩阵，pane-local `agent/environment/yolo` 编辑走同一 controller。
`/compact` 仅 `BOUND_THREAD` 可用，
且以 snapshot `manualCompaction.available` 前置门控（不可用时以 `disabledReason` 展示原因）。`/models` 与点击
Composer 模型按钮相同，打开后自动聚焦搜索框。

```text
ThreadPanelMainMode = 'conversation' | 'debug'
mainView?.debug ?? ThreadConversationView   # 互斥：任一时刻只有一个主滚动区
```

- Pane/Thread 级共享状态统一由 `useThreadPanelViewState(threadId, transcriptBodyRef, events)`
  持有：`mode` / `selectedEventId` / `debugBodyRef` / 双 scrollTop
  （conversation + debug 各一份，内存保存，不用 localStorage），每 Pane 一个实例，
  Chat Bound 与 Canvas Bound 复用。threadId 重绑全部重置回 conversation
  （mode/selected/scroll 清零）；切回 conversation 清空选中；`selectedEventId`
  就是当前选中行，详情只在有选中时展示；id 从列表消失即清空。
- 切换只替换主滚动区：Composer、queue、working、widgets 保持挂载，本地 draft 不丢。
  切换前捕获当前主视图位置，目标视图**把保存位置作为 mount `initialScrollTop` 传入**
  （conversation 经 `ThreadConversationView`、debug 经 `ThreadEventView`），由视图内部
  `useChatTranscriptAutoScroll` 挂载时应用并按 210px 阈值决定 stick——不靠父 effect
  对新 ref 派发假 scroll；Thread 重绑清空位置并回到贴底。Conversation 与 Debug 视图各自
  拥有独立的 stick 生命周期：视图卸载即销毁 scroll listener/ResizeObserver，重新挂载时
  重新绑定（`useAgentThreadController` 不常驻自动贴底 hook）。
- `/debug` 打开 `ThreadEventView`（listbox/option，紧凑行布局：固定时间列 + kind badge +
  单行 summary ellipsis；failed 行 danger 色、running 行 pulse dot）：初始无选中；
  点击选中并打开详情；`↑/↓` 只在已选中时切换相邻行；hover / Home / End / Page /
  Enter 不改选中；`Esc` 与详情 X 取消选中。顶部固定一块最新系统提示词预览（10 行，
  超出独立滚动）：进入 `/debug` 时经 `GET /system-prompt` 现算拉取（`useSystemPromptPreview`，
  staleTime=Infinity），turn 的 working 状态开始与结束时各 refetch 一次，
  使同批 `SET_ENVIRONMENT` 等设置在模型工作期间即可反映到预览。
- 事件详情是**只读 widget**（`ThreadEventDetail`，位于 widget zone 第一项、TaskStatus
  之前，ThreadWidgetStack、Composer 上方），不是 InteractionPanel：不隐藏 Composer、
  不抢焦点、无 backdrop、无 auto focus、无 Copy；展示当前选中 event 的 pretty JSON；
  X 按钮与详情内 `Esc` 关闭。同 id 更新内容、id 消失关闭详情。widget zone 高度契约冻结为
  `max-height: min(36vh, 320px)`（styles.css，有契约测试）。
- 事件投影是独立模型 `ThreadEventRecord { id, source, entryId, turnStartEntryId,
  turnNumber, kind, status, title, summary, createdAt, details, rawJson }`：
  `buildThreadEventTimeline({entries, modelInvocation, toolInvocations,
  modelAttemptFailures, modelStream, toolStreams})` 按当前 root-to-head branch 的
  Entry 顺序线性扫描。Debug 是调试视图：durable 行标签使用真实 `entryType`
  枚举（`ROOT` / `TURN_START` / `MESSAGE` / `TURN_END` …），摘要是压缩后的
  Entry payload JSON（超出单行 `…`），点击详情展示 pretty JSON。kind 仍保留内部
  分类（MESSAGE 按 role 分成 USER_MESSAGE / TOOL_CALL 等），但不作为行标签。
  每个 durable Entry **恰好一条记录**；活跃 model/tool invocation 与 attempt
  failure 是单条 synthetic 记录（Provider delta token 绝不逐条成行），锚定在所属
  durable Entry 之后，找不到锚点追加到末尾（synthetic 的 rawJson 为 null）。
  活跃 overlay 与 durable Entry 重叠窗口（`ModelTerminalPending`/`ToolTerminalPending`）
  按 **Turn 内**身份去重（沿 entries 线性路径跟踪当前 `TURN_START.entryId`）：
  failure 用 `turnStartEntryId + attempt + sequence`，tool 只认已物化的 durable
  `tool_result`（ToolInvocation 无 resultEntryId；Tool overlay 依据 invocation 存在性
  + durable ToolResult Entry 判定），身份为
  `assistantEntryId 所在 Turn + toolCallId`——旧 Turn 的 durable 记录绝不抑制新 Turn
  相同数字/复用 toolCallId 的活跃 overlay；durable assistant `tool_call` 不抑制
  运行中的 tool invocation。活跃状态映射五态：model 优先 realtime stream error，
  tool 优先 stream error → errorJson → resultJson → 常规映射。
- Turn usage（model token 用量）从 TURN_START 时点移到 TURN_END 时点展示：
  `EntryProjectionContext` 携带 `pendingTurnSummary`，ASSISTANT 分支写入、TURN_END
  发射 `turn_usage`，TURN_START / COMPACTION 清除过期摘要——usage 展示的是已结束
  Turn 的真实用量。TURN_END 事件摘要与 transcript 的 usage 行共用
  `parseAssistantUsage` + `formatTurnUsageText`：`↑input · ↓output ·
  RcacheRead · WcacheWrite · $cost`，无 reasoning `T`/cache hit `CH` 段，缺失/零的
  cache 段省略；详情保留完整 usage（input/output/cacheRead/cacheWrite(含 long)/
  reasoning/providerTotal/cost/outcome）。
- `/shortcuts` 打开只读 `ThreadShortcutsPanel`（分组快捷键目录
  `SHORTCUT_CATALOG`：Application / Thread / Debug / Canvas scope，只收录已实现快捷键，每个
  descriptionKey 在 zh-CN / en-US 均可解析 + 统一 ThreadInteractionPanel shell）：
  不创建 backdrop，`Esc` 关闭并恢复 Composer 焦点与草稿。
- 全局键盘语义：modal / alertdialog / lightbox（`.modal-backdrop, [aria-modal],
  [role="alertdialog"], .resource-media-lightbox`）优先拦截**全部**全局快捷键（含 Canvas
  Delete/T/数字键/Fit/Zoom 等），`hasBlockingOverlay()` / `shouldDeferToBlockingOverlay()`
  统一判定，Canvas 键盘入口整体受守卫。

## 5. Ambiguous exact replay 与 409 rebuild

`replayRef` 保存 `{plan, content}`；`CommandBatchPlan.identity = {threadId, content, draft}`（**不含 effectiveBase**：queued SET_* 投影变化不改变用户意图）。

- 发送失败（网络/不确定）：composer 为空时恢复文本并保留 exact plan——重试发送**完全相同的 batch**（同 command ids/payload/order + 原始 expected cursors）；服务端 ordered command-set replay 绕过移动的 cursors。
- 编辑内容或目标 draft → identity 变化 → mint 全新 batch。
- **`STALE_COMMAND_CURSOR` + 纯 `USER_MESSAGE` + 同一分支向前推进**：直接读取权威 snapshot，保留原 command IDs/payload，仅替换 head/sequence cursor 后有界重试；用于消除快速连续发送与后台 Turn 推进之间的正常 CAS 竞争。
- **其他 known 409**：batch 未被接受 → 清 `replayRef`，恢复 draft；下一次发送基于刷新后 snapshot 重建（新 cursors + 新 command IDs）。含 `SET_*` 的 batch、旧 head 已不在当前 root-to-head 路径、未知 reason 均不自动重试。

## 6. Pane 门禁（dirty/pending）

| 状态 | 定义 |
| --- | --- |
| Draft `paneDirty` | composer 文本非空 / pendingContent 存在 / frozenDraft 相对 initialFrozenDraft 不等 |
| Draft `panePending` | first-send HTTP in-flight（阻塞 `/thread`） |
| Bound `paneDirty` | branch draft dirty 或 composer 文本非空 |
| Bound `panePending` | queued commands 非空 / controller.pending / rebind pending / stop pending / stop replay pending / approval pending / replay pending |

`/thread`、`/new`、`/tree` 切换在 `panePending` 时拒绝，在 `paneDirty` 时要求确认丢弃草稿；replay/stop-replay pending 时切换会静默丢弃 exact retry，因此同样被 `panePending` 阻止。202 accepted 后清空 composer（已接受消息不再留在输入框）。

## 7. 同 Session EntryDraft（/tree）

`/tree` 选择历史 Entry 只把 Pane 切换为 `ENTRY_DRAFT(sessionId,startEntryId)`（零数据库写入）。`agent-pane/pane-target.ts` 只持久化 `PaneTarget` 与 `PendingAcceptance` sidecar；本地 `BranchDraft` 由 `useAgentPaneController` 持有，ENTRY base 从该 Entry 的 root-to-entry path 派生。发送时以 ENTRY target 原子 materialize 新 Thread（head 指向 startEntryId，不复制 Entry、不修改任何已有 Thread）；原 Thread 保持不变。成功后切换到 `BOUND_THREAD` 并重新初始化 branch draft 与 composer 文本（USER/CUSTOM 来源 Entry 恢复可编辑文本）。

## 8. Approval / Stop 身份

- **Approval**：同一 `(invocationId, decision)` 复用同一 `decisionId`；切换 ALLOW↔DENY mint 新 ID；输入 `ALLOW`/`DENY`，durable 值 `ALLOWED`/`DENIED`；成功后 invalidate snapshot + chats。
- **Stop**：完整 `PendingStopOperation {stopRequestId, expectedVersion, basisHeadEntryId, basisVersion}` 以 per-Thread local sidecar 保存。重试发送**完全相同** body（同 ID + 原始 expectedVersion，绝不从新 snapshot 重推导）；成功/已知 409/basis 变化时清空，网络失败或强制 rebind 保留并暴露 `stopReplayPending`。HTTP 成功结果的 ordered `cancelledUserMessages {sequence,clientCommandId,messageJson}` 被转为 TEXT/RESOURCE parts，消息之间及恢复前缀与当前草稿之间固定插入两个换行；同一 stopRequestId 最多应用一次。RESOURCE 重新提交时只允许目标 Session 已有 blob ref，不重复 retain。
- **Manual Compaction**：`compactThread(threadId, {expectedVersion})` 以 snapshot 的 `version` 为 CAS 提交 `POST /{threadId}/compact`；命令入口 `/compact` 由 `manualCompaction.available` 门控（disabledReason 展示原因）；成功后 invalidate snapshot。availability 是瞬时 advisory（每次 snapshot 现算），提交成功以 expectedVersion 守护。

## 9. Snapshot-first realtime / gap / terminal / duplicate

`useHarnessThreadRealtime`（`threads.snapshot(threadId)` 是唯一 realtime 驱动的 query key；
`sessions.entries(sessionId)` 仅在 `/tree` 打开时按需查询）：

1. 读取 snapshot；经 `useApplicationEvents().subscribe({kind:'thread', id})` 订阅：`subscribed`
   （首次订阅与每次重连重订阅后都会到达）、`version`、`resync` 与资源级 `error` 都只
   invalidate snapshot。订阅状态过渡（`subscription` 从 null 初始化、或 threadId 刚切换）**不清空**
   snapshot-seeded overlay：只有 Thread 消失或订阅真正禁用（`!threadId || !subscriptionReady`）
   才清空，因此 snapshot 首次就含 terminal-pending tool result 时，overlay 在
   事件通道订阅建立前后都保持可见（有回归测试）。
2. PostgreSQL `realtime` notification 叠加流式 overlay：MODEL_DELTA 按 invocation+attempt+sequence 严格推进；`TOOL_CALL_DELTA` 按 index 累积 `id/name/argumentsJson`，在 durable assistant `tool_call` 到达前投影为 streaming tool call；TOOL_PARTIAL 按 `createdAt|canonical payload` 指纹去重（FIFO 有界，attempt 变化/terminal/resultEntryId/消失时清空）。Transcript 把同一 `toolCallId` 的 call/result 收成一张卡片：header 展示工具名、Agent 原样给出的路径和 `WORKING/DONE/FAILED` 三态，长路径允许换行且不与状态争抢空间；body 上半是 write/edit 等自定义 preview，下半是 result。折叠 preview 固定五行并可滚动，不展示剩余行数文案；展开后显示完整高度；复制只作用于这一张卡。
3. **Attempt visibility**：snapshot `modelAttemptFailures` 按 attempt 排序并以 `(modelInvocationId,attempt)` 去重，先于当前 Model overlay 投影；`sequence` 必须是 canonical 非负 `DecimalLong`，malformed item fail closed 且不能 fence 当前输出。同 identity 的 durable failure 到达后抑制 stale realtime overlay；只有 invocation 仍处于同 attempt 的 READY/DISPATCHING 时显示活动倒计时，下一 attempt 已 RUNNING 后改为静态“已安排重试”。
4. **Durable recovery**：root-to-head `MODEL_ATTEMPT_FAILURE` 与 terminal `ASSISTANT_ERROR.attempt` 都投影为同一 failure block，保留原始 text/thinking、具体 error 与 retry 状态；状态标题使用“模型请求失败”并把 attempt 显示为次级“请求 #N”，错误码与正文分离，避免整块警告/危险色高亮；error 与 partial 分开渲染。纯空白 text/thinking 是合法用户可见内容，解析、渲染与复制都不得 trim。retryable failure 仍是 pending，不触发错误/完成浏览器通知。
5. **Gap recovery**：缺失 sequence 触发 `useGapRecoveryLoop`——单飞、指数退避（200→2000ms、最多 8 次）refetch snapshot；immutable per-recovery token 防旧 Thread tick 干扰新 Thread；refetch 失败继续退避不冻结；caught-up/stale/terminal 停止。
6. **Terminal fence**：`resultJson`/`errorJson`/`resultEntryId` 是 durable 边界——late MODEL_DELTA 被拒绝；snapshot reconcile 中 `status:'done'|'error'` 的 durable projection **无条件**压过更高 sequence 的 notification overlay；`resultEntryId` 落地后 overlay 移除。terminal error 从 checkpoint 提取 partial、从 `errorJson` 提取 code/message；CANCELLED + partial 与 durable `ASSISTANT_ABORTED` 一致，timeline 绝不把 terminal projection 标为 streaming。
7. TOOL_PARTIAL 永不携带 Resource；Tool overlay 投影按 `(assistantEntryId, ordinal)` durable identity + toolCallId 一致性匹配，禁止 first-candidate fallback；`task.status` 心跳是**完整 JSON 快照**（非 delta），顶层状态与扁平 `descendants` 一起进入规范化指纹并整帧替换、语义去重，绝不追加/合并——同一子 Thread 只保留最新一帧。
8. **Compaction suppression**：Runtime 不发布 compaction realtime MODEL_DELTA，也不向 snapshot/Entry transcript 暴露 compaction retry failure；timeline 按 root-to-head Entry 顺序识别 `TURN_START(reason=COMPACTION)...TURN_END`，整个内部 turn（COMPACTION/ASSISTANT_ERROR/ASSISTANT_ABORTED）不进入 transcript；最新 turn 是 COMPACTION 时，持久 checkpoint overlay 同样不渲染。Session Tree 的 all 视图仍保留这些 durable 审计节点。

## 10. Resource 安全呈现

durable Tool/User Resource 固定为 `resource(blobId,name,preview)`，不复制 URI、mediaType、size 或
sha256。`ChatPanel` 在渲染期通过 `ResourceBlobUrlContext` 并行请求
`/api/storage/blobs/{blobId}/presigned-original|presigned-preview`：

- 原件响应的 `mediaType/sizeBytes` 是权威媒体事实；`ResourceAttachmentChip` 依此分类 image/audio/video/file，不按文件扩展名猜测；
- image 在正文内直接使用权威 original 完整展示；video 优先使用 WebP poster，poster
  缺失或加载失败时回退 original video；hover/focus 在左上角显示半透明文件名，
  点击以 original 打开 Lightbox，Escape 可关闭；
- 非媒体资源使用紧凑 `[name]` 链接；original 缺失或解析失败时只显示名称与不可用提示；
- preview 保持纯文本视口，不执行富内容；durable message 与 DOM 都不保存长期 URL。

瞬时/Invocation `ResourceRef {uri,mediaType,name,size,sha256}` 仍有兼容 renderer：

- 仅 `data:` URI 自动媒体预览；http/https 只提供显式直连链接，不触发自动 GET；
- file/s3 绝不把宿主 URI 交给浏览器，只在内容身份完整时投影到同源
  `GET /api/ai/runtime/resources/{sha256}`；未知或不完整身份只显示 fallback 标签。

## 11. Agent 能力表单

Agent 表单的 Tools/Skills/Subagents 只保存**名称集合**（DTO 三个必填列表），不保存 Environment、Tool 实例或 MCP 摘要：

- **Tools 候选**只来自固定 `GET /api/ai/catalog/tools` 的 `ToolCatalogEntryDTO[]`（内部 `load_skill`/`task` 不在其中，不可直接勾选；由 skills/subagents 非空派生激活）；Environment live `tools` 与 MCP server 摘要只是展示数据，绝不动态并入可选目录，零 live Environment 时 Tools 仍可编辑；
- **Skills 候选**来自表单内**瞬态**的「Skill 目录 Environment」选择器：只允许从当前 `ready===true` 的 live Environment 中**显式选中一个**作为浏览来源（初始为无，不自动选择），仅用于浏览该 Environment 当前发布的 skill 名称；
- **Subagents 候选**来自全局 Agent catalog（`buildSubagentCandidates`：名称 + 描述；create 模式下排除与当前 draft 同名项）；已勾选但已不存在的引用保留为可移除 orphan，提交时服务端按现存 Agent 校验（未知引用 404）；
- 该选择是组件本地瞬态状态：**不进入 AgentDraft、不随提交 DTO 持久化、不绑定 Agent**；切换 Agent / 重新打开创建编辑器时重置；
- 切换来源保留已选 skill 名称（同一 short name 在不同 Environment 中是同一个持久化名称）；来源失效（消失或 `ready===false`）后保留为禁用「不可用」选项、不显示 live 候选，已选名称作为可移除 orphan 保留。

## 12. Tool renderer 分发与 task 呈现

- **renderer 分发**：`MessageList` 对 tool 消息不做工具名 switch，一律经 ExtensionHost `toolRenderers.get(message.rendererKey)` 取组件（`rendererKey` 由后端冻结在 ToolDescriptor/TOOL MESSAGE）；未注册的 key 落到默认 `ToolMessageBlock` 呈现（完整 arguments 保持可见）。内置 `task` renderer 按 `rendererKey=task` 注册（`ai-extension.definition.ts`，与后端 `TaskTool.RENDERER_KEY` 精确一致）。
- **TaskToolRenderer**：call 阶段展示解析后的 `{subagent_type, session_id, maxTurns, prompt}`（prompt 在 `ToolOutputViewport` 中）与最新 `task.status` 状态条；result 阶段解析 `<task id state>` envelope 展示 `<task_result>`/`<task_error>`；非规范文本回退原始文本 + 错误（不丢信息）。
- **ToolOutputViewport**：完整保留 Tool 输出（全量文本仍在 DOM），默认以可滚动五行视口跟随尾部；用户向上滚动后暂停自动跟随。
- **TaskStatusWidget**（挂载在 `ThreadWidgetStack.children`）：一次 reduce 把全部活动 task 消息按 `parentTaskLevel + depth` 聚合为层级列表（同子 Thread 只保留最新帧，深度变化移层）；`waiting_approval`/含审批项的行展示子工具名称、原因与 Allow/Deny——决策经宿主 controller 转发到**子 ThreadId** 的既有 approval 端点（`harnessService.decideApproval(targetThreadId, ...)`），**replay 身份包含 `targetThreadId + invocationId + decision`**（不同子 Thread 可能复用相同 invocationId）。
- **浏览器通知**（`useThreadNotifications`，best-effort UI 副作用）：开关唯一来自
  `BrowserPreferencesProvider` 的 `kkstudio.browser-preferences.v1.notificationsEnabled`（默认 false，
  同 Tab 经 CustomEvent、跨 Tab 经 storage 事件同步，浏览器禁用存储时仍在本 Tab 生效；非法/未知版本存储
  回退默认）。开启后汇总父 Thread、直接子 task 与 descendant relay 的**全部待决审批**（身份含实际目标
  Thread）；父/子 Agent 完成与错误通知在 working 结束后触发，deny 后 5 秒内抑制 completion；不建立第二套
  Session 状态。
- **TaskStatusWidget 永久挂载**在共享 `ChatPanel` 的 widget zone（事件详情之后），因此 Chat Bound 与 Canvas Bound 无需调用方手工拼装；无活动 task 消息时组件自身返回 null，不占位，也不存在偏好开关。
- **Footer 是纯只读事实视图**（`ThreadStatusFooter` → `buildThreadStatusModel`），顺序固定为 Environment/Workspace → Git branch → Branch Usage → `used/contextWindow` → cache hit。Bound Pane 只读取当前 Thread snapshot 已生效的 Environment/context，不把尚未随下一条 batch 提交的 Composer BranchDraft 伪装成当前事实。Environment 使用完整安全 wire binding；未绑定只读展示 `none env`，已绑定展示 `env:名称 · 路径`；路径文本中间省略但 title 保留完整相对路径；不可用时只标记 unavailable，不暴露 daemon 绝对路径。Branch Usage 只聚合当前 root-to-head 已关闭 `turn_usage`（未关闭 Turn、compaction 与失败残留不计入）；usage / cache 缺失时按 `0` 展示，context 在已知 contextWindow 时按 `0/total` 展示。Footer 不含 button、右键行为、Agent、Model、Permission 或 Notification。
- **Notification 唯一设置入口**是 `/settings` 的浏览器偏好开关；Bound Thread 只消费 `BrowserPreferencesProvider` 状态执行 best-effort 通知，Footer 不再提供第二个入口。

## 13. 前端目录

```text
frontend/src
├── app/
├── platform/
├── features/ai/
│   ├── catalog/
│   ├── chat/            # ChatWorkspacePane owner wrapper / branch-draft / command-batch-plan / SelectionPanel 系交互面板
│   ├── environment/
│   └── runtime/         # AgentPane / useAgentPaneController / ChatPanel / ThreadPanel / useAgentThreadController / useHarnessThreadRealtime / thread-timeline / thread-events / task-status / thread-notifications
├── features/canvas/
├── features/settings/      # General 本地偏好（local-only）+ server schema sections 动态渲染，无固定七个手写 Tab
├── shared/api/
│   ├── contracts/ai-catalog.ts
│   ├── contracts/ai-chat.ts
│   ├── contracts/ai-runtime.ts
│   ├── chat-service.ts
│   └── harness-service.ts
├── shared/i18n/
└── styles.css
```

### 13.0 Hook 职责边界

| 模块 | 职责 |
| --- | --- |
| `useBoundBranchPanel` | Bound Thread controller、branch base/draft、queued SET_* projection、原子 message batch、Thread rebind fail-closed |
| `useBoundThreadPanelViews` | Conversation/Debug 互斥视图、system prompt preview、Event detail 与公共 Footer/transcript 投影 |
| `useComposerFocus` | focus retry、Escape、interaction takeover、pending settle 后恢复与 timer cleanup |
| `useComposerSubmissionSettle` | submitted draft、失败恢复、detached upload 挂起与释放 |
| `useFunctionConfigSync` | Function config debounce、并发 flush 去重与失败保留 |
| `useCanvasFunctionRun` | start/cancel、本地 basis-CAS 投影与 response-lost fallback |
| `useCanvasTransformBatch` | transform debounce、in-flight owner、失败恢复、显式 group decision |
| `useCanvasUploadPipeline` | hash/reserve/PUT/complete、进度、alias 生命周期与 Resource node command |
| `useCanvasController` | Canvas query/queue 与上述 hooks 的 façade 编排；不内联 timer/ref-heavy 子状态机 |

Bound Thread、Composer 与 Canvas 的新状态机必须优先在独立 hook 测试中举证，再由场景组件测试证明 wiring；禁止仅用源码字符串或行数断言代替行为验证。

### 13.1 Canvas 内容优先交互

- `CanvasStage` 是 React Flow 投影与单一右键菜单所有者；选择状态仍唯一落在
  `useCanvasController`，不复制第二套选区状态机。
- Add Menu 只创建 Resource 与 Function。Group 由单个未分组 Resource 或框选的全部
  未分组 Resource 右键创建，持久化为位于成员下方的半透明范围；通用 Header 紧贴范围
  左上方。组右键执行 `RENAME_GROUP` / `UNGROUP` / `DELETE_GROUP`。成员包围框完全离开
  Group Body 后，节点 transform 与单成员 `UNGROUP` 在同一命令批中提交；Group 与其余成员
  保留。
- Resource 节点不保留常驻编辑、删除、运行、打开或下载按钮。节点/Group 删除在
  `CanvasContextMenu` 内二次确认；Delete/Backspace 只删除选中 Link。
- `CanvasNodeContainer` 统一承载 Header 与 Body；单资源尺寸由对应 Renderer 声明，
  image/video 使用有界自适应容器，audio 使用 320×112 Body，text 使用 320×220 Body。
  多资源统一进入固定 220×160 单元的 `CanvasResourceGrid`，布局优先接近正方形并让末行
  左对齐。`resources/` 子目录按 text/audio/visual/thumbnail 拆分 Renderer。
- 图片/视频 preview 的签名 URL 加载失败时强制重新签名一次，仍失败则签名 original；
  original 本身失败时显示“资源不可用”。右键打开/下载每次都重新签名，避免复用过期 URL。
- 音频节点使用紧凑自定义播放器；文本节点默认展示 Markdown，聚焦或双击后在节点下方
  展开非模态 `CanvasTextEditor`，打开编辑器不修改持久化 transform。
- 图片/视频 Function 节点只保留媒体主体与「类型 | 节点名」透明标题；独立
  `CanvasGenerationPanel` 只编辑配置和显示状态，运行/取消由右键菜单触发。

## 14. 验证

```bash
cd frontend
npm test
npm run lint
npm run build
```

后端验证入口：

```bash
env JAVA_HOME=$JAVA_HOME_21 mvn test -B -fae      # Java 全仓
env JAVA_HOME=$JAVA_HOME_21 mvn validate           # 格式/架构（Spotless + Checkstyle）
./scripts/e2e.sh                                   # 免费 API E2E；--ui / --with-tools / --real 显式开启其余矩阵
```

E2E 矩阵与开关见 [e2e-regression.md](e2e-regression.md)；真实 Provider case 执行前硬校验 `minimax/MiniMax-M2.7`。

前端 API 契约重点覆盖名称身份、Model ref、命令 batch 严格 wire、CAS、exact replay 与 409 rebuild、approval/stop 身份、snapshot-first 事件通道、attempt failure 的 active/durable/terminal 投影、stale overlay fence、whitespace 保真与 terminal 投影；task 呈现契约（`task.status` 心跳解析/规范化去重、`<task>` envelope 解析、renderer 分发、TaskStatusWidget 聚合与子审批转发、浏览器通知与浏览器偏好）由前端单测覆盖，不依赖真实付费模型。
