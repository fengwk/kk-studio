# Harness Environment

## 定位

`harness-environment` 是 Runtime、Contributor、Platform Gateway 与 Daemon 共享的 Environment 契约模块。它集中定义：

- canonical Environment identity 与冻结 workspace binding；
- 独立于模型 Tool 的 atomic Capability catalog、执行 SPI 与 transport certainty；
- Environment Daemon protocol v5 的 envelope、消息类型、READY capabilities、INVOKE/result codec 和目录值对象。

该模块依赖 [`harness-common`](harness-common.md) 的 `InputSchema`、`ResultContent` 与 `ResourceRef` 值对象，但不依赖 `harness-tool`、Runtime、Infra、Platform、Web、Spring、数据库或具体 Daemon 实现。

## Goals / Non-goals

### Goals

- 让 durable branch binding、Platform route、Daemon wire 使用同一个 `EnvironmentId`。
- 让 Environment Capability ID/schema/version 独立于模型 Tool name、prompt、visibility 和 permission。
- 固定 transport 的发送确定性、流式顺序、cancel 与 terminal-once 契约。
- 只保留 Daemon protocol v6 的通用消息和严格 codec。
- 统一 workspace 与目录浏览使用的相对 wire path 规则。

### Non-goals

- 不维护 live connection registry、心跳过期、数据库 route lease 或跨节点 mailbox。
- 不执行 filesystem、process、LSP、MCP 或 Skill 逻辑。
- 不定义 Contributor Tool、Runtime ToolInvocation、permission 或 admission。
- 不持久化 Daemon journal、Environment route 或 query。

## 依赖边界

```text
harness-common + Jackson
       │
       ▼
harness-environment
  ├─ EnvironmentId / Binding / WorkspacePath
  ├─ capability catalog + execution/transport SPI
  └─ daemon protocol v6 values + codecs

禁止：harness-tool / harness-runtime / harness-infra / harness-daemon implementation
      contributor-api / platform / web / Spring / JDBC
```

