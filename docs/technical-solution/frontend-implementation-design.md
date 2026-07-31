# 前端落地设计

本文描述 `frontend/` 的 Chat 工作区、Harness Session/Thread 面板、API 契约、transcript 投影和验证边界。Session 与 Thread 的运行事实以 [harness-runtime-architecture.md](harness-runtime-architecture.md) 为准。Live Environment 以 [environment-daemon-gateway.md](environment-daemon-gateway.md) 为准。

## 前端摘要

| 主题 | 当前契约 |
| --- | --- |
| 工程与发布 | `frontend/` 保持独立 Vite 工程；Maven `distribution` 在 `prepare-package` 构建并嵌入 `web` Fat JAR 的 `classpath:/static` |
| 页面范围 | Chat 卡片与本地 Pane 工作区、Provider/Model/Agent、只读 Environment Registry、Harness 设置、ComfyUI |
| 服务端状态 | React Query |
| 本地状态 | `localStorage` 的 `ChatPaneState`（按 chatId）与全局 Locale 偏好 |
| 资源 API | `/api/ai/catalog/providers`、`/api/ai/catalog/models`、`/api/ai/catalog/agents`、`/api/ai/environment` |
| Chat API | `/api/ai/chat` |
| Harness API | `/api/ai/runtime/threads`、`/api/ai/runtime/threads/{threadId}/snapshot`、`/api/ai/runtime/sessions`、`/api/ai/runtime/tool-invocations`、`/api/ai/runtime/usage`、`/api/ai/runtime/interactions` |
| 实时通道 | snapshot-first + durable revision SSE；无 id 的 Redis `realtime` 仅作临时 overlay |
| 浏览器路由 | `BrowserRouter`；Spring 对非 API/Actuator、无扩展名且不存在的 GET 路径回退到 `index.html` |
| 视觉实现 | 全局 token 见 [前端设计规范](../product-design/frontend-design-system.md) |

## 国际化

- 支持 `en-US` 与 `zh-CN`，无有效偏好时默认 `en-US`；英文二级导航使用 `Setting`，中文使用 `设置`。
- `shared/i18n` 维护单一运行时 Locale store、`useI18n()`/`translate()` 和按 Platform、Shared、AI、Canvas、ComfyUI 拆分的双语 catalog。React 展示组件订阅 Locale store，切换后无需刷新即可重渲染；纯 helper 在执行时解析文案，禁止在模块加载时冻结翻译。
- 右上角 `English` / `中文` 分段选择器写入 `localStorage` 键 `kk-studio.locale`，同步 `document.documentElement.lang`；Chat 沉浸工作区在自身 Header 保留同一选择器。
- Axios request interceptor 每次请求读取当前 Locale 并发送 `Accept-Language`，因此切换后下一次 API 调用直接使用新语言。
- 用户输入、Catalog/Workflow/Canvas 数据、模型与工具输出、协议 ID/状态值不翻译。
- Vitest 全局默认置为 `zh-CN` 以保持既有业务断言稳定；独立 live-switch 测试覆盖默认英文、持久化、`Setting`、Chat/Catalog/Settings/Environment、Canvas 与 ComfyUI。

## 路由

| 路由 | 页面 | 说明 |
| --- | --- | --- |
| `/` | redirect | 跳转至 `/chats` |
| `/chats` | Chat 卡片列表 | 创建/进入持久 Chat；默认 Agent 可选 |
| `/chats/:chatId` | Chat 工作区 | 1/2/3/4/6/8 Pane 本地布局；状态始终保存 8 个 Pane |
| `/agents` | Agent 管理 | Agent CRUD |
| `/models` | Model 管理 | Model CRUD |
| `/providers` | Provider 管理 | Provider CRUD |
| `/environments` | Environment Registry | 只读 live registry |
| `/settings` | Harness 设置 | 全局自动重试与 realtime Stream 容量策略 |
| `/comfyui` | ComfyUI 工作流 | 独立工作流运行时 |

