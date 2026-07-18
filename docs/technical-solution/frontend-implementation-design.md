# 前端落地设计

本文描述当前 `frontend/` 工程的页面结构、Harness Thread API 契约、transcript 投影和验证边界。

## 前端摘要

| 主题 | 当前实现 |
| --- | --- |
| 工程目录 | `frontend/` 独立 Vite 工程 |
| 页面范围 | Chat（Thread）、Provider/Model/Agent 资源管理、ComfyUI 工作流 |
| 服务端状态 | React Query |
| 资源 API | `/api/providers`、`/api/models`、`/api/agents` |
| Harness API | `/api/threads`、`/api/sessions`（只读树/activities/tasks）、`/api/tool-invocations`、`/api/usage` |
| 实时通道 | 数据库 cursor 驱动的 Thread Event SSE |
| 视觉实现 | 全局 token 以 Canvas 设计为事实源，见 [前端设计规范](../product-design/frontend-design-system.md)；组件层仍在迁移 |

## 路由

| 路由 | 页面 | 说明 |
| --- | --- | --- |
| `/` | redirect | 跳转至 `/threads` |
| `/threads` | Chat 列表 | 全局 Thread 列表、新建 Thread |
| `/threads/:threadId` | Chat 详情 | Thread transcript、处理状态、Task Timeline、工具权限、Usage |
| `/agents` | Agent 管理 | Agent CRUD |
| `/models` | Model 管理 | Model CRUD |
| `/providers` | Provider 管理 | Provider CRUD |
| `/comfyui` | ComfyUI 工作流 | 独立工作流运行时 |

扩展注册：`frontend/src/features/ai/extensions/ai-extension.tsx`（`ai.threads`、`ai.thread` 等）。

## API 边界

`shared/api/agent-service.ts` 承载 Provider、Model、Agent 与 Model 级 Usage。`shared/api/harness-service.ts` 是 Harness Thread / Session 观测的唯一前端边界。

| Harness service | HTTP 接口 | 用途 |
| --- | --- | --- |
| `listThreads` / `createThread` / `getThread` | `GET` / `POST /api/threads`、`GET /api/threads/{id}` | Thread 列表、创建与读取 |
| `listSessionThreads` | `GET /api/sessions/{id}/threads` | Session 上的 Thread |
| `submitThreadMessage` | `POST /api/threads/{id}/messages` | 入队用户消息（202；`clientMessageId` 幂等） |
| `setThreadYolo` / `setThreadAgent` | `PUT /api/threads/{id}/yolo`、`/agent` | 入队设置变更（202） |
| `listThreadEntries` | `GET /api/threads/{id}/entries` | root→head 路径 Entries |
| `listThreadInputs` | `GET /api/threads/{id}/inputs` | 有序 inputs（含 pending） |
| `listThreadEvents` | `GET /api/threads/{id}/events?afterEventId=` | ThreadEvent 快照 |
| `createThreadEventStream` | `GET /api/threads/{id}/events/stream?afterEventId=` | SSE；event name `thread_event`，id = `eventId` |
| `listThreadToolInvocations` | `GET /api/threads/{id}/tool-invocations` | Thread 工具投影 |
| `getThreadUsage` | `GET /api/usage/threads/{id}` | Thread Usage/Cost |
| `listSessions` / `getSession` / `listSessionEntries` | `GET /api/sessions`、`/{id}`、`/{id}/entries` | Session 只读 |
| `listRootActivities` | `GET /api/sessions/{id}/activities` | Root Activity 快照 |
| `listSessionTasks` | `GET /api/sessions/{id}/tasks` | 直接子 SubagentTask |
| `decideToolInvocation` | `POST /api/tool-invocations/{id}/decision` | allow / deny |

所有 Snowflake ID 在 TypeScript 契约中保持十进制字符串；前端不将 ID 作为 JavaScript number 使用。

## Chat transcript

### 投影公式

`buildThreadTimeline(entries, inputs, threadEvents)`（`thread-timeline-builder.ts`）：

```text
transcript =
  path Entries（语义基线）
  + 尚未物化的 USER_MESSAGE inputs
  + active ThreadEvents（流式 / 工具覆盖层）
```

