# Harness Environment Server

## 定位

`harness-environment-server` 是 Environment Daemon 的**服务端会话核心**：它以纯 Java 实现，唯一拥有 daemon 连接代际、
路由租约推进与在途调用生命周期，并向上层暴露窄端口。Platform 只提供持久化与产品适配，Web 只提供 WebSocket 传输。

它引入 `harness-common`、`harness-environment` 与 Jackson，不依赖 Spring、JDBC、MyBatis、Servlet、WebSocket 容器或任何产品
DTO；宿主通过构造器注入全部外部能力，因此会话状态机可以在普通单元测试中完整驱动。

## 职责

### 核心职责

- 拥有 daemon 协议 v1 的服务端会话状态：HELLO 认证与 WELCOME 下发、READY 能力登记、HEARTBEAT 续约、连接代际与关闭清理。
- 以 `DaemonLeaseStore` 的围栏返回值推进租约语义：抢占/接管、READY 写入、心跳续约、断开宽限；存储不可用时 fail-closed。
- 按 `(environmentId, invocationId)` 协调在途调用：发送 `INVOKE`、透传 `STARTED/PROGRESS`、收敛唯一终态、处理 `CANCEL` 与
  超时 `expire`，并以有界 tombstone 吸收迟到回调；同一 `daemonInstanceId` 重连时以相同 `invocationId` 重放在途 INVOKE。
- 在调用作用域内绑定资源 transfer，严格处理 `RESOURCE_UPLOAD_REQUEST/TICKET/COMMIT`，并通过窄端口在核心锁外签发、完成或释放全局 Blob 上传。
- 对外提供 `DaemonEndpoint`（transport 适配器入口）与 `EnvironmentCapabilityTransport`（调用方入口）两种窄端口。

### 协作边界

- 连接注册解析与租约持久化：由 Platform 的 `EnvironmentRegistry` 等适配器实现 `DaemonRegistrationDirectory` 与
  `DaemonLeaseStore`；核心不感知 SQL 或 `harness_work`。
- Blob 上传生命周期：由 Platform 的 `StorageDaemonResourceTicketService` 实现 `DaemonResourceTicketService`；核心不感知 S3、bucket、对象 key 或上传表。
- WebSocket 传输：由 Web 的 `EnvironmentDaemonWebSocketHandler` 把物理连接适配为 `DaemonChannel`；核心不感知 Spring 或
  JSR-356。
- 产品读模型映射：Environment Card 与 live projection 由 Platform 的 `EnvironmentDaemonGateway` 与 `EnvironmentServiceImpl` 映射为平台
  DTO；skill 正文由内部工具 `load_skill` 经 `BoundEnvironment` 调用 `skill.load` 取得，Platform 不提供旁路加载链。
- 会话设置：心跳超时与资源上限由宿主每次判定现读注入，核心不缓存配置。

## 依赖边界

```text
harness-common + harness-environment + Jackson
                    │
                    ▼
harness-environment-server
  ├─ EnvironmentDaemonServer（会话/租约/调用状态机）
  ├─ DaemonEndpoint / DaemonChannel（transport 窄端口）
  ├─ DaemonLeaseStore / DaemonRegistrationDirectory（持久化窄端口）
  ├─ DaemonResourceTicketService（Blob 上传票据窄端口）
  └─ EnvironmentSessionListener / EnvironmentServerSettings（宿主回调与现读设置）
```

生产依赖单向受限于 `harness-common`、`harness-environment` 与 Jackson；`platform` 与 `web` 依赖本模块，本模块绝不反向依赖二者。
详细依赖声明见 [`pom.xml`](../../harness/environment-server/pom.xml)，依赖方向与产物清单由
[`EnvironmentServerModuleArchitectureTest.java`](../../harness/environment-server/src/test/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentServerModuleArchitectureTest.java)
自动化守卫。

## 包架构

| 包路径 | 职责与边界 |
| --- | --- |
| `fun.fengwk.kkstudio.harness.environment.server` | Environment daemon 服务端会话核心。`EnvironmentDaemonServer` 独占 HELLO/WELCOME/READY/HEARTBEAT/INVOKE/CANCEL、资源 transfer、租约围栏与按 `invocationId` 维度的在途调用状态；协议 v1 没有全局 sequence/ACK。`DaemonEndpoint`/`DaemonChannel` 承载 transport 窄端口，`DaemonLeaseStore`/`DaemonRegistrationDirectory` 承载持久化窄端口，`DaemonResourceTicketService` 承载 Blob 上传生命周期，`EnvironmentSessionListener`/`EnvironmentServerSettings` 承载宿主回调与现读设置。本包只依赖 JDK、Jackson、`harness-common` 与 `harness-environment`，不感知 Spring、持久化实现与产品 DTO。 |
## 核心模型 / API

### 会话状态机

[`EnvironmentDaemonServer`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServer.java)
是唯一拥有 daemon 会话状态的类型，同时实现两个窄端口：

