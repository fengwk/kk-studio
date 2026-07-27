# 前端落地设计

本文描述 `frontend/` 的 Chat 工作区、Harness Session/Thread 面板、API 契约、transcript 投影和验证边界。Session 与 Thread 的运行事实以 [harness-runtime-architecture.md](harness-runtime-architecture.md) 为准。Live Environment 以 [environment-daemon-gateway.md](environment-daemon-gateway.md) 为准。

## 前端摘要

| 主题 | 当前契约 |
| --- | --- |
| 工程与发布 | `frontend/` 保持独立 Vite 工程；Maven `distribution` 在 `prepare-package` 构建并嵌入 `web` Fat JAR 的 `classpath:/static` |
| 页面范围 | Chat 卡片与本地 Pane 工作区、Provider/Model/Agent、只读 Environment Registry、ComfyUI |
| 服务端状态 | React Query |
| 本地状态 | `localStorage` 的 `ChatPaneState`（按 chatId） |
| 资源 API | `/api/providers`、`/api/models`、`/api/agents`、`/api/environments` |
| Chat API | `/api/chats` |
| Harness API | `/api/threads`、`/api/threads/{threadId}`、`/api/sessions`、`/api/tool-invocations`、`/api/usage`、`/api/interactions` |
| 实时通道 | snapshot-first + Redis-backed SSE（事件名 `realtime`，stream-id cursor） |
| 浏览器路由 | `BrowserRouter`；Spring 对非 API/Actuator、无扩展名且不存在的 GET 路径回退到 `index.html` |
| 视觉实现 | 全局 token 见 [前端设计规范](../product-design/frontend-design-system.md) |

## 路由

| 路由 | 页面 | 说明 |
| --- | --- | --- |
| `/` | redirect | 跳转至 `/chats` |
| `/chats` | Chat 卡片列表 | 创建/进入持久 Chat；默认 Agent 可选 |
| `/chats/:chatId` | Chat 工作区 | 1/2/3/6 Pane 本地布局 |
| `/agents` | Agent 管理 | Agent CRUD |
| `/models` | Model 管理 | Model CRUD |
| `/providers` | Provider 管理 | Provider CRUD |
| `/environments` | Environment Registry | 只读 live registry |
| `/comfyui` | ComfyUI 工作流 | 独立工作流运行时 |

## Chat 工作区

### Chat 卡片

- `GET/POST /api/chats` 列表/创建；title 与 defaultAgent 均可空。
- 进入 Chat 打开 `/chats/:chatId`。
- Chat.defaultAgentId 在 Agent 查询中缺失时前端视为 none。
- Chat 只提供默认 Agent 与本地 Pane 布局的入口，不持有 Session/Thread。

### 本地 `ChatPaneState`

按 `chatId` 存于 `localStorage`（键前缀 `kk-studio.chat-pane.`）：

| 字段 | 说明 |
| --- | --- |
| `layout` | `single` / `split-2` / `split-3` / `grid-6` |
| `focusedPaneId` | 当前聚焦 Pane |
| `panes[]` | `{ id, threadId }`（`threadId=null` 为空面板；只存可空 threadId，session 由 Thread 反查） |
| `sessionSort` / `threadSort` | `recent` 或 `created` |

服务端不存 Pane 状态。

### 空 Pane 与首发

1. Footer 显示 Agent：空 Pane 用 Chat default；已绑定 Pane 用 Thread DTO。
2. 无可用 Agent 时打开选择器。
3. 首发顺序（`chat-first-send.ts`）：
   - `POST /threads`（UNBOUND Thread）
   - `POST /threads/:id/bootstrap`（默认 Agent + yolo，带 `expectedExecutionEpoch`；返回 `{session, thread}`）
   - `POST /threads/:id/messages`（USER_MESSAGE，带 bootstrap 返回的 `executionEpoch`）
   - 将 `pane.threadId` 设为该 Thread

### Slash 命令

| 命令 | 行为 |
| --- | --- |
| `/session` | 列出全部 Session；选中后从其 Entry Tree 选目标 head，`PUT /threads/:id/head` 重定位当前 Thread |
| `/thread` | 列出全部 Thread；只替换 pane 的 `threadId`，不修改任何 Thread |
| `/agent` `/model` `/variant` | 入队 SET_AGENT / SET_MODEL |
| `/tree` | 打开历史面板，把当前 Thread 的 head 重定位到所选 Entry |
| `/yolo` `/stop` `/new` | 既有语义；无 `/retry` |

`/session` 与 `/tree` 都修改当前 Thread，因此只在 Thread 逻辑静止（`UNBOUND` / `IDLE` 且非 processing）时可用；否则提示先 `/stop`。所有会修改 Thread 的请求都带当前 `executionEpoch`，409 直接展示给用户不吞掉。

同一 Thread 可出现在多个 Pane；React Query 与 SSE 按 threadId 共享。

## API 边界

`shared/api/chat-service.ts`、`environment-service.ts`、`agent-service.ts`、`harness-service.ts` 为前端边界。