## Chat 工作区

### Chat 卡片

- `GET/POST /api/ai/chat` 列表/创建；`defaultAgentId` 创建时必填且必须指向现存 Agent。
- 进入 Chat 打开 `/chats/:chatId`。
- Chat.defaultAgentId 在 Agent 查询中缺失时视为 stale；Chat 仍可读取，只有 Blank Pane 首发或显式使用 Agent 选择器时要求重新选择。
- Chat 通过服务端 Chat↔Thread 历史多对多关系聚合 Thread；Pane 只保存本地绑定，不拥有 Thread 生命周期。

### 本地 `ChatPaneState`

按 `chatId` 存于 `localStorage`（键前缀 `kk-studio.chat-pane.`）：

| 字段 | 说明 |
| --- | --- |
| `layout` | `single` / `split-2` / `split-3` / `grid-4` / `grid-6` / `grid-8`；只控制可见前 N 个 Pane |
| `focusedPaneId` | 当前聚焦 Pane |
| `panes[]` | 永远为 `pane-1..pane-8` 八个 `{ id, threadId }`；`threadId=null` 为空面板，session 由 Thread 反查；布局缩放不清除隐藏槽位 |
| `sessionSort` / `threadSort` | `recent` 或 `created` |

服务端不存 Pane 状态。进入 Chat 时，旧 localStorage 绑定按 Thread ID 去重后通过幂等关联接口补建 Chat 关系；关联失败不会清除本地 Pane。

### 空 Pane 与首发

1. Footer 显示 Agent：空 Pane 用 Chat default；已绑定 Pane 用 Thread DTO。
2. Chat default Agent stale 或无可用 Agent 时打开选择器；选择成功先更新 Chat 默认 Agent。
3. 首发顺序（`chat-first-send.ts`）：
   - `POST /api/ai/chat/{chatId}/threads`（原子创建并归入 Chat 的 UNBOUND Thread）
   - `POST /api/ai/runtime/threads/:id/bootstrap`（默认 Agent + yolo，带 `expectedExecutionEpoch`；返回 `{session, thread}`）
   - `POST /api/ai/runtime/threads/:id/messages`（USER_MESSAGE，带 bootstrap 返回的 `executionEpoch`）
   - 将 `pane.threadId` 设为该 Thread

### Slash 命令

| 命令 | 行为 |
| --- | --- |
| `/session` | 列出全部 Session；选中后从其 Entry Tree 选目标 head，`PUT /api/ai/runtime/threads/:id/head` 重定位当前 Thread |
| `/thread` | 在“当前 Chat”与“全局 Thread”两个范围中按 `recent/created` 游标分页；当前范围直接替换 pane，切到全局后先 `PUT /api/ai/chat/{chatId}/threads/{threadId}` 成功再绑定 pane |
| `/agent` `/model` `/variant` | 入队 SET_AGENT / SET_MODEL |
| `/tree` | 打开历史面板，把当前 Thread 的 head 重定位到所选 Entry |
| `/yolo` `/stop` `/new` | 既有语义；无 `/retry` |

`/session` 与 `/tree` 都修改当前 Thread，因此只在 Thread 逻辑静止（`UNBOUND` / `IDLE` 且非 processing）时可用；否则提示先 `/stop`。所有会修改 Thread 的请求都带当前 `executionEpoch`，409 直接展示给用户不吞掉。

同一 Thread 可出现在多个 Pane。Thread snapshot 的 React Query key 按 `threadId` 复用 durable 快照；但每个已挂载的 Bound Pane 都创建自己的 `EventSource`，SSE 连接不在 Pane 之间共享。

## API 边界

`shared/api/chat-service.ts`、`environment-service.ts`、`agent-service.ts`、`harness-service.ts` 为前端边界。

