# 文档导航

首次使用从[项目 README](../README.md)开始；理解代码先读[系统设计](system-design.md)。
模块文档说明具体契约和实现，运行指南提供配置、命令与安全操作路径。

## 我现在要做什么

| 任务 | 从这里开始 |
| --- | --- |
| 第一次运行并完成对话 | [项目 README](../README.md)，随后查看 [本地栈](../deploy/local/README.md) |
| 把本机文件、命令和 LSP 提供给 Agent | [Environment Daemon 安装与运行](operations/environment-daemon.md) |
| 修改源码并选择合适的检查 | [开发与测试](operations/development-and-testing.md) |
| 修改页面或共享 UI，保持紧凑与一致 | [前端视觉与交互语言](modules/frontend-design-language.md) |
| 核对内置工具与异步委派的行为契约 | [内置工具与异步委派](modules/builtin-tools-design.md) |
| 运行仓库脚本，先确认命令、前置条件与副作用 | [脚本入口索引](../scripts/README.md) |
| 新增构建期 Plugin 并判断是否需要修改 Settings 前端 | [Platform：新增 Plugin](modules/platform.md#新增-plugin)，随后查看 [Frontend：Plugin 设置](modules/frontend.md#plugin-设置) |
| 构建 Fat JAR、容器或服务器部署 | [部署与运行](operations/deployment.md) |
| 从数据面不兼容的版本升级（导出备份后在全新空库重建） | [部署与运行：从数据面不兼容的版本升级](operations/deployment.md#从数据面不兼容的版本升级) |
| 运行隔离的 Canvas/Storage 测试栈 | [Canvas/Storage 隔离测试栈](../deploy/test/README.md) |
| 理解一次请求如何执行和恢复 | [系统设计](system-design.md) |
| 使用 Canvas 资源与 Function，配置 Issue 阶段和 Agent 分工 | [Canvas、Project 与交互](canvas-project.md) |
| 核对数据库关系、约束与基线 | [Schema](modules/schema.md)及 [`V1__schema.sql`](../schema/src/main/resources/db/migration/V1__schema.sql) |
| 报告安全漏洞 | [Security Policy](../SECURITY.md) |

## 按代码区域理解系统

先读[系统设计](system-design.md)，再进入正在修改的代码区域。

### Agent 状态机与扩展

| 文档 | 回答的问题 |
| --- | --- |
| [Harness Runtime](modules/harness-runtime.md) | Session、Entry Tree、Thread、Invocation、Work 和 Processor 如何推进 Agent？Tool、Contributor 与内置工具约定什么契约？ |
| [Harness Infra](modules/harness-infra.md) | Runtime 状态如何映射到 PostgreSQL，并通过 claim、lease 和 realtime 恢复？ |
| [Harness Provider](modules/harness-provider.md) | 模型请求、SSE、reasoning replay 和上游错误如何处理？ |
| [模型用量与计价](modules/model-pricing.md) | 原生用量、推理与缓存分类如何计价，缺少事实时如何表达未知？ |
| [内置工具与异步委派](modules/builtin-tools-design.md) | 文件窗口读取、LSP 生命周期和持久异步 task 使用什么统一契约？ |
| [Common](modules/common.md) | 严格 JSON、ResourceRef、ResultContent 与 InputSchema 共享哪些值契约？ |

### Environment 与主机能力

| 文档 | 回答的问题 |
| --- | --- |
| [Harness Environment](modules/harness-environment.md) | Environment 身份、能力目录与 Daemon wire 协议如何定义？Backend 如何管理 Daemon 会话、route lease、调用所有权和上传票据？ |
| [Environment Daemon](modules/daemon.md) | 独立主机进程如何执行文件、命令、LSP 与二进制上传？ |

### 平台

| 文档 | 回答的问题 |
| --- | --- |
| [Platform](modules/platform.md) | Catalog、Plugin、Chat、Storage、Environment、MCP 与外部系统如何编排？ |
| [Platform Plugin API](modules/platform-plugin-api.md) | 构建期 Plugin 与宿主共享哪些纯 SPI、Canvas Function SPI、凭据与资源端口契约？ |
| [Schema](modules/schema.md) | 唯一 Flyway baseline、表关系、seed 与数据库约束是什么？ |
| [Durable Work](modules/work.md) | 后台调度如何共用一张 mailbox 表、数据库时钟租约与唤醒通道？ |
| [Share](modules/share.md) | 浏览器与服务端共享的 DTO 和 JSON wire 如何保持严格、稳定？ |

### 应用

| 文档 | 回答的问题 |
| --- | --- |
| [Canvas](modules/canvas.md) | Graph 聚合、typed command 与 Function run 如何定义、持久化和终结？ |
| [Project](modules/project.md) | 配置化阶段、Issue 预算、稳定 Agent Thread 与 Run 如何调度和交接？ |
| [IDE](modules/ide.md) | 同源 code-server 视图如何经 Daemon 命令通道与反向数据流打开和转发？ |

### 组合根与前端

| 文档 | 回答的问题 |
| --- | --- |
| [Web](modules/web.md) | Spring Boot composition root 如何按 Maven dependency 装配可选 Plugin、HTTP、WebSocket、Worker 和生命周期？ |
| [Frontend](modules/frontend.md) | 浏览器如何把 durable Snapshot 与 lossy realtime 合并为可恢复体验？ |
| [Thread 控制与展示](modules/thread-control-and-presentation.md) | 根人工控制、子代理只读、交互归属与消息阅读如何协作？ |

## 仓库与文档维护

- [Apache License 2.0](../LICENSE) 说明代码许可。
- 入口、协议或行为变化时，同步更新其唯一事实源和所有指向它的导航。
- 本地源码、测试、脚本和配置在文档中使用可点击的相对链接；协议值、命令和配置键使用
  反引号。
- E2E case inventory 由
  [`scripts/dev/verify/e2e/run-matrix.mjs`](../scripts/dev/verify/e2e/run-matrix.mjs) 的 `--list` / `--docs`
  输出生成，文档只说明如何选择和运行矩阵。
- 从仓库根目录执行 [`scripts/dev/verify/repository/check.mjs`](../scripts/dev/verify/repository/check.mjs) 检查固定布局、
  标题、链接、模块拓扑和禁止的旧引用。
