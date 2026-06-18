# 前端落地设计

本文是整体技术方案的一部分，描述 Studio 前端工程结构、模块边界、聊天组件事件驱动模型和 API 协议适配方式。

## 技术栈

前端采用 React + TypeScript + Vite 的工程结构：

- React 负责 UI 组件。
- React Router 负责页面路由。
- React Query 负责服务端状态获取与缓存。
- 轻量客户端偏好状态使用独立 store 或 React state 管理。
- Axios 负责普通 HTTP API。
- SSE 客户端负责 session event / run / tool call 实时消息。

## 构建与测试配置

`package.json` 脚本：

```json
{
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "lint": "eslint .",
    "preview": "vite preview",
    "test": "vitest run",
    "test:watch": "vitest",
    "coverage": "vitest run --coverage"
  }
}
```

`vite.config.ts` 规则：

- 使用 `@vitejs/plugin-react`。
- 使用 `@tailwindcss/vite`。
- 配置 `@` 指向 `frontend/src`。
- dev server 代理 `/api` 到 `API_PROXY_TARGET`，默认 `http://127.0.0.1:8080`。

`vitest.config.ts` 规则：

- 测试环境使用 `jsdom`。
- `setupFiles` 指向 `src/test-setup.ts`。
- 测试文件匹配 `src/**/*.{test,spec}.{ts,tsx}`。
- coverage provider 使用 `v8`。
- coverage reporter 使用 `text`、`json`、`html`。
- coverage include 覆盖 `src/shared/**`、`src/design-system/**`、`src/features/agent-session/**`、`src/features/agent-profile/**`、`src/features/agent-env/**`、`src/rendering/**`。
- statements / branches / functions / lines 阈值均为 90。

## 目录结构

```text
frontend/src
├── app
│   ├── App.tsx
│   ├── providers.tsx
│   └── router.tsx
├── platform
│   ├── shell
│   │   └── app-shell.tsx
│   └── workspace
│       ├── workspace-switcher.tsx
│       └── workspace-empty-page.tsx
├── features
│   ├── studio-home
│   │   └── studio-home-page.tsx
│   ├── agent-profile
│   │   ├── agent-profile-page.tsx
│   │   └── components
│   ├── agent-env
│   │   ├── agent-env-page.tsx
│   │   └── components
│   └── agent-session
│       ├── agent-session-page.tsx
│       ├── agent-session-container.tsx
│       ├── agent-message-presenter.tsx
│       ├── domain
│       │   ├── chat-event.ts
│       │   ├── chat-state.ts
│       │   ├── chat-reducer.ts
│       │   ├── chat-selectors.ts
│       │   └── chat-command.ts
│       ├── hooks
│       │   ├── use-agent-session-runtime.ts
│       │   └── use-session-event-stream.ts
│       ├── adapters
│       │   ├── session-event-to-chat-event.ts
│       │   └── chat-command-to-request.ts
│       └── components
│           ├── runtime-main-pane.tsx
│           ├── runtime-load-error-state.tsx
│           ├── session-sidebar.tsx
│           ├── message-viewport.tsx
│           ├── chat-composer-panel.tsx
│           ├── run-status-bar.tsx
│           ├── tool-call-card.tsx
│           └── branch-head-selector.tsx
├── shared
│   ├── api
│   │   ├── client.ts
│   │   ├── contracts
│   │   │   ├── agent-profile.ts
│   │   │   ├── agent-env.ts
│   │   │   ├── agent-session.ts
│   │   │   ├── agent-run.ts
│   │   │   └── base.ts
│   │   └── services
│   │       ├── agent-profile-service.ts
│   │       ├── agent-env-service.ts
│   │       ├── agent-session-service.ts
│   │       ├── agent-run-service.ts
│   │       └── agent-session-sse-service.ts
│   ├── lib
│   │   ├── logger.ts
│   │   ├── query-keys.ts
│   │   └── utils.ts
│   └── ui
│       └── modal-dialog.tsx
├── design-system
│   └── components
│       ├── action-button.tsx
│       ├── badge.tsx
│       ├── button.tsx
│       ├── card-grid.tsx
│       ├── card-shell.tsx
│       ├── input.tsx
│       ├── page-state.tsx
│       └── page-toolbar.tsx
├── rendering
│   ├── markdown
│   │   ├── markdown-renderer.tsx
│   │   ├── json-artifact-block.tsx
│   │   └── html-artifact-block.tsx
│   └── message
│       ├── message-surface.tsx
│       ├── message-card.tsx
│       ├── thinking-block.tsx
│       └── blocks
│           └── message-blocks.tsx
├── test-setup.ts
└── main.tsx
```

