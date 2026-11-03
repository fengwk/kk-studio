# Harness Environment

`harness-environment` 是 Agent 使用主机能力的内核契约与服务端会话核心：路由身份、原子能力目录、调用侧发送语义、Gateway 与 Daemon 之间的 wire 协议，以及按连接代际、路由租约与 `invocationId` 裁决调用的 [`EnvironmentDaemonServer`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServer.java)。修改调用参数或协议时，以本模块的值对象、schema 与 codec 为准；主机进程侧的实现见 [Environment Daemon](daemon.md)，数据库、WebSocket 与对象存储由 [Platform](platform.md) 与 [Web](web.md) 经窄端口接入。

## Environment 身份

[`EnvironmentId`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/EnvironmentId.java) 是规范 UUID 路由身份：构造与解析只接受 `UUID#toString()` 的小写 canonical 文本，空白、大小写变体与其它形状一律拒绝，`toString()` 也只输出该形式。

Environment 资源、daemon envelope scope、分支执行路由与 Harness Work 亲和性共用这个 UUID。产品展示名与历史名称的冻结由 [Platform](platform.md)维护；调用目录在具体 capability arguments 中显式给出。Studio 保存的安装设置（`installConfig`）属于产品侧持久状态，不进入 wire；Daemon 只从 `--config` 指向的 `daemon.json` 消费其中的 `daemon` 对象（studioUrl、note、bashExecutable、内联 LSP servers）。

## 原子能力目录

[`EnvironmentCapabilityCatalog`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityCatalog.java)
定义了全部原子能力。
[`EnvironmentCapabilityDescriptor`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityDescriptor.java)
只有三个契约属性：

```text
id / inputSchema / defaultTimeout
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

EnvironmentCapabilityExecutionHandle attach(
    EnvironmentId environmentId,
    UUID invocationId,
    EnvironmentCapabilityExecutionListener listener);
```

