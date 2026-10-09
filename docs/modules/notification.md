# Notification

整体分层见[系统设计](../system-design.md)。

[`notification`](../../notification/pom.xml) 提供可独立装配的通知运行时。它只依赖 Share 的纯通知契约包（[`fun.fengwk.kkstudio.share.notification`](../../share/src/main/java/fun/fengwk/kkstudio/share/notification/)）、
Spring JDBC/transaction、PG driver 和日志 API，不依赖领域模块。领域模块定义固定 topic 与严格不可变 payload codec；
调用方必须保证 payload 不可变。该模块不会自动发现 topic 或注册 Spring Bean。

## 发布与交付

发布唯一入口为 [`NotificationBus.publish(topic, address, payload)`](../../share/src/main/java/fun/fengwk/kkstudio/share/notification/NotificationBus.java)。
[`DefaultNotificationBus`](../../notification/src/main/java/fun/fengwk/kkstudio/notification/DefaultNotificationBus.java)
构造时接收进程 UUID、DataSource、固定 topic 集合和资源预算。装配顺序不承担正确性：`start()` 先完成 LISTEN 再安排订阅者权威对账，而每次 `subscribe` 自身也会异步排入一次权威恢复标记（与建连对账走同一条队列路径，并发到达时折叠为一次），因此订阅早于或晚于 LISTEN 建连两种情况都不会留下无人对账的启动窗口。
目标为同进程（local-only）时不执行任何通知 SQL；目标为其他进程时只投递远端；广播投递本地与其他节点。
本地与 PG 入站都进入同一个 Inbox。订阅者拥有独立的串行执行线程，不在发布线程、PG reader 或
`afterCommit` 线程执行领域回调。订阅拥有 `ACTIVE`、`FAILED`、`CLOSED` 三种状态，失败是终态且可见，
直接影响总线健康检查（[`DefaultNotificationBus.healthy()`](../../notification/src/main/java/fun/fengwk/kkstudio/notification/DefaultNotificationBus.java)）。失效类 hint 的实体字段允许为空：`EntityHint(null)` 表示来源事实已不存在，消费方必须退化为全局权威回读，而不是丢弃这条提示。

总线只识别其自身 DataSource 绑定的 Spring 物理事务。事务内的跨节点通知在同一物理连接、同一事务内投递；
SQL 失败记录事务 poisoning 并向上抛出，即使调用者捕获异常事务也只能回滚，绝不提交脏状态。本地投递只在实际
`afterCommit` 入队，回滚不投递。无事务发布由有界发送队列处理远端、本地立即入队。
同事务内完全相同的 hint 按 topic、目标和编码字节 payload 折叠（保留 PG 对相同 channel/payload 的折叠效果）；
`hint=false` 的 realtime 等普通事件不折叠。无事务发送队列按批次接受：一批 transient outbox 的接受不是整批原子，部分 payload 可能因溢出被拒绝并请求恢复，其余仍被接受。

PG reader 在解析 publisher UUID 后无条件直接丢弃自身回声，不解码分片内容、不分配重组空间。
本地投递失败不会改从 PG 回声消费。通知不承诺持久化、离线重放或跨发布者全局顺序，
不能替代领域 claim、CAS、租约或权威查询。

## 资源与恢复

[`NotificationLimits`](../../share/src/main/java/fun/fengwk/kkstudio/share/notification/NotificationLimits.java)
是唯一的限额来源，集中限制逻辑消息、事务及发送待处理字节、队列、订阅者、重组并发与截止时间。Inbox 字节预算
涵盖所有订阅者正在执行和排队的普通消息。每个订阅者的恢复标记拥有独立控制槽：
普通队列溢出时清空待处理普通消息，优先回读权威状态；恢复回调失败后订阅状态进入 `FAILED` 终态，
不继续处理失效投影。关闭释放订阅、队列与连接，状态置为 `CLOSED`。

[`PgTransport`](../../notification/src/main/java/fun/fengwk/kkstudio/notification/PgTransport.java)
每进程只使用一个 LISTEN 连接，监听 `kk_notification` 和 UUID 派生的节点 inbox。
连接建立与重建先完成 LISTEN，再安排订阅者权威对账。发送接受不表示远端业务已执行。
监听连接按 `notificationPollMillis` 执行主动 JDBC 存活检查，检查超时取同一间隔向上取整的秒数（至少 1 秒）；
被动通知读取不能单独检测空闲 TCP 黑洞。检查失败先标记不健康并请求全量对账，再按已有重连节奏重新 LISTEN。
临时发送失败不自动重试；本地恢复请求和连接健康状态用于暴露不可用。

所有远端消息均使用一种 carrier，包含协议版本、publisher、target、topic、messageId、
index/count 和逻辑总字节数。`count=1` 也遵守同一校验。carrier 文本使用严格 UTF-8：编码拒绝 lone surrogate，解码拒绝坏字节与截断序列，绝不静默替换成 replacement character 后再参与 canonical 比对。最终 UTF-8 carrier 小于 7900 字节；
数据按字节分片，仅在完整重组后调用领域 codec。重复片必须一致，矛盾、缺片、超时与资源溢出
丢弃整包并请求恢复。瞬态发送按有界分片批次轮转，大消息不能独占发送队列。
分片与重组是 Share 的公共纯 JDK 原语 [`NotificationCarrier` / `NotificationReassembler`](../../share/src/main/java/fun/fengwk/kkstudio/share/notification/)，
PG 与两条 WS 通道直接复用同一算法，不复制第二套实现；公共 carrier/packet 深不可变、错误只描述字段或规则且不回显输入。

默认预算由 `NotificationLimits.defaults()` 提供：单逻辑消息 8 MiB、发送/Inbox 各 32 MiB、重组 32 MiB、8 条并发重组及 5 秒截止；
生产负载的最终预算仍需依据基准结果锁定。PG NOTIFY 队列有容量上限，可使用临时磁盘；
数据库参数日志应关闭，运行时日志不输出 payload 或领域异常消息；codec 与绑定清理抛出的异常只说明结构与位置，不回显 payload 内容。

## 验证入口

[`NotificationPostgresqlIntegrationTest`](../../notification/src/test/java/fun/fengwk/kkstudio/notification/NotificationPostgresqlIntegrationTest.java)
使用隔离 Testcontainers PG 验证物理提交/回滚、SQL poisoning、同源回声、双节点与重连。
分片与重组的正负单测随公共原语放在 Share（[`NotificationCarrierTest` / `NotificationReassemblerTest`](../../share/src/test/java/fun/fengwk/kkstudio/share/notification/)），
notification 侧单测覆盖 Inbox 的顺序、溢出和恢复状态。

```bash
env JAVA_HOME=$JAVA_HOME_21 mvn -B -ntp -pl notification -am test
```

JaCoCo 实测报告位于 `notification/target/site/jacoco/`，公共分片/重组原语的报告位于
`share/target/site/jacoco/`。这个命令不安装共享 Maven 制品，不需要运行全仓验证。