## 分层职责

| 层 | 目录 | 职责 |
| --- | --- | --- |
| app | `app` | 路由、全局 provider、应用装配 |
| platform | `platform` | 应用壳、workspace 切换、跨功能基础布局 |
| features | `features/*` | 业务功能页、功能内组件、功能内 hooks 与 adapters |
| shared api contracts | `shared/api/contracts` | 与后端 DTO 对齐的请求/响应类型 |
| shared api services | `shared/api/services` | HTTP API 与 SSE service 封装 |
| shared lib | `shared/lib` | query key、日志、通用工具函数 |
| shared ui | `shared/ui` | 带少量应用语义的共享组件 |
| design-system | `design-system/components` | 无业务语义的基础 UI 组件 |
| rendering | `rendering` | Markdown、message surface、artifact 与 tool block 渲染 |

页面组件只负责布局和路由上下文传递；业务状态推进由 feature 内 reducer 和 hooks 承担；展示组件只接收 props 和回调。

## API contract 设计

前端 API contract 与 `share.model` DTO 一一对应。

### agent profile contract

```ts
export interface AgentProfileDTO {
  profileId: string
  name: string
  description?: string
  systemPrompt?: string
  defaultProvider?: string
  defaultModel?: string
  enabled: boolean
  configJson?: string
  createTime: string
  updateTime: string
}

export interface AgentProfileToolBindingDTO {
  bindingId: string
  profileId: string
  envId: string
  toolName: string
  toolAlias?: string
  enabled: boolean
  bindingConfigJson?: string
}
```

### env contract

```ts
export type AgentEnvStatus = 'online' | 'offline' | 'disabled'

export interface AgentEnvDTO {
  envId: string
  envName?: string
  deviceName?: string
  osName?: string
  arch?: string
  workspaceRoots: string[]
  daemonVersion?: string
  status: AgentEnvStatus
  lastSeenAt?: string
  capabilityRevision: number
}

export interface AgentToolCapabilityDTO {
  capabilityId: string
  envId: string
  toolName: string
  displayName?: string
  description?: string
  sourceType: 'java' | 'process' | 'script' | 'remote'
  schemaJson: string
  enabled: boolean
  revision: number
}
```

### session / run contract

```ts
export interface PageDTO<T> {
  totalCount: number
  pageNumber: number
  pageSize: number
  data: T[]
}

export type AgentSessionStatus = 'active' | 'archived'
export type AgentRunStatus = 'pending' | 'running' | 'completed' | 'failed' | 'aborted'

export interface AgentSessionDTO {
  sessionId: string
  profileId: string
  title?: string
  status: AgentSessionStatus
  currentHeadEventId: string
  createTime: string
  updateTime: string
}

export interface AgentSessionEventDTO {
  eventId: string
  sessionId: string
  parentEventId: string
  runId?: string
  eventType: string
  payloadType: string
  payloadJson: string
  createTime: string
}

export interface AgentSessionHeadDTO {
  headId: string
  sessionId: string
  headName: string
  headEventId: string
}

export interface AgentSessionCreateDTO {
  profileId: string
  title?: string
}

export interface AgentUserMessageSubmitDTO {
  clientMessageId: string
  text: string
}

export interface AgentMessageSubmitResultDTO {
  clientMessageId: string
  run: AgentRunDTO
}

export interface AgentRunDTO {
  runId: string
  sessionId: string
  profileId: string
  baseEventId: string
  headEventId: string
  status: AgentRunStatus
  triggerType: string
  abortRequested: boolean
  startedAt?: string
  endedAt?: string
  errorCode?: string
  errorMessage?: string
}

export interface AgentSessionStreamEventDTO {
  seq: number
  sessionId: string
  headEventId: string
  type: 'session.snapshot' | 'session.updated' | 'session.heartbeat'
  session?: AgentSessionDTO
  heads?: AgentSessionHeadDTO[]
  run?: AgentRunDTO
  events?: AgentSessionEventDTO[]
  heartbeatTime?: string
}
```

