# Harness Environment Server

`harness-environment-server` 是 Platform 侧的环境会话核心，管理连接代际、路由租约围栏与按 `invocationId` 关联的在途调用。数据库、WebSocket 和对象存储由宿主通过窄端口接入。协议形状、字段语义与大小约束由
[Harness Environment](harness-environment.md) 单独维护，本文件只描述服务端如何裁决；
宿主进程侧的对应实现见 [Harness Daemon](harness-daemon.md)。

生产依赖为 `harness-common`、`harness-environment`、`share` 与 Jackson。会话状态机通过 lease、registration、channel、ticket 和 terminal listener 端口消费宿主能力，可用内存 fake 在普通单元测试中驱动；生产装配见 [Platform](platform.md) 与 [Web](web.md)。

## 会话建立与租约围栏

[`EnvironmentDaemonServer`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServer.java) 同时实现两个窄端口：面向传输适配器的 [`DaemonEndpoint`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonEndpoint.java)（`open` / `receive` / `close`），以及面向产品调用方的 `EnvironmentCapabilityTransport.invoke`。此外它提供 `isReady`、`readyEnvironments`、`holdsReadyLease` 与 `expire(handle)` 供宿主判定与收敛。

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

[`DaemonLeaseStore`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonLeaseStore.java) 只表达围栏返回值，[`LeaseBindResult`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/LeaseBindResult.java) 以 `Acquired`、`Rejected`、`RetryLater` 三态表达认证与抢占结论；`markReady`、`heartbeat`、`holdsReadyLease` 与建连时的 `hasActiveLeaseToken` 以布尔值表达围栏是否仍然成立。围栏失守时核心按协议错误关闭连接，不尝试自行修复路由。

`Rejected` 与 `RetryLater` 分别写入 `REGISTRATION_REJECTED` 与 `RETRY_LATER`。存储运行时异常使连接 fail closed，错误帧给出独立说明文本。[`DaemonRegistrationDirectory`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonRegistrationDirectory.java) 将 token 解析为 environmentId，反馈保持去敏。

## 调用所有权与并发

调用按 `(environmentId, invocationId)` 关联。同一 Environment 的多个 invocation 并发持有；传输队列负责有界出站，工具执行 admission 由调用层管理。`invoke` 按固定顺序复验：

1. 校验 capability descriptor 与 [`EnvironmentCapabilityCatalog`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityCatalog.java) 完全一致，并要求 `call.id` 是 canonical UUID；
2. 读取本节点 READY 连接与其 `leaseToken`，没有 READY 连接即判定环境不可用；
3. arguments 携带 `workdir` 时，按该连接 READY 时冻结的目标 OS 做词法校验；是否必填由各能力 input schema 决定；
4. 在核心锁外调用 `leaseStore.holdsReadyLease` 复核归属，存储不可用同样判定环境不可用；
5. 在连接状态锁内二次复核 READY 与围栏代次，登记 `ActiveInvocation` 并递交 INVOKE，把 [`DaemonOfferResult`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonOfferResult.java) 的三态映射为调用结果。

| 递交结果 | 映射 | 确定性 |
| --- | --- | --- |
| `ACCEPTED` | 返回执行句柄 | 帧已交给本地出站队列；不等于对端已收到 |
| `BUSY` | [`EnvironmentCapabilityBusyException`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityBusyException.java) | 帧确定未发送，连接保持可用，调用方可安全重试 |
| `CLOSED` | [`EnvironmentCapabilityUnavailableException`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilityUnavailableException.java) | 帧确定未发送，调用确定未执行 |

同一 Environment 内重复使用相同活动 `invocationId` 属于调用方错误，在发送任何帧之前即被拒绝。传输递交时抛错会关闭连接，但返回活动句柄，等待同实例恢复；不能把这条路径解释为确定未执行。恢复时，未取消的调用只向尚未发送过它的新连接代际重放 INVOKE，已取消的调用只重发 CANCEL，不再重发 INVOKE。取消意图与成功递交代际分别记录，BUSY/CLOSED 不算送达，也不靠空转轮询重试。

## 人工终端路由

