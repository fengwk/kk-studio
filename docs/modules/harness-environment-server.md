# Harness Environment Server

`harness-environment-server` 是 Environment 链路中 Platform 一侧的会话核心：它把 protocol v1 的消息流推进为可观察状态，独占连接代际、`environment_connection` 路由租约围栏与按 `invocationId` 的在途调用生命周期，并向上层暴露传输、持久化与票据三类窄端口。协议形状、字段语义与大小约束由 [Harness Environment](harness-environment.md) 单独维护，本文件只描述服务端如何裁决；宿主进程侧的对应实现见 [Harness Daemon](harness-daemon.md)。

它是一个纯 Java 模块：生产依赖只有 `harness-common`、`harness-environment` 与 Jackson，不感知 Spring、JDBC、Servlet、JSR-356 或任何产品 DTO，因此整个会话状态机可以在普通单元测试里用内存 fake 完整驱动。宿主的装配位置见 [Platform](platform.md) 与 [Web](web.md)。

## 会话建立与租约围栏

[`EnvironmentDaemonServer`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServer.java) 同时实现两个窄端口：面向传输适配器的 [`DaemonEndpoint`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonEndpoint.java)（`open` / `receive` / `close`），以及面向产品调用方的 `EnvironmentCapabilityTransport.invoke`。此外它提供 `isReady`、`readyEnvironments`、`holdsReadyLease` 与 `expire(handle)` 供宿主判定与收敛。

一次连接的建立顺序固定如下：

```text
open(channel)
Daemon -> HELLO(registrationToken, daemonInstanceId)
  核心 -> registrationDirectory.findByRegistrationToken   # 解析 environmentId
  核心 -> leaseStore.hasActiveLeaseToken                 # 本节点同节点活跃连接防冲突
  核心 -> leaseStore.tryAcquire                          # 原子抢占/接管
  Gateway -> WELCOME(environmentId, name, maxResourceBytes)
Daemon -> READY(capabilities)
  -> leaseStore.markReady                                # 围栏失效即协议错误
  -> 重放未在当前连接代际发出的 INVOKE
Daemon -> HEARTBEAT*
  -> leaseStore.heartbeat                                # 围栏失效即协议错误
close(connectionId)
  -> leaseStore.disconnect                               # 幂等，保留重连宽限
```

[`DaemonLeaseStore`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonLeaseStore.java) 只表达围栏返回值，[`LeaseBindResult`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/LeaseBindResult.java) 以 `Acquired`、`Rejected`、`RetryLater` 三态表达认证与抢占结论；`markReady`、`heartbeat`、`holdsReadyLease` 以布尔值表达围栏是否仍然成立。围栏失守时核心按协议错误关闭连接，不尝试自行修复路由。

`Rejected` 与 `RetryLater` 是仅有的两个会写入冻结错误码的路径（`REGISTRATION_REJECTED` 与 `RETRY_LATER`）；存储抛出运行时异常时同样 fail-closed 关闭连接，但错误帧只带说明文本，不冒充这两个码。[`DaemonRegistrationDirectory`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonRegistrationDirectory.java) 只做 token 到 `environmentId` 的解析，token 明文不回显。

## 调用所有权与并发

调用只按 `(environmentId, invocationId)` 关联。同一 Environment 的多个 invocation 立即发送并并发持有，不存在 per-Environment 并发槽位、队列或容量配置。`invoke` 的前置条件按固定顺序执行：

1. 校验 capability descriptor 与 [`EnvironmentCapabilityCatalog`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityCatalog.java) 完全一致，并要求 `call.id` 是 canonical UUID；
2. 只对 `requiresWorkdir` 的能力，按该连接 READY 时冻结的目标 OS 做 arguments.workdir 词法校验；
3. 读取本节点 READY 连接与其 `leaseToken`，没有 READY 连接即判定环境不可用；
4. 在核心锁外调用 `leaseStore.holdsReadyLease` 复核归属，存储不可用同样判定环境不可用；
5. 登记 `ActiveInvocation` 并递交 INVOKE，把 [`DaemonOfferResult`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonOfferResult.java) 的三态映射为调用结果。

| 递交结果 | 映射 | 确定性 |
| --- | --- | --- |
| `ACCEPTED` | 返回执行句柄 | 帧已交给本地出站队列；不等于对端已收到 |
| `BUSY` | [`EnvironmentCapabilityBusyException`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityBusyException.java) | 帧确定未发送，连接保持可用，调用方可安全重试 |
| `CLOSED` | [`EnvironmentCapabilityUnavailableException`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityUnavailableException.java) | 帧确定未发送，调用确定未执行 |