```text
DaemonEndpoint                 -> open / receive / close           （transport 适配器调用）
EnvironmentCapabilityTransport -> invoke                            （产品调用方调用）
```

握手时序与协议编解码仍由 `harness-environment` 的 v1 契约定义；本模块负责把协议推进为可观察状态：

```text
open(channel)
Daemon -> HELLO(registrationToken, daemonInstanceId)
  核心 -> registrationDirectory.findByRegistrationToken
  核心 -> leaseStore.hasActiveLeaseToken（同节点活跃连接防冲突）
  核心 -> leaseStore.tryAcquire（原子抢占/接管）
  core -> WELCOME(environmentId, name)
Daemon -> READY(capabilities)  -> leaseStore.markReady（围栏失效即协议错误）
                               -> 同实例重放未在当前代际发出的 INVOKE
Daemon -> HEARTBEAT*           -> leaseStore.heartbeat（围栏失效即协议错误）
close(connectionId)            -> leaseStore.disconnect（幂等，保留重连宽限）
```

[`DaemonLeaseStore`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonLeaseStore.java)
只表达围栏返回值：[`LeaseBindResult`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/LeaseBindResult.java)
以 `Acquired`/`Rejected`/`RetryLater` 三态表达认证与抢占结论；存储不可用时实现抛运行时异常，核心 fail-closed 并以
`REGISTRATION_REJECTED` 或 `RETRY_LATER` 错误码关闭连接。

### 调用协调与并发所有权

调用只按 `(environmentId, invocationId)` 关联。同一 Environment 的多个 invocation 立即发送并**并发持有**，不存在
per-Environment 并发槽位、队列或容量配置；同一 Environment 内重复使用相同活动 `invocationId` 属于调用方错误，直接拒绝且不产生
第二次 wire 发送。

```text
invoke(environmentId, request, listener)
  -> 校验 capability descriptor 与 catalog 一致、callId 为 canonical UUID
  -> 仅对 requiresWorkdir capability 按 READY 目标 OS 词法校验 arguments.workdir
  -> 读取本节点 READY 连接与其 leaseToken
  -> leaseStore.holdsReadyLease（准入围栏，存储访问在核心锁外）
  -> 登记 ActiveInvocation 并递交 INVOKE
       ACCEPTED -> 返回执行句柄
       BUSY     -> 抛出 EnvironmentCapabilityBusyException，帧确定未发送且连接保持可用
       CLOSED   -> 抛出 EnvironmentCapabilityUnavailableException，调用确定未执行
```

发送结果由
[`DaemonOfferResult`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonOfferResult.java)
的 `ACCEPTED`/`BUSY`/`CLOSED` 三态表达，与 `EnvironmentCapabilityTransport` 的发送前异常确定性契约一一对应。“已交给传输”不等于“对端已收到”：异步发送失败只体现为连接失效，并由同实例重连以相同 `invocationId` 重放收敛。

### 资源上传控制面

每个 `ActiveInvocation` 最多维护 16 个以 `transferId` 唯一标识的 `TransferBinding`。控制流程如下：

```text
RESOURCE_UPLOAD_REQUEST
  -> invocation 必须仍活动且属于当前 READY Environment
  -> 严格解码资源元数据，并在任何存储副作用前校验当前 maxResourceBytes
  -> 首次请求在核心锁外调用 ticketService.reserve
  -> 重复同形请求重放既有 PENDING/READY；同 transfer 漂移为协议错误

RESOURCE_UPLOAD_COMMIT
  -> transfer 必须已申请，uploadId 必须等于服务端签发值
  -> 在核心锁外调用 ticketService.commit
  -> READY 固化；FAILED 不缓存，允许 invocation deadline 内再次触达服务

COMPLETED
  -> 每个 resource uploadId 必须属于本 invocation、已 READY、只引用一次
  -> mediaType/name/size/sha256 必须与 REQUEST 完全一致
  -> 保留结果实际引用的上传给 history 物化事务，释放其余上传
```

FAILED/CANCELLED、`expire`、异实例接管及调用放弃会幂等释放全部未消费上传。票据服务异常统一转换为固定的无敏感信息 FAILED，不回显预签名 URL、签名 header 或存储异常原文。`DaemonResourceTicketService` 的所有数据库/对象存储动作、延迟 listener 回调和释放动作都在核心状态锁之外执行；同一 binding 自身串行化重复 reserve/commit，既允许同实例重连重放，也不会重复创建上传行。

### 终态唯一与迟到帧

`COMPLETED`/`FAILED`/`CANCELLED` 回调、实例接管与显式 `expire` 竞争时只有一个赢家：

- 显式 [`expire(handle)`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServer.java)
  由调用方在自身 deadline 上判定超时，至多发送一次 `CANCEL`，不向 listener 补发终态，并把该 invocation 移出重放集合；
