# 文档导航

从你要完成的任务出发选择文档。每个主题只有一份事实源；上级文档帮助建立心智模型，
具体参数和实现细节留在对应模块或运行指南中。

## 我现在要做什么

| 任务 | 从这里开始 |
| --- | --- |
| 第一次运行并完成对话 | [项目 README](../README.md)，随后查看 [本地栈](../deploy/local/README.md) |
| 把本机文件、命令和 LSP 提供给 Agent | [Environment Daemon 安装与运行](operations/environment-daemon.md) |
| 修改源码并选择合适的检查 | [开发与测试](operations/development-and-testing.md) |
| 新增构建期 Plugin 并判断是否需要修改 Settings 前端 | [Platform：新增 Plugin](modules/platform.md#新增-plugin)，随后查看 [Frontend：Plugin 设置](modules/frontend.md#plugin-设置) |
| 构建 Fat JAR、容器或服务器部署 | [部署与运行](operations/deployment.md) |
| 运行隔离的 Canvas/Storage 测试栈 | [Canvas/Storage 隔离测试栈](../deploy/test/README.md) |
| 理解一次请求如何执行和恢复 | [系统设计](system-design.md) |
| 报告安全漏洞 | [Security Policy](../SECURITY.md) |

## 按代码区域理解系统

先读[系统设计](system-design.md)，再进入正在修改的代码区域。

### Agent 状态机与扩展

| 文档 | 回答的问题 |
| --- | --- |
| [Harness Runtime](modules/harness-runtime.md) | Session、Entry Tree、Thread、Invocation、Work 和 Processor 如何组成 Agent Loop？ |
| [Harness Infra](modules/harness-infra.md) | Runtime 状态如何映射到 PostgreSQL，并通过 claim、lease 和 realtime 恢复？ |
| [Harness Provider](modules/harness-provider.md) | 模型请求、SSE、reasoning replay 和上游错误如何处理？ |
| [Harness Tool](modules/harness-tool.md) | Tool 的身份、定义、调用、校验和结果采用什么统一契约？ |
| [Harness Contributor API](modules/harness-contributor-api.md) | Builtin、构建期 Plugin 与 Trusted Contributor 如何在启动时注册并冻结为 catalog？ |
| [Harness Builtin](modules/harness-builtin.md) | 内置工具、Goal、Skill 和 Subagent 如何接入 Contributor 模型？ |
| [Harness Common](modules/harness-common.md) | Prompt、严格 JSON、ResourceRef、ResultContent 与 InputSchema 共享哪些值契约？ |
| [Harness MCP](modules/harness-mcp.md) | 无状态 MCP client 如何处理总预算、取消与结果映射？ |

### Environment 与主机能力

| 文档 | 回答的问题 |
| --- | --- |
| [Harness Environment](modules/harness-environment.md) | Environment 身份、能力目录和 protocol v1 如何定义？ |
| [Environment Server](modules/harness-environment-server.md) | Backend 如何管理 Daemon 会话、route lease、调用所有权和上传票据？ |
| [Harness Daemon](modules/harness-daemon.md) | 独立主机进程如何执行文件、命令、LSP 与二进制上传？ |

### Canvas 与数据契约

| 文档 | 回答的问题 |
| --- | --- |
| [Canvas Core](modules/canvas-core.md) | Graph 聚合、typed command、版本和 Function ports 如何定义？ |
| [Canvas Infra](modules/canvas-infra.md) | Graph 如何持久化，Function run 如何 claim、heartbeat 和终结？ |
| [Schema](modules/schema.md) | 唯一 Flyway baseline、表关系、seed 与数据库约束是什么？ |
| [Share](modules/share.md) | 浏览器与服务端共享的 DTO 和 JSON wire 如何保持严格、稳定？ |

### 应用边界

| 文档 | 回答的问题 |
| --- | --- |
| [Platform](modules/platform.md) | Catalog、Plugin、Chat、Project、Storage、Environment 与外部系统如何编排？ |
| [Web](modules/web.md) | Spring Boot composition root 如何按 Maven dependency 装配可选 Plugin、HTTP、WebSocket、Worker 和生命周期？ |
| [Frontend](modules/frontend.md) | 浏览器如何把 durable Snapshot 与 lossy realtime 合并为可恢复体验？ |

## 仓库与文档维护

- [Apache License 2.0](../LICENSE) 说明代码许可。
- 入口、协议或行为变化时，同步更新其唯一事实源和所有指向它的导航。
- 本地源码、测试、脚本和配置在文档中使用可点击的相对链接；协议值、命令和配置键使用
  反引号。
- E2E case inventory 由
  [`scripts/e2e/run-matrix.mjs`](../scripts/e2e/run-matrix.mjs) 的 `--list` / `--docs`
  输出生成，文档只说明如何选择和运行矩阵。
- 从仓库根目录执行 [`scripts/docs/check.mjs`](../scripts/docs/check.mjs) 检查固定布局、
  标题、链接、模块拓扑和禁止的旧引用。