传输外壳只携带冻结的 `environmentId`，不携带第二份 workdir。`attach` 用于接管已经在其他节点或原执行中发出的远端调用：它为指定的 `invocationId` 登记在途记录而不发送 `INVOKE`；连接处于 READY 时立即下发 `RECOVER`，未 READY 时随下次 READY 恢复；本节点不持有 READY 租约时抛 [`EnvironmentCapabilityUnavailableException`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityUnavailableException.java)；同一 `invocationId` 已处于活跃在途状态时抛 `IllegalArgumentException`，若存在历史 tombstone（如本节点此前放弃等待该调用）则允许替换 tombstone 并重新登记在途记录。事件契约与 `invoke` 一致：`PROGRESS* -> exactly one terminal`，`RECOVER_NOT_FOUND` 以 [`EnvironmentCapabilitySendUncertainException`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilitySendUncertainException.java) 结束。发送阶段的异常具有固定语义：

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
void terminate(EnvironmentCapabilityTerminationCause cause);
Duration terminalDeliveryGrace();
```

`cancel()` 请求取消，`isCancelled()` 表示已收到停止请求，不证明远端执行已经结束。
`request.timeout` 是 capability 的远端执行预算，`0` 显式表示无界；
`terminalDeliveryGrace` 是终态传输恢复窗，不控制 TypeScript Daemon 的执行计时器。
生产远端句柄在 invoke 时冻结当前 `heartbeatTimeout` 为该窗口，因此它只覆盖终态回传与重连恢复，
不是额外执行时间，也不会重置执行预算。Platform Tool Gateway 从发送前起只合成一次等待上界
`request.timeout + terminalDeliveryGrace`；partial 和重连不延长它，`timeout` 为 `0` 时保持无界，
不得因恢复窗变成有界等待。恢复窗内到达的真实终态（包括执行预算耗尽后的输出）原样保留。
缺少终态到期时返回 UNKNOWN，外部效果不确定，不可自动重放；到期或等待线程中断时调用
`terminate(cause)`，原子移除 server 在途记录、登记 tombstone、请求远端取消并释放 transfer，
迟到事件丢弃且后续重连不再恢复为执行；本地放弃不证明远端副作用已停止或回滚。null 或负的 `terminalDeliveryGrace` 是句柄契约违规：Gateway 显式失败并终止
未收敛的在途执行，不按 `ZERO` 静默兜底；等待上界换算异常同样先释放资源，再把原始异常向上抛出。
普通 `cancel()` 仍保留等待远端终态的协作语义。
Daemon Runtime 的唯一 invoke timer 通过 `context.signal` 区分 `TIMED_OUT` 与 `CANCELLED`；
执行器完成清理后交付真实结果，Runtime 不提前抢占终态，见 [Daemon 的超时与取消收尾](daemon.md#能力装配与运行时调度)。

能力结果 [`EnvironmentCapabilityResult`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityResult.java) 是 `callId` 加 `contents`（至多 64 项）、`error` 与 `detailsJson`（至多 1 MiB UTF-8）。错误结果可以只带文本，也可以带稳定错误码：[`EnvironmentCapabilityResultCodes`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityResultCodes.java) 只冻结码的语法 `[A-Z][A-Z0-9_]*`（至多 64 字符），当前没有预置码常量。

## daemon wire 协议会话与消息流

控制协议基于单一 Protobuf 定义 [`control.proto`](../../harness/environment/src/main/protocol/control.proto)，传输层由 [`ControlProtocolCodec`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/protocol/ControlProtocolCodec.java) 进行 4 字节大端长度前缀成帧与单阶段应用层 wire 校验。消息集合包括：

```text
SETTINGS / READY / HEARTBEAT / RUNTIME_STATUS
INVOKE / STARTED / PROGRESS / COMPLETED / FAILED
CANCEL / RECOVER / CANCELLED / EXECUTION_EXITED / ERROR
RESOURCE_UPLOAD_REQUEST / RESOURCE_UPLOAD_TICKET / RESOURCE_UPLOAD_COMMIT
UPDATE / UPDATE_RESULT
IDE_COMMAND / IDE_RESULT
IDE_LEASE
```

`ControlEnvelope` 的固定字段为：

```text
message_type / environment_id / invocation_id? / payload (oneof body)
```

`environment_id` 是规范小写 UUID 字符串作用域。调用与资源上传消息通过非空 `invocation_id`（UUID 字符串）关联具体执行；IDE 命令与回执同样携带请求 UUID；而 `SETTINGS`、`READY`、`HEARTBEAT`、`RUNTIME_STATUS` 与 `IDE_LEASE` 作用于整个连接作用域，不携带 `invocation_id`。

重连恢复按 `invocationId` 以不终止执行的 `RECOVER` 取回在途调用（Daemon 回放 STARTED，或重放冻结的终态与退出确认），只有调用方已表达取消意图的调用才重放 `CANCEL`；各调用沿自己的 STARTED/PROGRESS/终态推进。

握手与调用时序：

```text
Studio -> SETTINGS(resource_budget, temporary_resources, heartbeat_timeout_ms, execution_lease_timeout_ms)
Daemon -> READY(environment, runtime_status)
Daemon -> HEARTBEAT(runtime_status, ide_lease_round)*
Daemon -> RUNTIME_STATUS(runtime_status)

Studio -> INVOKE
Daemon -> STARTED
Daemon -> PROGRESS*
Daemon -> RESOURCE_UPLOAD_REQUEST
Studio -> RESOURCE_UPLOAD_TICKET(PENDING | READY | FAILED)
Daemon -> RESOURCE_UPLOAD_COMMIT
Studio -> RESOURCE_UPLOAD_TICKET(READY | FAILED)
Daemon -> COMPLETED | FAILED
Daemon -> EXECUTION_EXITED

Studio -> CANCEL
Daemon -> CANCELLED | 已冻结的终态重放
Daemon -> EXECUTION_EXITED | 已保存的退出确认重放

Studio -> RECOVER（同实例重连后取回，不终止执行）
Daemon -> STARTED(replayed=true) | 已冻结的终态重放
Daemon -> EXECUTION_EXITED | 已保存的退出确认重放