- 物理连接失效**不**终结在途调用：同一 `daemonInstanceId` 重连 READY 后以相同 `invocationId` 重放，Daemon journal 负责重放
  `STARTED`/终态而不重复执行副作用；
- 身份不同的新 Daemon 进程接管该 Environment 时，旧进程已不可能再提供终态，其全部在途调用恰好一次地以
  `EnvironmentCapabilitySendUncertainException` 收敛为结果不确定；
- 已终结 invocation 的迟到/重放 `COMPLETED`/`FAILED`/`CANCELLED` 由有界 tombstone 吸收并静默丢弃，`PROGRESS`/`STARTED` 同样不回调；
- 未知 `invocationId` 的回调按协议违规关闭连接（回调只按 `invocationId` 归属，不按连接代际）。

listener 回调一律在核心锁外执行，回调中重入核心 API 不会死锁。

### 锁边界

- 每个连接代际的 `gate` 只串行化该连接的入站协议处理；
- `state` 只保护该连接的协议字段与连接代际；
- `inventory` 保护环境/连接目录与 invocation 目录；
- 每个 `ActiveInvocation` 与 `TransferBinding` 的局部 monitor 只保护各自 transfer 集合和票据事实；
- 锁顺序固定为 `gate > state > inventory`；租约存储访问、票据服务 I/O、会话监听器回调、上传释放与连接关闭都在这些锁之外执行。

### 会话事件与现读设置

[`EnvironmentSessionListener`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentSessionListener.java)
在 READY 后于锁外回调宿主（生产装配用于唤醒 Harness Work dispatcher），实现异常不影响会话状态。
[`EnvironmentServerSettings`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentServerSettings.java)
以 supplier 注入，心跳超时与资源上限在每次判定点现读，数据库变更即时生效。

## 不变量、failure / recovery

- 同一 Environment 允许任意数量 invocation 并发在途，仅以 `invocationId` 区分；不存在环境级并发上限、容量通告或排队。
- 每次 `INVOKE` 递交前都以租约围栏复核归属；围栏失效即判定环境不可用且不发送 wire。
- 租约存储不可用时 HELLO、READY、HEARTBEAT 与 INVOKE 全部 fail-closed。
- 物理连接失效不终结在途调用；只有身份不同的 Daemon 进程接管或调用方 `expire` 才收敛为不确定，绝不重发可能已产生副作用的请求。
- 连接关闭、同 connectionId 新代际接管、环境被新 HELLO 抢占都幂等清理旧代际；在途调用交给同实例重连重放。
- 资源 transfer 只属于一个活动 invocation；REQUEST 漂移、伪造 COMMIT、未 READY/重复/跨调用 upload 引用均为协议违规，绝不交给消费方。
- 二进制字节不经过会话核心；核心只处理有界元数据和票据，并在任何外部 I/O 前完成作用域、预算与绑定校验。
- 核心不持久化任何状态：进程重启后的会话事实由 daemon 重新握手建立。

## 测试与源码入口

### 源码入口

- [`EnvironmentDaemonServer.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServer.java)、[`DaemonEndpoint.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonEndpoint.java)、[`DaemonChannel.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonChannel.java)
- [`DaemonLeaseStore.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonLeaseStore.java)、[`LeaseBindResult.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/LeaseBindResult.java)、[`DaemonRegistrationDirectory.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonRegistrationDirectory.java)
- [`DaemonResourceTicketService.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonResourceTicketService.java)、[`EnvironmentSessionListener.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentSessionListener.java)、[`EnvironmentServerSettings.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentServerSettings.java)、[`DaemonOfferResult.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonOfferResult.java)

### 关键测试守卫

- [`EnvironmentServerModuleArchitectureTest.java`](../../harness/environment-server/src/test/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentServerModuleArchitectureTest.java)：验证主源码只依赖 JDK、Jackson、`harness-common` 与 `harness-environment`，且 POM 生产依赖白名单之外没有新增声明。
- [`EnvironmentDaemonServerTest.java`](../../harness/environment-server/src/test/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServerTest.java)：以内存 fake channel/lease store/ticket service 驱动完整状态机，覆盖同环境并发调用、终态唯一、超时 `expire`、队列 BUSY 拒绝、同实例重连重放、异实例接管、连接代际接管、上传申请/提交幂等与伪造/漂移拒绝、终态引用核对/清理、握手校验与租约围栏 fail-closed。
- [`EnvironmentDaemonServerTestSupport.java`](../../harness/environment-server/src/test/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServerTestSupport.java)：测试基座，只表达核心真正依赖的窄端口语义，不模拟 SQL 或 WebSocket。

模块级覆盖率门禁为本模块 POM 中绑定到 `verify` 的 JaCoCo `check`：`EnvironmentDaemonServer` 行覆盖率必须不低于 `0.90`。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Environment](harness-environment.md)、[Harness Daemon](harness-daemon.md)、[Platform](platform.md)、[Web](web.md)。
