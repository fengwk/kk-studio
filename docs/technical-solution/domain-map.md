# 领域词汇与双域映射

本文是前后端共用的领域词汇事实源。实现代码与 UI 演示必须能映射到本文，而不是各自发明同名不同构的概念。

## 1. 两个产品域

| 域 | 代码位置 | 职责 | 当前成熟度 |
| --- | --- | --- | --- |
| **Harness / AI** | `harness-tool` / `harness-runtime` / `harness-daemon` + `core.ai.runtime` + `features/ai` | Session Entry Tree、可复用 HarnessThread、Model/Tool Invocation、Interaction | 现行契约 |
| **Studio / Canvas** | `studio` + `core.studio` + `features/canvas` | 全局单实例持久化画布（document / node / link / command-dedup） | 现行契约 |

依赖：

```text
frontend
  → web
    → core
      → studio           (纯领域)
      → harness-runtime  → harness-tool
      → harness-tool
  → share               (HTTP DTO)
harness-daemon → harness-tool
```

约束：

- `studio` 不依赖 Spring / MyBatis / Harness / web
- `harness-*` 不依赖 `studio`
- Agent 进入 Studio 只通过 `system.agent.execute` FunctionRef

## 2. Workspace 策略

| 规则 | 说明 |
| --- | --- |
| 全局单实例 | 产品无多租户 membership、无 workspace 表/列 |
| API | 全局 Canvas 列表、详情与命令路径 |

## 3. Studio 核心词汇

| 概念 | 含义 |
| --- | --- |
| CanvasDocument | 画布文档身份 + revision |
| CanvasNodeKind | `RESOURCE` / `FUNCTION` |
| CanvasLink | 可见性边：source 的 Node 对 target 可见 |
| CanvasSnapshot | 文档 + 节点 + 连线 读模型 |
| CanvasCommand | 客户端幂等命令（dedup 事实按 `(canvas_id, command_id)` 唯一） |

FUNCTION 节点当前唯一实例是 `system.generate-text` v1。

## 4. Harness 核心词汇

| 概念 | 含义 |
| --- | --- |
| Session | append-only Entry Tree 的边界；不持有 Thread |
| Entry | append-only 语义事实（含 `RUNTIME_CONFIG`） |
| HarnessThread | 可跨 Session 复用的 durable runtime process：可空 head / mailbox / lease / epoch 控制面；当前 Session 由 head Entry 派生 |
| Branch | 不是独立实体：把某个 Thread 的 head 重定位到历史 Entry |
| ThreadInput | 有序 mailbox 命令 |
| ModelInvocation | 冻结 ProviderRequest 的 durable Provider 调用 |
| ToolInvocation | 单次 ToolCall 的 durable 执行事实 |
| Interaction | 通用人机/外部交互事实 |
| Realtime projection | Redis Streams 有界覆盖层，非 durable journal |

## 5. 前端演示模型 → Studio 映射

前端 `features/canvas` 使用**表现型** `CanvasNodeType`（web/image/generator…），每个节点必须带 `domainKind`。

| 表现 type | domainKind | 后端语义 |
| --- | --- | --- |
| `web` / `image` / `file` / `text` / `matrix` / `result` | `RESOURCE` | ResourceNode（`node_type` 区分） |
| `generator` | `FUNCTION` | FunctionNode；持久化后端只有 `system.generate-text` v1 |

| 前端对象 | 后端对象 |
| --- | --- |
| `CanvasLink` | `CanvasLink`（可见性边） |
| generator 文本生成 | `system.generate-text` v1 |

映射代码：`frontend/src/features/canvas/domain-map.ts`。

## 6. API 边界（当前）

| API | 状态 |
| --- | --- |
| `GET /api/canvases` | 可用（持久 Canvas 列表，按 `updated_at` 倒序） |
| `GET /api/canvases/{canvasId}` | 可用（持久 Canvas snapshot：document / nodes / links） |
| `POST /api/canvases` | 可用（body 仅 `{title}`，创建 Canvas） |
| `POST /api/canvases/{canvasId}/commands` | 可用（create text/generate-text/link、move nodes、delete node；硬删除；按 `(canvas_id, command_id)` 幂等） |
| `/api/functions`、`/api/workflows`、`/api/function-runs` 等 | 暂不暴露 |
| Harness `/api/ai/runtime/sessions` | 仅查询 Session 列表/详情与 Entry Tree；Session/ROOT/RUNTIME_CONFIG 只由 Thread bootstrap 原子创建 |
| Harness `/api/ai/runtime/threads` | 全局 Thread 列表；创建 UNBOUND Thread |
| Harness `/api/ai/runtime/threads/{threadId}` | Thread 读取、bootstrap、`PUT /head` 重定位、mailbox 提交、Entries/Inputs、Redis realtime SSE、Stop；全局策略位于 `/api/ai/runtime/settings/retry-policy` 与 `/api/ai/runtime/settings/realtime-stream-policy` |

## 7. 实现进度一句话

Harness 是以 Session Entry Tree、可复用 Thread（可空 head + epoch fencing）、Thread mailbox、Model/Tool Invocation 与 Interaction 为基础的可恢复执行链；PostgreSQL 同时承载 truth 与 durable activation queue，Redis 仅提供 lossy realtime Stream。Studio 当前只承载 Canvas：document / node / link / command-dedup，硬删除节点并通过同 Canvas 复合 FK 级联清理连线。
