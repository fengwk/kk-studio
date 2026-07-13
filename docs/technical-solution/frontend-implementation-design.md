# 前端落地设计

本文描述当前 `frontend/` 工程的实际结构、页面范围、后端契约和测试边界。

## 前端摘要

| 主题 | 当前状态 |
| --- | --- |
| 工程目录 | `frontend/` |
| 运行方式 | 独立 Vite 工程 |
| 页面范围 | AI 控制台、Provider/Model/Agent CRUD、Chat 列表、Chat 详情 |
| 服务端状态 | React Query |
| API 来源 | `web` 暴露的 `/api/agent/*` |
| 事件流 | `EventSource` 订阅 `/api/agent/sessions/{sessionId}/events/stream` |
| 视觉来源 | `/mnt/c/Users/fengwk/Downloads/kk-studio-v17.html` 的 AI 模块原型 |

## 产品主线

| 主题 | 当前实现 |
| --- | --- |
| 主目标 | 云端内嵌 agent MVP |
| 数据模型 | 以后端 provider / model / agent / session / event / run 为准 |
| Chat 消息 | 由 session events 投影生成，前端直接可见 `user` / `assistant` / `tool` 三类节点 |
| 刷新方式 | events 使用 SSE 推送，runs 在存在 `queued/running` 时轮询 |

## 技术栈

| 组件 | 角色 |
| --- | --- |
| React 19 | 页面与组件 |
| TypeScript | 类型约束 |
| Vite | 开发与生产构建 |
| React Router 7 | 路由 |
| React Query 5 | 服务端状态管理 |
| Axios | HTTP client |
| EventSource | SSE 事件流 |
| Lucide React | 图标 |
| Vitest + Testing Library | 单元测试与页面测试 |
| ESLint | 静态检查 |

## 工程结构

```text
frontend/src
├── app
├── platform
├── features
│   └── ai
├── shared
│   ├── api
│   └── lib
├── main.tsx
├── styles.css
└── test-setup.ts
```

| 目录 | 职责 |
| --- | --- |
| `app` | 应用装配、providers、router |
| `platform/shell` | 顶部壳层与主框架 |
| `features/ai` | AI 控制台、Chat 详情、事件聚合、聊天组件 |
| `shared/api` | 后端 DTO、HTTP client、agent service、SSE 工厂 |
| `shared/lib` | query keys 等共享工具 |
| `styles.css` | 全局样式 |

## 路由

| 路由 | 页面 | 说明 |
| --- | --- | --- |
| `/` | redirect | 跳转到 `/agent/sessions` |
| `/agent/sessions` | `AiConsolePage` | Chat / session 卡片列表 |
| `/agent/agents` | `AiConsolePage` | Agent CRUD |
| `/agent/models` | `AiConsolePage` | Model CRUD |
| `/agent/providers` | `AiConsolePage` | Provider CRUD |
| `/agent/sessions/:sessionId` | `AgentSessionPage` | 会话详情、事件流消息、run 状态、消息提交 |

## AI 控制台拆分

| 组件 | 职责 |
| --- | --- |
| `AiConsolePage` | 页面壳层、tab 导航、错误态与弹窗装配 |
| `useAiConsoleController` | 搜索态、tab 跳转、四个 panel 的装配 |
| `useAiConsoleResourceController` | Provider / Model / Agent 查询、CRUD、资源编辑弹窗状态 |
| `useAiConsoleSessionController` | Session 查询、CRUD、新建与重命名弹窗状态 |
| `AiConsolePanels` | Chat / Agent / Model / Provider 卡片列表 |
| `AiConsoleModals` | Session 与资源编辑弹窗 |
| `AiResourceForms` | Provider / Model / Agent 结构化录入表单 |

## 结构化录入

Provider / Model / Agent 的录入以可见字段为主，不要求用户直接填写原始 JSON。

| 资源 | 当前录入方式 |
| --- | --- |
| Provider | 常规输入框、下拉选择、密码框、数值输入 |
| Model | Provider / 名称 / 描述 / default variant 输入框与下拉框，variants 使用结构化列表编辑 |
| Agent | 名称 / 描述 / system prompt 输入框，默认 model / variant 下拉框，tools 使用字符串列表编辑 |

## 后端契约

统一响应外层：

```ts
export interface ResultEnvelope<T> {
  status: number
  code: string
  message: string
  data: T
}
```

分页结构：

