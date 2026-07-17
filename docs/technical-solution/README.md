# 技术方案

本文档目录维护 `kk-studio` 当前生效的整体技术方案。

## 文档地图

```mermaid
flowchart TD
    A[技术方案入口]
    A --> B[architecture.md<br/>总体架构]
    A --> C[cloud-embedded-agent-runtime.md<br/>Harness 运行时]
    A --> D[backend-implementation-design.md<br/>后端实现]
    A --> E[storage-models.md<br/>存储模型]
    A --> F[frontend-implementation-design.md<br/>前端实现]
    A --> H[harness-extensions.md<br/>Harness 扩展]
    A --> I[prompt-cache-usage-cost.md<br/>Prompt Cache、Usage 与成本账本]
    A --> J[s3-presign.md<br/>S3 预签名直传]
    A --> K[comfyui-workflow-api.md<br/>ComfyUI 工作流与 S3 直传后端]
    A --> L[environment-daemon-gateway.md<br/>Environment Daemon Gateway]
    A --> M[prompt-to-artifact.md<br/>Prompt 到 Artifact 数据流]
```

## 阅读顺序

| 顺序 | 文档 | 关注点 | 适用场景 |
| --- | --- | --- | --- |
| 1 | [architecture.md](architecture.md) | 系统形态、模块边界、最小闭环 | 先建立全局认知 |
| 2 | [cloud-embedded-agent-runtime.md](cloud-embedded-agent-runtime.md) | Harness Run、Tool、Task、Control 与 Daemon 执行链路 | 理解消息提交后发生什么 |
| 3 | [backend-implementation-design.md](backend-implementation-design.md) | `share` / `core` / `web` 的 HTTP、SSE 和 WebSocket 边界 | 修改后端控制面 |
| 4 | [storage-models.md](storage-models.md) | 表结构、资源文件、seed 语义 | 调整存储或初始化脚本 |
| 5 | [frontend-implementation-design.md](frontend-implementation-design.md) | 路由、状态管理、测试边界 | 修改前端页面或 API 适配 |
| 6 | [harness-extensions.md](harness-extensions.md) | typed registry、hook 顺序、factory 与 lifecycle | 扩展 Harness 执行链 |
| 7 | [prompt-cache-usage-cost.md](prompt-cache-usage-cost.md) | cache control、usage、pricing、ledger 与聚合 API | 修改模型调用计量与缓存链路 |
| 8 | [s3-presign.md](s3-presign.md) | 固定 bucket、path-style 的 S3 预签名直传 / 直下载 | 接入浏览器到对象存储的直传链路 |
| 9 | [comfyui-workflow-api.md](comfyui-workflow-api.md) | ComfyUI 工作流卡片 CRUD + 无状态提交 / 查询 / 取消 + S3 输入桥 + job-scoped 输出下载 | 接入 ComfyUI 控制台与 S3 直传后端 |
| 10 | [environment-daemon-gateway.md](environment-daemon-gateway.md) | Environment 注册、Daemon v1 WebSocket、持久 ToolInvocation 分发与回调 | 接入远端 Environment Tool |
| 11 | [prompt-to-artifact.md](prompt-to-artifact.md) | Prompt、Provider、Tool、Artifact 和浏览器读取的端到端事实链 | 修改跨边界执行或 artifact 呈现 |

## 按主题索引

| 主题 | 文档 |
| --- | --- |
| 总体架构 | [architecture.md](architecture.md) |
| Harness 运行时 | [cloud-embedded-agent-runtime.md](cloud-embedded-agent-runtime.md) |
| 后端实现 | [backend-implementation-design.md](backend-implementation-design.md) |
| 存储模型 | [storage-models.md](storage-models.md) |
| 前端实现 | [frontend-implementation-design.md](frontend-implementation-design.md) |
| Harness 扩展 | [harness-extensions.md](harness-extensions.md) |
| Prompt Cache、Usage 与成本账本 | [prompt-cache-usage-cost.md](prompt-cache-usage-cost.md) |
| S3 预签名直传 | [s3-presign.md](s3-presign.md) |
| ComfyUI 工作流与 S3 直传后端 | [comfyui-workflow-api.md](comfyui-workflow-api.md) |
| Environment Daemon Gateway | [environment-daemon-gateway.md](environment-daemon-gateway.md) |
| Prompt 到 Artifact 数据流 | [prompt-to-artifact.md](prompt-to-artifact.md) |

## 维护规则

| 规则 | 说明 |
| --- | --- |
| 与代码一致 | 文档只描述当前主干已生效的职责、协议、约束和实现方案 |
| 不写历史依赖 | 不要求读者了解旧版本、讨论过程或废弃方案 |
| 分层清晰 | 架构、运行时、存储、前后端实现分别维护，避免交叉重复 |
