# kk-studio

`kk-studio` 是面向可信单用户部署的自托管 AI 工作台。你可以在浏览器中接入模型
Provider，定义 Agent，进行可恢复的流式对话，并按需让 Agent 使用本机文件、命令、
代码检索、LSP、Skill、MCP 和 Subagent。

最短使用链路：

```text
启动 Studio -> Provider -> Model -> Agent -> Chat
                                            |
                                            +-> Branch 选择 Environment（需要主机工具时）
```

> `kk-studio` 的登录鉴权由外部入口承担。默认本地栈只监听 `127.0.0.1`；部署到局域网或
> 公网前，必须配置 TLS 和访问控制。

## 能做什么

| 能力 | 用途 |
| --- | --- |
| Chat 与 Agent | 流式对话、思考内容、附件、历史分支、工具调用，以及可恢复的执行状态 |
| Model Catalog | 分别管理 Provider、真实模型 ID、上下文限制、能力、Variant 与价格 |
| Agent 能力组合 | 为 Agent 选择模型、系统提示词、Tools、Skills 和 Subagents；运行环境由 Branch 选择 |
| Environment | 通过独立 Daemon 在指定主机上执行文件读写、搜索、命令和 LSP 能力 |
| MCP | 注册 Streamable HTTP MCP Server，发现其工具并作为 Agent 可选工具执行 |
| Canvas | 用节点组织文本与媒体，通过资源引用和显式 Function 运行完成裁剪、生成或 ComfyUI 处理 |
| Project / Issue | 配置工作阶段、Agent 分工与执行额度，在稳定 Thread 中完成工作并跟踪交付证据 |

当前内置模型协议包括 OpenAI Chat Completions、OpenAI Responses、Anthropic
Messages 和 Google Gemini。

## 选择运行方式

| 目标 | 入口 | 适合场景 |
| --- | --- | --- |
| 先运行起来 | [`deploy/local`](deploy/local/README.md) | 推荐给首次使用者；Docker Compose 启动 App、PostgreSQL 和 MinIO |
| 修改源码 | [`scripts/dev/app.sh`](scripts/dev/app.sh) | Backend + Vite 开发；需要 JDK 21、Maven、Node/npm、curl、jq、lsof 和已配置的数据服务 |
| 部署到服务器 | [部署与运行](docs/operations/deployment.md) | Fat JAR、容器、多节点、外部 PostgreSQL/S3 与反向代理 |

Environment Daemon 是可选组件。只聊天时不需要安装；需要 Agent 操作某台主机时，再把
Daemon 安装到那台主机。

## 快速开始

前置条件：Git、Docker Engine 和 Docker Compose v2。

```bash
git clone https://github.com/fengwk/kk-studio.git
cd kk-studio
docker compose -f deploy/local/compose.yaml up -d --build --wait
```

首次构建会在容器中下载 Maven、Node 和镜像依赖，本机不需要安装 JDK 或 npm。启动完成后：

- 打开 <http://localhost:8080/>
- 健康检查：`curl -fsS http://localhost:8080/actuator/health`
- 查看 App 日志：
  `docker compose -f deploy/local/compose.yaml logs -f app`

本地栈使用 `dev` profile，并预置 `stub` Provider、`acceptance-stub` Model 和
`default-assistant` Agent，便于查看 Catalog 结构。这些示例配置指向 `stub.local`，
该模型服务需单独提供；直接使用 `default-assistant` 发消息会连接失败。
请先按下一节接入自己的 Provider。

## 完成第一次对话

Catalog 将连接信息、模型能力和 Agent 行为分开管理：

| 资源 | 表示什么 |
| --- | --- |
| Provider | 上游协议、Base URL、API Key 和调用超时 |
| Model | Provider 下的逻辑名称、发往上游的真实 Model ID、限制和能力 |
| Agent | 实际用于对话的 Model、系统提示词和可选工具 |

按下面的顺序配置：

