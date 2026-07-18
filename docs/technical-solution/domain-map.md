# 领域词汇与双域映射

本文是前后端共用的领域词汇事实源。实现代码与 UI 演示必须能映射到本文，而不是各自发明同名不同构的概念。

## 1. 两个产品域

| 域 | 代码位置 | 职责 | 当前成熟度 |
| --- | --- | --- | --- |
| **Harness / AI** | `harness/*` + `core.harness` + `features/ai` | Session Entry Tree、AgentThread、Tool、Agent 对话执行 | 已落地 |
| **Studio / Canvas** | `studio` + `core.studio` + `features/canvas` | 画布资源工作台、Function、Workflow | Canvas 最小持久化已落地；其余 Runtime 待补 |

依赖：

```text
frontend
  → web
    → core
      → studio      (纯领域)
      → harness/*   (执行内核)
  → share          (HTTP DTO)
```

约束：

- `studio` 不依赖 Spring / MyBatis / Harness / web
- `harness` 不依赖 `studio`
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

## 4. 前端演示模型 → Studio 映射

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

## 5. API 边界（当前）

| API | 状态 |
| --- | --- |
| `GET /api/functions?workspaceId=1` | 可用（内存 Catalog） |
| `GET /api/canvases?workspaceId=1`、`GET /api/canvases/{canvasId}` | 可用（持久 Canvas 列表与 snapshot） |
| `POST /api/canvases` | 可用（创建 Canvas） |
| `POST /api/canvases/{canvasId}/commands` | 可用（create text/generate-text/link、move、delete node） |
| Workflow / FunctionRun 写路径 | `501 Studio feature not ready` |
| Harness `/api/threads` | 完整 Thread 创建、mailbox、Entries、Events 与 SSE |
| Harness `/api/sessions` | 只读 Session Tree、activities 与 tasks |

## 6. 实现进度一句话

Harness 是完整可恢复的 AgentThread 执行链；Studio 已具备 Canvas 最小持久化，Resource、FunctionRun 与 Workflow Runtime 仍是领域契约或 stub；前端 AI 已接 Harness，Canvas Library/Create 已接持久 API，Editor snapshot/command 闭环待接。
