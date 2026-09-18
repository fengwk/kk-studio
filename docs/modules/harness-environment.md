# Harness Environment

`harness-environment` 提供 Runtime、Platform、Contributor API 与 Environment Daemon 共用的同一份环境执行契约：Environment 的唯一路由身份、与模型工具编排解耦的原子能力目录、调用侧的发送与流式语义，以及 Platform Gateway 与 Environment Daemon 之间的 protocol v1 全部 wire 形状。环境链路的另外两段——服务端会话与租约协调（[Harness Environment Server](harness-environment-server.md)）、宿主进程执行（[Harness Daemon](harness-daemon.md)）——只消费本模块的值契约与编解码器；protocol v1 的消息矩阵、字段语义与大小约束只在本文件维护。

## Environment 身份

[`EnvironmentId`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/EnvironmentId.java) 是规范 UUID 路由身份：构造与解析只接受 `UUID#toString()` 的小写 canonical 文本，空白、大小写变体与其它形状一律拒绝，`toString()` 也只输出该形式。

Stable Environment 资源、daemon envelope 的 scope、分支执行派生的环境路由与 harness work 亲和性共用这一个身份。display 名称只是元数据，不参与路由，路由身份也没有第二份包装类型。分支历史只冻结用户可见的 `agentName` 与 model；环境由 Agent definition 决定，目录只由每次工具调用自己的 arguments 提供。

## 原子能力目录

[`EnvironmentCapabilityCatalog`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityCatalog.java) 的 `VERSION = "1"`，它既是 catalog 版本（HELLO 的 `capabilityCatalogVersion` 必须相等），也是全部 descriptor 的 capability 版本。[`EnvironmentCapabilityDescriptor`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityDescriptor.java) 只有四个契约属性：

```text
capabilityId / capabilityVersion / inputSchema / timeout
```

模型可见能力按固定 canonical 顺序排列，共 11 项：

| capabilityId | 声明的能力超时 | arguments 需要 `workdir` |
| --- | --- | --- |
| `fs.read` | 1 分钟 | 是 |
| `fs.write` | 1 分钟 | 是 |
| `fs.edit` | 1 分钟 | 是 |
| `process.exec` | 1 小时 | 是 |
| `fs.grep` | 1 小时 | 是 |
| `fs.find` | 1 小时 | 是 |
| `lsp.goto-definition` | 2 分钟 | 是 |
| `lsp.workspace-symbols` | 2 分钟 | 是 |
| `lsp.java-decompile` | 2 分钟 | 是 |
| `skill.load` | 1 分钟 | 否 |
| `mcp.local.call` | 1 小时 | 否 |

管理专用能力共 4 项，只复用 INVOKE/CANCEL/终态通道，绝不进入模型 Tool 目录，也不接受 `workdir`：`skill.source.refresh`（5 分钟）、`skill.source.install`（30 分钟）、`skill.source.update`（30 分钟）、`mcp.local.discover`（5 分钟）。[`EnvironmentCapabilityIds`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityIds.java) 固化这些标识，并以 `MANAGEMENT_ONLY` 集合区分管理边界。

capability 身份是 [`EnvironmentCapabilityId`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityId.java) 的 canonical 形式：`[a-z0-9]+(?:[.-][a-z0-9]+)*`，最长 128 字符。`EnvironmentCapabilityCatalog.requiresWorkdir(id)` 只按 capability ID 判定，不从版本号推断。

各能力的 arguments schema 是冻结的 classpath 资源，需要目录的 9 项 coding/process/LSP 能力都显式要求目标 Daemon 文件系统上的绝对 `workdir`；`skill.load` 接收 `{sourceId, name, revision}`；三个来源管理能力共享同一份冻结来源配置 schema，必填 `sourceId`、`sourceVersion`、`sourceSetVersion`、`type`（`path` 或 `git`）与 `activeSourceIds`；`mcp.local.call` 接收 `serverId`、`configVersion`、`toolName`、`arguments` 与 `config`，`mcp.local.discover` 接收 `serverId`、`configVersion` 与 `config`，两者共享的 `config` 必填 `type=local`、`environmentId`、`command` 与 `cwd`。

