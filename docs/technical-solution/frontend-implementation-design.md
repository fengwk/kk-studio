# 前端落地设计

本文描述 `frontend/` 当前的 Chat 工作区、Pane、Thread snapshot、命令 batch、replay 身份与 transcript 投影。运行时类型与持久化语义见 [harness-runtime-architecture.md](harness-runtime-architecture.md)。

## 1. 前端摘要

| 主题 | 当前实现 |
| --- | --- |
| 工程 | 独立 Vite/TypeScript 工程，发布时由 Maven 嵌入 `web` |
| 服务端状态 | React Query |
| 本地状态 | `localStorage` 中按 Chat 保存的八个 Pane 槽位、全局 Locale 与 Thread UI 偏好 |
| Catalog API | `/api/ai/catalog/providers`、`/models`、`/agents`、`/tools` |
| Chat API | `/api/ai/chat` |
| Runtime API | `/api/ai/runtime/threads/{threadId}` 的 `snapshot` / `commands` / `head` / `stop` / `tool-invocations/{id}/approval` / `events/stream` |
| Realtime | REST snapshot first；durable revision SSE + 无 id 的 Redis realtime overlay |
| 浏览器路由 | `BrowserRouter`，服务端对 SPA 路径回退 `index.html` |
| 视觉规范 | [前端设计规范](../product-design/frontend-design-system.md) |

## 2. Chat defaults 与 Pane BranchDraft

Chat DTO 保存唯一的可见发送设置：

```ts
interface ChatDTO {
  id: string
  title: string | null
  agentName: string
  environmentName: string | null
  yoloEnabled: boolean
  version: string
}
```

Chat 编辑器更新 `agentName`、`environmentName`、`yoloEnabled` 时携带 `expectedVersion`，pending 期间锁定该 Chat 的发送。Pane 本地维护完整 `BranchDraft`：

```ts
interface BranchDraft {
  environmentName: string | null   // canonical 路由名称
  agentName: string
  model: { providerName, modelName, variant }
  activeTools: string[]
  yoloEnabled: boolean
}
```

- **Blank pane**：`frozenDraft` 是 Chat defaults 经 Catalog 首次可解析值物化的不可变副本（`initialFrozenDraft` 基线）；agent/env/yolo 编辑标记 dirty，后续 Chat/Catalog refetch 不静默改写。**activeTools 由 Agent 能力配置派生**（`activeToolsFromAgent`）：`config.tools` + skills 非空时的内部 `load_skill` + subagents 非空时的内部 `task`——内部工具不可直接选择，但作为派生值进入 branch settings。
- **Bound pane**：`branchState` 从 Thread snapshot 的 `branchSettings` 初始化（base）；queued SET_* 命令投影出 `effectiveBase`，dirty = `effectiveBase` 与用户 `draft` 不等；durable base 跟随 snapshot，用户 draft 不被覆盖。

## 3. Blank first send

空 Pane 首发的唯一顺序（`performBlankPaneFirstSend`）：

```text
POST /api/ai/chat/{chatId}/threads
  -> 原子创建 Session + ROOT（完整 BranchSettings）+ Thread，返回 snapshot
POST /api/ai/runtime/threads/{threadId}/commands
  -> USER_MESSAGE-only batch（expectedHeadEntryId + expectedNextCommandSequence 来自创建返回）
把 threadId 写入 Pane
```

失败恢复（`FirstSendMessageError` 携带 snapshot/plan/cause）：

- **非 409（网络/不确定）**：绑定已创建 Thread，恢复 composer 文本，并把 exact plan 交给 controller `replayRef`（byte-for-byte 重放，同 command id + 原始 cursors）。
- **known 409**：服务端明确未接受 stale batch。仍绑定已创建 Thread、恢复 composer 文本、invalidate/refetch 新 snapshot；**不**设置 replay——下一次 submit 基于新 snapshot 构造 fresh cursors + fresh command IDs。

`ChatWorkspacePane` 的 recovery state 为 `{threadId, content, replay?}`；BoundThreadPane 接收独立 `initialDraft` 与可选 `initialReplay`，controller 以 `initialDraft` 恢复文本、仅在 `initialReplay` 存在时设置 `replayRef`；Chat 切换/Thread 切换清理 recovery。