生产依赖见 [`pom.xml`](../../harness/environment/pom.xml)。[`EnvironmentModuleArchitectureTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/EnvironmentModuleArchitectureTest.java) 守卫依赖方向。

## 核心模型 / API

### Environment identity 与 binding

[`EnvironmentId`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/EnvironmentId.java) 是 canonical UUID route 值对象：

它是 BranchSettings、EnvironmentBinding、Daemon envelope 和跨节点 route 的唯一逻辑身份。

[`EnvironmentBinding`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/EnvironmentBinding.java) 冻结：

```text
environmentId + workspacePath
```

[`EnvironmentWorkspacePath`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/EnvironmentWorkspacePath.java) 定义跨平台纯字符串规则：

- `.` 单独表示 Environment root；
- 其余路径使用 `/` 分段，最长 2048 字符；
- 禁止 absolute、Windows drive、反斜杠、空段、`.`/`..` 段和控制字符；
- 这里只校验 wire shape，真实路径、symlink 和 root containment 由 Daemon 执行边界校验。

### Atomic Capability catalog

[`EnvironmentCapabilityDescriptor`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityDescriptor.java) 只包含：

```text
capabilityId / capabilityVersion / inputSchema / timeout
```

[`EnvironmentCapabilityCatalog`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityCatalog.java) 当前 catalog version 为 `1`，按稳定顺序冻结 12 个 capability：

```text
fs.read
fs.write
fs.apply-edit
fs.apply-patch
process.exec
fs.search
fs.find
fs.list-directory
lsp.goto-definition
lsp.workspace-symbols
lsp.java-decompile
skill.load
```

Capability descriptor 不携带模型 Tool name、prompt、renderer、visibility 或 side-effect label。Contributor 可以用具体 `Tool` 把模型契约映射到一个 Capability；Daemon 则按相同 ID/version 注册执行实现。

### 执行与 transport 契约

[`EnvironmentCapability`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapability.java) 是 Daemon 侧的异步执行 SPI：

```java
EnvironmentCapabilityDescriptor descriptor();
EnvironmentCapabilityExecutionHandle execute(
    EnvironmentCapabilityExecutionRequest request,
    EnvironmentCapabilityExecutionListener listener);
```

[`EnvironmentCapabilityTransport`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityTransport.java) 是调用侧窄端口。发送前异常的 certainty：

| 结果 | 语义 |
| --- | --- |
| `EnvironmentCapabilityBusyException` | 肯定未执行 |
| `EnvironmentCapabilityUnavailableException` | 肯定未执行 |
| `EnvironmentCapabilitySendUncertainException` | 可能已接受，禁止重放 |

成功返回 handle 后，listener 顺序固定为：

```text
PARTIAL* -> exactly one terminal
```

远端 `FAILED` 和 `CANCELLED` 分别映射为 `EnvironmentCapabilityFailedException` 与 `EnvironmentCapabilityCancelledException`；terminal 后的 late event 必须丢弃，cancel 必须透传而不能重排 partial/terminal。

### Daemon protocol v5

[`DaemonProtocol.VERSION`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonProtocol.java) 固定为 `5`。当前唯一消息集合：

```text
HELLO / WELCOME / READY / HEARTBEAT
INVOKE / STARTED / PARTIAL / COMPLETED / FAILED
CANCEL / CANCELLED / ACK / ERROR
```

[`DaemonEnvelope`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonEnvelope.java) 固定字段：

```text
protocolVersion / messageType / environmentName
invocationId? / sequence / payload
```

`INVOKE`、`CANCEL` 与所有 invocation callback 必须携带 non-blank `invocationId`；payload 始终是 JSON object。Envelope codec 严格拒绝未知字段、duplicate/trailing token、非法消息和违反 message-specific scope 的值。

握手与执行主序列：

```text
Daemon -> HELLO(protocolVersion=5, catalog version)
Gateway -> WELCOME
Daemon -> READY(capabilities version=5)
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

[`DaemonCapabilitiesCodec`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilitiesCodec.java) 编解码 READY payload：

```text
version=5
environment: operatingSystem / timeZone / note
skills: name / description
mcpServers: name / status / bounded error / tool summaries
```

READY 不暴露本地绝对路径、headers、environment values、command、URL 或完整 MCP schema。

[`DaemonCapabilityInvokeCodec`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilityInvokeCodec.java) 的字段固定为：

```text
capabilityId
capabilityVersion
workspacePath
arguments
timeoutMillis
```

所有字段必填；arguments 必须是 JSON object；timeout 是非负整数毫秒。workspacePath 的 canonical/root 校验由后续边界完成。

[`DaemonCapabilityResultCodec`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilityResultCodec.java) 只处理 generic `PARTIAL` / `COMPLETED` capability result。协议中没有 Skill/Directory 专用 message 或 codec；`skill.load` 和 `fs.list-directory` 与其它能力一样走 `INVOKE`。

目录结果由 [`EnvironmentDirectoryListing`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/EnvironmentDirectoryListing.java) 与 [`EnvironmentDirectoryEntry`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/EnvironmentDirectoryEntry.java) 表达，只携带 Environment root 下的安全相对路径与展示字段。

## 不变量、failure / recovery

- `EnvironmentId` 是 route、binding 与 wire 的唯一逻辑身份；不能用连接 ID、daemon ID 或节点 ID替代。
- `EnvironmentBinding` 一经写入 invocation 就同时冻结 id 与 workspace；不存在半空 binding。
- Capability catalog 与 model Tool catalog 分离；Capability ID/version mismatch 是确定性协议失败。
- Protocol v6 只允许当前通用消息集合；历史专用 Skill/Directory 消息必须按 unknown type 拒绝。
- `SendUncertain` 代表副作用结果未知，调用方不得自动重放。
- Codec 只负责 wire/value validation；connection generation、journal replay、route lease 和 durable recovery 分别由 Daemon、Platform 与 Runtime 负责。

## 测试与源码入口

### 源码入口

- [`EnvironmentId.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/EnvironmentId.java)、[`EnvironmentBinding.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/EnvironmentBinding.java)、[`EnvironmentWorkspacePath.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/EnvironmentWorkspacePath.java)
- [`EnvironmentCapabilityCatalog.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityCatalog.java)、[`EnvironmentCapability.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapability.java)、[`EnvironmentCapabilityTransport.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityTransport.java)
- [`DaemonProtocol.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonProtocol.java)、[`DaemonEnvelopeCodec.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonEnvelopeCodec.java)、[`DaemonCapabilitiesCodec.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilitiesCodec.java)
- [`DaemonCapabilityInvokeCodec.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilityInvokeCodec.java)、[`DaemonCapabilityResultCodec.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilityResultCodec.java)

### 关键测试守卫

- [`EnvironmentModuleArchitectureTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/EnvironmentModuleArchitectureTest.java)：模块依赖方向。
- [`EnvironmentIdTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/EnvironmentIdTest.java)、[`EnvironmentWorkspacePathTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/EnvironmentWorkspacePathTest.java)：identity 与 path grammar。
- [`EnvironmentCapabilityCatalogTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityCatalogTest.java)、[`EnvironmentCapabilityTransportTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityTransportTest.java)：catalog 与 certainty/ordering。
- [`DaemonEnvelopeCodecTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonEnvelopeCodecTest.java)、[`DaemonCapabilityInvokeCodecTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilityInvokeCodecTest.java)、[`DaemonCapabilityResultCodecTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilityResultCodecTest.java)：protocol v6 严格 codec。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Common](harness-common.md)、[Harness Tool](harness-tool.md)、[Harness Daemon](harness-daemon.md)、[Harness Runtime](harness-runtime.md)、[Platform](platform.md)。
