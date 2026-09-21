# kk-studio

`kk-studio` 是面向可信单用户部署的自托管 AI 工作台。你可以在浏览器中接入模型
Provider，定义 Agent，进行可恢复的流式对话，并按需让 Agent 使用本机文件、命令、
代码检索、LSP、Skill、MCP 和 Subagent。

最短使用链路：

```text
启动 Studio -> Provider -> Model -> Agent -> Chat
                                  |
                                  +-> Environment Daemon（需要本机工具时）
```

> `kk-studio` 当前没有内置登录鉴权。默认本地栈只监听 `127.0.0.1`；部署到局域网或
> 公网前，必须在外部入口配置 TLS 和访问控制。

## 能做什么

| 能力 | 用途 |
| --- | --- |
| Chat 与 Agent | 流式对话、思考内容、附件、历史分支、工具调用，以及可恢复的执行状态 |
| Model Catalog | 分别管理 Provider、真实模型 ID、上下文限制、能力、Variant 与价格 |
| Agent 能力组合 | 为 Agent 选择模型、系统提示词、Tools、Skills、Subagents 和 Environment |
| Environment | 通过独立 Daemon 在指定主机上执行文件读写、搜索、命令和 LSP 能力 |
| MCP | 注册 Streamable HTTP MCP Server，发现其工具并作为 Agent 可选工具执行 |
| Projects 与 Canvas | 管理 Project / Issue，并通过 Canvas、Function 和 ComfyUI 组织图形工作流 |

当前内置模型协议包括 OpenAI Chat Completions、OpenAI Responses、Anthropic
Messages 和 Google Gemini。

## 选择运行方式

| 目标 | 入口 | 适合场景 |
| --- | --- | --- |
| 先运行起来 | [`deploy/local`](deploy/local/README.md) | 推荐给首次使用者；Docker Compose 启动 App、PostgreSQL 和 MinIO |
| 修改源码 | [`scripts/dev.sh`](scripts/dev.sh) | Backend + Vite 开发；需要 JDK 21、Maven、Node/npm、curl、jq、lsof 和已配置的数据服务 |
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
`default-assistant` Agent，便于查看 Catalog 结构。`deploy/local` **不包含**
`stub.local` 模型服务；它们是 Catalog 示例，不是可用的离线模型。直接使用
`default-assistant` 发消息会连接失败，请先按下一节接入自己的 Provider。

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
   - 第一次对话可以先不绑定 Environment、Tools、Skills 或 Subagents。
4. 打开 [Chat](http://localhost:8080/chats)，新建 Chat，选择这个 Agent 并发送消息。

如果调用失败，Chat 中会保留错误；会话的调试视图可查看完整 Provider 响应。

## 让 Agent 使用本机工具

1. 在 [Environment](http://localhost:8080/environments) 页面创建 Environment，并复制
   registration token。
2. 在目标主机安装 JDK 21，并从
   [GitHub Releases](https://github.com/fengwk/kk-studio/releases) 下载和校验 Daemon JAR。
3. 将 token 保存为仅当前用户可读的文件：

   ```bash
   install -d -m 700 ~/.config/kk-studio
   (umask 077; cat > ~/.config/kk-studio/daemon.token)
   chmod 600 ~/.config/kk-studio/daemon.token
   ```

4. 连接本地 Studio：

   ```bash
   java -jar /path/to/kk-studio-daemon.jar \
     --gateway-uri ws://localhost:8080/api/harness/environment-daemon/v1 \
     --registration-token-file ~/.config/kk-studio/daemon.token
   ```

5. Environment 页面显示 `READY` 后，编辑 Agent，绑定该 Environment，并选择需要的
   Tools 或 Skills。

Daemon 直接继承启动用户的主机权限，没有文件系统沙箱。下载校验、TLS 地址、systemd
常驻、升级和故障处理见
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
./scripts/dev.sh start
./scripts/dev.sh status
./scripts/dev.sh logs all
./scripts/dev.sh stop
```

脚本默认启动 Vite `http://127.0.0.1:5173`、Backend
`http://127.0.0.1:18080`，并使用 `e2e` profile；它与上文监听 `8080` 的本地 Compose
栈是两条独立运行路径。要把本机 preview 指向 NAS 上已有的 PostgreSQL/S3，改用
[`scripts/local-dev.sh`](scripts/local-dev.sh)：它读一份 owner-only 配置文件里的
endpoint 与凭据，强制 `prod` profile 并关闭本机 Flyway 与 Harness worker，配置文件模板见
[`scripts/local-dev.config.example`](scripts/local-dev.config.example)。依赖服务和环境变量
配置见[开发与测试](docs/operations/development-and-testing.md)。

常用质量检查：

```bash
env JAVA_HOME="$JAVA_HOME_21" mvn -B -ntp test
npm --prefix frontend run test
npm --prefix frontend run lint
npm --prefix frontend run build
node scripts/docs/check.mjs
python3 scripts/security/check-sensitive-data.py
```

完整的本地配置、E2E、覆盖率、可靠性、性能和供应链入口见
[开发与测试](docs/operations/development-and-testing.md)。

## 文档

| 文档 | 从这里解决什么问题 |
| --- | --- |
| [本地一键启动栈](deploy/local/README.md) | Compose 服务、端口、参数、日志和数据清理 |
| [Environment Daemon](docs/operations/environment-daemon.md) | 下载、注册、常驻、升级和本机状态 |
| [部署与运行](docs/operations/deployment.md) | Fat JAR、镜像、部署拓扑和运行配置 |
| [开发与测试](docs/operations/development-and-testing.md) | 开发环境和全部质量入口 |
| [系统设计](docs/system-design.md) | 数据边界、执行链路、并发和恢复模型 |
| [文档导航](docs/README.md) | 所有模块与 Operations 文档 |

## 安全与许可证

- Provider credential、MCP bearer token、Daemon registration token 和部署密钥不得提交到
  源码、镜像或 seed。
- PostgreSQL 保存业务事实，MinIO/S3 保存附件和媒体对象；对外部署时应同时保护两者。
- 安全漏洞请按 [Security Policy](SECURITY.md) 通过 GitHub 私密渠道报告，不要在公开
  Issue 中披露。

本项目采用 [Apache License 2.0](LICENSE)。
