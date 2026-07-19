# 前端落地设计

本文描述 `frontend/` 的 Chat 工作区、Harness Session/Thread 面板、API 契约、transcript 投影和验证边界。Session 与 Thread 的运行事实以 [Harness Session Tree 与 Thread Actor](harness-thread-actor.md) 为准。Live Environment 以 [Environment Daemon Gateway](environment-daemon-gateway.md) 为准。

## 前端摘要

| 主题 | 最终前端契约 |
| --- | --- |
| 工程目录 | `frontend/` 独立 Vite 工程 |
| 页面范围 | Chat 卡片与本地 Pane 工作区、Provider/Model/Agent、只读 Environment Registry、ComfyUI |
| 服务端状态 | React Query |
| 本地状态 | `localStorage` 的 `ChatPaneState`（按 chatId） |
| 资源 API | `/api/providers`、`/api/models`、`/api/agents`、`/api/environments` |
| Chat API | `/api/chats` 与 Chat↔Session 成员关系 |
| Harness API | `/api/sessions`、`/api/threads/{threadId}`、`/api/tool-invocations`、`/api/usage` |
| 实时通道 | 数据库 cursor 驱动的 Thread Event SSE |
| 视觉实现 | 全局 token 以 Canvas 设计为事实源，见 [前端设计规范](../product-design/frontend-design-system.md) |

## 路由

| 路由 | 页面 | 说明 |
| --- | --- | --- |
| `/` | redirect | 跳转至 `/chats` |
| `/chats` | Chat 卡片列表 | 创建/进入持久 Chat；默认 Agent 可选 |
| `/chats/:chatId` | Chat 工作区 | 1/2/3/6 Pane 本地布局；无永久 Session/Thread 侧栏 |
| `/agents` | Agent 管理 | Agent CRUD（model/variant/config） |
| `/models` | Model 管理 | Model CRUD |
| `/providers` | Provider 管理 | Provider CRUD |
| `/environments` | Environment Registry | 只读 live registry |
| `/comfyui` | ComfyUI 工作流 | 独立工作流运行时 |

不再注册 Session-as-Chat 路由（`/sessions`、`/sessions/:id`、`/sessions/:id/threads/:threadId`、`/threads/:threadId`）。

## Chat 工作区

### Chat 卡片

- `GET/POST /api/chats` 列表/创建；创建对话框 title 与 defaultAgent 均可空。
- 进入 Chat 打开 `/chats/:chatId`。
- Chat.defaultAgentId 在 Agent 查询中缺失/已删除时前端视为 none，并提示重新选择。

### 本地 `ChatPaneState`

按 `chatId` 存于 `localStorage`（键前缀 `kk-studio.chat-pane.`）：

| 字段 | 说明 |
| --- | --- |
| `layout` | `single` / `split-2` / `split-3` / `grid-6` |
| `focusedPaneId` | 当前聚焦 Pane |
| `panes[]` | 固定 1/2/3/6 个格子；`target={sessionId?,threadId?}` |
| `sessionSort` / `threadSort` | `recent`（updateTime）或 `created`（createTime） |

布局切换尽可能保留既有 pane target。服务端不存 Pane 状态。无 Window 抽象。

### 空 Pane 与首发

每个 Pane 初始为会话 composer；空 Pane 仅暴露 `/session` 以复用本 Chat 成员 Session（选中后替换该 Pane target 为 Main Thread）：

1. Footer/chip 显示 Agent：空 Pane 用 Chat default；已绑定 Pane 用 Thread DTO 的 active agent。
2. 无可用 Agent 时提交会打开 Agent 选择器；选中后更新 Chat.defaultAgentId，并继续 pending 首发。
3. 首发顺序（每个空 Pane 各自一套 Session/Main Thread）：
   - `POST /sessions`（agentless，title 可选）
   - `POST /chats/:id/sessions` attach
   - `PUT /threads/:id/agent`（SET_AGENT，新 clientMessageId）
   - `POST /threads/:id/messages`（USER_MESSAGE，另一个新 clientMessageId）
   - 将 pane target 设为该 Main Thread

### Slash 命令（已绑定 Thread Pane）

| 命令 | 行为 |
| --- | --- |
| `/session` | 空 Pane 与已绑定 Pane 均可用；列出当前 Chat **全部**成员 Session；按各 Session 的 Threads 判断 running 后优先，再按用户 sort；选中后替换 **该** Pane target 为 Main Thread |
| `/thread` | 当前 Session Threads；同样 running 优先 + sort；选中后替换 pane target |
| `/agent` | Agent 选择器；对当前 Thread `setThreadAgent`；不手工同步其它 Pane，依赖 query invalidate + SSE |
| `/tree` `/stop` `/retry` `/yolo` `/clear` | 保留既有语义 |

同一 Thread 可出现在多个 Pane；React Query 与 SSE 按 threadId 共享。Agent/model/yolo 标签读 Thread DTO 字段（`activeAgentDefinitionId/name`、`modelId`、`variant`、`yoloEnabled`），不依赖旧 snapshot 解析。SSE 对 `agent_changed` / `model_changed` / `yolo_changed` 会使 Thread detail 失效，重复 Pane 响应式更新。

## API 边界

`shared/api/chat-service.ts` 承载 Chat 集合与成员关系。`shared/api/environment-service.ts` 承载只读 Environment registry。`shared/api/agent-service.ts` 承载 Provider、Model、Agent 与 Usage。`shared/api/harness-service.ts` 是 Session/Thread 观测与 mailbox 的唯一前端边界。

