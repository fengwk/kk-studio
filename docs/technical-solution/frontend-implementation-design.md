# 前端落地设计

本文描述当前 `frontend/` 工程的页面结构、Harness API 契约、持久化时间线投影和验证边界。

## 前端摘要

| 主题 | 当前实现 |
| --- | --- |
| 工程目录 | `frontend/` 独立 Vite 工程 |
| 页面范围 | Chat、Provider/Model/Agent 资源管理、ComfyUI 工作流 |
| 服务端状态 | React Query |
| 资源 API | `/api/providers`、`/api/models`、`/api/agents` |
| Harness API | `/api/sessions`、`/api/runs`、`/api/tool-invocations`、`/api/usage` |
| 实时通道 | 数据库 cursor 驱动的 Run SSE |
| 视觉实现 | `styles.css` 与 AI Extension Host 组件 |

## 路由

| 路由 | 页面 | 说明 |
| --- | --- | --- |
| `/` | redirect | 跳转至 `/sessions` |
| `/sessions` | Chat 列表 | 全局根 Session、新建 Session |
| `/sessions/:sessionId` | Chat 详情 | Entry 时间线、Run 状态、工具、权限、Usage/Cost |
| `/agents` | Agent 管理 | Agent CRUD |
| `/models` | Model 管理 | Model CRUD |
| `/providers` | Provider 管理 | Provider CRUD |
| `/comfyui` | ComfyUI 工作流 | 独立工作流运行时 |

## API 边界

`shared/api/agent-service.ts` 只承载 Provider、Model、Agent 与 Usage 的资源接口。`shared/api/harness-service.ts` 是 Harness 会话、Run、观测和控制的唯一前端边界。

| Harness service | HTTP 接口 | 用途 |
| --- | --- | --- |
| `listSessions` / `createSession` / `getSession` | `GET` / `POST /api/sessions`、`GET /api/sessions/{id}` | 根 Session 列表、创建与读取 |
| `listEntries` | `GET /api/sessions/{id}/entries` | 完整持久 Session Entry 时间线 |
| `createMessage` | `POST /api/sessions/{id}/messages` | 以 `expectedLeafEntryId` 提交用户消息 |
| `listRuns` | `GET /api/sessions/{id}/runs` | Session Run 状态 |
| `listRunEvents` | `GET /api/runs/{id}/events?afterSequence=` | Run Event 快照与 cursor 恢复 |
| `createRunEventStream` | `GET /api/runs/{id}/events/stream?afterSequence=` | Run Event SSE；服务端 event id 是 sequence |
| `listToolInvocations` | `GET /api/runs/{id}/tool-invocations` | 工具、目标类型与 Environment 绑定 |
| `decideToolInvocation` | `POST /api/tool-invocations/{id}/decision` | 持久化 allow / deny 决策 |
| `getYolo` / `setYolo` | `GET` / `PUT /api/sessions/{id}/yolo` | 根 Session YOLO 策略 |
| `getSessionUsage` | `GET /api/usage/sessions/{id}` | Token、cache 与 Cost 汇总 |
| `GET /api/artifacts/{id}` | Artifact bytes | Tool artifact 的原始媒体资源 |

所有 Snowflake ID 在 TypeScript 契约中保持十进制字符串；前端不将 ID 作为 JavaScript number 使用。

## Chat 时间线

### 持久基线与实时覆盖层

`HarnessSessionEntryDTO[]` 是聊天历史的唯一基线：`message` Entry 投影为 user、assistant、tool 气泡，`agent_snapshot` 提供冻结模型/variant 摘要，`compaction` 提供系统摘要。Tool Result 中的 artifact 内容映射到 `/api/artifacts/{artifactId}`，浏览器直接加载媒体，不读取或重组 artifact bytes。

活动 Run 的 `RunEventDTO[]` 只用于尚未物化的实时覆盖层：

| Run Event | 前端行为 |
| --- | --- |
| `assistant_started` / `assistant_delta_batch` | 创建并追加流式 assistant 文本与 thinking |
| `assistant_failed` | 标记当前流式 assistant 为错误 |
| `tool_prepared` / `tool_started` | 显示待执行或执行中的工具调用 |
| `tool_delta_batch` | 追加部分 Tool Result 文本与 artifact 引用 |
| `tool_completed` | 标记临时工具节点完成或失败 |

每次 `assistant_completed` 都已经与语义 Assistant Entry 原子持久化。投影器仅处理最后一个 `assistant_completed` 之后的 Run Event，避免 SSE 回放重复渲染已物化的 Assistant 内容。

### SSE cursor 恢复

页面先使用 `listRunEvents` 获取快照，再用当前最大 `sequence` 打开 EventSource。收到 `run_event` 后以 sequence 去重并排序；浏览器自动重连时携带上一个 SSE event id，服务端从数据库 cursor 继续发送。终态 Event 会失效 Session、Entry、Run 与 Session 列表 query，使持久化基线重新成为唯一显示结果。

## 运行观测与交互

Chat 详情的观测栏直接读取持久事实：

- YOLO 开关更新根 Session 的持久策略。
- `WAITING_APPROVAL` Invocation 显示 tool、target type、Environment ID 与 allow / deny 操作。
- Session Usage 显示输入/输出 token、cache read 与聚合 Cost。
- Tool artifact 在时间线内显示 image、audio、video 预览和原始内容链接。

前端不维护可恢复的 EventBus、工具状态机或租约；断线、刷新和重连后全部状态由 REST 快照和数据库驱动的 SSE 重建。

## 工程结构

```text
frontend/src
├── app
├── platform
├── features/ai
│   ├── useAgentSessionController.ts
│   ├── useHarnessRunEventStream.ts
│   ├── useHarnessSessionObservability.ts
│   ├── session-timeline-builder.ts
│   └── ChatObservabilityPanel.tsx
├── shared/api
│   ├── agent-service.ts
│   ├── harness-service.ts
│   └── contracts.ts
├── shared/lib/query-keys.ts
└── styles.css
```

## 验证

```bash
cd frontend
npm test
npm run lint
npm run build
npm run coverage
```

前端覆盖率门禁使用 statements / branches / functions / lines 均不低于 80%。
