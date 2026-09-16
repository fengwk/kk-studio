# Harness Environment

## 定位

`harness-environment` 是 Runtime、Contributor、Platform Gateway 与 Daemon 之间共享的契约模块，负责规范环境标识、原子能力目录、执行接口与传输协议：

- 确立规范的环境唯一标识（`EnvironmentId`），它是冻结执行的唯一环境绑定形态；
- 定义独立于上层模型工具编排的标准原子能力目录（Capability catalog）、执行 SPI 及传输确定性契约；
- 提供 Environment Daemon WebSocket 协议 v1 的封包结构（`DaemonEnvelope`）、控制与执行消息类型、READY capabilities、调用与流式结果编解码器，以及 workdir 与 Skill 来源配置的严格校验规则。

该模块仅依赖 [`harness-common`](harness-common.md) 的基础值对象（`InputSchema`、`ResultContent`、`ResourceRef`）与 Jackson，独立于具体的模型工具编排、执行运行时、外部存储、网络基础设施及 Daemon 实现。

## 职责

### 核心职责

- 将分支持久化配置（`BranchSettings` 的 `agentName`）、Platform 寻址路由与 Daemon 物理传输收敛到统一的 `EnvironmentId` 标识体系。
- 确立与模型层解耦的原子能力标准（Capability catalog），通过稳定的能力标识、输入模式（schema）与版本约束规范环境执行标准。
- 确立传输层（Transport）调用语义，保证发送确定性、有序流式事件及单次终态（terminal-once）约束。
- 统一使用 Daemon protocol v1 通用协议封包与严格编解码契约，其他版本与未知形状不做兼容回退。
- 为需要目录的能力提供发送前的纯词法 workdir 形状校验，目录本身只存在于具体能力 arguments 中。

### 协作边界

- 连接管理与寻址租约：活跃连接注册表、心跳超时判定、数据库路由租约（lease）及跨节点路由由 Platform 统一管理；本模块提供无状态的协议值对象。
- 真实环境执行：本地文件系统操作、进程启动、LSP 服务及技能加载的具体实现由 Daemon 进程承载；本模块仅定义抽象执行接口与调用参数约束。
- 工具编排与权限准入：面向模型的 Tool 包装、参数准入（admission）与权限决策由 Runtime 与 Contributor API 承载；本模块聚焦原子能力描述。
- MCP 外部工具：区分 Remote 与 Local 接入。Remote MCP 由 Platform 在 Backend 进程中通过 Streamable HTTP 独立调用；Local MCP 则基于 stdio 在其绑定的目标 Environment Daemon 中通过通用 capability 契约（`mcp.local.call` / `mcp.local.discover`）接入并执行，与文件和进程能力共享同一套 Daemon 会话通道。
- 状态管理：Daemon 以进程内 journal 管理执行日志，Platform 管理连接与路由状态，Runtime 管理持久化调用快照；本模块提供这些边界共享的值契约。

## 依赖边界

```text
harness-common + Jackson
       │
       ▼
harness-environment
  ├─ EnvironmentId
  ├─ Capability catalog + execution/transport SPI
  └─ Daemon protocol v1 values + codecs
```