service 方法约定：

- `agent-profile-service.ts`
  - `list(query): Promise<PageDTO<AgentProfileDTO>>`
  - `create(dto): Promise<AgentProfileDTO>`
  - `update(profileId, dto): Promise<AgentProfileDTO>`
  - `bindTool(profileId, dto): Promise<AgentProfileToolBindingDTO>`
- `agent-env-service.ts`
  - `list(query): Promise<PageDTO<AgentEnvDTO>>`
  - `listTools(envId): Promise<AgentToolCapabilityDTO[]>`
- `agent-session-service.ts`
  - `create(dto: AgentSessionCreateDTO): Promise<AgentSessionDTO>`
  - `get(sessionId): Promise<AgentSessionDTO>`
  - `listHeads(sessionId): Promise<AgentSessionHeadDTO[]>`
  - `listEvents(sessionId, headEventId): Promise<AgentSessionEventDTO[]>`
  - `submit(sessionId, dto: AgentUserMessageSubmitDTO): Promise<AgentMessageSubmitResultDTO>`
  - `buildStreamUrl(sessionId, headEventId, afterSeq?): string`
- `agent-run-service.ts`
  - `get(runId): Promise<AgentRunDTO>`
  - `abort(runId): Promise<AgentRunDTO>`

## 聊天域事件模型

聊天组件使用事件驱动结构。外部输入、服务端 session event、run 状态变化和用户 UI 操作都先转换为 `ChatEvent`，再由 reducer 生成 `ChatState`。

### `ChatEvent`

```ts
export type ChatEvent =
  | { type: 'session_loaded'; session: AgentSessionDTO; branchHeads: AgentSessionHeadDTO[]; headEventId: string }
  | { type: 'user_submit_requested'; clientMessageId: string; text: string }
  | { type: 'submit_accepted'; clientMessageId: string; run: AgentRunDTO }
  | { type: 'system_message_set'; eventId: string; text: string }
  | { type: 'model_info_set'; eventId: string; provider: string; model: string; variant?: string }
  | { type: 'user_messages_replayed'; eventId: string; runId?: string; messages: string[] }
  | { type: 'assistant_started'; eventId: string; runId?: string; parentEventId: string }
  | { type: 'assistant_delta'; eventId: string; runId: string; textDelta?: string; thinkingDelta?: string }
  | { type: 'assistant_ended'; eventId: string; runId: string }
  | { type: 'assistant_failed'; eventId: string; runId: string; errorMessage: string }
  | { type: 'tool_started'; eventId: string; runId: string; toolCallId: string; toolName: string; argumentsJson: string }
  | { type: 'tool_delta'; eventId: string; runId: string; toolCallId: string; contentDeltaJson: string }
  | { type: 'tool_ended'; eventId: string; runId: string; toolCallId: string; resultJson?: string }
  | { type: 'tool_failed'; eventId: string; runId: string; toolCallId: string; errorMessage: string }
  | { type: 'abort_applied'; eventId: string; runId?: string; reason?: string }
  | { type: 'run_status_changed'; run: AgentRunDTO }
  | { type: 'branch_head_changed'; headEventId: string }
  | { type: 'connection_changed'; connected: boolean }
```

事件命名规则：

- 用户意图使用 `*_requested`。
- 服务端确认使用 `*_accepted` 或后端状态事件。
- agent/session event 投影使用过去式，例如 `assistant_started`、`tool_ended`。
- 连接、run、branch 这类状态同步使用 `*_changed`。

### `ChatState`

