# Notification

整体分层见[系统设计](../system-design.md)。

[`notification`](../../notification/pom.xml) 提供可独立装配的通知运行时。它只依赖 Share 的通知契约、
Spring JDBC/transaction、PG driver 和日志 API，不依赖领域模块。领域定义固定 topic 与严格 codec；
调用方必须保证 payload 不可变。该模块不会自动发现 topic 或注册 Spring Bean。

## 发布与交付

[`DefaultNotificationBus`](../../notification/src/main/java/fun/fengwk/kkstudio/notification/DefaultNotificationBus.java)
构造时接收进程 UUID、DataSource、固定 topic 集合和资源预算。组合根应先绑定订阅者，再启动传输。
目标为同进程时不执行通知 SQL；目标为其他进程时只投递远端；广播投递本地与其他节点。
本地与 PG 入站都进入同一个 Inbox。订阅者拥有独立的串行执行线程，不在发布、PG reader 或
`afterCommit` 线程执行领域回调。

Bus 只识别其自身 DataSource 绑定的 Spring 物理事务。事务内的远端通知在同连接、同事务执行；
SQL 失败传播并标记事务回滚，即使调用者捕获异常也不能提交。本地只在实际 `afterCommit` 入队。
无事务发布由有界发送队列处理远端、本地立即入队。同事务完全相同的 hint 按 topic、目标和
编码内容合并，保留 PG 对相同 channel/payload 的折叠效果；普通事件不合并。

PG reader 在解析 publisher UUID 后直接丢弃自身回声，不解码片内容、不分配重组空间。
本地投递失败不会改从 PG 回声消费。通知不承诺持久化、离线重放或跨发布者全局顺序，
不能替代领域 claim、CAS、租约或权威查询。

## 资源与恢复

[`NotificationLimits`](../../notification/src/main/java/fun/fengwk/kkstudio/notification/NotificationLimits.java)
集中限制逻辑消息、事务及发送待处理字节、队列、订阅者、重组并发与截止时间。Inbox 字节预算
涵盖所有订阅者正在执行和排队的普通消息。每个订阅者的恢复标记拥有独立控制槽：
普通队列溢出时清空待处理普通消息，优先回读权威状态；恢复回调失败后订阅状态为 `FAILED`，
不继续处理失效投影。关闭释放订阅、队列与连接，对不能及时停止的消费者明确报告失败。

[`PgTransport`](../../notification/src/main/java/fun/fengwk/kkstudio/notification/PgTransport.java)
每进程只使用一个 LISTEN 连接，监听 `kk_notification` 和 UUID 派生的节点 inbox。
连接重建先完成 LISTEN，再安排订阅者权威对账。发送接受不表示远端业务已执行。
临时发送失败不自动重试；本地恢复请求和连接健康状态用于暴露不可用。

所有远端消息均使用一种 carrier，包含协议版本、publisher、target、topic、messageId、
index/count 和逻辑总字节数。`count=1` 也遵守同一校验。最终 UTF-8 carrier 小于 7900 字节；
数据按字节分片，仅在完整重组后调用领域 codec。重复片必须一致，矛盾、缺片、超时与资源溢出
丢弃整包并请求恢复。瞬态发送按有界分片批次轮转，大消息不能独占发送队列。

默认预算为单消息 8 MiB、发送/Inbox 各 32 MiB、重组 32 MiB、8 条并发重组及 5 秒截止；
生产负载的最终预算仍需依据基准结果锁定。PG NOTIFY 队列有容量上限，可使用临时磁盘；
数据库参数日志应关闭，运行时日志不输出 payload 或领域异常消息。

## 验证入口

[`NotificationPostgresqlIntegrationTest`](../../notification/src/test/java/fun/fengwk/kkstudio/notification/NotificationPostgresqlIntegrationTest.java)
使用隔离 Testcontainers PG 验证物理提交/回滚、SQL poisoning、同源回声、双节点与重连。
单测覆盖 UTF-8 carrier、重组与 Inbox 的顺序、溢出和恢复状态。

```bash
env JAVA_HOME=$JAVA_HOME_21 mvn -pl notification -am test \
  -Dtest=CarrierTest,ReassemblerTest,LocalInboxTest,NotificationPostgresqlIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

JaCoCo 实测报告位于 `notification/target/site/jacoco/`。这个命令不安装共享 Maven 制品，
不需要运行全仓验证。