| Service | HTTP 接口 | 用途 |
| --- | --- | --- |
| Chat list/create/get/update/delete | `GET/POST /api/chats`、`GET/PUT/DELETE /api/chats/{id}` | Chat CRUD |
| Chat sessions | `GET/POST /api/chats/{id}/sessions`、`DELETE .../sessions/{sessionId}` | 成员关系 |
| Environments | `GET /api/environments` | 只读 live registry |
| `createSession` | `POST /api/sessions` | **agentless** Session + Main Thread |
| `listSessionThreads` / `createSessionThread` | `GET` / `POST /api/sessions/{id}/threads` | Thread 列表；以 `fromEntryId` 创建 Secondary Thread |
| `getThread` | `GET /api/threads/{id}` | Thread actor（含 active agent/model/yolo） |
| `submitThreadMessage` | `POST /api/threads/{id}/messages` | 入队用户消息（202） |
| `setThreadAgent` / `setThreadModel` / `setThreadYolo` | `PUT /api/threads/{id}/agent`、`/model`、`/yolo` | 入队路径配置变更（202）；**无 `/toolset`** |
| `listThreadEntries` / `inputs` / `events` | `GET /api/threads/{id}/...` | 路径 Entries、mailbox、journal |
| `createThreadEventStream` | `GET /api/threads/{id}/events/stream` | SSE |
| `stopThread` / `retryThread` | `POST .../stop`、`/retry` | 取消 queued Input / 显式重试 FAILED |

所有 Snowflake ID 在 TypeScript 契约中保持十进制字符串。

### Agent DTO

```text
AgentDefinitionDTO {
  id, name, description, systemPrompt,
  modelId, variant,
  config: {
    environmentName?, tools[], skills[], allowedSubagents[],
    executionPolicy?: { maxTurns?, maxDepth?, maxDirectSubagents?, maxTotalSubagents? }
  }
}
```

Agent 表单：选择 model/variant、system prompt、可选 live Environment、短名 tools/skills、subagents。候选 tools/skills 采用 platform-first（READY `platform` + 可选所选 Environment，platform 同名优先）。对当前选择的 offline/invalid 明确提示；不提供 Environment CRUD。

## Chat transcript

### 投影公式

`buildThreadTimeline(entries, inputs, threadEvents)`（`thread-timeline-builder.ts`）：

```text
transcript =
  path Entries（语义基线）
  + 已 INPUT_APPLIED、但 Entry 查询尚未刷新的短暂 journal 覆盖
  + active ThreadEvents（流式 / 工具覆盖层）

decoration queue =
  QUEUED USER_MESSAGE / CUSTOM_MESSAGE inputs（保持后端 mailbox 顺序）
```

| 来源 | 前端行为 |
| --- | --- |
| `message` / `agent_snapshot` / `compaction` Entry | 稳定气泡与路径配置基线 |
| QUEUED `user_message` / `custom_message` input | 不进入 transcript；显示在 Working 装饰栏 |
| `input_applied` 且 Entry 查询暂时落后 | 以 subject Entry ID 短暂补位 |
| `assistant_*` / `tool_*` / `permission_*` | 流式与工具覆盖；物化后抑制 |
| Tool Result artifact | 映射 `/api/artifacts/{artifactId}` |

Thread 主区纵向固定：可滚动 transcript；Working/queue/widgets；Composer；底部 Agent/Model/Usage footer。

### 历史分支与 Stop

- `/tree` 按需查询 Session Entry Tree，打开独立历史分支面板；确认后创建 Secondary Thread，并把 **当前 Pane** target 切到新 Thread（不改 URL）。
- `/stop` 的 `restoredMessages` 以空行合并回 Composer，并生成新 `clientMessageId`。
- FAILED Thread 通过 `/retry` 显式恢复。

### SSE cursor 恢复

先分页 `listThreadEvents` 拉到末页，再用最后一个 `eventId` 打开 EventSource。`eventId` 以字符串位数 + 字典序比较，**绝不**转为 JavaScript number。终态与配置变更事件会使 entries/inputs/tool/usage/thread detail query 失效。

## 工程结构

```text
frontend/src
├── app/                 路由与启动（/ → /chats）
├── platform/
├── features/ai
│   ├── extensions/            Chat/Agent/Environment 页面注册
│   ├── chat-pane-state.ts     本地布局与 sort
│   ├── chat-first-send.ts     空 Pane 首发顺序
│   ├── ChatWorkspacePage.tsx  Pane 网格
│   ├── ChatWorkspacePane.tsx  空/绑定 Pane
│   ├── thread-panel/          Composer、Transcript、slash commands、footer
│   └── ...
├── shared/api
│   ├── chat-service.ts
│   ├── environment-service.ts
│   ├── agent-service.ts
│   ├── harness-service.ts
│   └── contracts.ts
├── shared/lib/query-keys.ts
└── styles.css
```

`queryKeys.chats.*` 覆盖 Chat/sessions；`queryKeys.environments.list` 覆盖 live registry；`queryKeys.sessions.*` / `queryKeys.threads.*` 覆盖 Session/Thread 观测。

## 验证

```bash
cd frontend
npm test
npm run lint
npm run build
npm run coverage
```

前端覆盖率门禁：statements / branches / functions / lines 均不低于 80%。