## 4. Bound 发送：固定 diff 顺序与 CAS

每次发送由 `buildMessageBatchPlan` 构造：

```text
SET_* diff（固定顺序 SET_ENVIRONMENT -> SET_AGENT -> SET_MODEL ->
           SET_ACTIVE_TOOLS -> SET_YOLO）
+ USER_MESSAGE（不携带 role）
```

- batch 的 `expectedHeadEntryId` / `expectedNextCommandSequence` 来自最新 snapshot Thread DTO。
- `USER_MESSAGE` 之外的命令携带 pane 本地 draft 的对应字段（diff 相对 `effectiveBase`，避免重发 in-flight 设置）。
- 服务端 202 只表示已接受；queued 命令由 ThreadProcessor 收割，前端以 snapshot 轮询/SSE 投影。

### 共享 Attachment Pill Composer

Blank Chat、Bound Thread 与 Canvas Chat 共用唯一 `ThreadComposer`：

- 草稿是 ordered `TEXT/ATTACHMENT` parts；`contenteditable=false` pill 在 DOM 仅保存
  `data-part-id`、`data-upload-id`、`data-filename`，展示为 `[name](upload)`；
- 左侧 `+` 直接打开命令表，不向草稿写入 `/`；slash 输入仍复用同一命令过滤与执行状态机；
  `/upload` 由 Composer 本地消费并点击 `display:none` 的原生 file input；
- editor 收到含文件的 paste 时从 `clipboardData.files` 或 `items[].getAsFile()` 取文件并走同一上传链路；
  纯文本 paste 仍只插入 `text/plain`；
- 上传状态只用紧凑 Markdown 引用显示，不创建独立媒体 tile；发送前必须全部 READY，
  payload 中 attachment 的客户端 localId 才解析为服务端 uploadId。

### Composer interaction slot

每个 Pane 的输入区只有一个可交互槽位：

```text
Composer(active)
  -- open selector/operation -->
ThreadInteractionPanel(active) + Composer(hidden but mounted)
  -- Escape/select/cancel -->
Composer(active, focus + caret restored)
```

- Agent、Environment、Chat-scoped Thread 使用统一 `SelectionPanel`；面板挂在 transcript 与
  footer 之间，不创建 backdrop，不使用 modal。
- 面板打开后搜索框立即获得焦点；普通字符直接过滤，`↑/↓` 移动高亮项，`Enter` 确认，
  `Esc` 返回 Composer。Thread picker 的控制行提供“最近更新/创建时间”，`Tab` 可循环切换。
- `/tree` 使用同一 `ThreadInteractionPanel` shell，保留记录过滤、搜索、树列表和确认区；
  搜索框自动聚焦，方向键移动分支，Enter 重定位，Esc 返回。
- Composer 与 interaction panel 在视觉、焦点和键盘事件上互斥；Composer 仅设置
  `hidden` 而不卸载，因此本地 draft、附件上传注册表与失败恢复身份不会丢失。
- interaction panel 打开时仅保留全局 Working 状态；queued 输入与 Task widgets 暂时隐藏，
  footer 继续展示。破坏性丢弃确认仍使用 alertdialog，取消后返回原 interaction panel。

### Conversation/Event 互斥主视图与只读面板

Bound 场景提供 `/events`、`/conversation`；全部 4 个 Composer 场景（`chat-blank` /
`chat-bound` / `canvas-blank` / `canvas-bound`）提供 `/shortcuts`；不再提供始终禁用的
`/session`。命令表由单一 `threadCommandsForScene(scene)` 投影
（`THREAD_COMMANDS` + `SCENE_AVAILABILITY: Record<ThreadCommandId, ThreadCommandScene[]>`，
`ThreadCommandId` 为字面量联合类型），Chat Bound / Canvas Bound / Blank / Canvas Blank
不再各自维护命令表副本；Canvas Bound 只投影 `stop/upload/events/conversation/shortcuts`
（controller 仅支持 stop，其余经 Composer 处理）。当前激活视图的切换命令由
`threadCommandsForActiveView(commands, mode)` 置为 `disabled`（保持可见）。

