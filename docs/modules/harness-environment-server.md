# Harness Environment Server

## 定位

`harness-environment-server` 是 Environment Daemon 的**服务端会话核心**：它以纯 Java 实现，唯一拥有 daemon 连接代际、
路由租约推进与在途调用生命周期，并向上层暴露窄端口。Platform 只提供持久化与产品适配，Web 只提供 WebSocket 传输。

它引入 `harness-common`、`harness-environment` 与 Jackson，不依赖 Spring、JDBC、MyBatis、Servlet、WebSocket 容器或任何产品
DTO；宿主通过构造器注入全部外部能力，因此会话状态机可以在普通单元测试中完整驱动。

## 职责

### 核心职责

- 拥有 daemon 协议 v1 的服务端会话状态：HELLO 认证与 WELCOME 下发、READY 能力登记、HEARTBEAT 续约、入站/出站 sequence、
  连接代际与关闭清理。
- 以 `DaemonLeaseStore` 的围栏返回值推进租约语义：抢占/接管、READY 写入、心跳续约、断开宽限；存储不可用时 fail-closed。
- 按 `(environmentId, invocationId)` 协调在途调用：发送 `INVOKE`、透传 `STARTED/PARTIAL`、收敛唯一终态、处理 `CANCEL` 与
  超时 `expire`，并以有界 tombstone 吸收迟到回调。
- 对外提供 `DaemonEndpoint`（transport 适配器入口）与 `EnvironmentCapabilityTransport`（调用方入口）两种窄端口。

### 协作边界

- 连接注册解析与租约持久化：由 Platform 的 `EnvironmentRegistry` 等适配器实现 `DaemonRegistrationDirectory` 与
  `DaemonLeaseStore`；核心不感知 SQL 或 `harness_work`。
- WebSocket 传输：由 Web 的 `EnvironmentDaemonWebSocketHandler` 把物理连接适配为 `DaemonChannel`；核心不感知 Spring 或
  JSR-356。
- 产品读模型映射：skill 正文与目录浏览结果由 Platform 的 `EnvironmentDaemonGateway` 与 `LocalDirectoryExecutor` 映射为平台
  DTO。
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
  └─ EnvironmentSessionListener / EnvironmentServerSettings（宿主回调与现读设置）
```

生产依赖单向受限于 `harness-common`、`harness-environment` 与 Jackson；`platform` 与 `web` 依赖本模块，本模块绝不反向依赖二者。
详细依赖声明见 [`pom.xml`](../../harness/environment-server/pom.xml)，依赖方向与产物清单由
[`EnvironmentServerModuleArchitectureTest.java`](../../harness/environment-server/src/test/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentServerModuleArchitectureTest.java)
自动化守卫。

## 包架构

| 包路径 | 职责与边界 |
| --- | --- |
| `fun.fengwk.kkstudio.harness.environment.server` | Environment daemon 服务端会话核心。`EnvironmentDaemonServer` 独占 HELLO/WELCOME/READY/HEARTBEAT/INVOKE/CANCEL 等协议会话状态、序列号、租约围栏与按 `invocationId` 维度的在途调用；`DaemonEndpoint`/`DaemonChannel` 承载 transport 窄端口，`DaemonLeaseStore`/`DaemonRegistrationDirectory` 承载持久化窄端口，`EnvironmentSessionListener`/`EnvironmentServerSettings` 承载宿主回调与现读设置。本包只依赖 JDK、Jackson、`harness-common` 与 `harness-environment`，不感知 Spring、持久化实现与产品 DTO。 |
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
Daemon -> HELLO(registrationToken)
  核心 -> registrationDirectory.findByRegistrationToken
  核心 -> leaseStore.hasActiveLeaseToken（同节点活跃连接防冲突）
  核心 -> leaseStore.tryAcquire（原子抢占/接管）
  core -> WELCOME(environmentId, name)
Daemon -> READY(capabilities)  -> leaseStore.markReady（围栏失效即协议错误）
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
invoke(binding, request, listener)
  -> 校验 capability descriptor 与 catalog 一致、workdir 为 null、callId 为 canonical UUID
  -> 读取本节点 READY 连接与其 leaseToken
  -> leaseStore.holdsReadyLease（准入围栏，存储访问在核心锁外）
  -> 登记 ActiveInvocation 并发送 INVOKE
       SENT      -> 返回执行句柄
       NOT_SENT  -> 抛出 EnvironmentCapabilityUnavailableException，调用确定未执行
       UNCERTAIN -> 关闭连接并抛出 EnvironmentCapabilitySendUncertainException
```