[`sendShell`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServer.java) 接收 [`TerminalDispatch`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/terminal/TerminalDispatch.java)，向本节点当前 READY 连接递交 `SHELL_COMMAND`，不登记 capability 调用或持久化终端内容。

- `OPEN`、`ATTACH`、`CLAIM`、`TAKEOVER`、`RELEASE`、`INPUT`、`RESIZE`、`KEEPALIVE`、`CLOSE` 在锁外查询 `holdsReadyLease`，数据库不可用即返回 `CLOSED`。
- `DETACH` 与 `VIEW_APPLIED` 只作用当前本地 READY 连接和相同 lease，不逐包访问数据库。
- 发送锁内复核当前连接对象、generation、READY 和 lease token。换代或围栏失效不得向旧连接递交；`BUSY`、`CLOSED` 均表示未递交，不自动重试。

入站 `SHELL_EVENT` 必须与当前认证 READY 连接的 Environment、Daemon 实例匹配。核心按连接顺序向 [`EnvironmentTerminalListener`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/terminal/EnvironmentTerminalListener.java) 回调，携带认证绑定的 lease token 与 Daemon 实例；回调排队期间若发生接管，尚未开始的旧连接事件被丢弃，已开始的回调不撤销。

跨节点采用 [`ShellTopics`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/terminal/ShellTopics.java) 的两个固定、非 hint topic：

- `shell.command`：`{leaseToken,request}`，目标为 Daemon owner 节点。
- `shell.event`：`{ownerNodeId,leaseToken,daemonInstanceId,response}`，目标为 `response.route.appNodeId`。

codec 拒绝未知字段、重复键、尾随 token 与非 canonical UUID；内层只复用 `TerminalControlCodec`。元数据与 payload 不在 `toString` 或解码异常中回显。

跨节点的 App 侧入口是 [`ShellGateway`](../../web/src/main/java/fun/fengwk/kkstudio/web/events/ShellGateway.java)：它订阅上述两个 topic，先经窄端口 [`EnvironmentTerminalRouteSource`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/terminal/EnvironmentTerminalRouteSource.java) 读取当前 READY owner/lease，再发布 `TerminalDispatch` 并复用 `EnvironmentDaemonServer.sendShell`。该端口只暴露 `resolveReadyRoute(environmentId)`：按数据库现在时（`status='READY'` 且 `lease_until > statement_timestamp()`）返回 owner 节点与冻结 lease，不构成准入授权，也不写表。

`shell.event` 的 `daemonInstanceId` 是四个根字段中唯一允许显式 `null` 的字段，且只在 owner 于真正递交 Daemon 之前确定「未执行」时为 `null`（ERROR 且无终端 identity），表示本节点当前没有已认证 Daemon；字段绝不省略，其余事件必须携带真实 UUID。

## 资源上传控制面

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

同一 `transferId` 的重复同形 REQUEST 是幂等的：已绑定的票据直接复用，绝不重复创建上行；同一 transfer 漂移为不同请求、对未申请的 transfer 提交、使用与签发值不同的 uploadId、引用已释放的 transfer，都是协议违规。[`DaemonResourceTicketService`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonResourceTicketService.java) 的所有数据库与对象存储动作、票据回调与释放动作都在核心状态锁之外执行；同一 binding 自身串行化重复 reserve/commit，因此同实例重连重放不会重复创建上传行。

票据服务异常统一转换为固定的 `resource upload is unavailable` 失败回执：预签名 URL、签名 header 与存储异常原文都不进入控制面反馈。`FAILED` 票据不缓存，允许在 invocation deadline 内再次触达服务；`FAILED`/`CANCELLED`、接管、`expire` 与调用放弃都幂等释放全部未消费上传，而 `COMPLETED` 只释放未被结果引用的上传。

二进制字节不经过会话核心：核心只处理有界元数据与票据，并在任何外部 I/O 之前完成作用域、预算与绑定校验。

## 受管更新与准入

