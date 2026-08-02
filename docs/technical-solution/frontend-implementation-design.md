# 前端落地设计

本文描述 `frontend/` 当前的 Chat 工作区、Pane、Thread snapshot、逐消息设置和 transcript 投影。运行时类型与持久化语义见 [harness-runtime-architecture.md](harness-runtime-architecture.md)。

## 1. 前端摘要

| 主题 | 当前实现 |
| --- | --- |
| 工程 | 独立 Vite/TypeScript 工程，发布时由 Maven 嵌入 `web` |
| 服务端状态 | React Query |
| 本地状态 | `localStorage` 中按 Chat 保存的八个 Pane 槽位与全局 Locale |
| Catalog API | `/api/ai/catalog/providers`、`/models`、`/agents`、`/tools` |
| Chat API | `/api/ai/chat` |
| Runtime API | `/api/ai/runtime/threads`、`snapshot`、`environment`、`sessions`、`interactions`、`tool-invocations`、`usage` |
| Realtime | REST snapshot first；durable revision SSE + 无 id 的 Redis realtime overlay |
| 浏览器路由 | `BrowserRouter`，服务端对 SPA 路径回退 `index.html` |
| 视觉规范 | [前端设计规范](../product-design/frontend-design-system.md) |

## 2. Catalog 与 Chat 设置

Catalog 以名称为身份：

- Provider 和 Agent 使用 immutable `name`。
- Model 使用 `(providerName, name)`，前端显示/引用为 `providerName/modelName`。
- Model ref 的 canonical 解析只切第一个 `/`。
- Catalog DTO 不包含 bigint resource ID；版本字段仍以十进制字符串用于并发更新。

Chat DTO 保存并展示唯一的发送设置：

```ts
interface ChatDTO {
  id: string
  title: string | null
  agentName: string
  yoloEnabled: boolean
  version: string
}
```

Chat 编辑器更新这两项设置时携带 `expectedVersion`。Thread snapshot 单独展示 nullable `environmentName`；静止 Thread 通过 `/api/ai/runtime/threads/{threadId}/environment` 设置或清除它。Agent 编辑器只维护 Agent 的 system prompt、Model ref、variant、tools 与 skills；Model/Variant/Tool/Skill 的选择由 Agent 决定。

## 3. Chat 工作区与 Pane

`ChatPaneState` 按 `chatId` 存在 `localStorage`：

| 字段 | 语义 |
| --- | --- |
| `layout` | `single`、`split-2`、`split-3`、`grid-4`、`grid-6`、`grid-8` |
| `focusedPaneId` | 当前焦点 Pane |
| `panes` | 固定 `pane-1..pane-8`，每项保存 `threadId: string \| null` |
| `sessionSort` / `threadSort` | `recent` 或 `created` |

服务端不保存 Pane。进入 Chat 时，已有 localStorage Thread 绑定通过幂等 `PUT /api/ai/chat/{chatId}/threads/{threadId}` 补建关系；失败不会清掉本地绑定。

## 4. 首发与每次发送

空 Pane 首发的唯一顺序是：

```text
POST /api/ai/chat/{chatId}/threads
  -> 可携带初始 environmentName，返回 Session、非空 head、executionEpoch=0 的已绑定 Thread
POST /api/ai/runtime/threads/{threadId}/messages
  -> 携带 Chat 当前可见 agentName/yoloEnabled
  -> 携带返回 Thread 的 executionEpoch 与 clientMessageId
把 threadId 写入 Pane
```

实现位于 `frontend/src/features/ai/chat/chat-first-send.ts`。服务端第一步已经完成 Session、ROOT、Thread 和 Chat 关系的原子创建，前端不再另行准备运行配置。

绑定 Pane 的每次发送都直接从当前 Chat 设置捕获：

```ts
{
  content,
  agentName,
  yoloEnabled,
  clientMessageId,
  expectedExecutionEpoch,
}
```

CUSTOM message 使用 `/messages/custom`，额外携带 `role: 'system' | 'user'`。Input 与 Entry 中的 `TurnSettings` 只保存 `agentName` 与 `yoloEnabled`；Environment 由 Thread 当前字段提供，消息 DTO 不携带 `environmentName`。每次 planning 再读取最新 Agent、Model、Provider、Variant、ToolCatalog 与 Thread Environment。

