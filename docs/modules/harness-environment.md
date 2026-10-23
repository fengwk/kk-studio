# Harness Environment

`harness-environment` 是 Agent 使用主机能力的内核契约与服务端会话核心：路由身份、原子能力目录、调用侧发送语义、Gateway 与 Daemon 之间的 wire 协议，以及按连接代际、路由租约与 `invocationId` 裁决调用的 [`EnvironmentDaemonServer`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServer.java)。修改调用参数或协议时，以本模块的值对象、schema 与 codec 为准；主机进程侧的实现见 [Environment Daemon](daemon.md)，数据库、WebSocket 与对象存储由 [Platform](platform.md) 与 [Web](web.md) 经窄端口接入。

## Environment 身份

[`EnvironmentId`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/EnvironmentId.java) 是规范 UUID 路由身份：构造与解析只接受 `UUID#toString()` 的小写 canonical 文本，空白、大小写变体与其它形状一律拒绝，`toString()` 也只输出该形式。

Environment 资源、daemon envelope scope、分支执行路由与 Harness Work 亲和性共用这个 UUID。产品展示名与历史名称的冻结由 [Platform](platform.md)维护；调用目录在具体 capability arguments 中显式给出。Studio 保存的安装设置（`installConfig`）属于产品侧持久状态，不进入 wire；Daemon 只从 `--config` 指向的 `daemon.json` 消费其中的 `daemon` 对象（studioUrl、note、bashExecutable、内联 LSP servers）。

## 原子能力目录

[`EnvironmentCapabilityCatalog`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityCatalog.java)
的 `VERSION = "3"`，它既是 catalog 版本（HELLO 的 `capabilityCatalogVersion` 必须
相等），也是全部 descriptor 的 capability 版本。
[`EnvironmentCapabilityDescriptor`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityDescriptor.java)
只有四个契约属性（INVOKE wire 上的 `capabilityId` / `capabilityVersion` 就是前两项）：

```text
id / version / inputSchema / defaultTimeout
```

目录按固定 canonical 顺序排列，共 11 项：

| capabilityId | 默认超时 | `workdir` | 模型映射 |
| --- | --- | --- | --- |
| `fs.read` | 1 分钟 | 无 | `read` |
| `fs.write` | 1 分钟 | 无 | `write` |
| `fs.edit` | 1 分钟 | 无 | `edit` |
| `fs.edit.preview` | 1 分钟 | 无 | 内部只读审查 |
| `process.exec` | 5 分钟 | 必填 | `bash` |
| `fs.grep` | 15 秒 | 无 | `grep` |
| `fs.find` | 15 秒 | 无 | `find` |
| `lsp.goto-definition` | 3 分钟 | 无 | `lsp_goto_definition` |
| `lsp.workspace-symbols` | 3 分钟 | 无 | `lsp_workspace_symbols` |
| `lsp.java-decompile` | 3 分钟 | 无 | `lsp_java_decompile` |
| `skill.sync` | 不设总时限 | 无 | 内部控制面 |

`skill.sync` 与 `fs.edit.preview` 由内部调用，不映射为模型工具；其余 9 项由 Contributor 映射为模型工具。
[`EnvironmentCapabilityIds`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityIds.java)
固化标识；Contributor 的 visibility 决定模型选择面。

capability 身份是 [`EnvironmentCapabilityId`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityId.java)
的 canonical 形式：`[a-z0-9]+(?:[.-][a-z0-9]+)*`，最长 128 字符。

各能力的 arguments schema 是冻结的 classpath 资源。只有 `process.exec` 要求每次显式提供绝对
`workdir`；`fs.read`、`fs.write`、`fs.edit`、`fs.edit.preview`、`fs.grep`、`fs.find` 与三个 `lsp.*` 能力的本地 `path`
必须是绝对路径，相对路径直接拒绝，且不接受 `workdir`。反编译 `target` 接受绝对 class 路径、`file:` 或 `jdt:` URI；
`skill.sync` 不接受 `workdir`。`process.exec` 的 `workdir` 必须是目标 Daemon 文件系统上的绝对现存目录，
绝不回退到 cwd、HOME、Environment 根或任何会话默认值。

能力描述符只描述底层执行契约，独立于 Prompt 提示词、界面渲染、可见性与副作用标记；模型可见的工具层映射由 Contributor 侧完成，Daemon 依据相同的能力标识与版本注册本地实现。能力标识未知或版本不匹配都是确定性的协议错误，没有回退路径。

## 执行与传输契约

[`EnvironmentCapability`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapability.java) 是 Daemon 侧的异步执行 SPI：

```java
EnvironmentCapabilityDescriptor descriptor();
EnvironmentCapabilityExecutionHandle execute(
    EnvironmentCapabilityExecutionRequest request,
    EnvironmentCapabilityExecutionListener listener);
```