能力描述符只描述底层执行契约，独立于 Prompt 提示词、界面渲染、可见性与副作用标记；模型可见的工具层映射由 Contributor 侧完成，Daemon 依据相同的能力标识与版本注册本地实现。能力标识未知或版本不匹配都是确定性的协议错误，没有回退路径。

## 执行与传输契约

[`EnvironmentCapability`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapability.java) 是 Daemon 侧的异步执行 SPI：

```java
EnvironmentCapabilityDescriptor descriptor();
EnvironmentCapabilityExecutionHandle execute(
    EnvironmentCapabilityExecutionRequest request,
    EnvironmentCapabilityExecutionListener listener);
```

[`EnvironmentCapabilityExecutionRequest`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityExecutionRequest.java) 只组合 `descriptor`、`call`（调用标识与 arguments）与 `timeout`，构造时按 descriptor schema 归一化并严格校验。它不携带 `workdir`：目录只存在于具体能力的 arguments 中。有效超时由 `effectiveTimeout()` 定义——请求值为 0 时用 descriptor 声明的超时，否则不超过 descriptor 声明的超时。

[`EnvironmentCapabilityTransport`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityTransport.java) 是调用方的传输窄端口：

```java
EnvironmentCapabilityExecutionHandle invoke(
    EnvironmentId environmentId,
    EnvironmentCapabilityExecutionRequest request,
    EnvironmentCapabilityExecutionListener listener);
```

传输外壳只携带冻结的 `environmentId`，不携带第二份 workdir。发送阶段的异常具有固定语义：

| 异常 | 语义 |
| --- | --- |
| [`EnvironmentCapabilityBusyException`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityBusyException.java) | 明确未被远端受理，调用方可安全重试 |
| [`EnvironmentCapabilityUnavailableException`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityUnavailableException.java) | 服务不可用，调用确定未执行 |
| [`EnvironmentCapabilitySendUncertainException`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilitySendUncertainException.java) | 请求可能已被远端接收且可能已产生副作用，调用方必须停止自动重放 |

拿到执行句柄后，事件严格遵循 `PROGRESS* -> exactly one terminal`：远端 `FAILED` 与 `CANCELLED` 分别转换为 [`EnvironmentCapabilityFailedException`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityFailedException.java) 与 [`EnvironmentCapabilityCancelledException`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityCancelledException.java) 交给监听器。终态产生后到达的事件一律丢弃，取消指令直接透传给底层句柄。

能力结果 [`EnvironmentCapabilityResult`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityResult.java) 是 `callId` 加 `contents`（至多 64 项）、`error` 与 `detailsJson`（至多 1 MiB UTF-8）。错误结果可以只带文本，也可以带稳定错误码，目前唯一冻结的码是 `RESOURCE_CHANGED`（见 [`EnvironmentCapabilityResultCodes`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityResultCodes.java)），用于精确 revision 已不可用的场景；码值形如 `[A-Z][A-Z0-9_]*`。

## protocol v1 会话与消息流

[`DaemonProtocol.VERSION`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonProtocol.java) 固定为 `1`，消息集合为：

```text
HELLO / WELCOME / READY / HEARTBEAT
INVOKE / STARTED / PROGRESS / COMPLETED / FAILED
CANCEL / CANCELLED / ERROR
RESOURCE_UPLOAD_REQUEST / RESOURCE_UPLOAD_TICKET / RESOURCE_UPLOAD_COMMIT
```

[`DaemonEnvelope`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonEnvelope.java) 的固定字段为：

```text
protocolVersion / messageType / environmentId / invocationId? / payload
```