`EnvironmentDaemonServer` 额外承载专用管理通道（`UPDATE` / `UPDATE_RESULT`），它不经过 capability 目录，因此即使 Daemon 声明的
`capabilityCatalogVersion` 与本地目录不一致，连接仍可完成认证并承载版本查询与受管更新；只有普通 capability 调用在起点被拒绝。

- `beginUpdate(environmentId, operationId)`：要求当前节点 READY、无在途调用、无其它活动 operation；成功后登记该 operation，此后该
  Environment 的普通调用（含 Skill 同步）在起点 busy。同一 operationId 重复准入幂等。
- `sendUpdate(environmentId, command)`：只在同一 operation 已准入时下发 `UPDATE`；命令未进入传输时按 BUSY/UNAVAILABLE 收敛。
- `endUpdate(environmentId, operationId)`：只有与当前登记一致的 operationId 才释放准入，旧 operation 不能释放新 operation。
- `UPDATE_RESULT` 只做协议校验后转交 `EnvironmentUpdateListener` 在核心锁外消费；核心不判定最终成功。

「一个 Environment 同一时刻至多一次更新」由内存准入与 Platform 侧持久 operation 行（部分唯一索引）双重保证；更新期间对旧二进制的
任何替换都由 Daemon 与独立 OS 更新器完成，核心只负责准入、下发与回执转发。

## 终态唯一与迟到帧

`COMPLETED`/`FAILED`/`CANCELLED` 回调、实例接管与显式 `expire` 竞争时只有一个赢家；已终结 invocation 的标识由 `MAX_INVOCATION_TOMBSTONES = 1024` 的有界 tombstone 记录，超限时淘汰最旧条目。

- `expire(handle)` 由调用方在自身 deadline 上判定超时：它至多发送一次 `CANCEL`，不向 listener 补发终态，并把该 invocation 移出重放集合，之后到达的 daemon 终态被 tombstone 静默丢弃。
- 物理连接失效**不**终结在途调用：同一 `daemonInstanceId` 重连 READY 后以相同 `invocationId` 重放，Daemon journal 负责重放 `STARTED` 与终态而不重复执行副作用。
- 身份不同的新 Daemon 接管 Environment 时，旧在途调用恰好一次地以 [`EnvironmentCapabilitySendUncertainException`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilitySendUncertainException.java) 收敛为结果不确定，并释放其上传；新进程从自己的实例事实开始接受调用。旧主机副作用的停止或回滚仍需独立核查。
- 已终结 invocation 的迟到或重放 `COMPLETED`/`FAILED`/`CANCELLED` 由 tombstone 吸收并静默丢弃，`STARTED`/`PROGRESS` 同样不回调；未知 `invocationId` 的回调按协议违规关闭连接，因为回调只按 `invocationId` 归属，不按连接代际。
- `STARTED` 的可选 replayed 字段仅允许 true，表示已有 journal 记录的重放。

资源上传控制帧与终态的先后顺序由两端共同保证：Daemon 只在「调用仍活动」与「发送」的临界区内出站控制帧，核心则在终态核对前拒绝一切未就绪、伪造、重复或与申请不符的上传引用。

### 回调顺序与背压上界

`DaemonEndpoint.receive` 不要求调用方串行化同一连接的多帧。核心的保证是：状态推进在 `gate` 内按到达顺序完成，回调批次在该顺序下入队，再由单一 drainer 在锁外同步执行，因此并发 `receive` 不会让同一连接上的 `PARTIAL*` 越过其后的 terminal。批次的执行不走任何异步 executor，drain 就发生在投递该帧的调用线程上：回调耗时直接构成该调用方的背压，而不是转嫁给另一个线程池形成无界积压。

等待执行的批次以每连接固定上界 `MAX_PENDING_CALLBACK_BATCHES = 64` 约束（入站排队本身由传输适配器拥有，核心不感知其容量）。达到上界即 fail-closed：丢弃该帧、关闭连接，并把该 Environment 仍在途的调用一次性回收为 [`EnvironmentCapabilitySendUncertainException`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/capability/EnvironmentCapabilitySendUncertainException.java)，与实例接管同语义（回收回调追加在同一队列尾部，因此不会迟到于已入队的 partial）。

