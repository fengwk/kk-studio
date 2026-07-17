# 领域词汇与双域映射

本文是前后端共用的领域词汇事实源。实现代码与 UI 演示必须能映射到本文，而不是各自发明同名不同构的概念。

## 1. 两个产品域

| 域 | 代码位置 | 职责 | 当前成熟度 |
| --- | --- | --- | --- |
| **Harness / AI** | `harness/*` + `core.harness` + `features/ai` | 会话、Run、Tool、Agent 对话执行 | 已落地 |
| **Studio / Canvas** | `studio` + `core.studio` + `features/canvas` | 画布资源工作台、Function、Workflow | 领域清晰；持久化/执行多为 stub |

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
| `run` | `FUNCTION` | FunctionNode 运行实例视图（演示；对应 `system.agent.execute` 的 Run 呈现） |

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
| `GET /api/canvases?workspaceId=1` | 空列表 |
| Canvas/Workflow/Function 写路径 | `501 Studio feature not ready` |
| Harness `/api/sessions` 等 | 完整 |

## 6. 实现进度一句话

Harness 是完整可恢复执行链；Studio 是清晰的领域骨架 + stub 适配；前端 AI 已接 Harness，前端 Canvas 是带 domainKind 的高保真演示，尚未接 Studio 持久化。