`environmentId` 是可空 scope：`HELLO` 在认证前不知道目标 Environment，必须为 null；`WELCOME`、`READY`、`HEARTBEAT` 与全部调用消息由已绑定连接发出，必须非空；`ERROR` 在握手失败时可能没有绑定 scope。除 `HELLO`、`WELCOME`、`READY`、`HEARTBEAT`、`ERROR` 外的全部消息都必须携带非空的 `invocationId`，资源上传控制消息也以它关联调用，`transferId` 只存在于 payload。`payload` 必须是 JSON 对象。

协议没有全局序号，也没有 ACK：消息可靠性来自 `invocationId` 与 Daemon invocation journal，而不是传输层确认，因此编解码与运行时都不做跨消息顺序校验。[`DaemonEnvelopeCodec`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonEnvelopeCodec.java) 采用严格策略，未知字段、重复键、尾随字符、非 canonical scope 或错误 `protocolVersion` 都直接作为协议异常拒绝。

握手与调用时序：

```text
Daemon -> HELLO(protocolVersion=1, registrationToken, capabilityCatalogVersion=1, daemonInstanceId)
Gateway -> WELCOME(environmentId, name, maxResourceBytes)
Daemon -> READY(capability descriptors, environment, sourceSetVersion, skillSources)
Daemon -> HEARTBEAT*

Gateway -> INVOKE
Daemon -> STARTED
Daemon -> PROGRESS*
Daemon -> RESOURCE_UPLOAD_REQUEST
Gateway -> RESOURCE_UPLOAD_TICKET(PENDING | READY | FAILED)
Daemon -> RESOURCE_UPLOAD_COMMIT
Gateway -> RESOURCE_UPLOAD_TICKET(READY | FAILED)
Daemon -> COMPLETED | FAILED

Gateway -> CANCEL
Daemon -> CANCELLED | 已冻结的终态重放
```

`WELCOME` payload 携带认证结果 `environmentId`、展示用 `name` 与本连接的资源字节预算 `maxResourceBytes`；Daemon 在收到它之前不接受任何 resource/binary 结果。

`daemonInstanceId` 是 Daemon 进程构造期随机生成一次、所有重连复用的规范 UUID；同一进程断开重连时 Gateway 以相同 `invocationId` 重放在途 INVOKE（Daemon journal 去重，副作用不重复执行），身份不同则判定为换进程接管。握手失败时 Gateway 以 `ERROR` 收尾，`REGISTRATION_REJECTED` 与 `RETRY_LATER` 是仅有的两个冻结错误码。

传输层强制协商 `permessage-deflate`：两端都在握手阶段要求该扩展，任一侧未协商成功即按 RFC 6455 close code `1010` 关闭连接，双方都不退化为未压缩会话。

## 载荷编解码器

[`DaemonCapabilitiesCodec`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilitiesCodec.java) 编解码 READY 载荷：

```text
version=1
environment: operatingSystem / timeZone / note / rootPath
sourceSetVersion
skillSources:
  sourceId / sourceVersion / sourceRevision
  skills: sourceId / sourceVersion / name / description / baseDirectory / contentRevision
  diagnostics: location / message
```

READY 公开目标操作系统、时区、可信操作者备注与 Daemon 的 canonical Environment root，以及按来源分组的版本化 Skill 描述与有界诊断。`operatingSystem` 是发送前 workdir 词法校验的目标 OS 依据；`rootPath` 只展示。`sourceSetVersion` 是必填非负整数，缺失或非整数即协议错误：消费方以它为这次 READY 报告做持久围栏，否则无法判断报告是否已被更新的来源集合超越。

载荷的硬约束：单 payload 至多 512 个来源、4096 个 Skill，每来源至多 256 条诊断；`sourceId` 全局唯一，Skill `name` 跨来源唯一；`sourceRevision` 是 40 或 64 位小写十六进制 commit，`contentRevision` 是 64 位小写十六进制 SHA-256；`name` 至多 128 字符、`description` 至多 1024 字符、`baseDirectory` 至多 4096 字符且必须是目标宿主上的绝对路径；`note` 单行且不超过 512 字符。凭证、请求头、环境变量、命令、Git URL/ref 与 Skill 正文不进入 READY。

