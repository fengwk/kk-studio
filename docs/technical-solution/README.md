# 技术方案

本文档目录维护 `kk-studio` 已落地实现与当前批准的目标落地设计；每份文档必须明确自身边界。

## 文档地图

```mermaid
flowchart TD
    A[技术方案入口]
    A --> B[architecture.md<br/>总体架构]
    A --> H[infinite-canvas-implementation-design.md<br/>无限画布与 Workflow]
    A --> C[cloud-embedded-agent-runtime.md<br/>运行时]
    A --> D[backend-implementation-design.md<br/>后端实现]
    A --> E[storage-models.md<br/>存储模型]
    A --> F[frontend-implementation-design.md<br/>前端实现]
    A --> G[agent-engine.md<br/>agent 内核]
```

## 阅读顺序

| 顺序 | 文档 | 关注点 | 适用场景 |
| --- | --- | --- | --- |
| 1 | [architecture.md](architecture.md) | 系统形态、模块边界、最小闭环 | 先建立全局认知 |
| 2 | [infinite-canvas-implementation-design.md](infinite-canvas-implementation-design.md) | Canvas、Resource、Function、Workflow 和 Agent Port | 实现无限画布与 Workflow |
| 3 | [cloud-embedded-agent-runtime.md](cloud-embedded-agent-runtime.md) | embedded runtime 执行链路 | 理解 message submit 后发生什么 |
| 4 | [backend-implementation-design.md](backend-implementation-design.md) | `share` / `core` / `web` 结构与 API | 修改后端控制面 |
| 5 | [storage-models.md](storage-models.md) | 表结构、资源文件、seed 语义 | 调整存储或初始化脚本 |
| 6 | [frontend-implementation-design.md](frontend-implementation-design.md) | 路由、状态管理、测试边界 | 修改前端页面或 API 适配 |
| 7 | [agent-engine.md](agent-engine.md) | `agent` 模块执行内核 | 深入运行时与事件语义 |

## 按主题索引

| 主题 | 文档 |
| --- | --- |
| 总体架构 | [architecture.md](architecture.md) |
| 无限画布与 Workflow | [infinite-canvas-implementation-design.md](infinite-canvas-implementation-design.md) |
| 运行时 | [cloud-embedded-agent-runtime.md](cloud-embedded-agent-runtime.md) |
| 后端实现 | [backend-implementation-design.md](backend-implementation-design.md) |
| 存储模型 | [storage-models.md](storage-models.md) |
| 前端实现 | [frontend-implementation-design.md](frontend-implementation-design.md) |
| agent 内核 | [agent-engine.md](agent-engine.md) |

## 维护规则

| 规则 | 说明 |
| --- | --- |
| 状态准确 | 已落地文档与当前代码一致；目标落地设计明确标注为实施契约，不声称对应类、表或 API 已存在 |
| 不写历史依赖 | 不要求读者了解旧版本、讨论过程或废弃方案 |
| 分层清晰 | 架构、运行时、存储、前后端实现分别维护，避免交叉重复 |