跨连接代际的精确边界：被接管或回收的 invocation 不再活动，因此旧连接队列里**尚未开始**的 partial 会在 drain 时复核活动性并静默丢弃，不会在新连接的 terminal 之后投递；已经进入 listener 的 partial 回调不做撤销承诺。

## 锁边界

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

## 注入端口与宿主设置

构造器只接收五个依赖：`DaemonLeaseStore`、`DaemonRegistrationDirectory`、`EnvironmentSessionListener`、`DaemonResourceTicketService` 与 `Supplier<EnvironmentServerSettings>`。

[`EnvironmentServerSettings`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentServerSettings.java)
携带心跳超时（同时用作租约期限）、资源字节上限与临时资源 TTL/扫描间隔，以 supplier 注入并在每个判定点现读，因此宿主修改配置立即
生效，核心不缓存配置；临时资源策略在同一连接的心跳通道按需推送 `TEMPORARY_RESOURCE_POLICY`，只对尚未回收的 workspace 生效。[`EnvironmentSessionListener`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentSessionListener.java)
在每次 READY 后于锁外唤醒宿主；生产组合 listener 同时唤醒 Harness Work dispatcher 与
异步 Skill Package 对账，两者失败彼此隔离，也不回滚已经成立的 READY 会话。

持久化实现（连接注册、租约、上传行与对象存储）与 WebSocket 传输实现在 `platform` 与 `web` 模块完成适配；本模块只定义窄端口语义，不感知 SQL、bucket、对象 key 或 Spring。

## 包架构

| 包路径 | 职责与边界 |
| --- | --- |
| `fun.fengwk.kkstudio.harness.environment.server` | 会话、调用、transfer 与围栏裁决；外部 I/O 通过 transport、lease、registration、ticket 端口接入，不引入产品 DTO 或框架 |
| `fun.fengwk.kkstudio.harness.environment.server.terminal` | 人工终端固定 topic、严格 codec、认证围栏元数据与监听端口；不拥有 PTY、VT 或持久化 |

## 源码与测试

主要源码入口：[`EnvironmentDaemonServer.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServer.java)、[`DaemonEndpoint.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonEndpoint.java)、[`DaemonChannel.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonChannel.java)、[`DaemonLeaseStore.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonLeaseStore.java)、[`LeaseBindResult.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/LeaseBindResult.java)、[`DaemonResourceTicketService.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonResourceTicketService.java)、[`DaemonOfferResult.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonOfferResult.java)、[`EnvironmentServerSettings.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentServerSettings.java)。

测试守卫：

- [`EnvironmentDaemonServerTest.java`](../../harness/environment-server/src/test/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServerTest.java)：以内存 fake 验证握手/租约围栏、并发调用、终态与 expire 竞争、同实例恢复/异实例接管、上传幂等/绑定校验/清理，以及回调保序、重入和积压超限失败关闭。
- [`EnvironmentDaemonServerTestSupport.java`](../../harness/environment-server/src/test/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServerTestSupport.java)：测试基座，只表达核心真正依赖的窄端口语义，不模拟 SQL 或 WebSocket。
- [`EnvironmentShellServerTest.java`](../../harness/environment-server/src/test/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentShellServerTest.java)：shell READY 准入、查询期间换代、观测命令免查库、入站身份与排队回执围栏。
- [`ShellTopicsCodecTest.java`](../../harness/environment-server/src/test/java/fun/fengwk/kkstudio/harness/environment/server/terminal/ShellTopicsCodecTest.java)：固定 topic、字段与 UUID 校验、重复键拒绝及异常去敏。
- [`EnvironmentServerModuleArchitectureTest.java`](../../harness/environment-server/src/test/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentServerModuleArchitectureTest.java)：守卫主源码 import 白名单与 POM 生产依赖白名单。

模块级覆盖率门禁为本模块 POM 中绑定到 `verify` 的 JaCoCo `check`：`EnvironmentDaemonServer` 行覆盖率不低于 `0.90`。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Environment](harness-environment.md)、[Harness Daemon](harness-daemon.md)、[Platform](platform.md)、[Web](web.md)。
