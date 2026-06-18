# 技术方案

本文档目录维护 `kk-studio` 的整体技术方案，按阅读顺序组织：

1. `architecture.md`
   - 总体架构与模块边界。
2. `cloud-embedded-agent-runtime.md`
   - 云端内嵌自研 agent、本地 daemon 提供 remote tool 能力的运行时方案。
3. `backend-implementation-design.md`
   - 后端包结构、领域模型、DTO、API 与 remote tool 路由落地方案。
4. `daemon-local-env.md`
   - 本地环境 daemon 的 keepalive、能力注册与 remote tool host 模型。
5. `storage-models.md`
   - core / web 侧的 agent、env、session、event、run、tool call 等表结构与存储模型。
6. `frontend-implementation-design.md`
   - 前端工程结构、聊天组件模块化与事件驱动模型。
7. `agent-engine.md`
   - `agent` 模块执行内核技术方案。

维护方式：

- 维护一套持续演进的整体技术方案文档。
- 模块内部文档也放在同一目录中，作为整体方案的一部分。