[`EnvironmentCapabilityExecutionRequest`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityExecutionRequest.java) 组合 descriptor、call（标识与 arguments）和 timeout，构造时按 descriptor schema 归一化并严格校验。`workdir` 只属于具体工具的 arguments（仅 `process.exec`）；timeout 是上游解析后的有效值，执行层原样采用，0 表示无限 execution deadline。

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

拿到执行句柄后，事件严格遵循 `PROGRESS* -> exactly one terminal`：远端 `FAILED` 与 `CANCELLED` 分别转换为 [`EnvironmentCapabilityFailedException`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityFailedException.java) 与 [`EnvironmentCapabilityCancelledException`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityCancelledException.java) 交给监听器。终态产生后到达的事件一律丢弃，取消指令直接透传给底层句柄。服务端在并发入站帧、接管与回收竞态下如何维持该顺序，见 [回调顺序与背压上界](#回调顺序与背压上界)。

[`EnvironmentCapabilityExecutionHandle`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityExecutionHandle.java) 是执行收尾的窄契约：

```java
void cancel();
boolean isCancelled();
default void terminate(EnvironmentCapabilityTerminationCause cause) { cancel(); }
default Duration terminationGrace() { return Duration.ZERO; }
```

`cancel()` 请求取消，`isCancelled()` 表示已收到停止请求，不证明远端执行已经结束。
`request.timeout` 是 capability 的远端执行预算，`0` 显式表示无界；
`terminationGrace` 是执行结束后的传输恢复窗，不控制 TypeScript Daemon 的执行计时器。
生产远端句柄在 invoke 时冻结当前 `heartbeatTimeout` 为该窗口，因此它只覆盖终态回传与重连补发，
不是额外执行时间，也不会重置执行预算。Platform Tool Gateway 从发送前起只合成一次等待上界
`request.timeout + terminationGrace`；partial 和重连不延长它，`timeout` 为 `0` 时保持无界，
不得因恢复窗变成有界等待。恢复窗内到达的真实终态（包括执行预算耗尽后的输出）原样保留。
缺少终态到期时返回 UNKNOWN，外部效果不确定，不可自动重放；到期或等待线程中断时调用
`terminate(cause)`，原子移除 server 在途记录、登记 tombstone、请求远端取消并释放 transfer，
迟到事件丢弃且后续重连不重放。null 或负的 `terminationGrace` 是句柄契约违规：Gateway 显式失败并终止
未收敛的在途执行，不按 `ZERO` 静默兜底；等待上界换算异常同样先释放资源，再把原始异常向上抛出。
普通 `cancel()` 仍保留等待远端终态的协作语义。
Daemon Runtime 的唯一 invoke timer 通过 `context.signal` 区分 `TIMED_OUT` 与 `CANCELLED`；
执行器完成清理后交付真实结果，Runtime 不提前抢占终态，见 [Daemon 的超时与取消收尾](daemon.md#能力装配与运行时调度)。

能力结果 [`EnvironmentCapabilityResult`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityResult.java) 是 `callId` 加 `contents`（至多 64 项）、`error` 与 `detailsJson`（至多 1 MiB UTF-8）。错误结果可以只带文本，也可以带稳定错误码：[`EnvironmentCapabilityResultCodes`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityResultCodes.java) 只冻结码的语法 `[A-Z][A-Z0-9_]*`（至多 64 字符），当前没有预置码常量。

## daemon wire 协议会话与消息流

[`DaemonProtocol.VERSION`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonProtocol.java) 固定为 `4`，消息集合为：

```text
HELLO / WELCOME / READY / HEARTBEAT
INVOKE / STARTED / PROGRESS / COMPLETED / FAILED
CANCEL / CANCELLED / ERROR
RESOURCE_UPLOAD_REQUEST / RESOURCE_UPLOAD_TICKET / RESOURCE_UPLOAD_COMMIT
TEMPORARY_RESOURCE_POLICY / UPDATE / UPDATE_RESULT
IDE_COMMAND / IDE_RESULT
```

[`DaemonEnvelope`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonEnvelope.java) 的固定字段为：

```text
protocolVersion / messageType / environmentId / invocationId? / payload
```

`environmentId` 是可空 scope：`HELLO` 在认证前不知道目标 Environment，必须为 null；`WELCOME`、`READY`、`HEARTBEAT`、`IDE_COMMAND`、`IDE_RESULT` 与全部调用消息由已绑定连接发出，必须非空；`ERROR` 在握手失败时可能没有绑定 scope。除 `HELLO`、`WELCOME`、`READY`、`HEARTBEAT`、`ERROR`、`TEMPORARY_RESOURCE_POLICY` 外的全部消息都必须携带非空的 `invocationId`（IDE 消息携带 IDE 请求 UUID）；`TEMPORARY_RESOURCE_POLICY` 由已绑定连接发出，只携带非空 `environmentId`，`invocationId` 为 null。资源上传控制消息也以 `invocationId` 关联调用，`transferId` 只存在于 payload。`payload` 必须是 JSON 对象。

重连去重以 invocationId 与 Daemon journal 关联消息，各调用沿自己的 STARTED/PROGRESS/终态推进。[`DaemonEnvelopeCodec`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonEnvelopeCodec.java) 严格拒绝未知字段、重复键、尾随字符、非 canonical scope 和错误 protocolVersion。同实例恢复的进程生命周期限制见下文。

握手与调用时序：

```text
Daemon -> HELLO(protocolVersion=4, registrationToken, capabilityCatalogVersion=3, daemonVersion, daemonInstanceId)
Gateway -> WELCOME(environmentId, name, maxResourceBytes, temporaryResourceTtlSeconds, temporaryResourceCleanupIntervalSeconds)
Daemon -> READY(version=5, daemonVersion, environment)
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

Gateway -> IDE_COMMAND
Daemon -> IDE_RESULT
```

`WELCOME` payload 携带认证结果 `environmentId`、展示用 `name`、本连接的资源字节预算 `maxResourceBytes` 与临时资源 `temporaryResourceTtlSeconds` / `temporaryResourceCleanupIntervalSeconds`（缺失或非正数是协议错误）；Daemon 在收到它之前不接受任何 resource/binary 结果。TTL 或扫描间隔变化时 Gateway 在同一心跳通道推送 `TEMPORARY_RESOURCE_POLICY`，热更只作用于尚未回收的 workspace。

`daemonInstanceId` 是 Daemon 进程构造期随机生成一次、所有重连复用的规范 UUID；Gateway 保有在途记录时，同实例重连以相同 `invocationId` 恢复（已取消的调用只重发 CANCEL），Daemon journal 去重，副作用不重复执行。不同实例接管将旧调用判为结果不确定。两端在途记录均为进程内状态，协议不保证跨 Backend 重启或迁移的执行恢复。握手失败时 Gateway 以 `ERROR` 收尾，`REGISTRATION_REJECTED` 与 `RETRY_LATER` 是仅有的两个冻结错误码。

传输层强制协商 `permessage-deflate`：两端都在握手阶段要求该扩展，任一侧未协商成功即按 RFC 6455 close code `1010` 关闭连接，双方都不退化为未压缩会话。

## 载荷编解码器

[`DaemonCapabilitiesCodec`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilitiesCodec.java) 编解码 READY 载荷：

```text
version=5
daemonVersion=构建版本
environment: operatingSystem / timeZone / userName / homeDirectory / note / tempDirectory / architecture
```

READY 只公开宿主侧的七项环境元数据与 Daemon 自身构建版本，**不发送 capability descriptor 列表或 LSP 可用性**；能力集合由 HELLO 中的 catalog 版本与两端固定目录约定。`daemonVersion` 取自 Daemon 构建版本（对齐 `package.json` 版本），未打包运行时为固定标记 `development`（明确表示不是发布版本），与 Card 上的 CAS
`version` 是两个不同事实。七项数据为目标操作系统、时区、Daemon 进程用户、该用户的
HOME、可信操作者备注、受控临时目录 `tempDirectory` 与宿主 CPU 架构 `architecture`（HOME 优先 canonical，无法解析时为绝对规范路径）。`operatingSystem` 是发送前路径词法校验的目标
OS 依据；`userName`、`homeDirectory` 与 `architecture` 只用于 Card 和当前 Environment Prompt 展示，
不构成 cwd、默认 workdir 或沙箱；`tempDirectory` 是受控临时 workspace 的 canonical 绝对路径，同样只是展示事实。`architecture` 是 Daemon 真实采集的宿主事实
（例如 `amd64`、`arm64`），JSON `null` 明确表示该事实未知，绝不填造取值。`environment` 是必填对象且字段固定；未知字段、必填
字段缺失或类型错误都是协议错误，其中 `architecture` 的键必须存在且值允许明确 JSON `null`。

载荷的硬约束：`userName` 与 `homeDirectory` 非空、单行且无控制字符，`note` 必填、非空白、无周边空白、单行且不超过 512 字符，`architecture` 是 `null` 或有界非空的单行标识；未指定 note 时 Daemon 按 OS 生成默认说明。当前 codec 只接受版本 5，不双读或兼容旧 V4 payload。凭证、请求头、环境变量、命令与 Git URL/ref 都不进入 READY；
Skill 安装状态通过内部 `skill.sync` 调用结果报告，MCP 目录完全属于 Platform。

[`DaemonCapabilityInvokeCodec`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilityInvokeCodec.java)
规定 INVOKE payload 固定包含 `capabilityId`、`capabilityVersion`、`arguments` 与
`timeoutMillis`，全部必填，`arguments` 必须是 JSON 对象，`timeoutMillis` 必须是非负
整数毫秒。对于 `fs.edit` 能力，payload 允许携带可选内部元数据 `expectedFile`（包含 `path`
与 64 位小写 SHA-256 摘要文本 `sha256`）；非 `fs.edit` 携带 `expectedFile` 或形状非法直接拒绝。
`expectedFile` 仅供宿主核对文件版本一致性，绝不修改模型传入的 `arguments`。
INVOKE 外壳不携带目录字段；能力自己的 arguments 若含 `workdir`，其值必须是
目标 Daemon 上的绝对目录。`timeoutMillis` 是 Platform 解析完成的唯一有效超时，`0`
表示没有 execution deadline，Daemon 原样消费它。

`IDE_COMMAND`（Server → Daemon）与 `IDE_RESULT`（Daemon → Server）是同一连接上的 IDE 通道：内核只校验 envelope、按 `invocationId` 匹配在途请求并原样交付 JSON 载荷，不解释其内容，也不写入 Agent journal。载荷协议属于 [IDE](ide.md) 应用。

[`DaemonCapabilityResultCodec`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilityResultCodec.java) 编解码流式与终态结果：

- `PROGRESS` 只接受 text/json 内容，resource 与 binary 在任何上传副作用前拒绝。
- `COMPLETED` 先执行预算预检——单资源与聚合资源字节都不超过 `WELCOME` 通告的 `maxResourceBytes`、内容条目不超过固定的 64 项、最终 JSON 不超过 16 MiB UTF-8——全部通过后才通过 [`DaemonResourceUploader`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonResourceUploader.java) 外部化 `BinaryResultContent`。
- resource wire 以 `type=resource` 判别，只包含 `uploadId`、`mediaType`、`name`、`size`、`sha256` 与可选 `preview`，不含 URI、字节、Base64 或未经内容复核的文本工件元数据；解码方在 Backend 进程内构造瞬时 `blob-upload:<uploadId>` 引用。
- 终态 `FAILED` 携带 `message`，`CANCELLED` 携带 `reason`。

[`DaemonResourceTransferCodec`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonResourceTransferCodec.java) 定义 invocation 作用域的上传控制消息：

```text
REQUEST: transferId / mediaType / name / size / sha256
TICKET PENDING(state=PENDING): transferId / uploadId / presignedPut(method=PUT, url, headers, expiresAt)
TICKET READY(state=READY): transferId / uploadId
TICKET FAILED(state=FAILED): transferId / bounded message
COMMIT: transferId / uploadId
```

`transferId` 是同一 invocation 内一次资源传输的稳定关联键。控制面只承载元数据：单条控制消息至多 16 KiB 字符，FAILED 说明至多 512 字符。[`DaemonPresignedPut`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonPresignedPut.java) 限制方法只能是 `PUT`、URL 至多 4096 字符、header 至多 16 条且名与值各至多 1024 字符；过期时刻的规范 `Instant` 文本由 codec 校验。codec 两端都拒绝未知/缺失/重复字段、尾随 JSON、非 canonical UUID/Instant、非法摘要与互相矛盾的 ticket 状态。

二进制字节不进入 WebSocket：控制面只描述 transfer、预签名 PUT 与终态 upload 元数据，实际 PUT 由 Daemon 直接请求对象存储。所有能力统一通过通用 INVOKE 与结果协议交互。

内部 `skill.sync` 与模型可见环境工具复用同一 INVOKE/STARTED/PROGRESS/终态协议。其
arguments 只携带 Package、repository URL 与 Platform 当前确认的 exact commit；branch
只能作为 fetch hint，Daemon 必须以 commit 校验安装结果。该 capability 不注册为模型
Tool，READY 与 Package 发布后的同步由 Platform 发起。

技能远程仓库只支持 HTTP/HTTPS；`file:` 保留本地仓库读取，不经代理。Git 连接超时 60 秒、
网络读取空闲超时 180 秒；持续收到数据时不因累计时长终止。`skill.sync` 的 timeout 为 0，
没有外层总期限，但断线、关闭和取消仍会收尾在途任务。其他 capability 的默认超时不变。

## workdir 词法契约（`process.exec`）

[`DaemonWorkdirSyntax`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonWorkdirSyntax.java) 的 `requireAbsolute(workdir, operatingSystem)` 是发送前的纯文本校验：

- 拒绝 null、空白、周边空白、ISO 控制字符与超过 `MAX_LENGTH = 2048` 的值；
- 拒绝 `~/`、`~\` 以及未展开的 `$VAR`、`${VAR}`、`%VAR%` 占位符，不做 home 或环境变量展开；
- Unix（Linux/macOS/WSL）目标要求 `/` 开头的绝对路径；
- Windows 目标接受 drive-rooted（`C:\dir`、`C:/dir`）或 UNC（`\\server\share`、`//server/share`）路径，拒绝 drive-relative 与 root-relative 形式，并把分隔符归一为 `/`。

该校验只判定词法形状，不使用 Backend 本机 `Path` 解析远端路径。目录是否真实存在、是否为目录、是否可访问，由 Daemon 以自己的文件系统判定。

## 依赖边界

生产依赖只有 [Common](common.md) 与 Jackson（见 [`pom.xml`](../../harness/environment/pom.xml)）；Runtime、Platform、IDE 应用与 Daemon 都向本模块单向依赖，方向由仓库检查 [`check.mjs`](../../scripts/dev/verify/repository/check.mjs) 校验。

## 服务端会话核心

服务端核心管理连接代际、路由租约围栏与按 `invocationId` 关联的在途调用。它不持久化任何状态：数据库、WebSocket 和对象存储由宿主通过 lease、registration、channel 与 ticket 端口接入，可用内存 fake 在普通单元测试中驱动；进程重启后的会话事实由 Daemon 重新握手建立。

### 会话建立与租约围栏

`EnvironmentDaemonServer` 同时实现两个窄端口：面向传输适配器的 [`DaemonEndpoint`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonEndpoint.java)（`open` / `receive` / `close`），以及面向产品调用方的 `EnvironmentCapabilityTransport.invoke`。此外它提供 `isReady`、`readyEnvironments`、`holdsReadyLease` 与 `expire(handle)` 供宿主判定与收敛，并为 [IDE](ide.md) 提供不解析载荷的 `requestIde` / `abandonIde` 通道与连接代际快照 `connectionIdentity`。

一次连接的建立顺序固定如下：

```text
open(channel)
Daemon -> HELLO(registrationToken, capabilityCatalogVersion, daemonVersion, daemonInstanceId)
  核心 -> registrationDirectory.findByRegistrationToken   # 解析 environmentId
  核心 -> leaseStore.hasActiveLeaseToken                 # 本节点同节点活跃连接防冲突
  核心 -> leaseStore.tryAcquire                          # 原子抢占/接管
  Gateway -> WELCOME(environmentId, name, maxResourceBytes, temporaryResourceTtlSeconds, temporaryResourceCleanupIntervalSeconds)
Daemon -> READY(version, daemonVersion, environment)
  -> leaseStore.markReady                                # 围栏失效即协议错误
  -> 重放未在当前连接代际发出的 INVOKE
Daemon -> HEARTBEAT*
  -> reconcileTemporaryResourcePolicy                    # TTL/扫描间隔变化时推送 TEMPORARY_RESOURCE_POLICY
  -> leaseStore.heartbeat                                # 围栏失效即协议错误
close(connectionId)
  -> leaseStore.disconnect                               # 幂等，保留重连宽限
```

[`DaemonLeaseStore`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonLeaseStore.java) 只表达围栏返回值，[`LeaseBindResult`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/server/LeaseBindResult.java) 以 `Acquired`、`Rejected`、`RetryLater` 三态表达认证与抢占结论；`markReady`、`heartbeat`、`holdsReadyLease` 与建连时的 `hasActiveLeaseToken` 以布尔值表达围栏是否仍然成立。围栏失守时核心按协议错误关闭连接，不尝试自行修复路由。

`Rejected` 与 `RetryLater` 分别写入 `REGISTRATION_REJECTED` 与 `RETRY_LATER`。存储运行时异常使连接 fail closed，错误帧给出独立说明文本。[`DaemonRegistrationDirectory`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonRegistrationDirectory.java) 将 token 解析为 environmentId，反馈保持去敏。

### 调用所有权与并发

调用按 `(environmentId, invocationId)` 关联。同一 Environment 的多个 invocation 并发持有；传输队列负责有界出站，工具执行 admission 由调用层管理。`invoke` 按固定顺序复验：

1. 校验 capability descriptor 与 [`EnvironmentCapabilityCatalog`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityCatalog.java) 完全一致，并要求 `call.id` 是 canonical UUID；
2. 读取本节点 READY 连接与其 `leaseToken`，没有 READY 连接即判定环境不可用；
3. arguments 携带 `workdir` 时，按该连接 READY 时冻结的目标 OS 做词法校验；是否必填由各能力 input schema 决定；
4. 在核心锁外调用 `leaseStore.holdsReadyLease` 复核归属，存储不可用同样判定环境不可用；
5. 在连接状态锁内二次复核 READY 与围栏代次，登记 `ActiveInvocation` 并递交 INVOKE，把 [`DaemonOfferResult`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonOfferResult.java) 的三态映射为调用结果。

| 递交结果 | 映射 | 确定性 |
| --- | --- | --- |
| `ACCEPTED` | 返回执行句柄 | 帧已交给本地出站队列；不等于对端已收到 |
| `BUSY` | [`EnvironmentCapabilityBusyException`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityBusyException.java) | 帧确定未发送，连接保持可用，调用方可安全重试 |
| `CLOSED` | [`EnvironmentCapabilityUnavailableException`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityUnavailableException.java) | 帧确定未发送，调用确定未执行 |

同一 Environment 内重复使用相同活动 `invocationId` 属于调用方错误，在发送任何帧之前即被拒绝。传输递交时抛错会关闭连接，但返回活动句柄，等待同实例恢复；不能把这条路径解释为确定未执行。恢复时，未取消的调用只向尚未发送过它的新连接代际重放 INVOKE，已取消的调用只重发 CANCEL，不再重发 INVOKE。取消意图与成功递交代际分别记录，BUSY/CLOSED 不算送达，也不靠空转轮询重试。

### 资源上传控制面

每个 `ActiveInvocation` 至多维护 `MAX_TRANSFERS_PER_INVOCATION = 16` 个以 `transferId` 唯一标识的 `TransferBinding`，上限的存在意义是阻止失控 Daemon 无界创建上传行。控制流程：

```text
RESOURCE_UPLOAD_REQUEST
  -> invocation 必须仍活动且属于当前 READY Environment
  -> 严格解码元数据，并在任何存储副作用前校验当前 settings 的 maxResourceBytes
  -> 首次请求在核心锁外调用 ticketService.reserve，把结果固化为该 transfer 的票据事实

RESOURCE_UPLOAD_COMMIT
  -> transfer 必须已申请，uploadId 必须等于服务端签发值
  -> 在核心锁外调用 ticketService.commit

COMPLETED
  -> 结果引用的每个 uploadId 必须属于本 invocation、已 READY、只引用一次
  -> mediaType / name / size / sha256 必须与 REQUEST 完全一致
  -> 保留结果实际引用的上传给 history 物化事务，释放其余上传
```

同一 `transferId` 的重复同形 REQUEST 是幂等的：已绑定的票据直接复用，绝不重复创建上行；同一 transfer 漂移为不同请求、对未申请的 transfer 提交、使用与签发值不同的 uploadId、引用已释放的 transfer，都是协议违规。[`DaemonResourceTicketService`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonResourceTicketService.java) 的所有数据库与对象存储动作、票据回调与释放动作都在核心状态锁之外执行；同一 binding 自身串行化重复 reserve/commit，因此同实例重连重放不会重复创建上传行。

票据服务异常统一转换为固定的 `resource upload is unavailable` 失败回执：预签名 URL、签名 header 与存储异常原文都不进入控制面反馈。`FAILED` 票据不缓存，允许在 invocation deadline 内再次触达服务；`FAILED`/`CANCELLED`、接管、`expire` 与调用放弃都幂等释放全部未消费上传，而 `COMPLETED` 只释放未被结果引用的上传。

二进制字节不经过会话核心：核心只处理有界元数据与票据，并在任何外部 I/O 之前完成作用域、预算与绑定校验。

### 受管更新与准入

`EnvironmentDaemonServer` 额外承载专用管理通道（`UPDATE` / `UPDATE_RESULT`），它不经过 capability 目录，因此即使 Daemon 声明的
`capabilityCatalogVersion` 与本地目录不一致，连接仍可完成认证并承载版本查询与受管更新；只有普通 capability 调用在起点被拒绝。

- `beginUpdate(environmentId, operationId)`：要求当前节点 READY、无在途调用、无其它活动 operation；成功后登记该 operation，此后该
  Environment 的普通调用（含 Skill 同步）在起点 busy。同一 operationId 重复准入幂等。
- `sendUpdate(environmentId, command)`：只在同一 operation 已准入时下发 `UPDATE`；命令未进入传输时按 BUSY/UNAVAILABLE 收敛。
- `endUpdate(environmentId, operationId)`：只有与当前登记一致的 operationId 才释放准入，旧 operation 不能释放新 operation。
- `UPDATE_RESULT` 只做协议校验后转交 `EnvironmentUpdateListener` 在核心锁外消费；核心不判定最终成功。

「一个 Environment 同一时刻至多一次更新」由内存准入与 Platform 侧持久 operation 行（部分唯一索引）双重保证；更新期间对旧二进制的
任何替换都由 Daemon 与独立 OS 更新器完成，核心只负责准入、下发与回执转发。

### 终态唯一与迟到帧

`COMPLETED`/`FAILED`/`CANCELLED` 回调、实例接管与显式 `expire` 竞争时只有一个赢家；已终结 invocation 的标识由 `MAX_INVOCATION_TOMBSTONES = 1024` 的有界 tombstone 记录，超限时淘汰最旧条目。

- `expire(handle)` 由调用方在自身 deadline 上判定超时：它至多发送一次 `CANCEL`，不向 listener 补发终态，并把该 invocation 移出重放集合，之后到达的 daemon 终态被 tombstone 静默丢弃。
- 物理连接失效**不**终结在途调用：同一 `daemonInstanceId` 重连 READY 后以相同 `invocationId` 重放，Daemon journal 负责重放 `STARTED` 与终态而不重复执行副作用。
- 身份不同的新 Daemon 接管 Environment 时，旧在途调用恰好一次地以 [`EnvironmentCapabilitySendUncertainException`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilitySendUncertainException.java) 收敛为结果不确定，并释放其上传；新进程从自己的实例事实开始接受调用。旧主机副作用的停止或回滚仍需独立核查。
- 已终结 invocation 的迟到或重放 `COMPLETED`/`FAILED`/`CANCELLED` 由 tombstone 吸收并静默丢弃，`STARTED`/`PROGRESS` 同样不回调；未知 `invocationId` 的回调按协议违规关闭连接，因为回调只按 `invocationId` 归属，不按连接代际。
- `STARTED` 的可选 replayed 字段仅允许 true，表示已有 journal 记录的重放。

资源上传控制帧与终态的先后顺序由两端共同保证：Daemon 只在「调用仍活动」与「发送」的临界区内出站控制帧，核心则在终态核对前拒绝一切未就绪、伪造、重复或与申请不符的上传引用。

#### 回调顺序与背压上界

`DaemonEndpoint.receive` 不要求调用方串行化同一连接的多帧。核心的保证是：状态推进在 `gate` 内按到达顺序完成，回调批次在该顺序下入队，再由单一 drainer 在锁外同步执行，因此并发 `receive` 不会让同一连接上的 `PARTIAL*` 越过其后的 terminal。批次的执行不走任何异步 executor，drain 就发生在投递该帧的调用线程上：回调耗时直接构成该调用方的背压，而不是转嫁给另一个线程池形成无界积压。

等待执行的批次以每连接固定上界 `MAX_PENDING_CALLBACK_BATCHES = 64` 约束（入站排队本身由传输适配器拥有，核心不感知其容量）。达到上界即 fail-closed：丢弃该帧、关闭连接，并把该 Environment 仍在途的调用一次性回收为 [`EnvironmentCapabilitySendUncertainException`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilitySendUncertainException.java)，与实例接管同语义（回收回调追加在同一队列尾部，因此不会迟到于已入队的 partial）。

跨连接代际的精确边界：被接管或回收的 invocation 不再活动，因此旧连接队列里**尚未开始**的 partial 会在 drain 时复核活动性并静默丢弃，不会在新连接的 terminal 之后投递；已经进入 listener 的 partial 回调不做撤销承诺。

### 锁边界

理解并发时先区分协议推进、状态快照与回调执行。连接级锁的嵌套顺序为 `gate > state > inventory`，调用与 transfer 另有各自 monitor：

| monitor | 保护对象 |
| --- | --- |
| 每个连接代际的 `gate` | 该连接的入站协议处理序列与该连接待执行回调批次的排队 |
| `ConnectionState` 自身 | 该连接的协议字段（绑定、`leaseToken`、`ready`、清理标记、目标 OS）与该连接最近送达的临时资源 TTL/扫描间隔基线 |
| 核心 `inventory` | 环境目录、连接目录、`invocationId` 目录与 tombstone |
| 每个 `ActiveInvocation` | 其 transfer 集合与取消意图（终态标记是无锁 `AtomicBoolean`） |
| 每个 `TransferBinding` | 该 transfer 的请求事实、已签发 uploadId、票据缓存与释放标记 |

租约存储访问发生在连接 `gate` 内，但不持有 `state`、`inventory`、`ActiveInvocation` 或 `TransferBinding`；`invoke` 的围栏复核、票据服务 I/O、上传释放、连接关闭与全部 listener/session 回调都在上述锁之外执行，因此回调中重入核心 API 不会死锁。

回调本身不占用 `gate`：一帧产生的回调批次只在 `gate` 内按处理顺序入队，之后由单一 drainer 在锁外同步执行，其余并发 `receive` 把批次留在队列里即返回。因此同一连接上不存在两个线程同时执行回调，`onPartial` 阻塞只会推迟该连接后续批次的 drain，不会阻塞其他连接的协议处理，也允许回调内重入 `receive`。核心不持久化任何状态：进程重启后的会话事实由 daemon 重新握手建立。

### 注入端口与宿主设置

构造器只接收五个依赖：`DaemonLeaseStore`、`DaemonRegistrationDirectory`、`EnvironmentSessionListener`、`DaemonResourceTicketService` 与 `Supplier<EnvironmentServerSettings>`。

[`EnvironmentServerSettings`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentServerSettings.java)
携带心跳超时（同时用作租约期限）、资源字节上限与临时资源 TTL/扫描间隔，以 supplier 注入并在每个判定点现读，因此宿主修改配置立即
生效，核心不缓存配置；临时资源策略在同一连接的心跳通道按需推送 `TEMPORARY_RESOURCE_POLICY`，只对尚未回收的 workspace 生效。[`EnvironmentSessionListener`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentSessionListener.java)
在每次 READY 后于锁外唤醒宿主；生产组合 listener 同时唤醒 Harness Work dispatcher 与
异步 Skill Package 对账，两者失败彼此隔离，也不回滚已经成立的 READY 会话。

持久化实现（连接注册、租约、上传行与对象存储）与 WebSocket 传输实现在 `platform` 与 `web` 模块完成适配；本模块只定义窄端口语义，不感知 SQL、bucket、对象 key 或 Spring。

## 包架构

| 包路径 | 职责与边界 |
| --- | --- |
| `fun.fengwk.kkstudio.harness.environment` | canonical Environment UUID，无 I/O |
| `fun.fengwk.kkstudio.harness.environment.capability` | 固定目录、schema、执行与传输 SPI、发送确定性与终态契约 |
| `fun.fengwk.kkstudio.harness.environment.daemon` | wire 值与 codec、资源票据、远端 workdir 词法校验；不拥有连接、租约或执行状态 |
| `fun.fengwk.kkstudio.harness.environment.server` | 会话、调用、transfer、IDE 通道与围栏裁决；外部 I/O 通过 transport、lease、registration、ticket 端口接入，不引入产品 DTO 或框架 |

## 源码与测试

主要源码入口：[`EnvironmentId.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/EnvironmentId.java)、[`EnvironmentCapabilityCatalog.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityCatalog.java)、[`EnvironmentCapabilityTransport.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityTransport.java)、[`DaemonProtocol.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonProtocol.java)、[`DaemonEnvelopeCodec.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonEnvelopeCodec.java)、[`DaemonCapabilitiesCodec.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilitiesCodec.java)、[`DaemonCapabilityResultCodec.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilityResultCodec.java)、[`DaemonResourceTransferCodec.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonResourceTransferCodec.java)。

测试守卫：

- [`EnvironmentIdTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/EnvironmentIdTest.java) 与 [`EnvironmentCapabilityIdTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityIdTest.java)：身份与 capability id 的 canonical 解析与拒绝规则。
- [`EnvironmentCapabilityCatalogTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityCatalogTest.java)：11 项能力的固定顺序与版本、workdir schema 边界与模型/内部能力映射。
- [`EnvironmentCapabilityContractTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityContractTest.java) 与 [`EnvironmentCapabilityTransportTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityTransportTest.java)：请求归一化与 timeout 原样传递、结果体积边界、发送异常分类、事件顺序与终态唯一。
- [`DaemonEnvelopeCodecTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonEnvelopeCodecTest.java)、[`DaemonEnvelopeTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonEnvelopeTest.java)：协议版本、scope 与 invocationId 规则、未知/重复/尾随字段拒绝。
- [`DaemonCapabilitiesCodecTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilitiesCodecTest.java)：READY 只接受 `{version, environment}` wire 形状，未知字段、缺失字段与非法值都被拒绝。
- [`DaemonCapabilityInvokeCodecTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilityInvokeCodecTest.java) 与 [`DaemonCapabilityResultCodecTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilityResultCodecTest.java)：INVOKE 四个必填字段、PROGRESS 内容限制、终态预算预检先于上传、无字节 resource wire。
- [`DaemonResourceTransferCodecTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonResourceTransferCodecTest.java) 与 [`DaemonWorkdirSyntaxTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonWorkdirSyntaxTest.java)：票据三态互斥字段与预签名 PUT 约束；workdir 占位符、长度、跨 OS 与分隔符归一规则。
- [`EnvironmentDaemonServerTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServerTest.java)：以内存 fake 验证握手/租约围栏、并发调用、终态与 expire 竞争、同实例恢复/异实例接管、上传幂等/绑定校验/清理，以及回调保序、重入和积压超限失败关闭；[`EnvironmentDaemonServerIdeTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServerIdeTest.java) 覆盖 IDE 通道的准入、单次回执与断线收尾。基座 [`EnvironmentDaemonServerTestSupport.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServerTestSupport.java) 只表达核心依赖的窄端口语义。

覆盖率门禁为 POM 中绑定到 `verify` 的 JaCoCo `check`：能力目录、envelope codec 与 `EnvironmentDaemonServer` 的行覆盖率不低于 `0.90`。

---

上级：[系统设计](../system-design.md)。相关文档：[Common](common.md)、[Harness Runtime](harness-runtime.md)、[IDE](ide.md)、[Environment Daemon](daemon.md)、[Platform](platform.md)。
