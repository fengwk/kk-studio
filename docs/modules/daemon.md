# Environment Daemon

Environment Daemon 是部署在目标宿主上的 Go 独立进程，负责把 Platform 的能力调用安全地执行在该主机的文件系统、进程与工具链中，并托管 code-server IDE。控制连接只承载协议与数据；连接中断后，进程内已接受的任务仍由同一 Daemon 管理。每个 Environment 使用自己的配置、注册凭据、运行数据与 Daemon 实例。安装与运行入口见 [Environment Daemon 安装与运行](../operations/environment-daemon.md)；模型工具契约见 [内置工具](builtin-tools.md)。

Daemon 源码位于 [`daemon-go/`](../../daemon-go/)，由 Go 1.27.4 构建为独立可执行文件；扩展宿主桥接仍是 JavaScript，随 IDE 扩展目录一起分发。受管 code-server 自带其 Node 运行时，该 Node 仅用于启动 IDE，并非 Daemon 运行时。Go 侧优先使用标准库，不要求目标主机安装 Go、JDK 或 ripgrep；Bash 与配置中的语言服务器由宿主提供。

## 启动与本地状态

入口为 [`cmd/kk-studio-daemon`](../../daemon-go/cmd/kk-studio-daemon/main.go)。Daemon 支持 `--help`、`--version`、只读 `--check-config <绝对路径>` 与长驻服务 `--config <绝对路径>`；未知或多余参数失败关闭。配置缓存包含 `{environmentId, version, daemonConfig}`，同目录 `daemon.token` 存放注册凭据。配置字段为 `studioUrl`、`bashExecutable`、`lsp` 与 `proxyUrl`；token 不写入配置 JSON，也不接受 token 命令行参数。配置读取会拒绝未知字段、重复 JSON 键和不符合约束的值。

`daemon.json` 的父目录是运行数据目录。Daemon 在启动时取得目录锁，避免同一 Environment 被多个进程同时运行；安装锁由独立安装 helper 管理。POSIX 下私有目录与敏感文件分别采用属主权限。运行数据包含配置和 token、锁、IDE 数据、受控临时输出、技能文件与最近一次更新诊断；实际布局由发行版定义，参见 [安装与运行](../operations/environment-daemon.md)。

## 控制会话与运行时

主要实现位于 [`internal/runtime`](../../daemon-go/internal/runtime/) 和 [`internal/transport`](../../daemon-go/internal/transport/)；控制协议的唯一 Schema 是 [`control.proto`](../../platform/daemon-server/src/main/protocol/control.proto)，Go 生成代码在 [`internal/protocol`](../../daemon-go/internal/protocol/)。服务端先绑定 Environment 并发送 SETTINGS，再发送完整 ENVIRONMENT_CONFIG 快照；配置可用后 Daemon 进入 READY。配置校验失败时 Daemon 上报状态并等待修复，不会把无效配置作为已应用配置公布。

Daemon 定期发送心跳；连接中断后按退避策略重连。能力调用通过 invocation journal 去重，并在重连后响应 RECOVER，使已受理任务可以恢复对账而不重复执行副作用。取消、超时和关停都由运行时协调执行器收敛；子进程只有在受管范围结束得到确认后才报告退出。运行状态通过 READY、HEARTBEAT 和 RUNTIME_STATUS 上报。

### 能力装配与运行时调度

`internal/runtime` 负责会话、能力调用、取消与终态对账；Transport 提供连接和资源端口，coding、LSP、IDE 与技能包实现各自的能力。配置更新只有在候选配置验证及资源准备成功后才发布为有效快照，旧资源代际继续服务已经接受的调用，直至调用结束。

Studio 是卡片配置的权威，本地 JSON 是最后接受配置的缓存。候选快照按版本串行校验和应用；有效候选会更新缓存与运行快照，失败时保留已生效配置并报告对应错误。代理或 LSP 配置变化不会改变已受理调用正在使用的资源代际。

## 能力与宿主资源

Go 能力实现位于 [`internal/coding`](../../daemon-go/internal/coding/)、[`internal/lsp`](../../daemon-go/internal/lsp/) 与 [`internal/skill`](../../daemon-go/internal/skill/)。文件读写与编辑、查找与检索、命令执行及 LSP 查询均在 Daemon 侧执行。检索由 Go 实现，不依赖外部 ripgrep；能力 Schema 与 Go 代码同在 `internal/capability/`，Java 资源由一致性测试校验。完整模型可见说明见 [内置工具](builtin-tools.md)。

### 原生进程监管

子进程由 [`internal/native`](../../daemon-go/internal/native/) 的平台监管器管理。POSIX 使用独立 session 与受控 helper 收敛进程范围；Windows 使用 Job Object。工作目录不是文件系统沙箱：进程以 Daemon 运行用户的操作系统权限访问文件。宿主必须提供 Bash 及需要的语言服务器。

命令与检索的较大输出写入受控本地临时存储，结果只带有界预览和文件信息；临时资源按配置策略清理。二进制资源通过 [`internal/transport`](../../daemon-go/internal/transport/) 申请服务端票据并直接传输，不经控制消息承载字节。技能下载与更新由 [`internal/skill`](../../daemon-go/internal/skill/) 和 [`internal/update`](../../daemon-go/internal/update/) 负责。

## 受管 IDE

IDE 由 Daemon 管理 code-server 进程与视图生命周期，相关代码位于 [`internal/ide`](../../daemon-go/internal/ide/)。IDE HTTP 请求和 WebSocket 数据经 transport 与控制连接复用转发；JavaScript 扩展位于 [`ide-extension/`](../../daemon-go/ide-extension/)，通过 loopback 桥接与 Daemon 通信，使用 VS Code 公共 API 管理终端、工作区、脏文档、恢复内容与窄屏布局。code-server 的 Node 运行时属于 IDE 发行文件，不承载 Daemon 逻辑。视图绑定、租约和浏览器入口见 [IDE 模块](ide.md)。

## 开发与验证

需要 Go 1.27.4、`protoc` 和 `protoc-gen-go`。从仓库根目录生成并校验协议：

```sh
daemon-go/scripts/generate-protocol.sh
daemon-go/scripts/check-generated.sh
```

Go 工程定向检查示例：

```sh
cd daemon-go
gofmt -w <changed-files.go>
go vet ./...
CGO_ENABLED=0 go test ./...
```

扩展宿主测试使用 Node 内置测试运行器：`node --test daemon-go/ide-extension/extension.test.cjs`。真实生产链路和浏览器验收入口见 [开发与测试](../operations/development-and-testing.md)。

上级：[系统设计](../system-design.md)。相关文档：[Harness Environment](harness-environment.md)、[内置工具](builtin-tools.md)、[IDE](ide.md)、[Environment Daemon 安装与运行](../operations/environment-daemon.md)。