```ts
export interface ChatState {
  session?: AgentSessionDTO
  branchHeads: AgentSessionHeadDTO[]
  headEventId: string
  connected: boolean
  lastStreamSeq?: number
  runningRunId?: string
  runStatusById: Record<string, AgentRunDTO>
  messageOrder: string[]
  messageById: Record<string, ChatMessageView>
  toolCallById: Record<string, ChatToolCallView>
  pendingClientMessageIds: string[]
}

export interface ChatMessageView {
  messageId: string
  role: 'user' | 'assistant' | 'system'
  text: string
  thinking?: string
  eventIds: string[]
  runId?: string
  streaming: boolean
  error?: string
}

export interface ChatToolCallView {
  toolCallId: string
  runId: string
  toolName: string
  argumentsJson: string
  contentText: string
  status: 'running' | 'completed' | 'failed' | 'cancelled'
  error?: string
}
```

### reducer 规则

`chat-reducer.ts` 只处理状态计算：

- 不调用 HTTP API。
- 不打开 SSE 连接。
- 不读取浏览器全局对象。
- 不直接显示 toast。
- 相同输入事件序列必须得到相同 `ChatState`。

网络调用和副作用放在 hooks / adapters 中。

## 聊天组件模块边界

### `AgentSessionContainer`

职责：

- 接收 `sessionId`、`profileId`、`headEventId`。
- 调用 `agent-session-service` 加载 session、branch heads 和 branch events。
- 建立 session event SSE。
- 将 API 响应和 stream 消息转换为 `ChatEvent`。
- 持有 `useReducer(chatReducer)`。
- 向 `RuntimeMainPane` 传入 `state` 与 `dispatchCommand`。

### `RuntimeMainPane`

职责：

- 布局聊天主区域。
- 组合 `RunStatusBar`、`MessageViewport`、`ChatComposerPanel`、`BranchHeadSelector`。
- 不直接调用 API。
- 不解析 session event payload。

### `MessageViewport`

职责：

- 根据 `messageOrder` 渲染消息列表。
- 将消息视图交给 `MessageSurface`。
- 处理加载态、空态、错误态、滚动定位和自动滚动。

### `MessageSurface`

职责：

- 渲染用户、assistant、system 消息。
- 渲染 assistant streaming 状态。
- 渲染 thinking block、tool block、artifact block。
- 渲染错误状态。
- 不持有网络状态。

### `ToolCallCard`

职责：

- 根据 `ChatToolCallView` 渲染工具名、参数、输出、状态。
- 展示 running / completed / failed / cancelled。
- 不执行工具调用。

### `ChatComposerPanel`

职责：

- 管理输入框临时文本。
- 提交时发出 `ChatCommand`。
- 根据 run 状态控制输入禁用。
- 不直接调用 `agent-session-service`。

## ChatCommand 模型

组件对外发出命令，container 把命令转换为 API 调用或本地事件。

```ts
export type ChatCommand =
  | { type: 'submit_user_message'; text: string }
  | { type: 'abort_run'; runId: string }
  | { type: 'switch_branch'; headEventId: string }
  | { type: 'reload_session' }
```

命令处理规则：

- `submit_user_message` 由 container 先生成 `clientMessageId`，再调用 `POST /api/agent/sessions/{sessionId}/messages`。
- `abort_run` 调用 `POST /api/agent/runs/{runId}/abort`。
- `switch_branch` 更新 `headEventId` 并重新加载 branch events。
- `reload_session` 重新加载 session 与当前 branch。

消息提交确认规则：

- `AgentUserMessageSubmitDTO` 必须携带 `clientMessageId`。
- `AgentMessageSubmitResultDTO` 必须回传相同 `clientMessageId` 与新建 `run`。
- reducer 使用 `clientMessageId` 清理 `pendingClientMessageIds` 并对齐 optimistic user message。

## session event 到 ChatEvent 的适配

`session-event-to-chat-event.ts` 负责解析后端 `AgentSessionEventDTO`：