| 来源 | 前端行为 |
| --- | --- |
| `message` / `agent_snapshot` / `compaction` Entry | 稳定气泡与冻结摘要 |
| unapplied `user_message` input | 立即显示用户气泡（`pendingInput`），Entry 出现后抑制 |
| `assistant_started` / `assistant_delta_batch` | 流式 assistant / thinking；以 `subjectEntryId` 关联 |
| `assistant_completed` / Entry 物化 | 抑制对应 stream 覆盖层 |
| `assistant_failed` | 标记流式 assistant 错误 |
| `tool_*` / `permission_*` | 工具与权限覆盖；Entry 物化后抑制 |
| Tool Result artifact | 映射 `/api/artifacts/{artifactId}` 原生预览或文件链 |

连续重叠提交：composer 使用 `clientMessageId` 与 202 入队，不等待前一轮处理结束；pending inputs 与 processing 状态可同时可见。

### SSE cursor 恢复

先分页 `listThreadEvents` 拉到末页，再用最后一个后端事件的 `eventId` 打开 EventSource。收到 `thread_event` 后以 `eventId` 字符串去重并按接收顺序追加；浏览器重连携带 `Last-Event-ID`。REST page 与 SSE 均以后端 journal 顺序为准，前端不按时间、sequence 或 ID 重排。分页前进校验使用字符串位数 + 字典序比较 `eventId`，**绝不**转为 JavaScript number。

终态类事件与 idle/failed 触发 entries / inputs / tool / usage query 失效，使 durable 基线重新成为唯一显示结果。

## Root Activity 与 Subagent Task

详情页使用 `RootActivityDTO` 与 `listSessionTasks` 构建任务时间线（`useHarnessTaskTimeline`、`subagent-task-tree.ts`、`TaskTimelinePanel.tsx`）。从 Thread 所属 root Session 递归拉取直接任务；路径集合阻断循环。权限 relay 调用同一 Tool decision API；刷新后由 REST 快照重建。

## 运行观测与交互

`useHarnessThreadObservability`：

- YOLO 通过 `setThreadYolo` 入队（202），非即时 Session 字段。
- `WAITING_APPROVAL` Invocation 展示 allow / deny。
- Thread Usage 显示 token、cache 与 Cost。
- Tool artifact 在时间线内预览；不在投影时丢弃未知 media type。

前端不维护可恢复 EventBus 或租约状态机；断线、刷新后由 REST + SSE 重建。

## 工程结构

```text
frontend/src
├── app/router.tsx
├── platform/
├── features/ai
│   ├── extensions/ai-extension.tsx
│   ├── AgentThreadPage.tsx
│   ├── useAgentThreadController.ts
│   ├── useAgentThreadQueries.ts
│   ├── useAgentThreadMessageMutation.ts
│   ├── useAiConsoleController.ts
│   ├── useAiConsoleThreadController.ts
│   ├── useAiConsoleThreadQueries.ts
│   ├── useAiConsoleThreadMutations.ts
│   ├── useHarnessThreadEventStream.ts
│   ├── useHarnessThreadObservability.ts
│   ├── useHarnessTaskTimeline.ts
│   ├── harness-thread-event-stream.ts
│   ├── thread-events.ts / thread-event-types.ts / thread-event-payload.ts
│   ├── thread-timeline-builder.ts
│   ├── thread-panel/          ThreadPanel, Composer, Transcript, ...
│   ├── AiConsoleThreadCard.tsx / AiConsoleThreadModals.tsx / AiConsolePanels.tsx
│   ├── ChatObservabilityPanel.tsx
│   └── TaskTimelinePanel.tsx
├── shared/api
│   ├── agent-service.ts
│   ├── harness-service.ts
│   └── contracts.ts
├── shared/lib/query-keys.ts
└── styles.css
```

`queryKeys.threads.*` 覆盖 list/detail/entries/inputs/events/toolInvocations；`queryKeys.usage.thread` 覆盖用量。

## 验证

```bash
cd frontend
npm test
npm run lint
npm run build
npm run coverage
```

前端覆盖率门禁：statements / branches / functions / lines 均不低于 80%。