同一 Environment 内重复使用相同活动 `invocationId` 属于调用方错误，在发送任何帧之前即被拒绝。传输自身发送失败只体现为连接失效：核心关闭该连接，调用仍保留在途，交由同实例重连以相同 `invocationId` 重放收敛。挂起调用的重放只针对尚未在当前连接代际发出过的 INVOKE，因此不会重复发送。

## 资源上传控制面

每个 `ActiveInvocation` 至多维护 `MAX_TRANSFERS_PER_INVOCATION = 16` 个以 `transferId` 唯一标识的 `TransferBinding`，上限的存在意义是阻止失控 Daemon 无界创建上传行。控制流程：

```text
RESOURCE_UPLOAD_REQUEST
  -> invocation 必须仍活动且属于当前 READY Environment
  -> 严格解码元数据，并在任何存储副作用前校验 WELCOME 通告的 maxResourceBytes
  -> 首次请求在核心锁外调用 ticketService.reserve，把结果固化为该 transfer 的票据事实

RESOURCE_UPLOAD_COMMIT
  -> transfer 必须已申请，uploadId 必须等于服务端签发值
  -> 在核心锁外调用 ticketService.commit

COMPLETED
  -> 结果引用的每个 uploadId 必须属于本 invocation、已 READY、只引用一次
  -> mediaType / name / size / sha256 必须与 REQUEST 完全一致
  -> 保留结果实际引用的上传给 history 物化事务，释放其余上传
```

同一 `transferId` 的重复同形 REQUEST 是幂等的：已绑定的票据直接复用，绝不重复创建上行；同一 transfer 漂移为不同请求、对未申请的 transfer 提交、使用与签发值不同的 uploadId、引用已释放的 transfer，都是协议违规。[`DaemonResourceTicketService`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonResourceTicketService.java) 的所有数据库与对象存储动作、票据回调与释放动作都在核心状态锁之外执行；同一 binding 自身串行化重复 reserve/commit，因此同实例重连重放不会重复创建上传行。

票据服务异常统一转换为固定的 `resource upload is unavailable` 失败回执：预签名 URL、签名 header 与存储异常原文都不进入控制面反馈。`FAILED` 票据不缓存，允许在 invocation deadline 内再次触达服务；`FAILED`/`CANCELLED`、接管、`expire` 与调用放弃都幂等释放全部未消费上传，而 `COMPLETED` 只释放未被结果引用的上传。

二进制字节不经过会话核心：核心只处理有界元数据与票据，并在任何外部 I/O 之前完成作用域、预算与绑定校验。

## 终态唯一与迟到帧

`COMPLETED`/`FAILED`/`CANCELLED` 回调、实例接管与显式 `expire` 竞争时只有一个赢家；已终结 invocation 的标识由 `MAX_INVOCATION_TOMBSTONES = 1024` 的有界 tombstone 记录，超限时淘汰最旧条目。

- `expire(handle)` 由调用方在自身 deadline 上判定超时：它至多发送一次 `CANCEL`，不向 listener 补发终态，并把该 invocation 移出重放集合，之后到达的 daemon 终态被 tombstone 静默丢弃。
- 物理连接失效**不**终结在途调用：同一 `daemonInstanceId` 重连 READY 后以相同 `invocationId` 重放，Daemon journal 负责重放 `STARTED` 与终态而不重复执行副作用。
- 身份不同的新 Daemon 进程接管该 Environment 时，旧进程已不可能再提供终态，其全部在途调用恰好一次地以 [`EnvironmentCapabilitySendUncertainException`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilitySendUncertainException.java) 收敛为结果不确定，并行释放名下上传，绝不留给新进程消费。
- 已终结 invocation 的迟到或重放 `COMPLETED`/`FAILED`/`CANCELLED` 由 tombstone 吸收并静默丢弃，`STARTED`/`PROGRESS` 同样不回调；未知 `invocationId` 的回调按协议违规关闭连接，因为回调只按 `invocationId` 归属，不按连接代际。
- `STARTED` 若携带 `replayed` 字段只能为 `true`，避免把从未执行过的调用伪装成重放。

资源上传控制帧与终态的先后顺序由两端共同保证：Daemon 只在「调用仍活动」与「发送」的临界区内出站控制帧，核心则在终态核对前拒绝一切未就绪、伪造、重复或与申请不符的上传引用。

## 锁边界

核心只用五个 monitor，锁顺序固定为 `gate > state > inventory`：