Studio -> IDE_COMMAND
Daemon -> IDE_RESULT
Studio -> IDE_LEASE(round, bindings)
```

`SETTINGS` 载荷通告认证准入后的资源预算（`max_resource_bytes`）与临时资源策略（`temporary_resource_ttl_seconds` / `temporary_resource_cleanup_interval_seconds`）。Daemon 收到 `SETTINGS` 并回执 `READY` 后连接进入可用状态。

`daemon_instance_id` 是 Daemon 进程构造期随机生成一次、所有重连复用的规范 UUID；Studio 在同实例重连后，对未收敛调用发送 `RECOVER`（已取消的发送 `CANCEL`）而绝不重发 `INVOKE`，Daemon 命中 journal 时重放既有事实，副作用不重复执行。不同实例接管将旧调用判为结果不确定。两端在途记录均为进程内状态，协议不保证跨 Backend 重启的执行恢复。握手失败时 Studio 以 `ERROR` 帧并关闭连接收尾。

## 载荷与编解码

控制协议各帧的载荷均为 Protobuf 强类型消息，集中定义于 `control.proto`。传输层入站解码由 `ControlProtocolCodec.decodeFrame` 完成单一阶段 wire 校验；服务端核心 `DaemonSession.receive` 假定输入帧已通过 wire 校验，只执行会话语义与状态机校验（如 `READY` 仅允许一次、环境标识匹配等）。

宿主环境元数据持久化：[`DaemonCapabilitiesCodec`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilitiesCodec.java) 编解码宿主侧环境元数据，由 Server 持久化到 `runtime_info`。当前运行状态仅保存在连接会话内存中，不持久化。`environment_connection` 保留：
- `runtime_info`：最后接受的 READY 宿主 metadata，断线保留。实际 Daemon 版本只取此上报事实。
- `skill_state`：可重建投影，READY 后重新同步；旧代际不能覆盖新代际。
- `recent_events`：最近 200 条去敏、有界运维事件，包括更新失败；不作为锁或完整审计账本。

`READY` 携带宿主侧的环境元数据与当前运行状态。环境元数据包含目标操作系统、时区、Daemon 进程用户、用户 HOME、操作者备注、受控临时目录与 CPU 架构。运行状态（`RuntimeStatus`）由 revision 与状态详情组成：

```text
runtimeStatus: {
  revision: string (规范正十进制且不超过 long 上限)
  status: {
    daemonVersion: string
    update: {
      targetVersion: string
      downloadedBytes: number (非负)
      totalBytes: number | null (非负且不小于已下载量)
    } | null
  }
}
```

版本更新状态在增量变化时通过 `RUNTIME_STATUS` 或心跳载荷同步；无更新时 `update` 为 null。Server 仅在接受更高 revision 后替换缓存；同 revision 内容不同是协议错误，更低 revision 视为迟到快照忽略。

`IDE_COMMAND` 与 `IDE_RESULT` 是连接上的 IDE 控制通道；`IDE_LEASE` 承载 IDE 租约批量证明下发。资源上传控制消息（`RESOURCE_UPLOAD_REQUEST`、`RESOURCE_UPLOAD_TICKET`、`RESOURCE_UPLOAD_COMMIT`）通过 `invocationId` 与 `transferId` 精确关联单次上传。二进制字节不进入控制通道，实际 PUT 上传由 Daemon 直连对象存储。

### 数据面环境隧道与多节点路由

数据面复用与当前 Daemon 连接绑定的环境隧道（`EnvironmentTunnel`），专用于受控 IDE 视图的 HTTP 请求与 WebSocket 流量：

- **HTTP 交换**：网关通过 `EnvironmentTunnel.openHttp` 提交请求行、请求头与输入流；响应头到达后写入浏览器响应，响应体通过流式复制透传至浏览器输出，完成后正常关闭交换。
- **WebSocket 隧道**：网关通过 `EnvironmentTunnel.openWebSocket` 发起隧道建立，上游 code-server 确认后返回双向字节流，网关直接接管底层 TCP/WebSocket 连接进行双向透传。
- **背压与并发**：流式数据传输直接受两端 TCP 与 HTTP/2 流控协议的背压约束，流量在两端原生背压控制下流转，不进行私有二进制切帧封装。物理连接失联时中止所有活跃交换。
- **非 owner 转发**：浏览器不要求粘性路由；非 owner 节点 B 收到 IDE 流量时，从 `environment_connection` 和 `cluster_node` 找到 owner 节点 A，通过内部单跳代理转发给 A，只有 owner 节点直接操作环境隧道。owner 变化或内部转发失败时终结请求，不改投另一节点重放副作用。

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

`EnvironmentDaemonServer` 实现核心服务接口：面向会话管理的 [`DaemonSessions`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonSessions.java)（`admit` / `open`）、面向数据面隧道的 [`EnvironmentTunnels`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/tunnel/EnvironmentTunnels.java)，以及面向产品调用方的 `EnvironmentCapabilityTransport.invoke`。此外它提供 `isReady`、`readyEnvironments`、`holdsReadyLease` 与 `expire(handle)` 供宿主判定与收敛，并为 [IDE](ide.md) 提供不解析载荷的 `requestIde` / `abandonIde` 通道与连接代际快照 `connectionIdentity`。

一次连接的建立顺序固定如下：

```text
admit(registrationToken, clientCapabilities)
  核心 -> registrationDirectory.findByRegistrationToken   # 解析 environmentId
  核心 -> leaseStore.hasActiveLeaseToken                 # 本节点同节点活跃连接防冲突
  核心 -> leaseStore.tryAcquire                          # 原子抢占/接管