发送结果由
[`DaemonSendOutcome`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonSendOutcome.java)
的 `SENT`/`NOT_SENT`/`UNCERTAIN` 三态表达，与 `EnvironmentCapabilityTransport` 的发送前异常确定性契约一一对应。

### 终态唯一与迟到帧

`COMPLETED`/`FAILED`/`CANCELLED` 回调、连接清理与显式 `expire` 竞争时只有一个赢家：

- 显式 [`expire(handle)`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServer.java)
  由调用方在自身 deadline 上判定超时，至多发送一次 `CANCEL`，不向 listener 补发终态；
- 连接清理把全部未完成调用以 `EnvironmentCapabilitySendUncertainException` 收敛为结果不确定（副作用无法确认）；
- 已终结 invocation 的迟到 `COMPLETED`/`FAILED`/`CANCELLED` 由有界 tombstone 吸收并静默丢弃，`PARTIAL`/`STARTED` 同样不回调；
- 未知 `invocationId` 或错误连接代际的回调按协议违规关闭连接；
- 连接在准入与写入之间失效时抛 `EnvironmentCapabilityUnavailableException`（确定未执行），写入结果不可确认时关闭连接。

listener 回调一律在核心锁外执行，回调中重入核心 API 不会死锁。

### 锁边界

- 每个连接代际的 `gate` 只串行化该连接的入站协议处理；
- `state` 只保护该连接的协议字段与出站 sequence；
- `inventory` 保护连接目录与 invocation 目录；
- 锁顺序固定为 `gate > state > inventory`；租约存储访问、会话监听器回调与连接关闭都在这些锁之外执行。

### 会话事件与现读设置

[`EnvironmentSessionListener`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentSessionListener.java)
在 READY 后于锁外回调宿主（生产装配用于唤醒 Harness Work dispatcher），实现异常不影响会话状态。
[`EnvironmentServerSettings`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentServerSettings.java)
以 supplier 注入，心跳超时与资源上限在每次判定点现读，数据库变更即时生效。

## 不变量、failure / recovery

- 同一 Environment 允许任意数量 invocation 并发在途，仅以 `invocationId` 区分；不存在环境级并发上限、容量通告或排队。
- 每次 `INVOKE` 发送前都以租约围栏复核归属；围栏失效即判定环境不可用且不发送 wire。
- 租约存储不可用时 HELLO、READY、HEARTBEAT 与 INVOKE 全部 fail-closed。
- 发送结果不确定时关闭连接并按 `UNCERTAIN` 收敛在途调用；绝不重发可能已产生副作用的请求。
- 连接关闭、同 connectionId 新代际接管、环境被新 HELLO 抢占都幂等清理旧代际，并把未完成调用收敛为不确定。
- 核心不持久化任何状态：进程重启后的会话事实由 daemon 重新握手建立。

## 测试与源码入口

### 源码入口

- [`EnvironmentDaemonServer.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServer.java)、[`DaemonEndpoint.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonEndpoint.java)、[`DaemonChannel.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonChannel.java)
- [`DaemonLeaseStore.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonLeaseStore.java)、[`LeaseBindResult.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/LeaseBindResult.java)、[`DaemonRegistrationDirectory.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonRegistrationDirectory.java)
- [`EnvironmentSessionListener.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentSessionListener.java)、[`EnvironmentServerSettings.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentServerSettings.java)、[`DaemonSendOutcome.java`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonSendOutcome.java)

### 关键测试守卫

- [`EnvironmentServerModuleArchitectureTest.java`](../../harness/environment-server/src/test/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentServerModuleArchitectureTest.java)：验证主源码只依赖 JDK、Jackson、`harness-common` 与 `harness-environment`，且 POM 生产依赖白名单之外没有新增声明。
- [`EnvironmentDaemonServerTest.java`](../../harness/environment-server/src/test/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServerTest.java)：以内存 fake channel/lease store 驱动完整状态机，覆盖同环境并发调用、终态唯一、超时 `expire`、连接代际接管、握手校验、sequence 冲突与租约围栏 fail-closed。
- [`EnvironmentDaemonServerTestSupport.java`](../../harness/environment-server/src/test/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServerTestSupport.java)：测试基座，只表达核心真正依赖的窄端口语义，不模拟 SQL 或 WebSocket。

模块级覆盖率门禁为本模块 POM 中绑定到 `verify` 的 JaCoCo `check`：`EnvironmentDaemonServer` 行覆盖率必须不低于 `0.90`。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Environment](harness-environment.md)、[Harness Daemon](harness-daemon.md)、[Platform](platform.md)、[Web](web.md)。