| Service | HTTP 接口 | 用途 |
| --- | --- | --- |
| Chat CRUD | `/api/chats` | Chat 列表与 CRUD |
| Environments | `GET /api/environments` | 只读 live registry |
| `listThreads` | `GET /api/threads` | 全局 Thread 列表（`/thread` 与 `/session` 的运行态来源） |
| `createThread` | `POST /api/threads` | 创建 UNBOUND Thread（无 body） |
| `bootstrapThread` | `POST /api/threads/{id}/bootstrap` | 创建 Session 并绑定 head，返回 `{session, thread}` |
| `updateThreadHead` | `PUT /api/threads/{id}/head` | bind / 跨 Session rebind / unbind（`headEntryId` 可为 `null`） |
| `getThread` | `GET /api/threads/{id}` | Thread 视图（含派生 status、可空 `sessionId`、`executionEpoch`） |
| `submitThreadMessage` | `POST /api/threads/{id}/messages` | 入队用户消息（202） |
| `setThreadAgent` / `setThreadModel` / `setThreadYolo` | `PUT /api/threads/{id}/agent`、`/model`、`/yolo` | 入队配置命令（202） |
| `listThreadEntries` / `inputs` | `GET /api/threads/{id}/entries`、`/inputs` | 路径 Entries 与 mailbox |
| `createThreadRealtimeStream` | `GET /api/threads/{id}/events/stream` | Redis SSE（`afterEventId` = stream-id） |
| `stopThread` | `POST .../stop` | Stop |
| `listSessions` / `getSession` / `listSessionEntries` | `/api/sessions` | Session 列表、详情与 Entry Tree |
| `getRetryPolicy` / `updateRetryPolicy` | `GET` / `PUT /api/harness/retry-policy` | 全局自动重试策略 |

所有 durable ID 在 TypeScript 中保持十进制字符串。`bootstrapThread`、`updateThreadHead`、`stopThread` 与全部 mailbox 请求体都带必填 `expectedExecutionEpoch`。

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
| `RUNTIME_CONFIG` Entry | 配置快照事实；不渲染完整配置气泡 |
| QUEUED `user_message` / `custom_message` input | 不进入 transcript；显示在 Working 装饰栏 |
| APPLIED input | 不渲染；Entry 与 apply 同事务，Entries 为唯一 transcript 权威 |
| Tool Result artifact | 映射 `/api/artifacts/{artifactId}` |
| Redis SSE `/events/stream` (`realtime`) | 仅 invalidate Entries/Inputs/Thread 等 snapshot query，不投影正文 |

Timeline 以 Entries/Inputs snapshot 为权威基线。

### 历史重定位与 Stop

- `/tree` 按需查询 Session Entry Tree；确认后 `PUT /threads/:id/head` 把当前 Thread 的 head 重定位到所选 Entry，pane 绑定不变，并把该 Entry 的用户文本回填为草稿。
- `/session` 先选 Session，再由其 Entry Tree 提供新 head，同样走 `PUT /head`。
- `/stop` 携带当前 `executionEpoch`；成功后仅清理本地 message replay identity，并 invalidate Thread/Entries/Inputs 等 snapshot query。不回填 Composer。stop 后 Thread 立即可重定位。
- 派生状态按 `RUNNING > WAITING > RUNNABLE > UNBOUND/IDLE` 驱动 UI 指示与 query 轮询；`RUNNABLE` 视为 active/working，避免丢失 Redis wake 后 UI 卡住；invocation 重试等待归入 `WAITING`。

### Realtime cursor 恢复

1. REST 加载 Thread / Entries / Inputs / Invocations
2. 以 Redis stream-id（默认 `0-0`）打开 EventSource
3. stream-id 以字符串比较，**绝不**转为 JavaScript number
4. 终态与配置推进会使 entries/inputs/tool/usage/thread detail query 失效

## 工程结构

```text
frontend/src
├── app/
├── platform/
├── features/ai
│   ├── extensions/
│   ├── chat-pane-state.ts
│   ├── chat-first-send.ts
│   ├── chat-session-picker.ts
│   ├── ChatWorkspacePage.tsx
│   ├── ChatWorkspacePane.tsx
│   ├── HistoryBranchPanel.tsx
│   ├── thread-panel/
│   └── thread-timeline/
├── shared/api
│   ├── chat-service.ts
│   ├── environment-service.ts
│   ├── agent-service.ts
│   ├── harness-service.ts
│   └── contracts.ts
├── shared/lib/query-keys.ts
└── styles.css
```

`queryKeys.chats.*` 覆盖 Chat；`queryKeys.environments.list` 覆盖 live registry；`queryKeys.threads.list` 覆盖全局 Thread 列表；`queryKeys.sessions.*` / `queryKeys.threads.detail(...)` 覆盖 Session/Thread 观测。head 重定位成功后同时失效 `threads.detail/entries/inputs` 与 `threads.list`。

## 验证

```bash
cd frontend && npm test && npm run lint && npm run build
```