```text
ThreadPanelMainMode = 'conversation' | 'events'
mainView?.events ?? ThreadConversationView   # 互斥：任一时刻只有一个主滚动区
```

- Pane/Thread 级共享状态统一由 `useThreadPanelViewState(threadId, transcriptBodyRef, events)`
  持有：`mode` / `selectedEventId` / `activeEventId` / `eventsBodyRef` / 双 scrollTop
  （conversation + events 各一份，内存保存，不用 localStorage），每 Pane 一个实例，
  Chat Bound 与 Canvas Bound 复用。threadId 重绑全部重置回 conversation
  （mode/selected/active/scroll 清零）；切回 conversation 关闭 detail，Event active
  保留（跨视图恢复）；selected id 从列表消失即清空，active id 消失回到最新事件。
- 切换只替换主滚动区：Composer、queue、working、widgets 保持挂载，本地 draft 不丢。
  切换前捕获当前主视图位置，目标视图**把保存位置作为 mount `initialScrollTop` 传入**
  （conversation 经 `ThreadConversationView`、events 经 `ThreadEventView`），由视图内部
  `useChatTranscriptAutoScroll` 挂载时应用并按 210px 阈值决定 stick——不靠父 effect
  对新 ref 派发假 scroll；Thread 重绑清空位置并回到贴底。Conversation 与 Event 视图各自
  拥有独立的 stick 生命周期：视图卸载即销毁 scroll listener/ResizeObserver，重新挂载时
  重新绑定（`useAgentThreadController` 不常驻自动贴底 hook）。
- `/events` 打开 `ThreadEventView`（listbox/option，紧凑行布局：固定时间列 + kind badge +
  单行 summary ellipsis；failed 行 danger 色、running 行 pulse dot）：`↑/↓`、`Home/End`、
  `PageUp/PageDown` 移动 active（初始为最新事件，Page 步长 8），`Enter/Space` 打开详情，
  `Esc` 在详情打开时关闭详情、否则透传给全局 Escape（恢复 Composer 焦点）；鼠标只有
  `mousemove` 改变 active，click 同时设为 active 并选择详情。
- 事件详情是**只读 widget**（`ThreadEventDetail`，位于 widget zone 第一项、TaskStatus
  之前，ThreadWidgetStack、Composer 上方），不是 InteractionPanel：不隐藏 Composer、
  不抢焦点、无 backdrop、无 auto focus、无 Copy；展示结构化 label/value 与 durable
  Entry 原始 payload JSON；X 按钮与详情内 `Esc` 关闭。详情内容按事件 id 跟随最新投影
  （同 id 更新内容、id 消失关闭详情）。widget zone 高度契约冻结为
  `max-height: min(36vh, 320px)`（styles.css，有契约测试）。
- 事件投影是独立模型 `ThreadEventRecord { id, source, entryId, turnStartEntryId,
  turnNumber, kind, status, title, summary, createdAt, details, rawJson }`：
  `buildThreadEventTimeline({entries, modelInvocation, toolInvocations,
  modelAttemptFailures, modelStream, toolStreams})` 线性扫描 entries 计算
  turnNumber/turnStartEntryId（TURN_START 之前为 0/null），15 个 kind 与 5 态 status
  （`pending/running/completed/failed/stopped`）精确映射，i18n 用 `kind.*` / `status.*`
  key；每个 durable Entry **恰好一条记录**（不隐藏 TURN_START/TURN_END/COMPACTION；
  MESSAGE 按 role 分类：USER→USER_MESSAGE，ASSISTANT 含 tool_call→TOOL_CALL 否则
  ASSISTANT_MESSAGE，TOOL→TOOL_RESULT，SYSTEM/未知→CUSTOM_MESSAGE，CUSTOM→CUSTOM，
  未知 entryType→CUSTOM 且标题携带原类型）；活跃 model/tool invocation 与 attempt
  failure 是单条 synthetic 记录（Provider delta token 绝不逐条成行），锚定在所属
  durable Entry 之后，找不到锚点追加到末尾（synthetic 的 rawJson 为 null）。
  活跃 overlay 与 durable Entry 重叠窗口（`ModelTerminalPending`/`ToolTerminalPending`）
  按 **Turn 内**身份去重（沿 entries 线性路径跟踪当前 `TURN_START.entryId`）：
  failure 用 `turnStartEntryId + attempt + sequence`，tool 只认已物化的 durable
  `tool_result`（或 DTO `resultEntryId` 已指向已存在 Entry），身份为
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
  `SHORTCUT_CATALOG`：Application / Thread / Events / Canvas，只收录已实现快捷键，每个
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
| Blank `paneDirty` | composer 文本非空 / pendingContent 存在 / frozenDraft 相对 initialFrozenDraft 不等 |
| Blank `panePending` | first-send HTTP in-flight（阻塞 `/thread`） |
| Bound `paneDirty` | branch draft dirty 或 composer 文本非空 |
| Bound `panePending` | queued commands 非空 / controller.pending / rebind pending / stop pending / stop replay pending / approval pending / replay pending |