[`DaemonCapabilityInvokeCodec`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilityInvokeCodec.java) 规定 INVOKE payload 固定包含 `capabilityId`、`capabilityVersion`、`arguments` 与 `timeoutMillis`，全部必填，`arguments` 必须是 JSON 对象，`timeoutMillis` 必须是非负整数毫秒。INVOKE 外壳不携带目录字段，需要目录的能力由自己的 arguments 携带绝对 `workdir`；`timeoutMillis` 为 0 表示沿用默认超时。

[`DaemonCapabilityResultCodec`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilityResultCodec.java) 编解码流式与终态结果：

- `PROGRESS` 只接受 text/json 内容，resource 与 binary 在任何上传副作用前拒绝。
- `COMPLETED` 先执行预算预检——内容条目数、单资源与聚合资源字节都不超过 `WELCOME` 通告的预算、最终 JSON 不超过 16 MiB UTF-8——全部通过后才通过 [`DaemonResourceUploader`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonResourceUploader.java) 外部化 `BinaryResultContent`。
- resource wire 以 `type=resource` 判别，只包含 `uploadId`、`mediaType`、`name`、`size`、`sha256` 与可选 `preview`，不含 URI、字节、Base64 或未经内容复核的文本工件元数据；解码方在 Backend 进程内构造瞬时 `blob-upload:<uploadId>` 引用。
- 终态 `FAILED` 携带 `message`，`CANCELLED` 携带 `reason`。

[`DaemonResourceTransferCodec`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonResourceTransferCodec.java) 定义 invocation 作用域的上传控制消息：

```text
REQUEST: transferId / mediaType / name / size / sha256
TICKET PENDING: transferId / uploadId / presignedPut(method=PUT, url, headers, expiresAt)
TICKET READY: transferId / uploadId
TICKET FAILED: transferId / bounded message
COMMIT: transferId / uploadId
```

`transferId` 是同一 invocation 内一次资源传输的稳定关联键。控制面只承载元数据：单条控制消息至多 16 KiB 字符，FAILED 说明至多 512 字符。[`DaemonPresignedPut`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonPresignedPut.java) 限制方法只能是 `PUT`、URL 至多 4096 字符、header 至多 16 条且名与值各至多 1024 字符、过期时刻必须是规范 `Instant`。codec 两端都拒绝未知/缺失/重复字段、尾随 JSON、非 canonical UUID/Instant、非法摘要与互相矛盾的 ticket 状态。

二进制字节不进入 WebSocket：控制面只描述 transfer、预签名 PUT 与终态 upload 元数据，实际 PUT 由 Daemon 直接请求对象存储。所有能力——包括 `skill.load` 与 `mcp.local.*`——统一通过通用 INVOKE 与结果协议交互。

## workdir 词法契约

[`DaemonWorkdirSyntax`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonWorkdirSyntax.java) 的 `requireAbsolute(workdir, operatingSystem)` 是发送前的纯文本校验：

- 拒绝 null、空白、周边空白、ISO 控制字符与超过 `MAX_LENGTH = 2048` 的值；
- 拒绝 `~/`、`~\` 以及未展开的 `$VAR`、`${VAR}`、`%VAR%` 占位符，不做 home 或环境变量展开；
- Unix（Linux/macOS/WSL）目标要求 `/` 开头的绝对路径；
- Windows 目标接受 drive-rooted（`C:\dir`、`C:/dir`）或 UNC（`\\server\share`、`//server/share`）路径，拒绝 drive-relative 与 root-relative 形式，并把分隔符归一为 `/`。

该校验只判定词法形状，不使用 Backend 本机 `Path` 解析远端路径。目录是否真实存在、是否为目录、是否可访问，由 Daemon 以自己的文件系统判定。

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