| Service | HTTP 接口 | 用途 |
| --- | --- | --- |
| Chat CRUD | `/api/ai/chat` | Chat 列表与 CRUD |
| `listChatThreads` | `GET /api/ai/chat/{chatId}/threads?sort&cursor&limit` | 当前 Chat 的 `{items,nextCursor}` 游标分页 |
| `createChatThread` | `POST /api/ai/chat/{chatId}/threads` | 同事务创建并关联 UNBOUND Thread |
| `associateThread` | `PUT /api/ai/chat/{chatId}/threads/{threadId}` | 幂等建立历史 Chat↔Thread 关系 |
| Environments | `GET /api/ai/environment` | 只读 live registry |
| `listThreads` | `GET /api/ai/runtime/threads?sort&cursor&limit` | 全局 `{items,nextCursor}` Thread 游标分页（`/thread` 与 `/session` 的运行态来源） |
| `createThread` | `POST /api/ai/runtime/threads` | 创建 UNBOUND Thread（无 body） |
| `bootstrapThread` | `POST /api/ai/runtime/threads/{id}/bootstrap` | 创建 Session 并绑定 head，返回 `{session, thread}` |
| `updateThreadHead` | `PUT /api/ai/runtime/threads/{id}/head` | bind / 跨 Session rebind / unbind（`headEntryId` 可为 `null`） |
| `getThreadSnapshot` | `GET /api/ai/runtime/threads/{id}/snapshot` | 唯一 chat-runtime 投影：revision、Thread、Entries、Inputs、invocations、open interactions 与 usage |
| `submitThreadMessage` | `POST /api/ai/runtime/threads/{id}/messages` | 入队用户消息（202） |
| `setThreadAgent` / `setThreadModel` / `setThreadYolo` | `PUT /api/ai/runtime/threads/{id}/agent`、`/model`、`/yolo` | 入队配置命令（202） |
| `createThreadRealtimeStream` | `GET /api/ai/runtime/threads/{id}/events/stream?afterRevision={revision}` | revision/resync invalidation 与无 id 的 Redis realtime overlay |
| `stopThread` | `POST .../stop` | Stop |
| `listSessions` / `getSession` / `listSessionEntries` | `/api/ai/runtime/sessions` | Session 列表、详情与 Entry Tree |
| `getRetryPolicy` / `updateRetryPolicy` | `GET` / `PUT /api/ai/runtime/settings/retry-policy` | 全局自动重试策略 |
| `getRealtimeStreamPolicy` / `updateRealtimeStreamPolicy` | `GET` / `PUT /api/ai/runtime/settings/realtime-stream-policy` | 全局 Redis realtime Stream 最大保留事件数 |

所有 durable ID 在 TypeScript 中保持十进制字符串。`bootstrapThread`、`updateThreadHead`、`stopThread` 与全部 mailbox 请求体都带必填 `expectedExecutionEpoch`。

Thread 列表默认 `limit=20`、最大 `100`；`recent` 按 `updated_at`、`created` 按 `created_at`，cursor 为 opaque token。关系表不作为排序时间源。

`/settings` 的两张卡片独立加载、校验与保存。实时流容量调整在本实例影响既有和新建 Stream 的下一次写入，其他实例最多一秒刷新；调大不恢复已经裁剪的 event。

### Agent DTO

```text
AgentDefinitionDTO {
  id, name, description, systemPrompt,
  modelId, variant,
  config: {
    environmentName: string | null,
    tools[], skills[]
  }
}
```

Model 与 Agent 的 `PUT` 提交完整 editable body。候选 tools/skills 采用 platform-first。

## Chat transcript

### 投影公式

`buildThreadTimeline(entries, inputs)`：

```text
transcript =
  path Entries（唯一权威）

decoration queue =
  QUEUED USER_MESSAGE / CUSTOM_MESSAGE inputs（保持 mailbox 顺序）
```

