# 文档导航

这里是仓库文档的唯一入口。文档只描述当前代码、协议、职责和运行方式；
同一主题只保留一份事实源。

## 阅读顺序

1. [系统设计](system-design.md)：先了解 Maven reactor、逻辑模块、全局不变量
   和跨域主链路。
2. [模块文档](#模块)：按职责阅读相关模块的边界、API、恢复语义和测试入口。
3. [运行文档](#operations)：需要构建、测试、部署或运行 E2E 时阅读 operations。

## 系统

| 文档 | 内容 |
| --- | --- |
| [system-design.md](system-design.md) | 系统边界、依赖、耐久事实、并发、恢复和阅读导航 |

## 模块

| 模块 | 文档 | 内容 |
| --- | --- | --- |
| share | [modules/share.md](modules/share.md) | Public DTO 与 JSON wire |
| schema | [modules/schema.md](modules/schema.md) | Flyway baseline、seed 与数据库约束 |
| canvas-core | [modules/canvas-core.md](modules/canvas-core.md) | Canvas 领域模型、typed command 与 ports |
| canvas-infra | [modules/canvas-infra.md](modules/canvas-infra.md) | Canvas PostgreSQL 适配与 Function runtime |
| frontend | [modules/frontend.md](modules/frontend.md) | React 宿主、feature 边界与浏览器恢复 |
| harness-builtin | [modules/harness-builtin.md](modules/harness-builtin.md) | 第一方内置 17 工具与 Goal 契约 |
| harness-common | [modules/harness-common.md](modules/harness-common.md) | Prompt、JSON、ResourceRef、ResultContent 与 InputSchema 基础契约 |
| harness-contributor-api | [modules/harness-contributor-api.md](modules/harness-contributor-api.md) | Trusted Java Contributor SPI 与 catalog |
| harness-daemon | [modules/harness-daemon.md](modules/harness-daemon.md) | Environment Daemon 与本地工具执行 |
| harness-environment | [modules/harness-environment.md](modules/harness-environment.md) | Environment binding、Capability 与 Daemon v6 wire |
| harness-infra | [modules/harness-infra.md](modules/harness-infra.md) | Harness Store、Work、通知和 ResourceStore |
| harness-runtime | [modules/harness-runtime.md](modules/harness-runtime.md) | Agent Runtime 状态机与 processors |
| harness-tool | [modules/harness-tool.md](modules/harness-tool.md) | Tool identity、descriptor、call/result 与 Tool JSON codecs |
| platform | [modules/platform.md](modules/platform.md) | Application service、gateway 与外部适配 |
| web | [modules/web.md](modules/web.md) | Spring Boot composition root 与 transport |

## Operations

| 文档 | 内容 |
| --- | --- |
| [development-and-testing.md](operations/development-and-testing.md) | 开发、质量、E2E、可靠性、性能和供应链入口 |
| [deployment.md](operations/deployment.md) | Fat JAR、Compose stacks、运行配置和清理 |

## 仓库策略

| 文件 | 内容 |
| --- | --- |
| [LICENSE](../LICENSE) | Apache License 2.0 |
| [SECURITY.md](../SECURITY.md) | 支持范围和私密漏洞报告入口 |

## 维护规则

- 文档路径和源码路径必须与仓库当前布局一致；变更入口或协议时同步更新相关
  模块和 operations 文档。
- 每份文档只保留一个一级标题，并以系统设计为跨模块边界的上级事实源。
- 精确的 E2E case inventory 由
  `node scripts/e2e/run-matrix.mjs --list` 和 `--docs` 生成，不在文档中复制
  case ID 清单。
- 从仓库根目录运行 `node scripts/docs/check.mjs` 检查固定布局、模块拓扑、
  链接、标题和禁止旧路径。