| monitor | 保护对象 |
| --- | --- |
| 每个连接代际的 `gate` | 该连接的入站协议处理序列 |
| `ConnectionState` 自身 | 该连接的协议字段（绑定、`leaseToken`、`ready`、清理标记、目标 OS） |
| 核心 `inventory` | 环境目录、连接目录、`invocationId` 目录与 tombstone |
| 每个 `ActiveInvocation` | 其 transfer 集合与取消、终态标记 |
| 每个 `TransferBinding` | 该 transfer 的请求事实、已签发 uploadId、票据缓存与释放标记 |

租约存储访问发生在连接 `gate` 内，但不持有 `state`、`inventory`、`ActiveInvocation` 或 `TransferBinding`；`invoke` 的围栏复核、票据服务 I/O、上传释放、连接关闭与全部 listener/session 回调都在上述锁之外执行，因此回调中重入核心 API 不会死锁。核心不持久化任何状态：进程重启后的会话事实由 daemon 重新握手建立。

## 注入端口与宿主设置

构造器只接收五个依赖：`DaemonLeaseStore`、`DaemonRegistrationDirectory`、`EnvironmentSessionListener`、`DaemonResourceTicketService` 与 `Supplier<EnvironmentServerSettings>`。

[`EnvironmentServerSettings`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentServerSettings.java) 携带心跳超时与资源字节上限，以 supplier 注入并在每个判定点现读，因此宿主修改配置立即生效，核心不缓存配置。[`EnvironmentSessionListener`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentSessionListener.java) 在每次 READY 后于锁外唤醒宿主（生产装配用于唤醒 Harness Work dispatcher），实现抛出的异常不影响会话状态。

持久化实现（连接注册、租约、上传行与对象存储）与 WebSocket 传输实现在 `platform` 与 `web` 模块完成适配；本模块只定义窄端口语义，不感知 SQL、bucket、对象 key 或 Spring。

## 包架构

| 包路径 | 职责与边界 |
| --- | --- |
| `fun.fengwk.kkstudio.harness.environment.server` | protocol v1 的服务端会话核心。`EnvironmentDaemonServer` 独占 HELLO/WELCOME/READY/HEARTBEAT/INVOKE/CANCEL、资源 transfer、租约围栏与按 `invocationId` 维度的在途调用状态；`DaemonEndpoint`/`DaemonChannel` 是 transport 窄端口，`DaemonLeaseStore`/`DaemonRegistrationDirectory` 是持久化窄端口，`DaemonResourceTicketService` 承载 Blob 上传生命周期，`EnvironmentSessionListener`/`EnvironmentServerSettings` 承载宿主回调与现读设置。本包只依赖 JDK、Jackson、`harness-common` 与 `harness-environment`，不感知 Spring、持久化实现与产品 DTO。 |

## 源码与测试

主要源码入口：[`EnvironmentDaemonServer.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServer.java)、[`DaemonEndpoint.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonEndpoint.java)、[`DaemonChannel.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonChannel.java)、[`DaemonLeaseStore.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonLeaseStore.java)、[`LeaseBindResult.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/LeaseBindResult.java)、[`DaemonResourceTicketService.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonResourceTicketService.java)、[`DaemonOfferResult.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonOfferResult.java)、[`EnvironmentServerSettings.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentServerSettings.java)。

测试守卫：

- [`EnvironmentDaemonServerTest.java`](../../harness/environment-server/src/test/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServerTest.java)：以内存 fake 驱动完整状态机，覆盖同环境并发调用、终态唯一、超时 `expire`、队列 `BUSY` 拒绝、同实例重连重放、异实例接管、连接代际接管、上传申请/提交幂等与伪造/漂移拒绝、终态引用核对与清理、握手校验与租约围栏 fail-closed。
- [`EnvironmentDaemonServerTestSupport.java`](../../harness/environment-server/src/test/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServerTestSupport.java)：测试基座，只表达核心真正依赖的窄端口语义，不模拟 SQL 或 WebSocket。
- [`EnvironmentServerModuleArchitectureTest.java`](../../harness/environment-server/src/test/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentServerModuleArchitectureTest.java)：守卫主源码 import 白名单与 POM 生产依赖白名单。

模块级覆盖率门禁为本模块 POM 中绑定到 `verify` 的 JaCoCo `check`：`EnvironmentDaemonServer` 行覆盖率不低于 `0.90`。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Environment](harness-environment.md)、[Harness Daemon](harness-daemon.md)、[Platform](platform.md)、[Web](web.md)。
