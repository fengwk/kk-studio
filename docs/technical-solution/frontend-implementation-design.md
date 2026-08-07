# 前端落地设计

本文描述 `frontend/` 当前的 Chat 工作区、Pane、Thread snapshot、命令 batch、replay 身份与 transcript 投影。运行时类型与持久化语义见 [harness-runtime-architecture.md](harness-runtime-architecture.md)。

## 1. 前端摘要

| 主题 | 当前实现 |
| --- | --- |
| 工程 | 独立 Vite/TypeScript 工程，发布时由 Maven 嵌入 `web` |
| 服务端状态 | React Query |
| 本地状态 | `localStorage` 中按 Chat 保存的八个 Pane 槽位与全局 Locale |
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
  yoloEnabled: boolean
  version: string
}
```

Chat 编辑器更新这两项设置时携带 `expectedVersion`，pending 期间锁定该 Chat 的发送。Pane 本地维护完整 `BranchDraft`：

```ts
interface BranchDraft {
  environmentName: string | null   // canonical 路由名称
  agentName: string
  model: { providerName, modelName, variant }
  thinkingLevel: string
  activeTools: string[]
  yoloEnabled: boolean
}
```

- **Blank pane**：`frozenDraft` 是 Chat defaults 经 Catalog 首次可解析值物化的不可变副本（`initialFrozenDraft` 基线）；agent/env/yolo 编辑标记 dirty，后续 Chat/Catalog refetch 不静默改写。
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
           SET_THINKING_LEVEL -> SET_ACTIVE_TOOLS -> SET_YOLO）
+ USER_MESSAGE（不携带 role）
```

- batch 的 `expectedHeadEntryId` / `expectedNextCommandSequence` 来自最新 snapshot Thread DTO。
- `USER_MESSAGE` 之外的命令携带 pane 本地 draft 的对应字段（diff 相对 `effectiveBase`，避免重发 in-flight 设置）。
- 服务端 202 只表示已接受；queued 命令由 ThreadProcessor 收割，前端以 snapshot 轮询/SSE 投影。

## 5. Ambiguous exact replay 与 409 rebuild

`replayRef` 保存 `{plan, content}`；`CommandBatchPlan.identity = {threadId, content, draft}`（**不含 effectiveBase**：queued SET_* 投影变化不改变用户意图）。

- 发送失败（网络/不确定）：composer 为空时恢复文本并保留 exact plan——重试发送**完全相同的 batch**（同 command ids/payload/order + 原始 expected cursors）；服务端 ordered command-set replay 绕过移动的 cursors。
- 编辑内容或目标 draft → identity 变化 → mint 全新 batch。
- **known 409**：batch 未被接受 → 清 `replayRef`，下一次发送基于刷新后 snapshot 重建（新 cursors + 新 command IDs）。

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
2. Redis `realtime` 事件叠加流式 overlay：MODEL_DELTA 按 invocation+attempt+sequence 严格推进，TOOL_PARTIAL 按 `createdAt|canonical payload` 指纹去重（FIFO 有界，attempt 变化/terminal/resultEntryId/消失时清空）。
3. **Gap recovery**：缺失 sequence 触发 `useGapRecoveryLoop`——单飞、指数退避（200→2000ms、最多 8 次）refetch snapshot；immutable per-recovery token 防旧 Thread tick 干扰新 Thread；refetch 失败继续退避不冻结；caught-up/stale/terminal 停止。
4. **Terminal fence**：`resultJson`/`errorJson`/`resultEntryId` 是 durable 边界——late MODEL_DELTA 被拒绝；snapshot reconcile 中 `status:'done'|'error'` 的 durable projection **无条件**压过更高 sequence 的 Redis overlay；`resultEntryId` 落地后 overlay 移除；timeline 按 `modelStream.status` 渲染，terminal projection 绝不标 streaming。
5. TOOL_PARTIAL 永不携带 Resource；Tool overlay 投影按 `(assistantEntryId, ordinal)` durable identity + toolCallId 一致性匹配，禁止 first-candidate fallback。

## 10. Resource 安全呈现

Tool Result 的 Resource（`ResourceRef {uri, mediaType, name, size, sha256}` + 可选文本 preview）：

- **仅 `data:` URI** 自动媒体预览（图片等）；http/https/file/s3 只展示稳定 URI 文本 + 显式 `rel="noopener noreferrer"` 链接。
- 允许的 scheme 集合仍是 data/file/s3/http/https；未知 scheme 不渲染链接。
- preview 保持 `<pre>` 文本块，不执行富内容。

## 11. 前端目录

```text
frontend/src
├── app/
├── platform/
├── features/ai/
│   ├── catalog/
│   ├── chat/            # ChatWorkspacePane / BlankComposerPane / BoundThreadPane / command-batch-plan
│   ├── environment/
│   └── runtime/         # useAgentThreadController / useHarnessThreadRealtime / thread-timeline
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

## 12. 验证

```bash
cd frontend
npm test
npm run lint
npm run build
```

前端 API 契约重点覆盖名称身份、Model ref、命令 batch 严格 wire、CAS、exact replay 与 409 rebuild、approval/stop 身份、snapshot-first SSE 与 terminal 投影。