open(admitted, channel, listener) -> DaemonSession
  核心 -> 发送 SETTINGS(resourceBudget, temporaryResources, timeouts)
Daemon -> READY(environment, runtimeStatus)
  -> leaseStore.markReady                                # 围栏失效即协议错误
  -> 未终结在途调用发送 RECOVER（已取消的发送 CANCEL），不重发 INVOKE
Daemon -> HEARTBEAT(runtimeStatus, ideLeaseRound)*
  -> leaseStore.heartbeat                                # 围栏失效即协议错误
close(connectionId)
  -> leaseStore.disconnect                               # 幂等，保留重连宽限
```

[`DaemonLeaseStore`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonLeaseStore.java) 只表达围栏返回值，[`LeaseBindResult`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/server/LeaseBindResult.java) 以 `Acquired`、`Rejected`、`RetryLater` 三态表达认证与抢占结论；`markReady`、`heartbeat`、`holdsReadyLease` 与建连时的 `hasActiveLeaseToken` 以布尔值表达围栏是否仍然成立。围栏失守时核心按协议错误关闭连接，不尝试自行修复路由。

准入阶段在升级前裁决：`Rejected` 映射为 HTTP 401（注册凭据无效），`RetryLater` 映射为 HTTP 503 与 Retry-After 响应头（暂不可准入）。存储运行时异常使连接 fail closed，错误响应给出独立说明文本。[`DaemonRegistrationDirectory`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonRegistrationDirectory.java) 将 token 解析为 environmentId，反馈保持去敏。

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

同一 Environment 内重复使用相同活动 `invocationId` 属于调用方错误，在发送任何帧之前即被拒绝。传输递交时抛错会关闭连接，但返回活动句柄，等待同实例恢复；不能把这条路径解释为确定未执行。恢复时，旧连接代际或初次递交结果不确定的在途调用发送相同 `invocationId` 的 `RECOVER`，仅已有取消意图时发送 `CANCEL`，绝不重发 `INVOKE`；当前代际刚成功初次递交 INVOKE 且尚未取消的调用保持排除。这些判断集中在连接状态锁内，避免 READY 与新调用并发时误恢复。Daemon 命中 journal 时，`RECOVER` 回放 STARTED 或既有终态与独立退出事实，不停止仍在运行的 scope；`CANCEL` 才要求停止。未命中时不执行、不造终态或 ACK，调用沿现有 deadline/terminate 路径结束等待。取消意图与成功递交代际分别记录，BUSY/CLOSED 不算送达，也不靠空转轮询重试。

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

`EnvironmentDaemonServer` 额外承载专用管理通道（`UPDATE` / `UPDATE_RESULT`），它不经过 capability 目录，因此即使连接未注册任何普通 capability，连接仍可完成认证并承载版本查询与受管更新。

- `beginUpdate(environmentId, operationId)`：要求当前节点 READY、无在途调用、无其它活动 operation；成功后登记该 operation，此后该
  Environment 的普通调用（含 Skill 同步）在起点 busy。同一 operationId 重复准入幂等。
- `sendUpdate(environmentId, command)`：只在同一 operation 已准入时下发 `UPDATE`；命令未进入传输时按 BUSY/UNAVAILABLE 收敛。
- `endUpdate(environmentId, operationId)`：只有与当前登记一致的 operationId 才释放准入，旧 operation 不能释放新 operation。
- `UPDATE_RESULT` 只做协议校验后转交 `EnvironmentUpdateListener` 在核心锁外消费；核心不判定最终成功。

「一个 Environment 同一时刻至多一次更新」由核心内存准入保证：`UPDATE` / `UPDATE_RESULT` 不落任务表，Server 只保留当前命令的有界回执，不提供更新历史查询。更新期间对旧二进制的
任何替换都由 Daemon 与独立 OS 更新器完成，核心只负责准入、下发与回执转发。

### 终态唯一与迟到帧

`COMPLETED`/`FAILED`/`CANCELLED` 回调、实例接管与显式 `expire` 竞争时只有一个赢家；已终结 invocation 的标识由 `MAX_INVOCATION_TOMBSTONES = 1024` 的有界 tombstone 记录，超限时优先淘汰退出确认已交付的最旧条目，只有全部条目都仍在等待退出确认时才淘汰最旧条目。

- `expire(handle)` 由调用方在自身 deadline 上判定超时：它至多发送一次 `CANCEL`，不向 listener 补发终态，并把该 invocation 移出活动恢复集合，之后到达的 daemon 终态被 tombstone 静默丢弃。同一有界 tombstone 保留原 listener 与发起调用时的 daemon 身份：随后真正到来的空 `EXECUTION_EXITED` 仍调用 `onExitConfirmed()` 一次。其它 daemon 身份、重复帧和非空 payload 都不确认。条目被容量淘汰后不再保留 listener 或 daemon 身份，迟到确认不再交付；此时该帧与未知 invocation 一样按协议违规关闭连接，关闭本身不是退出证据。退出确认与结果终态无关，取消、超时和断连本身不产生它。若终态或 `expire` 已移除活动调用而退出确认尚未交付，同一 daemon 实例重新 READY 时只为该 tombstone 重发一次 `CANCEL`，让 Daemon 从 journal 重放既有终态/退出事实或停止仍运行的执行范围；绝不重发 `INVOKE`，不重新执行。未知 `CANCEL` 不产生确认，已交付或已被淘汰的条目、以及不同 daemon 身份都不触发重发。
- `onExitConfirmed()` 的回调交付不等于退出账本已提交。回调失败只记录固定阶段与异常类名，不记录原始异常正文或凭据，也不阻断后续回调；重复 ACK 不重试失败写入，不补造持久退出事实。
- 物理连接失效**不**终结在途调用：同一 `daemonInstanceId` 重连 READY 后，旧代际或初次递交结果不确定的调用以相同 `invocationId` 发送 `RECOVER`，绝不重发 `INVOKE`；Daemon 命中 journal 时回放 STARTED 并继续执行，或重放既有终态/退出事实；只有调用方已表达取消意图的调用才重放 `CANCEL` 并停止 scope。控制连接的一次抖动因此不会停止仍在运行的命令，调用仍由原 listener 收敛。
- 身份不同的新 Daemon 接管 Environment 时，旧在途调用恰好一次地以 [`EnvironmentCapabilitySendUncertainException`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilitySendUncertainException.java) 收敛为结果不确定，并释放其上传；新进程从自己的实例事实开始接受调用。旧主机副作用的停止或回滚仍需独立核查。
- 已终结 invocation 的迟到或重放 `COMPLETED`/`FAILED`/`CANCELLED` 由 tombstone 吸收并静默丢弃，`STARTED`/`PROGRESS` 同样不回调；完全未知的调用帧（既不在活跃在途表也不在 tombstone 中）安全忽略并记录 DEBUG 日志，连接保持打开，Daemon 的 journal 随后在 `RECOVER` 中重放事实。
- `STARTED` 的可选 replayed 字段仅允许 true，表示已有 journal 记录的重放。

资源上传控制帧与终态的先后顺序由两端共同保证：Daemon 只在「调用仍活动」与「发送」的临界区内出站控制帧，核心则在终态核对前拒绝一切未就绪、伪造、重复或与申请不符的上传引用。

#### 优雅排空与路由释放

[`DaemonControlChannel`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonControlChannel.java) 的 `drain()` 由传输层实现，依次使隧道拒绝新流、向对端发送 `NO_ERROR` 的 HTTP/2 `GOAWAY`、并执行控制流 `closeAfterFlush`，已建立的在途流可正常完成。

[`EnvironmentDaemonServer`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServer.java) 的 `drain()` 编排节点退出：对当前全部活动会话调用 `control.drain()`，并以各自的 `leaseToken` 调用 [`DaemonLeaseStore.release(...)`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonLeaseStore.java) 主动释放路由租约，供新节点立即接管；排空开始后，随后的准入请求一律拒绝并返回 `RetryLater`，`invoke` 与 `attach` 均判定环境不可用。此外，`drain()` 之后本节点不再代表任何 Environment，调用方放弃在途执行时（`expire`）只做本地退场与释放 transfer，不再向 Daemon 发送 `CANCEL` 帧，远端调用留给下一个 owner 重新挂接。`drain()` 操作保证幂等。

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
异步 Skill Package 对账，两者失败彼此隔离，也不回滚已经成立的 READY 会话。另一个 Daemon 实例在本节点接管某个 Environment 时，
listener 在锁外收到 `onDaemonInstanceReplaced(previous)`，宿主据此关闭旧实例的 IDE 代际；同一实例断线重连不触发该回调。

持久化实现（连接注册、租约、上传行与对象存储）与 WebSocket 传输实现在 `platform` 与 `web` 模块完成适配；本模块只定义窄端口语义，不感知 SQL、bucket、对象 key 或 Spring。

## 包架构

| 包路径 | 职责与边界 |
| --- | --- |
| `fun.fengwk.kkstudio.harness.environment` | canonical Environment UUID，无 I/O |
| `fun.fengwk.kkstudio.harness.environment.capability` | 固定目录、schema、执行与传输 SPI、发送确定性与终态契约 |
| `fun.fengwk.kkstudio.harness.environment.daemon` | wire 值与 codec、资源票据、远端 workdir 词法校验；不拥有连接、租约或执行状态 |
| `fun.fengwk.kkstudio.harness.environment.protocol` | 控制协议的 protobuf binary：单一 `control.proto` 生成类型、四字节成帧与应用层校验；覆盖 SETTINGS（含资源预算）、调用、恢复、上传、更新、IDE 元数据与 426 `UpgradeRequired` 载荷 |
| `fun.fengwk.kkstudio.harness.environment.server` | 会话、调用、transfer、IDE 通道与围栏裁决；外部 I/O 通过 transport、lease、registration、ticket 端口接入，不引入产品 DTO 或框架 |
| `fun.fengwk.kkstudio.harness.environment.tunnel` | 本节点隧道、HTTP 交换与 WebSocket 隧道（RFC 8441 extended CONNECT）端口及值对象；不选择传输引擎。测试 fake 使用零缓冲 rendezvous 与 16KiB 分段，通过 environment test-jar 复用 |
| `fun.fengwk.kkstudio.harness.environment.transport` | 基于 Jetty 12 的传输实现：`WebSocketEndPoint`（单槽 demand 驱动 backpressure、flush 模式缓冲维护）、`DaemonHttp2ClientSession`（HTTP/2 会话生命周期、15s PING/30s ACK 超时、GOAWAY 状态与优雅终止）、`DaemonControlStream`（`POST /control` 封帧、256 帧/16 MiB 预算与 BUSY 背压、10s 超时）、`DaemonEnvironmentTunnel`（流式 HTTP 交换与 RFC 8441 扩展 CONNECT 双向字节管道） |

## 源码与测试

主要源码入口：[`EnvironmentId.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/EnvironmentId.java)、[`EnvironmentCapabilityCatalog.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityCatalog.java)、[`EnvironmentCapabilityTransport.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityTransport.java)、[`ControlProtocolCodec.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/protocol/ControlProtocolCodec.java)、[`DaemonSessions.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonSessions.java)、[`DaemonSession.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonSession.java)、[`EnvironmentDaemonServer.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServer.java)、[`DaemonConnectionSession.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonConnectionSession.java)、[`DaemonCapabilitiesCodec.java`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilitiesCodec.java)。

测试守卫：

- [`EnvironmentIdTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/EnvironmentIdTest.java) 与 [`EnvironmentCapabilityIdTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityIdTest.java)：身份与 capability id 的 canonical 解析与拒绝规则。
- [`EnvironmentCapabilityCatalogTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityCatalogTest.java)：11 项能力的固定顺序、workdir schema 边界与模型/内部能力映射。
- [`EnvironmentCapabilityContractTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityContractTest.java) 与 [`EnvironmentCapabilityTransportTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityTransportTest.java)：请求归一化与 timeout 原样传递、结果体积边界、发送异常分类、事件顺序与终态唯一。
- [`ControlProtocolCodecTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/protocol/ControlProtocolCodecTest.java)：成帧大端长度、超限防爆（16 MiB 上限）、强类型 Protobuf 消息往返与各消息类型字段校验。
- [`DaemonCapabilitiesCodecTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilitiesCodecTest.java)：宿主 metadata 只接受 `{daemonVersion, environment}` wire 形状，未知字段、缺失字段与非法值都被拒绝。
- [`DaemonWorkdirSyntaxTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonWorkdirSyntaxTest.java)：workdir 占位符、长度、跨 OS 与分隔符归一规则。
- [`EnvironmentDaemonServerTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServerTest.java)：以内存 fake 验证握手/租约围栏、并发调用、终态与 expire 竞争、同实例恢复/异实例接管、上传幂等/绑定校验/清理，以及回调保序、重入和积压超限失败关闭；[`EnvironmentDaemonServerIdeTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServerIdeTest.java) 覆盖 IDE 通道的准入、单次回执与断线收尾。基座 [`EnvironmentDaemonServerTestSupport.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServerTestSupport.java) 只表达核心依赖的窄端口语义。
- [`WebSocketEndPointTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/transport/WebSocketEndPointTest.java)、[`DaemonControlStreamTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/transport/DaemonControlStreamTest.java)、[`DaemonEnvironmentTunnelTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/transport/DaemonEnvironmentTunnelTest.java)、[`DaemonHttp2ClientSessionTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/transport/DaemonHttp2ClientSessionTest.java)、[`DaemonHttp2SessionLoopbackTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/transport/DaemonHttp2SessionLoopbackTest.java)：通过管道和内存 HTTP/2 验证端到端握手、控制通道背压/超限、通道生命周期、PING/ACK 存活心跳超时与断线清理。

覆盖率门禁为 POM 中绑定到 `verify` 的 JaCoCo `check`：能力目录、控制编解码器、`EnvironmentDaemonServer` / `DaemonConnectionSession` / `DaemonInvocationTracker` / `DaemonTransferCoordinator` / `DaemonIdeCoordinator` / `DaemonUpdateCoordinator` 等核心组件，以及传输实现（`fun.fengwk.kkstudio.harness.environment.transport.*`）行覆盖率不低于 `0.90`。

---

上级：[系统设计](../system-design.md)。相关文档：[Common](common.md)、[Harness Runtime](harness-runtime.md)、[IDE](ide.md)、[Environment Daemon](daemon.md)、[Platform](platform.md)。
