# 领域词汇与双域映射

本文是前后端共用的领域词汇事实源。实现代码与 UI 演示必须能映射到本文，而不是各自发明同名不同构的概念。

## 1. 两个产品域

| 域 | 代码位置 | 职责 | 当前成熟度 |
| --- | --- | --- | --- |
| **Harness / AI** | `harness-tool` / `harness-runtime` / `harness-daemon` + `core.harness` + `features/ai` | Session Entry Tree、可复用 HarnessThread、Model/Tool Invocation、Interaction | 现行契约 |
| **Studio / Canvas** | `studio` + `core.studio` + `features/canvas` | 画布资源工作台、Function、Workflow | Canvas 最小持久化已落地；其余 Runtime 待补 |

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
| 单实例 | 当前产品无多租户 membership |
| 默认 ID | `StudioWorkspaces.DEFAULT_ID = 1` / 前端 `DEFAULT_WORKSPACE_ID = '1'` |
| API | 仍接收 `workspaceId` 以便未来扩展；非默认值拒绝 |

## 3. Studio 核心词汇

| 概念 | 含义 |
| --- | --- |
| CanvasDocument | 画布文档身份 + revision |
| CanvasNodeKind | `RESOURCE` / `FUNCTION` / `GROUP` |
| CanvasLink | 可见性边：source 的 Resource 对 target 可见 |
| ResourceReference | 实际依赖：target 某输入真正使用的 Resource |
| Resource | 稳定逻辑身份 |
| ResourceVersion | 不可变内容版本 |
| FunctionRef | `functionId + version` |
| FunctionRun | 一次 Function 执行 |
| Workflow | 独立程序；发布后成为 Function |

Link ≠ Reference。演示层若只做连线，必须标注为 visibility。

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
| `frame` | `GROUP` | GroupNode |
| `web` / `image` / `file` / `text` / `matrix` / `result` | `RESOURCE` | ResourceNode |
| `generator` | `FUNCTION` | FunctionNode（`system.generate-*`） |
| `run` | `FUNCTION` | FunctionNode 运行实例视图（演示；对应 `system.agent.execute` 的 FunctionRun 呈现） |

| 前端对象 | 后端对象 |
| --- | --- |
| `CanvasLink.role='visibility'` | `CanvasLink` |
| （未建模） | `ResourceReference` |
| generator mode text/image/video | `system.generate-text/image/video` v1 |
| Agent Dock 任务 | `system.agent.execute` v1 |

映射代码：`frontend/src/features/canvas/domain-map.ts`。

## 6. API 边界（当前）

| API | 状态 |
| --- | --- |
| `GET /api/functions?workspaceId=1` | 可用（内存 Catalog） |
| `GET /api/canvases?workspaceId=1`、`GET /api/canvases/{canvasId}` | 可用（持久 Canvas 列表与 snapshot） |
| `POST /api/canvases` | 可用（创建 Canvas） |
| `POST /api/canvases/{canvasId}/commands` | 可用（create text/generate-text/link、move、delete node） |
| Workflow / FunctionRun 写路径 | `501 Studio feature not ready` |
| Harness `/api/sessions` | 创建 Session/ROOT/RUNTIME_CONFIG、Session 列表/详情与 Entry Tree 查询 |
| Harness `/api/threads` | 全局 Thread 列表；创建 UNBOUND Thread |
| Harness `/api/threads/{threadId}` | Thread 读取、bootstrap、`PUT /head` 重定位、mailbox 提交、Entries/Inputs、Redis realtime SSE、Stop；重试策略位于 `/api/harness/retry-policy` |

## 7. 实现进度一句话

Harness 是以 Session Entry Tree、可复用 Thread（可空 head + epoch fencing）、Thread mailbox、Model/Tool Invocation 与 Interaction 为基础的可恢复执行链；PostgreSQL 为 truth，Redis 为 lossy wake/realtime。Studio 的 Canvas、Resource、FunctionRun 与 Workflow 保持独立领域边界。