```ts
export interface PageResult<T> {
  pageNumber: number
  pageSize: number
  totalCount: number | string
  results: T[]
}
```

服务函数与后端接口映射：

| 服务函数 | 后端接口 | 用途 |
| --- | --- | --- |
| `listProviders` / `createProvider` / `updateProvider` / `deleteProvider` | `/api/agent/providers` | Provider CRUD |
| `listModels` / `createModel` / `updateModel` / `deleteModel` | `/api/agent/models` | Model CRUD |
| `listAgents` / `createAgent` / `updateAgent` / `deleteAgent` | `/api/agent/agents` | Agent CRUD |
| `listSessions` / `createSession` / `getSession` / `updateSession` / `deleteSession` | `/api/agent/sessions` | Chat CRUD |
| `createMessage` | `POST /api/agent/sessions/{sessionId}/messages` | 提交用户消息 |
| `listEvents` | `GET /api/agent/sessions/{sessionId}/events` | branch events 快照 |
| `createEventStream` | `GET /api/agent/sessions/{sessionId}/events/stream` | branch events SSE |
| `listRuns` | `GET /api/agent/sessions/{sessionId}/runs` | run 列表 |

## Chat 组件拆分

| 组件 | 职责 |
| --- | --- |
| `AgentSessionPage` | 读取路由参数、装配聊天页 |
| `useAgentSessionController` | 查询 session、订阅 SSE、提交消息、组装页面状态 |
| `ChatSidebar` | 左侧会话列表 |
| `ChatRuntimeBar` | 当前 agent/provider/model/variant 与 run 状态 |
| `ChatTranscript` | user / assistant / tool 消息列表、加载态、错误态、空态 |
| `ChatComposer` | 输入框和发送按钮 |
| `RunStatus` | 最新 run 状态展示 |

## 事件渲染

`AgentSessionPage` 不要求后端提供 message 视图，前端通过 `features/ai/session-events.ts` 将事件流聚合为聊天消息。

| 事件类型 | 前端行为 |
| --- | --- |
| `user_message` | 渲染 user 气泡，读取 `payloadJson.content` |
| `set_agent_info` | 更新当前 runtime agent 摘要 |
| `set_model_info` | 更新当前 provider / model / variant 摘要 |
| `assistant_start` | 创建 assistant 气泡 |
| `assistant_delta` | 追加 assistant 文本增量 |
| `assistant_end` | 将 assistant 气泡置为完成 |
| `assistant_error` | 渲染失败态 assistant 气泡 |
| `tool_start` | 创建 tool 气泡，展示 tool name 与 arguments |
| `tool_delta` | 追加 tool result 文本或媒体预览附件 |
| `tool_end` | 将 tool 气泡置为完成 |
| `tool_error` | 将 tool 气泡置为失败，并保留错误信息 |

## 状态管理

| 主题 | 当前规则 |
| --- | --- |
| server state | 统一交给 React Query |
| provider/model/agent/session 列表 | 分别使用独立 query key |
| session 详情 | `metadata`、`events`、`runs` 分别建独立 query |
| message submit | 成功后失效 session detail、events、runs、session list |
| events 快照 | 页面进入时调用 `listEvents` 重建当前 branch |
| events 流 | `session_event` 合并进 events query cache，按 `eventId` 去重；timeline 直接消费合并后的 event cache |
| runs 刷新 | 存在 `queued/running` 时轮询 |
| message composer | session 存在 active run 时禁用发送，等待当前 run 进入终态 |

## 测试边界

| 层级 | 文件 | 验证目标 |
| --- | --- | --- |
| client 单测 | `client.test.ts` | 响应 envelope 解包、错误消息归一化、HTTP 方法委托 |
| service 单测 | `agent-service.test.ts` | 后端路径与 DTO 映射正确 |
| 事件单测 | `session-events.test.ts` | event -> message 聚合、run 状态 |
| 表单单测 | `AiResourceForms.test.tsx` | Provider / Model / Agent 结构化录入交互 |
| 页面测试 | `AiConsolePage.test.tsx` | provider/model/agent/session CRUD 渲染与交互 |
| 页面测试 | `AgentSessionPage.test.tsx` | 事件渲染、SSE 合并、run 状态、message submit、空态与错误态 |

覆盖率门禁按 `statements / branches / functions / lines >= 80%` 执行。

## 验证命令

```bash
cd frontend
npm run lint
npm run coverage
npm run build
```