`/thread`、`/new`、`/tree` 切换在 `panePending` 时拒绝，在 `paneDirty` 时要求确认丢弃草稿；replay/stop-replay pending 时切换会静默丢弃 exact retry，因此同样被 `panePending` 阻止。202 清空 composer 是既定语义（accepted 消息不再留在输入框）。

## 7. 同 Session relocation（/tree）

`/tree` 只允许逻辑静止 Thread（IDLE/CONTINUATION_DUE 且无 panePending）：`PUT /head` 携带 `targetEntryId` + `expectedRevision`；服务端约束同 Session、无 queued、无 live/terminal context、不能指向 continueModel TURN_END。成功后重新初始化 branch draft 与 composer 文本（USER/CUSTOM 来源 Entry 恢复可编辑文本）。

## 8. Approval / Stop 身份

- **Approval**：同一 `(invocationId, decision)` 复用同一 `decisionId`；切换 ALLOW↔DENY mint 新 ID；输入 `ALLOW`/`DENY`，durable 值 `ALLOWED`/`DENIED`；成功后 invalidate snapshot + chats。
- **Stop**：失败后保留完整 `PendingStopOperation {stopRequestId, expectedRevision, basisHeadEntryId, basisRevision}`。重试发送**完全相同** body（同 ID + 原始 expectedRevision，绝不从新 snapshot 重推导）；成功/已知 409 清空；网络失败保留并暴露 `stopReplayPending`；**同步 basis fence**：`stopThread` 在复用前比较当前渲染 thread 的 headEntryId/revision 与 basis，任一不同立即 retire 并 mint 新 ID + 当前 expectedRevision（不依赖被动 effect）；权威 snapshot 证明 basis 变化时 effect 同样 retire。

## 9. Snapshot-first realtime / gap / terminal / duplicate

`useHarnessThreadRealtime`（`threads.snapshot(threadId)` 是唯一业务 query key）：

1. 读取 snapshot；用 `revision` 创建 EventSource；revision/resync 事件只 invalidate snapshot。
   订阅状态过渡（`subscription` 从 null 初始化、或 threadId 刚切换）**不清空**
   snapshot-seeded overlay：只有 Thread 消失或订阅真正禁用（`!threadId || !subscriptionReady`）
   才清空，因此 snapshot 首次就含 terminal-pending tool result 时，overlay 在
   EventSource 建立前后都保持可见（有回归测试）。