| 后端 eventType | ChatEvent |
| --- | --- |
| `set_agent_info` | `system_message_set` |
| `set_model_info` | `model_info_set` |
| `assistant_start` | `assistant_started` |
| `assistant_start.userMessages` | `user_messages_replayed` |
| `assistant_delta` | `assistant_delta` |
| `assistant_end` | `assistant_ended` |
| `assistant_error` | `assistant_failed` |
| `tool_start` | `tool_started` |
| `tool_delta` | `tool_delta` |
| `tool_end` | `tool_ended` |
| `tool_error` | `tool_failed` |
| `abort` | `abort_applied` + `run_status_changed` |

历史加载规则：

- `session_loaded` 只承载 `session`、`branchHeads` 和当前 `headEventId`。
- branch 历史 `AgentSessionEventDTO[]` 不直接进入 reducer。
- container 先 dispatch `session_loaded`，再将历史 events 通过 adapter 展开为一串 `ChatEvent` 顺序 replay。

payload 解析规则：

- `payloadJson` 只在 adapter 中解析。
- adapter 输出强类型 `ChatEvent`。
- reducer 不接触后端 payload JSON 字符串。
- 解析失败转换为 `assistant_failed` 或组件级错误事件。

投影规则：

- `set_agent_info` 生成 system 级 ChatEvent；默认由 selector 决定是否在主聊天窗展示。
- `assistant_start.userMessages` 按顺序投影为用户消息。
- `assistant_error.message` 追加到当前 open assistant 文本中。
- `abort` 到达时关闭所有 open assistant / tool，并分别追加 `[assistant response interrupted]`、`[tool execution interrupted]`。

## Session SSE 规则

- stream endpoint：`GET /api/agent/sessions/{sessionId}/stream?headEventId={headEventId}&afterSeq={afterSeq}`。
- 首包必须是 `session.snapshot`。
- `session.snapshot` 包含当前 `session`、`heads`、当前 branch `events` 与最新 `run`。
- `session.updated` 只发送新增 `events` 与变更后的 `run`。
- `session.heartbeat` 只发送 `heartbeatTime`。
- 前端用 `seq` 去重；SSE 重连时带 `afterSeq`，服务端无法补发时重新下发 `session.snapshot`。

## 状态管理规则

- `React Query` 管理 profile/env/session/run 的服务端查询缓存。
- `AgentSessionContainer` 管理单个聊天会话运行态。
- 跨页面偏好状态只保存最近打开的 profile、侧栏折叠、主题、输入草稿。
- 聊天消息流状态不放入全局 store，避免多个 session 相互污染。
- 后端 canonical store 是 session event tree，前端刷新后通过事件重放恢复聊天视图。

## 样式与组件规则

- `design-system/components` 不包含业务文案和 API 逻辑。
- 业务组件优先使用 className 与设计变量，不在大组件中堆叠大量 inline style。
- 页面文件不承载大段业务渲染，复杂区域拆到领域组件。
- 单个 TSX 文件超过一个稳定功能区域时拆分组件。
- 聊天组件的输入、消息列表、工具卡片、运行状态条、事件流桥接必须分文件维护。

## 前端最小闭环页面

| 页面 | 路由 | 组件 |
| --- | --- | --- |
| Studio 首页 | `/` | `studio-home-page.tsx` |
| Agent Profile 管理 | `/agent/profiles` | `agent-profile-page.tsx` |
| 环境与工具能力 | `/agent/envs` | `agent-env-page.tsx` |
| Agent Session | `/agent/sessions/:sessionId` | `agent-session-page.tsx` + `AgentSessionContainer` |

## 可测试边界

- `chat-reducer.ts` 使用纯函数单测覆盖事件序列。
- `session-event-to-chat-event.ts` 使用 payload fixture 覆盖后端事件适配。
- `ChatComposerPanel` 使用组件测试覆盖提交、禁用、清空输入。
- `ToolCallCard` 使用视图模型测试覆盖各状态渲染。
- `agent-session-service.ts` 使用 mock client 测试请求路径和 DTO 映射。
- `agent-session-sse-service.ts` 使用 EventSource mock 覆盖 snapshot、updated、heartbeat 与 malformed payload。
- `MessageSurface` 使用组件测试覆盖 streaming placeholder、thinking block、tool block、artifact block。