| 来源 | 前端行为 |
| --- | --- |
| `MESSAGE` / `CUSTOM_MESSAGE` / `ASSISTANT_ERROR` Entry | 稳定对话气泡与路径语义基线 |
| `ASSISTANT_ABORTED` Entry | 持久化 partial assistant turn：投影为 assistant `TextDialogueMessage`（`aborted: true`），渲染“已停止”标识；只保留 text/thinking 内容，不带 tool fragment |
| `RUNTIME_CONFIG` Entry | 配置快照事实；不渲染完整配置气泡 |
| QUEUED `user_message` / `custom_message` input | 不进入 transcript；显示在 Working 装饰栏 |
| APPLIED input | 不渲染；Entry 与 apply 同事务，Entries 为唯一 transcript 权威 |
| Tool Result artifact | 映射 `/api/ai/runtime/artifacts/{artifactId}` |
| revision / resync SSE | 仅 invalidate `threads.snapshot(threadId)` |
| Redis SSE `/events/stream` (`realtime`) | 临时模型 text/thinking overlay；不使业务 query 失效 |

Timeline 以单一 Thread snapshot 的 Entries/Inputs 为权威基线。

### 历史重定位与 Stop

- `/tree` 按需查询 Session Entry Tree；确认后 `PUT /api/ai/runtime/threads/:id/head` 把当前 Thread 的 head 重定位到所选 Entry，pane 绑定不变，并把该 Entry 的用户文本回填为草稿。
- `/session` 先选 Session，再由其 Entry Tree 提供新 head，同样走 `PUT /head`。
- `/stop` 携带当前 `executionEpoch`；服务端原子追加 `ASSISTANT_ABORTED`（仅 text/thinking）或 `ASSISTANT_ERROR(CANCELLED)` barrier 并 epoch fence。前端在 snapshot invalidate 后，durable `ASSISTANT_ABORTED` 投影为 assistant `TextDialogueMessage`（`aborted: true`）并渲染“已停止”标识；realtime SSE delta 仍然落到当前 invocation overlay，但只要 durable aborted/cancellation 落库就视为该 overlay committed 并被覆盖。成功后前端仅清理本地 message replay identity 并 invalidate `threads.snapshot`。不回填 Composer。stop 后 Thread 立即可重定位。
- 派生状态按 `RUNNING > WAITING > RUNNABLE > UNBOUND/IDLE` 驱动 UI 指示；`RUNNABLE` 视为 active/working；invocation 重试等待归入 `WAITING`。业务状态不使用周期轮询。

### Realtime cursor 恢复

1. REST 加载单一 Thread snapshot
2. 以 snapshot 的十进制 revision 打开 EventSource
3. `revision` 的 SSE id 只作为 durable cursor；Redis realtime 没有 id，重新连接从 live edge 开始
4. revision/resync 只使 `threads.snapshot` 失效

## 工程结构

```text
frontend/src
├── app/
├── platform/
├── features/ai/
│   ├── catalog/
│   ├── chat/
│   ├── environment/
│   ├── extensions/
│   ├── runtime/
│   └── settings/
├── shared/api/
│   ├── contracts/
│   │   ├── ai-catalog.ts
│   │   ├── ai-chat.ts
│   │   ├── ai-environment.ts
│   │   ├── ai-runtime.ts
│   │   ├── comfyui.ts
│   │   ├── storage.ts
│   │   └── studio.ts
│   ├── chat-service.ts
│   ├── environment-service.ts
│   ├── agent-service.ts
│   └── harness-service.ts
├── shared/i18n/
│   ├── index.ts
│   ├── LocaleSelector.tsx
│   └── catalogs/{platform,shared,ai,canvas,comfyui}.ts
├── shared/lib/query-keys.ts
└── styles.css
```

`queryKeys.chats.*` 覆盖 Chat；`queryKeys.environments.list` 覆盖 live registry；`queryKeys.threads.list` 覆盖全局 Thread 列表；`queryKeys.threads.snapshot(...)` 是 chat runtime 的唯一 Thread 业务状态。head 重定位成功后失效 snapshot 与 `threads.list`。

## 验证

```bash
cd frontend && npm test && npm run lint && npm run build
```