生产依赖单向受限于 `harness-common` 与 Jackson；Agent Runtime、Tool 编排层、持久化存储以及 Daemon 具体实现均向本模块单向依赖。详细依赖声明见 [`pom.xml`](../../harness/environment/pom.xml)，依赖方向由 [`EnvironmentModuleArchitectureTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/EnvironmentModuleArchitectureTest.java) 自动化守卫。

## 包架构

| 包路径 | 职责与边界 |
| --- | --- |
| `fun.fengwk.kkstudio.harness.environment` | 环境身份标识。定义跨 Runtime、Platform Gateway 与 Daemon 共享的规范 UUID 路由身份（`EnvironmentId`）。本包聚焦纯内存值对象与 canonical 格式校验，物理文件与网络 I/O 交由各端具体实现承载。 |
| `fun.fengwk.kkstudio.harness.environment.capability` | 跨环境共享的原子能力契约与执行 SPI。目录版本为 `"3"`，定义 11 项模型可见能力和 4 项管理专用能力、底层异步执行接口以及传输窄端口；严格约束发送异常确定性和 `PROGRESS* -> exactly one terminal`。管理能力只复用执行通道，不进入模型 Tool 目录。 |
| `fun.fengwk.kkstudio.harness.environment.daemon` | Platform Gateway 与 Environment Daemon 之间的 WebSocket JSON 协议（v1）。包含协议封包、READY 来源快照、通用调用与结果编解码器、Skill 来源/描述/诊断值对象、资源引用模型及 workdir 词法校验。连接代际、路由租约与执行日志分别由网关和 Daemon 状态机管理。 |

## 核心模型 / API

### Environment identity

[`EnvironmentId`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/EnvironmentId.java) 是规范的 UUID 路由标识值对象：

- 构造与解析只接受 `UUID#toString()` 形式的小写 canonical 文本，空白、大小写变体或非法形状一律拒绝；
- 它是 Stable Environment 资源、Daemon envelope scope、`BranchSettings` 派生的环境路由与 harness work 亲和性共用的唯一逻辑身份；
- display 名称只是元数据，绝不参与路由；路由身份不被第二份值对象包装。

分支历史只冻结用户可见选择（`agentName` 与 model），环境由 Agent definition 决定，目录只由每次工具调用的 arguments 提供。

### Atomic Capability catalog

[`EnvironmentCapabilityDescriptor`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityDescriptor.java) 包含四个核心契约属性：

```text
capabilityId / capabilityVersion / inputSchema / timeout
```

[`EnvironmentCapabilityCatalog`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityCatalog.java) 的目录版本固定为 `"1"`。模型可见 descriptor 按确定性顺序维护 11 项原子能力：

```text
fs.read
fs.write
fs.edit
process.exec
fs.grep
fs.find
lsp.goto-definition
lsp.workspace-symbols
lsp.java-decompile
skill.load
mcp.local.call
```

另有四个管理专用 descriptor：

```text
skill.source.refresh
skill.source.install
skill.source.update
mcp.local.discover
```

所有 15 项能力描述符（11 项模型可见 + 4 项管理专用）统一使用单一基础版本 `VERSION = "1"`。需要目录的 coding/process/LSP 能力 schema 要求显式提供目标 Daemon 文件系统上的绝对 `workdir`；`skill.load` 接收 `{sourceId, name, revision}`；三个来源管理能力共享同一冻结来源配置 schema，包含 `sourceId`、`sourceVersion`、`sourceSetVersion`、类型字段和 `activeSourceIds`；`mcp.local.call` 与 `mcp.local.discover` 接收各自的冻结 MCP 配置和调用/发现参数。四项管理能力都不接受 `workdir`。`EnvironmentCapabilityCatalog.requiresWorkdir(id)` 按 capability ID 判定，不从版本号推断。

能力描述符聚焦底层执行契约，独立于模型层的 Prompt 提示词、界面渲染、可见性或副作用标记。模型层调用由 Contributor 模块通过具体的 `Tool` 映射到对应的底层能力；Daemon 则依据相同的能力标识与版本注册本地执行实现。

### 执行与 transport 契约

[`EnvironmentCapability`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapability.java) 是 Daemon 侧的异步执行 SPI：

```java
EnvironmentCapabilityDescriptor descriptor();
EnvironmentCapabilityExecutionHandle execute(
    EnvironmentCapabilityExecutionRequest request,
    EnvironmentCapabilityExecutionListener listener);
```

[`EnvironmentCapabilityExecutionRequest`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityExecutionRequest.java) 只组合 `descriptor`、`call`（调用标识与 arguments）与 `timeout`；构造时按 descriptor schema 执行归一化与严格校验，有效超时为「请求值截断到 descriptor 默认值」。请求不携带 workdir。

[`EnvironmentCapabilityTransport`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityTransport.java) 是调用方的传输窄端口：

```java
EnvironmentCapabilityExecutionHandle invoke(
    EnvironmentId environmentId,
    EnvironmentCapabilityExecutionRequest request,
    EnvironmentCapabilityExecutionListener listener);
```

传输外壳只携带冻结的 `environmentId`，不携带第二份 workdir。发送阶段产生的异常具备确定的语义分类：

| 结果 | 语义 |
| --- | --- |
| `EnvironmentCapabilityBusyException` | 明确未被远端受理，调用方可安全重试 |
| `EnvironmentCapabilityUnavailableException` | 明确服务不可用，未产生执行副作用 |
| `EnvironmentCapabilitySendUncertainException` | 请求可能已被远端接收，存在未确认的副作用，调用方必须停止自动重放 |

调用成功返回执行句柄后，监听器接收的事件严格遵循确定性时序：

```text
PROGRESS* -> exactly one terminal
```

远端报告的 `FAILED` 与 `CANCELLED` 分别转换为强类型异常 `EnvironmentCapabilityFailedException` 与 `EnvironmentCapabilityCancelledException`。终态产生后，任何延迟到达的事件均直接丢弃；取消指令直接透传给底层句柄，严格保持事件流的先后顺序。

### Daemon protocol v1

[`DaemonProtocol.VERSION`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonProtocol.java) 固定为 `1`。协议消息集合：

```text
HELLO / WELCOME / READY / HEARTBEAT
INVOKE / STARTED / PROGRESS / COMPLETED / FAILED
CANCEL / CANCELLED / ERROR
```

[`DaemonEnvelope`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonEnvelope.java) 包含以下固定字段：

```text
protocolVersion / messageType / environmentId
invocationId? / payload
```

协议没有全局序号，也没有 ACK：消息可靠性来自 `invocationId` 与 Daemon invocation journal，而不是传输层确认，因此编解码与运行时都不做任何跨消息顺序校验。

`environmentId` 是 nullable scope：`HELLO` 在认证前不知道目标 Environment，必须为 null；`WELCOME`、`READY`、`HEARTBEAT` 及全部调用消息由已绑定连接发出，必须非空；`ERROR` 在握手失败时可能没有绑定 scope。所有与特定执行相关的消息（`INVOKE`、`CANCEL` 及全部生命周期回调）均必须携带非空的 `invocationId`；载荷必须为 JSON 对象。编解码器采用严格校验策略，遇到未知字段、重复键、尾随字符或作用域不匹配时直接作为协议异常拒绝。

握手与主执行时序：

```text
Daemon -> HELLO(protocolVersion=1, registrationToken, capabilityCatalogVersion=1,
                daemonInstanceId)
Gateway -> WELCOME(environmentId)
Daemon -> READY(capability descriptors)
Daemon -> HEARTBEAT*

Gateway -> INVOKE
Daemon -> STARTED
Daemon -> PROGRESS*
Daemon -> COMPLETED | FAILED

Gateway -> CANCEL
Daemon -> CANCELLED | 已冻结 terminal replay
```

传输层强制协商 `permessage-deflate`：Daemon 侧由 OkHttp 在生产连接上声明并要求服务端接受，Gateway 侧在握手未见该扩展时以 RFC 6455 close code `1010` 关闭，双方都不退化为未压缩会话。

`daemonInstanceId` 是 Daemon 进程在构造期随机生成一次、所有重连复用的规范 UUID：同一进程断开重连时 Gateway 以相同 `invocationId` 重放在途 INVOKE（Daemon journal 去重，副作用不重复执行），身份不同则判定为换进程接管。

### READY、INVOKE 与 result codec

[`DaemonCapabilitiesCodec`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilitiesCodec.java) 编解码 READY 阶段的能力载荷：

```text
version=1
environment: operatingSystem / timeZone / note / rootPath
sourceSetVersion
skillSources:
  sourceId / sourceVersion / sourceRevision
  skills: sourceId / sourceVersion / name / description / baseDirectory / contentRevision
  diagnostics: location / message
```

READY 载荷公开操作系统类型、时区、可信操作者备注、Daemon 实际 canonical Environment root、已发布来源集合的生成版本，以及按来源分组的成功 revision、Skill 描述和有界诊断。单 payload 最多 512 个来源、4096 个 Skill，每来源最多 256 条诊断；source ID 唯一，Skill name 跨来源唯一，descriptor 的 source 身份/版本必须与所在快照一致。顶层 `sourceSetVersion` 是必填非负整数（与行级 `sourceVersion` 独立），缺失、负数或非整数一律协议错误：Platform 以它为该 READY 报告做持久围栏，缺失就无法判断该报告是否已被更新的集合超越。`operatingSystem` 是发送前 workdir 词法校验的目标 OS 依据；`rootPath` 只展示。凭证、请求头、环境变量、命令、Git URL/ref 与 Skill 正文不进入 READY。非当前 `version` 或缺少 `sourceSetVersion` 的形状直接拒绝。

[`DaemonCapabilityInvokeCodec`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilityInvokeCodec.java) 规范调用请求结构，固定包含以下字段：

```text
capabilityId
capabilityVersion
arguments
timeoutMillis
```

所有字段均为必填项；arguments 必须是 JSON 对象；timeoutMillis 必须为非负整数毫秒。INVOKE 外壳不携带目录字段，需要目录的能力由具体 arguments 携带目标 OS 上的绝对 `workdir`。

[`DaemonCapabilityResultCodec`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilityResultCodec.java) 负责通用流式（`PROGRESS`）与完成态（`COMPLETED`）结果编解码。所有能力（包括技能正文加载 `skill.load`）均统一通过通用 `INVOKE` 模型与流式结果协议交互，保持编解码管道的一致性。

`skill.load` 成功结果是 `{body, baseDirectory}` JSON；精确 revision 不再保留时以 `EnvironmentCapabilityResultCodes.RESOURCE_CHANGED` 结束。`skill.source.refresh/install/update` 使用相同 INVOKE/CANCEL/terminal 通道，但只供 Platform 管理执行器调用，不属于 contributor/model tool catalog。

### workdir 词法校验

daemon 包内的 `DaemonWorkdirSyntax` 提供发送前的纯文本校验 `requireAbsolute(workdir, operatingSystem)`：

- 拒绝 null、空白、周边空白、ISO 控制字符与超过 `MAX_LENGTH = 2048` 的值；
- 拒绝 `~/`、`~\` 与未展开的 `$VAR`、`${VAR}`、`%VAR%` 占位符，不做 home 或环境变量展开；
- Unix（Linux/macOS/WSL）目标要求 `/` 开头的绝对路径；
- Windows 目标接受 drive-rooted（`C:\dir`、`C:/dir`）或 UNC（`\\server\share`、`//server/share`）路径，拒绝 drive-relative 与 root-relative 形式，并把分隔符归一为 `/`。

该规则只校验词法形状，不使用 Backend 本机 `Path` 解析远端路径；真实存在性、目录类型与可访问性由 Daemon 以自身文件系统判定。

## 不变量、failure / recovery

- 每个逻辑 Environment 由一个 `EnvironmentId` 标识，该标识贯穿路由寻址、冻结执行与协议封包。
- 分支历史只冻结 `agentName` 与 model；环境由 Agent definition 决定，目录只来自每次工具调用自己的 arguments，调用之间不继承。
- 原子能力目录与上层模型工具目录保持独立解耦；能力标识或版本不匹配直接判定为确定性的协议错误。
- 协议严格限定为 v1；任何其他版本、未定义消息类型或非当前 READY 形状都直接拒绝。
- 遇到 `SendUncertainException` 异常时，表明远端执行状态未知且可能已产生副作用，调用方必须停止自动重试，转入既定恢复流程。
- 协议编解码器专注于数据包格式与字段值的严格校验；连接代际管理与执行日志重放由 Daemon 承载，路由租约由 Platform 维持，持久化状态恢复由 Runtime 统一协调。

## 测试与源码入口

### 源码入口

- [`EnvironmentId.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/EnvironmentId.java)
- [`EnvironmentCapabilityCatalog.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityCatalog.java)、[`EnvironmentCapability.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapability.java)、[`EnvironmentCapabilityTransport.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityTransport.java)
- [`DaemonProtocol.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonProtocol.java)、[`DaemonEnvelopeCodec.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonEnvelopeCodec.java)、[`DaemonCapabilitiesCodec.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilitiesCodec.java)
- [`DaemonCapabilityInvokeCodec.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilityInvokeCodec.java)、[`DaemonCapabilityResultCodec.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilityResultCodec.java)、daemon 包内的 `DaemonWorkdirSyntax`

### 关键测试守卫

- [`EnvironmentModuleArchitectureTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/EnvironmentModuleArchitectureTest.java)：验证模块依赖方向与隔离边界。
- [`EnvironmentIdTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/EnvironmentIdTest.java)：验证身份标识生成与 canonical 解析规则。
- [`EnvironmentCapabilityCatalogTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityCatalogTest.java)、[`EnvironmentCapabilityContractTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityContractTest.java)、[`EnvironmentCapabilityTransportTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityTransportTest.java)：验证能力目录注册完整性、workdir 版本约束、异常确定性分类与事件有序性。
- [`DaemonEnvelopeCodecTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonEnvelopeCodecTest.java)、[`DaemonCapabilitiesCodecTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilitiesCodecTest.java)、[`DaemonCapabilityInvokeCodecTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilityInvokeCodecTest.java)、[`DaemonCapabilityResultCodecTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilityResultCodecTest.java)：验证 protocol v1、READY 来源快照及严格载荷校验；INVOKE 只接受 `capabilityId`、`capabilityVersion`、`arguments` 与 `timeoutMillis`，额外外壳字段与其他版本都无兼容回退。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Common](harness-common.md)、[Harness Tool](harness-tool.md)、[Harness Daemon](harness-daemon.md)、[Harness Runtime](harness-runtime.md)、[Platform](platform.md)。