生产依赖只有 `harness-common` 与 Jackson；Runtime、Contributor、Platform 与 Daemon 都向本模块单向依赖。依赖声明见 [`pom.xml`](../../harness/environment/pom.xml)，由 [`EnvironmentModuleArchitectureTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/EnvironmentModuleArchitectureTest.java) 守卫。

## 包架构

| 包路径 | 职责与边界 |
| --- | --- |
| `fun.fengwk.kkstudio.harness.environment` | 环境身份标识。定义跨 Runtime、Platform Gateway 与 Daemon 共享的规范 UUID 路由身份（`EnvironmentId`），只做纯内存值对象与 canonical 格式校验；物理文件与网络 I/O 由各端实现承载。 |
| `fun.fengwk.kkstudio.harness.environment.capability` | 跨环境共享的原子能力契约与执行 SPI。冻结 catalog 版本 `"1"` 的 15 项 descriptor（11 项模型可见 + 4 项管理专用）、底层异步执行接口与传输窄端口，并严格约束发送异常确定性与 `PROGRESS* -> exactly one terminal`。管理能力只复用执行通道，不进入模型 Tool 目录。 |
| `fun.fengwk.kkstudio.harness.environment.daemon` | Platform Gateway 与 Environment Daemon 之间的 protocol v1 全部 wire 形状：协议封包、READY 来源快照、INVOKE/结果/资源票据编解码器、预签名 PUT 值与 workdir 词法校验。连接代际、路由租约、对象存储 I/O 与执行日志由外层状态机和适配器管理。 |

## 源码与测试

主要源码入口：[`EnvironmentId.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/EnvironmentId.java)、[`EnvironmentCapabilityCatalog.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityCatalog.java)、[`EnvironmentCapabilityTransport.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityTransport.java)、[`DaemonProtocol.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonProtocol.java)、[`DaemonEnvelopeCodec.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonEnvelopeCodec.java)、[`DaemonCapabilitiesCodec.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilitiesCodec.java)、[`DaemonCapabilityResultCodec.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilityResultCodec.java)、[`DaemonResourceTransferCodec.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonResourceTransferCodec.java)。

测试守卫：

- [`EnvironmentIdTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/EnvironmentIdTest.java) 与 [`EnvironmentCapabilityIdTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityIdTest.java)：身份与 capability id 的 canonical 解析与拒绝规则。
- [`EnvironmentCapabilityCatalogTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityCatalogTest.java)：11 项模型可见能力的固定顺序与版本、4 项管理能力集合、`requiresWorkdir` 判定与 schema 共享关系。
- [`EnvironmentCapabilityContractTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityContractTest.java) 与 [`EnvironmentCapabilityTransportTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityTransportTest.java)：请求归一化与有效超时、结果体积边界、发送异常分类、事件顺序与终态唯一。
- [`DaemonEnvelopeCodecTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonEnvelopeCodecTest.java)、[`DaemonEnvelopeTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonEnvelopeTest.java)：protocol v1 版本、scope 与 invocationId 规则、未知/重复/尾随字段拒绝。
- [`DaemonCapabilitiesCodecTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilitiesCodecTest.java) 与 [`DaemonSkillSourceSnapshotCodecTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonSkillSourceSnapshotCodecTest.java)：READY 来源快照、`sourceSetVersion` 规则、来源/Skill 配额与命名唯一性。
- [`DaemonCapabilityInvokeCodecTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilityInvokeCodecTest.java) 与 [`DaemonCapabilityResultCodecTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilityResultCodecTest.java)：INVOKE 四个必填字段、PROGRESS 内容限制、终态预算预检先于上传、无字节 resource wire。
- [`DaemonResourceTransferCodecTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonResourceTransferCodecTest.java) 与 [`DaemonWorkdirSyntaxTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonWorkdirSyntaxTest.java)：票据三态互斥字段与预签名 PUT 约束；workdir 占位符、长度、跨 OS 与分隔符归一规则。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Common](harness-common.md)、[Harness Tool](harness-tool.md)、[Harness Environment Server](harness-environment-server.md)、[Harness Daemon](harness-daemon.md)、[Harness Runtime](harness-runtime.md)、[Platform](platform.md)。