1. 打开 [Provider](http://localhost:8080/providers)，新建 Provider。
   - 选择上游实际使用的协议：`openai`、`openai_response`、`anthropic` 或 `google`。
   - 填写 Base URL；上游需要认证时再填写 API Key。保存后的密钥不会在 UI 中回显。
2. 打开 [Model](http://localhost:8080/models)，新建 Model。
   - 选择刚创建的 Provider。
   - `Name` 是 Studio 内的逻辑名称；`Model ID` 必须是上游接口接受的真实模型标识。
   - 按上游能力填写上下文、最大输出、输入类型、Tools、Reasoning 和 Variant。
3. 打开 [Agent](http://localhost:8080/agents)，新建 Agent。
   - 选择 Model，填写系统提示词。
   - 第一次对话可以先不配置 Tools、Skills 或 Subagents。
4. 打开 [Chat](http://localhost:8080/chats)，新建 Chat，选择这个 Agent 并发送消息。

如果调用失败，Chat 中会保留错误；会话的调试视图可查看请求预览、活动调用的冻结请求与执行事件。

## 让 Agent 使用本机工具

1. 在 [Environment](http://localhost:8080/environments) 页面创建 Environment，并复制
   registration token。
2. 目标主机准备 JDK 21。Linux 使用 `systemd --user`，macOS 使用当前用户图形登录域的
   LaunchAgent；Unix 安装还需要 Bash、curl 和 SHA256 工具，不需要 Git、Maven 或源码 checkout。
3. 在 Linux/macOS 的交互终端执行：

   ```bash
   curl -fsSL https://raw.githubusercontent.com/fengwk/kk-studio/main/scripts/daemon/install.sh | bash
   ```

   脚本默认下载最新官方 Release 的 Daemon JAR 并校验 SHA256。按提示输入 gateway URI
   （本地栈为 `ws://localhost:8080/api/harness/environment-daemon/v1`）和 registration token；
   token 输入不回显，写入仅当前用户可读的文件，不必放进命令参数或 shell 历史。
   Windows 10/11 的 PowerShell 安装与当前用户计划任务操作见
   [Environment Daemon 安装与运行](docs/operations/environment-daemon.md)。
4. Environment 页面显示 `READY` 后，为 Agent 选择需要的 Tools 或 Skills，并在 Chat 的
   Branch 设置中选择这个 Environment。Environment 不绑定在 Agent 定义上。

后续升级复用已安装服务的配置：

```bash
curl -fsSL https://raw.githubusercontent.com/fengwk/kk-studio/main/scripts/daemon/install.sh | bash -s -- upgrade
```

Daemon 直接继承启动用户的主机权限，没有文件系统沙箱。Windows 与 macOS 的安装差异、
升级、状态查询、可选参数、卸载和前台调试见
[Environment Daemon 安装与运行](docs/operations/environment-daemon.md)。

## 停止与清理

```bash
# 停止容器，保留 PostgreSQL 和 MinIO 数据
docker compose -f deploy/local/compose.yaml down

# 删除容器和数据卷，下一次从空数据重新初始化
docker compose -f deploy/local/compose.yaml down -v
```

端口、远程访问、数据卷和所有可覆盖参数见
[本地一键启动栈](deploy/local/README.md)。

## 开发与验证

源码开发使用 JDK 21。配置好 PostgreSQL、MinIO 和所需环境变量后，通过统一脚本管理
Backend 与 Vite：

```bash
./scripts/dev/app.sh start
./scripts/dev/app.sh status
./scripts/dev/app.sh logs all
./scripts/dev/app.sh stop
```

脚本默认启动 Vite `http://127.0.0.1:5173`、Backend
`http://127.0.0.1:18080`，并使用 `e2e` profile；它与上文监听 `8080` 的本地 Compose
栈是两条独立运行路径。要把本机 preview 指向 NAS 上已有的 PostgreSQL/S3，改用
[`scripts/dev/shared-preview.sh`](scripts/dev/shared-preview.sh)：它读一份 owner-only 配置文件里的
endpoint 与凭据，强制 `prod` profile 并关闭本机 Flyway 与 Harness worker，配置文件模板见
[`scripts/dev/shared-preview.env.example`](scripts/dev/shared-preview.env.example)。依赖服务和环境变量
配置见[开发与测试](docs/operations/development-and-testing.md)。

常用质量检查：

```bash
env JAVA_HOME="$JAVA_HOME_21" mvn -B -ntp test
npm --prefix frontend run test
npm --prefix frontend run lint
npm --prefix frontend run build
node scripts/dev/verify/repository/check.mjs
python3 scripts/dev/verify/repository/check-sensitive-data.py
```

完整的本地配置、E2E、覆盖率、可靠性、性能和供应链入口见
[开发与测试](docs/operations/development-and-testing.md)。

## 文档

| 文档 | 从这里解决什么问题 |
| --- | --- |
| [本地一键启动栈](deploy/local/README.md) | Compose 服务、端口、参数、日志和数据清理 |
| [Environment Daemon](docs/operations/environment-daemon.md) | 安装、注册、常驻、升级和本机状态 |
| [部署与运行](docs/operations/deployment.md) | Fat JAR、镜像、部署拓扑和运行配置 |
| [开发与测试](docs/operations/development-and-testing.md) | 开发环境和全部质量入口 |
| [系统设计](docs/system-design.md) | 系统职责、依赖、配置与执行恢复链 |
| [Canvas、Project 与交互](docs/canvas-project.md) | 资源编辑、Function 运行、Issue 分工、交接和人工输入 |
| [文档导航](docs/README.md) | 所有模块与 Operations 文档 |

## 安全与许可证

- Provider credential、MCP bearer token、Daemon registration token 和部署密钥不得提交到
  源码、镜像或 seed。
- PostgreSQL 保存业务事实，MinIO/S3 保存附件和媒体对象；对外部署时应同时保护两者。
- 安全漏洞请按 [Security Policy](SECURITY.md) 通过 GitHub 私密渠道报告，不要在公开
  Issue 中披露。

本项目采用 [Apache License 2.0](LICENSE)。
