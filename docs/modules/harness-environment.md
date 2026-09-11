# Harness Environment

## 定位

`harness-environment` 是 Runtime、Contributor、Platform Gateway 与 Daemon 之间共享的契约模块，负责规范环境标识、原子能力目录、执行接口与传输协议：

- 确立规范的环境唯一标识（`EnvironmentId`）与已冻结的工作区绑定模型（`EnvironmentBinding`）；
- 定义独立于上层模型工具编排的标准原子能力目录（Capability catalog）、执行 SPI 及传输确定性契约；
- 提供 Environment Daemon WebSocket 协议 v1 的封包结构（`DaemonEnvelope`）、控制与执行消息类型、能力通告规范（READY capabilities）、调用与流式结果编解码器以及安全目录模型。

该模块仅依赖 [`harness-common`](harness-common.md) 的基础值对象（`InputSchema`、`ResultContent`、`ResourceRef`）与 Jackson，独立于具体的模型工具编排、执行运行时、外部存储、网络基础设施及 Daemon 实现。

## 职责

### 核心职责

- 将分支持久化配置（BranchSettings）、Platform 寻址路由与 Daemon 物理传输收敛到统一的 `EnvironmentId` 标识体系。
- 确立与模型层解耦的原子能力标准（Capability catalog），通过稳定的能力标识、输入模式（schema）与版本约束规范环境执行标准。
- 确立传输层（Transport）调用语义，保证发送确定性、有序流式事件及单次终态（terminal-once）约束。
- 统一使用 Daemon protocol v1 通用协议封包与严格编解码契约。
- 统一跨平台工作区与目录浏览使用的规范相对路径规则。

### 协作边界

- 连接管理与寻址租约：活跃连接注册表、心跳超时判定、数据库路由租约（lease）及跨节点路由由 Platform 统一管理；本模块提供无状态的协议值对象。
- 真实环境执行：本地文件系统操作、进程启动、LSP 服务及技能加载的具体实现由 Daemon 进程承载；本模块仅定义抽象执行接口与调用参数约束。
- 工具编排与权限准入：面向模型的 Tool 包装、参数准入（admission）与权限决策由 Runtime 与 Contributor API 承载；本模块聚焦原子能力描述。
- MCP 外部工具：平台在每次调用时通过 HTTP 访问外部 MCP 服务，采用独立于 Environment Daemon protocol v1 的接入路径。
- 状态管理：Daemon 以进程内 journal 管理执行日志，Platform 管理连接与路由状态，Runtime 管理持久化调用快照；本模块提供这些边界共享的值契约。

## 依赖边界

```text
harness-common + Jackson
       │
       ▼
harness-environment
  ├─ EnvironmentId / Binding / WorkspacePath
  ├─ Capability catalog + execution/transport SPI
  └─ Daemon protocol v1 values + codecs
```