2. Redis `realtime` 事件叠加流式 overlay：MODEL_DELTA 按 invocation+attempt+sequence 严格推进，TOOL_PARTIAL 按 `createdAt|canonical payload` 指纹去重（FIFO 有界，attempt 变化/terminal/resultEntryId/消失时清空）。
3. **Attempt visibility**：snapshot `modelAttemptFailures` 按 attempt 排序并以 `(modelInvocationId,attempt)` 去重，先于当前 Model overlay 投影；`sequence` 必须是 canonical 非负 `DecimalLong`，malformed item fail closed 且不能 fence 当前输出。同 identity 的 durable failure 到达后抑制 stale realtime overlay；只有 invocation 仍处于同 attempt 的 READY/DISPATCHING 时显示活动倒计时，下一 attempt 已 RUNNING 后改为静态“已安排重试”。
4. **Durable recovery**：root-to-head `MODEL_ATTEMPT_FAILURE` 与 terminal `ASSISTANT_ERROR.attempt` 都投影为同一 failure block，保留原始 text/thinking、具体 error 与 retry 状态；error 与 partial 分开渲染。纯空白 text/thinking 是合法用户可见内容，解析、渲染与复制都不得 trim。retryable failure 仍是 pending，不触发错误/完成浏览器通知。
5. **Gap recovery**：缺失 sequence 触发 `useGapRecoveryLoop`——单飞、指数退避（200→2000ms、最多 8 次）refetch snapshot；immutable per-recovery token 防旧 Thread tick 干扰新 Thread；refetch 失败继续退避不冻结；caught-up/stale/terminal 停止。
6. **Terminal fence**：`resultJson`/`errorJson`/`resultEntryId` 是 durable 边界——late MODEL_DELTA 被拒绝；snapshot reconcile 中 `status:'done'|'error'` 的 durable projection **无条件**压过更高 sequence 的 Redis overlay；`resultEntryId` 落地后 overlay 移除。terminal error 从 checkpoint 提取 partial、从 `errorJson` 提取 code/message；CANCELLED + partial 与 durable `ASSISTANT_ABORTED` 一致，timeline 绝不把 terminal projection 标为 streaming。
7. TOOL_PARTIAL 永不携带 Resource；Tool overlay 投影按 `(assistantEntryId, ordinal)` durable identity + toolCallId 一致性匹配，禁止 first-candidate fallback；`task.status` 心跳是**完整 JSON 快照**（非 delta），顶层状态与扁平 `descendants` 一起进入规范化指纹并整帧替换、语义去重，绝不追加/合并——同一子 Thread 只保留最新一帧。
8. **Compaction suppression**：Runtime 不发布 compaction Redis MODEL_DELTA，也不向 snapshot/Entry transcript 暴露 compaction retry failure；timeline 按 root-to-head Entry 顺序识别 `TURN_START(reason=COMPACTION)...TURN_END`，整个内部 turn（COMPACTION/ASSISTANT_ERROR/ASSISTANT_ABORTED）不进入 transcript；最新 turn 是 COMPACTION 时，持久 checkpoint overlay 同样不渲染。Session Tree 的 all 视图仍保留这些 durable 审计节点。

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
- **浏览器通知**（`useThreadNotifications`，best-effort UI 副作用）：汇总父 Thread、直接子 task 与 descendant relay 的**全部待决审批**（身份含实际目标 Thread）；父/子 Agent 完成与错误通知在 working 结束后触发，deny 后 5 秒内抑制 completion；不建立第二套 Session 状态。
- **Thread UI 偏好**：`localStorage` 键 `kkstudio.ai.thread-ui-preferences.v1` 控制任务状态 widget / 通知 footer 的开关（默认任务状态开、通知关），是浏览器本地偏好，**不是 durable runtime 状态**。

## 13. 前端目录

```text
frontend/src
├── app/
├── platform/
├── features/ai/
│   ├── catalog/
│   ├── chat/            # ChatWorkspacePane / BlankComposerPane / BoundThreadPane / command-batch-plan
│   ├── environment/
│   └── runtime/         # useAgentThreadController / useHarnessThreadRealtime / thread-timeline / thread-events / thread-panel（transcript、event view、shortcuts、composer）/ task-status / thread-notifications / thread-ui-preferences
├── features/canvas/
├── shared/api/
│   ├── contracts/ai-catalog.ts
│   ├── contracts/ai-chat.ts
│   ├── contracts/ai-runtime.ts
│   ├── chat-service.ts
│   └── harness-service.ts
├── shared/i18n/
└── styles.css
```

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

前端 API 契约重点覆盖名称身份、Model ref、命令 batch 严格 wire、CAS、exact replay 与 409 rebuild、approval/stop 身份、snapshot-first SSE、attempt failure 的 active/durable/terminal 投影、stale overlay fence、whitespace 保真与 terminal 投影；task 呈现契约（`task.status` 心跳解析/规范化去重、`<task>` envelope 解析、renderer 分发、TaskStatusWidget 聚合与子审批转发、浏览器通知、UI 偏好）由前端单测覆盖，不依赖真实付费模型。