HTTP 重试沿用同一 `clientMessageId`，服务端直接返回已存在 Input，保留第一次发送的 TurnSettings。草稿只在相同内容的提交失败重放时复用该 id。

## 5. Thread 操作

| UI 行为 | API |
| --- | --- |
| Thread 列表 | `GET /api/ai/runtime/threads?sort&cursor&limit` |
| Chat 内 Thread 列表 | `GET /api/ai/chat/{chatId}/threads?sort&cursor&limit` |
| 绑定已有 Thread | `PUT /api/ai/chat/{chatId}/threads/{threadId}` |
| 设置/清除 Thread Environment | `PUT /api/ai/runtime/threads/{threadId}/environment` |
| `/session` 或 `/tree` 选择历史 Entry | `PUT /api/ai/runtime/threads/{threadId}/head` |
| `/stop` | `POST /api/ai/runtime/threads/{threadId}/stop` |
| 读取运行态 | `GET /api/ai/runtime/threads/{threadId}/snapshot` |

head 与 Environment 请求始终使用当前 `executionEpoch`。head 仍要求非空 `headEntryId` 与 Thread 静止；Environment 请求体为 `environmentName: string | null` 与 `expectedExecutionEpoch`，mutation pending 期间锁定同一 Pane 的发送，成功后按 revision 合并返回值并刷新 snapshot。任一命令在 Thread 运行中或 epoch 陈旧时返回 `409`，不吞掉冲突。

前端不把 Environment 放入消息请求：Chat 的 Agent/YOLO 修改仍由 Chat API 负责，Thread Environment 通过独立的 fenced command 设置或清除。用户在 Chat 工作区修改可见设置后，后续每条消息都使用最新 `agentName`/`yoloEnabled`；Agent 的 Model、Variant、Tools、Skills 随 Agent 名称在服务端逐轮解析。

## 6. Query 与 realtime

`threads.snapshot(threadId)` 是 Thread 业务状态的唯一 React Query key。恢复顺序：

1. 读取 snapshot；
2. 用 `snapshot.revision` 创建 `EventSource`；
3. revision/resync 事件只 invalidate snapshot；
4. Redis `realtime` 事件叠加流式 text/thinking，不作为业务恢复 cursor。

同一 Thread 出现在多个 Pane 时，snapshot query 可以复用，但每个已挂载 Pane 建立自己的 `EventSource`。

## 7. Transcript 投影

`buildThreadTimeline(entries, inputs, realtime)` 的基线是当前 head 的 Entry path：

| 来源 | 前端行为 |
| --- | --- |
| `MESSAGE` | USER、ASSISTANT、TOOL 语义消息 |
| `CUSTOM_MESSAGE` | SYSTEM/USER 业务上下文消息 |
| `ASSISTANT_ERROR` | 错误气泡与 planning/provider 错误 |
| `ASSISTANT_ABORTED` | 仅 text/thinking 的 partial assistant turn，并标记已停止 |
| `ROOT` | 结构根，不渲染为气泡 |
| QUEUED Input | Working 装饰栏，不进入稳定 transcript |
| Redis realtime | 当前 Model 的临时 text/thinking overlay |
| Artifact ref | 请求 `/api/ai/runtime/artifacts/{artifactId}` 读取 bytes |

Entry 类型集合固定为 `ROOT/MESSAGE/CUSTOM_MESSAGE/ASSISTANT_ERROR/ASSISTANT_ABORTED`；Input 类型集合固定为 `USER_MESSAGE/CUSTOM_MESSAGE`。

## 8. 前端目录

```text
frontend/src
├── app/
├── platform/
├── features/ai/
│   ├── catalog/
│   ├── chat/
│   ├── environment/
│   ├── runtime/
│   └── settings/
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

## 9. 验证

```bash
cd frontend
npm test
npm run lint
npm run build
```

前端 API 契约重点覆盖名称身份、Model ref、逐消息设置、幂等重放、非空 head 与 snapshot-first SSE。