生产依赖单向受限于 `harness-common` 与 Jackson；Agent Runtime、Tool 编排层、持久化存储以及 Daemon 具体实现均向本模块单向依赖。详细依赖声明见 [`pom.xml`](../../harness/environment/pom.xml)，依赖方向由 [`EnvironmentModuleArchitectureTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/EnvironmentModuleArchitectureTest.java) 自动化守卫。

## 包架构

| 包路径 | 职责与边界 |
| --- | --- |
| `fun.fengwk.kkstudio.harness.environment` | 环境身份标识与绑定模型。定义跨 Runtime、Platform Gateway 与 Daemon 共享的规范 UUID 路由身份（`EnvironmentId`）、已冻结的环境与工作区相对路径绑定（`EnvironmentBinding`）以及跨平台相对路径语法规则（`EnvironmentWorkspacePath`）。本包聚焦纯内存值对象与格式校验，物理文件与网络 I/O 交由各端具体实现承载。 |
| `fun.fengwk.kkstudio.harness.environment.capability` | 跨环境共享的原子能力契约与执行 SPI。定义版本固定的 11 项原子能力描述符（catalog version 1）、底层异步执行接口（`EnvironmentCapability`）以及传输层窄端口（`EnvironmentCapabilityTransport`），严格约束发送异常确定性以及流式事件序列（`PARTIAL* -> exactly one terminal`）。底层能力由 Daemon 注册执行，模型工具映射由上层 Contributor 承接。 |
| `fun.fengwk.kkstudio.harness.environment.daemon` | Platform Gateway 与 Environment Daemon 之间的 WebSocket JSON 通信协议（v1）。包含协议封包（`DaemonEnvelope`）、握手能力载荷（READY）、通用调用与流式结果编解码器，以及目录浏览（`EnvironmentDirectoryListing`）与资源引用模型。本包聚焦报文的序列化与严格校验，连接代际、路由租约与执行日志分别由网关和 Daemon 状态机管理。 |

## 核心模型 / API

### Environment identity 与 binding

[`EnvironmentId`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/EnvironmentId.java) 是规范的 UUID 路由标识值对象：

它是 BranchSettings、EnvironmentBinding、Daemon 通信封包以及跨节点路由寻址的唯一逻辑身份。

[`EnvironmentBinding`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/EnvironmentBinding.java) 原子冻结环境标识与工作区路径：

```text
environmentId + workspacePath
```

[`EnvironmentWorkspacePath`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/EnvironmentWorkspacePath.java) 统一定义跨平台的相对路径语法规则：

- 单独使用 `.` 表示环境根目录（Environment root）；
- 其余路径均使用正斜杠 `/` 分隔，最大长度限制为 2048 字符；
- 路径必须为规范相对路径，遇到绝对路径、Windows 盘符、反斜杠、空路径段、相对导航段（`.` 或 `..`）以及控制字符时均判定非法并直接拒绝；
- 规则在协议层验证网络传输格式（wire shape）；Daemon 以此解析本次调用的缺省 cwd，capability 参数路径不限制在环境根目录内。

### Atomic Capability catalog

[`EnvironmentCapabilityDescriptor`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityDescriptor.java) 包含四个核心契约属性：

```text
capabilityId / capabilityVersion / inputSchema / timeout
```

[`EnvironmentCapabilityCatalog`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityCatalog.java) 的目录版本固定为 `1`，按确定性顺序维护 11 项标准原子能力：

```text
fs.read
fs.write
fs.apply-edit
process.exec
fs.search
fs.find
fs.list-directory
lsp.goto-definition
lsp.workspace-symbols
lsp.java-decompile
skill.load
```

能力描述符聚焦底层执行契约，独立于模型层的 Prompt 提示词、界面渲染、可见性或副作用标记。模型层调用由 Contributor 模块通过具体的 `Tool` 映射到对应的底层能力；Daemon 则依据相同的能力标识与版本注册本地执行实现。

### 执行与 transport 契约

[`EnvironmentCapability`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapability.java) 是 Daemon 侧的异步执行 SPI：

```java
EnvironmentCapabilityDescriptor descriptor();
EnvironmentCapabilityExecutionHandle execute(
    EnvironmentCapabilityExecutionRequest request,
    EnvironmentCapabilityExecutionListener listener);
```

[`EnvironmentCapabilityTransport`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityTransport.java) 是调用方的传输窄端口。发送阶段产生的异常具备确定的语义分类：

| 结果 | 语义 |
| --- | --- |
| `EnvironmentCapabilityBusyException` | 明确未被远端受理，调用方可安全重试 |
| `EnvironmentCapabilityUnavailableException` | 明确服务不可用，未产生执行副作用 |
| `EnvironmentCapabilitySendUncertainException` | 请求可能已被远端接收，存在未确认的副作用，调用方必须停止自动重放 |

调用成功返回执行句柄后，监听器接收的事件严格遵循确定性时序：

```text
PARTIAL* -> exactly one terminal
```

远端报告的 `FAILED` 与 `CANCELLED` 分别转换为强类型异常 `EnvironmentCapabilityFailedException` 与 `EnvironmentCapabilityCancelledException`。终态产生后，任何延迟到达的事件均直接丢弃；取消指令直接透传给底层句柄，严格保持事件流的先后顺序。

### Daemon protocol v1

[`DaemonProtocol.VERSION`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonProtocol.java) 固定为 `1`。系统支持的消息集合由如下枚举定义：

```text
HELLO / WELCOME / READY / HEARTBEAT
INVOKE / STARTED / PARTIAL / COMPLETED / FAILED
CANCEL / CANCELLED / ACK / ERROR
```

[`DaemonEnvelope`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonEnvelope.java) 包含以下固定字段：

```text
protocolVersion / messageType / environmentId
invocationId? / sequence / payload
```

所有与特定执行相关的消息（`INVOKE`、`CANCEL` 及全部生命周期回调）均必须携带非空的 `invocationId`；载荷必须为 JSON 对象。编解码器采用严格校验策略，遇到未知字段、重复键、尾随字符或作用域不匹配时直接作为协议异常拒绝。

握手与主执行时序：

```text
Daemon -> HELLO(protocolVersion=1, registrationToken, catalog version)
Gateway -> WELCOME(environmentId)
Daemon -> READY(capabilities version=1)
Daemon -> HEARTBEAT*

Gateway -> INVOKE
Daemon -> ACK
Daemon -> STARTED
Daemon -> PARTIAL*
Daemon -> COMPLETED | FAILED

Gateway -> CANCEL
Daemon -> ACK
Daemon -> CANCELLED | 已冻结 terminal replay
```

### READY、INVOKE 与 result codec

[`DaemonCapabilitiesCodec`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilitiesCodec.java) 编解码 READY 阶段的能力载荷：

```text
version=1
environment: operatingSystem / timeZone / note / rootPath
skills: name / description
```

READY 载荷公开操作系统类型、时区、可信操作者备注、Daemon 实际 canonical Environment root 的展示路径，以及已安装技能的名称与描述。`rootPath` 仅用于展示，不参与路径解析；凭证、请求头、环境变量、命令与连接 URL 不进入该载荷。

[`DaemonCapabilityInvokeCodec`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilityInvokeCodec.java) 规范调用请求结构，固定包含以下字段：

```text
capabilityId
capabilityVersion
workspacePath
arguments
timeoutMillis
```

所有字段均为必填项；arguments 必须是 JSON 对象；timeoutMillis 必须为非负整数毫秒。工作区的物理真实路径解析由执行方在入口处完成，并作为该次调用的缺省 cwd。

[`DaemonCapabilityResultCodec`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilityResultCodec.java) 负责通用流式（`PARTIAL`）与完成态（`COMPLETED`）结果编解码。所有能力（包括技能正文加载 `skill.load` 与目录浏览 `fs.list-directory`）均统一通过通用 `INVOKE` 模型与流式结果协议交互，保持编解码管道的一致性。

目录浏览结果由 [`EnvironmentDirectoryListing`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/EnvironmentDirectoryListing.java) 与 [`EnvironmentDirectoryEntry`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/EnvironmentDirectoryEntry.java) 建模，仅使用环境根目录下的规范相对路径与必要展示字段。

## 不变量、failure / recovery

- 每个逻辑 Environment 由一个 `EnvironmentId` 标识，该标识贯穿路由寻址、工作区绑定与协议封包。
- `EnvironmentBinding` 在写入 Invocation 时原子绑定环境标识与工作区相对路径，确保执行上下文完整确定。
- 原子能力目录与上层模型工具目录保持独立解耦；能力标识或版本不匹配直接判定为确定性的协议错误。
- 协议严格限定在 v1 消息集合内；收到任何未定义的消息类型直接判定为协议违规并终止处理。
- 遇到 `SendUncertainException` 异常时，表明远端执行状态未知且可能已产生副作用，调用方必须停止自动重试，转入既定恢复流程。
- 协议编解码器专注于数据包格式与字段值的严格校验；连接代际管理与执行日志重放由 Daemon 承载，路由租约由 Platform 维持，持久化状态恢复由 Runtime 统一协调。

## 测试与源码入口

### 源码入口

- [`EnvironmentId.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/EnvironmentId.java)、[`EnvironmentBinding.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/EnvironmentBinding.java)、[`EnvironmentWorkspacePath.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/EnvironmentWorkspacePath.java)
- [`EnvironmentCapabilityCatalog.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityCatalog.java)、[`EnvironmentCapability.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapability.java)、[`EnvironmentCapabilityTransport.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityTransport.java)
- [`DaemonProtocol.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonProtocol.java)、[`DaemonEnvelopeCodec.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonEnvelopeCodec.java)、[`DaemonCapabilitiesCodec.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilitiesCodec.java)
- [`DaemonCapabilityInvokeCodec.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilityInvokeCodec.java)、[`DaemonCapabilityResultCodec.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilityResultCodec.java)

### 关键测试守卫

- [`EnvironmentModuleArchitectureTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/EnvironmentModuleArchitectureTest.java)：验证模块依赖方向与隔离边界。
- [`EnvironmentIdTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/EnvironmentIdTest.java)、[`EnvironmentWorkspacePathTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/EnvironmentWorkspacePathTest.java)：验证身份标识生成与路径语法校验规则。
- [`EnvironmentCapabilityCatalogTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityCatalogTest.java)、[`EnvironmentCapabilityTransportTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityTransportTest.java)：验证能力目录注册完整性、异常确定性分类与事件有序性。
- [`DaemonEnvelopeCodecTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonEnvelopeCodecTest.java)、[`DaemonCapabilityInvokeCodecTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilityInvokeCodecTest.java)、[`DaemonCapabilityResultCodecTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilityResultCodecTest.java)：验证 protocol v1 封包与载荷编解码的严格校验策略。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Common](harness-common.md)、[Harness Tool](harness-tool.md)、[Harness Daemon](harness-daemon.md)、[Harness Runtime](harness-runtime.md)、[Platform](platform.md)。
