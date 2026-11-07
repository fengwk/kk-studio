# Durable Work

本文属于[系统设计](../system-design.md)的模块层，面向修改后台调度的开发者。

`work` 模块提供全部后台调度共用的 PostgreSQL mailbox。Harness 的 THREAD / TOOL、Builtin Driver 的 MODEL、Project 的 ISSUE 与 Canvas 的 CANVAS_RUN 都是 `durable_work` 表中的一种 `target_type`；每个 `(target_type, target_id)` 至多一行，表示「这个 target 需要被重新处理一次」。类型由所属模块定义，名称以大写字母开头，后接大写字母、数字或下划线，长度不超过 16；数据库通过通用格式校验 `target_type`，各调用方负责约束自身的调度语义与环境亲和性。行里只有调度事实（due、wake 次数、租约），业务状态始终留在各自的领域表里，Worker 领取后必须重新读取当前业务状态再决定做什么。

## 心智模型

```text
request(target, delay)   -> 新行 wake_version=1；已有行 wake_version+1、due 取更早值、保留当前租约
claimNext(token, lease)  -> FOR UPDATE SKIP LOCKED 选「已到期且无有效租约」的行，签发租约
renew(claim, lease)      -> 有效租约延长到至少「此刻 + lease」
complete(claim)          -> wake_version 未变：删除行；处理期间来了新 wake：释放租约并立即到期
reschedule(claim, delay) -> 释放租约，下一次处理推迟 delay；有新 wake 时保留更早的 due
```

- **时间只有一个权威**：due、租约签发与有效性判断都取数据库 `statement_timestamp()` 并截断到毫秒，调用方只传相对 `Duration`。多节点时钟偏差不会让某个节点提前抢占别人的租约。
- **wake 不会被吞掉**：`wake_version` 记录请求次数。Worker 完成时只删除与自己领取时版本一致的行；处理期间到达的新请求让行留下并立即可再领取。
- **租约围栏调度跃迁**：续租、完成与重排都以调用方的 lease token 和数据库时间下仍有效的租约为条件；token 不匹配或租约过期时被拒绝（`IllegalArgumentException`，行已不存在时 `IllegalStateException`），由新 Worker 接手。业务事实的写回也必须在所属事务内复核执行权。

## 唤醒

请求、完成时保留行、重排与显式释放都会在同一事务内，当行已到期（`available_at <= clock_timestamp()`）时向通道 `durable_work` 发送 payload 为 target type 的 `NOTIFY`；仍持有租约时到达的新 wake 同样发送，不被租约吞掉。组合根只为这类唤醒监听这一个通道，按 payload 唤醒负责该类型的 dispatcher；连接断开重连后，组合根主动唤醒全部类型的 dispatcher 触发补读。通知只降低延迟，通知丢失由各 dispatcher 的周期轮询兜底恢复。

## 使用方

- [`DurableWorkStore`](../../work/src/main/java/fun/fengwk/kkstudio/work/DurableWorkStore.java) 是 `durable_work` 的唯一 SQL 实现，每个实例绑定一种 target type。跃迁先由 [`WorkState`](../../common/src/main/java/fun/fengwk/kkstudio/common/work/WorkState.java) 计算，本类只在调用方物理事务中读写行并发送提示。Project 注册 `issueWorkQueue`（`ISSUE`），Canvas 注册 `canvasRunWorkQueue`（`CANVAS_RUN`），各自的 dispatcher 复用 [`BoundedWorkDispatcher`](../../work/src/main/java/fun/fengwk/kkstudio/work/BoundedWorkDispatcher.java) 做有界 handoff。
- Harness 的 Work 嵌在 Runtime 的强类型事务里，需要锁序校验与环境亲和性，因此由 [Platform](platform.md#work-的-claim--lease--wake) 用同一 `DurableWorkStore` 读写同一张表，SQL 与事务状态由 Harness 提供的 JDBC 钩子承载。
- 请求、完成与重排在调用方已有事务时加入，无外层事务时自开短事务，保证事实与提示在同一事务内原子提交。
- `lockClaimed(claim)` 仅允许在调用方的活动事务中调用，否则抛出 `IllegalStateException`；匹配 token 且租约有效时锁住 Work 行直到事务结束并返回该行，不匹配或过期返回空且不产生锁。调用方先锁业务行，再锁 Work，避免反转锁序；此方法不自行开启事务。Issue 调度归还租约时先在短事务中确认 `lockClaimed` 仍持有，再 `reschedule`。
- [`WorkDispatcherLifecycle`](../../work/src/main/java/fun/fengwk/kkstudio/work/WorkDispatcherLifecycle.java) 是 Canvas 与 Issue 共用的 Spring 生命周期：按 `enabled` 决定是否启动，start 失败回滚，stop 失败保持 running；`BoundedWorkDispatcher` 保持为纯调度组件由其受控托管。

## 测试

[`DurableWorkStoreIntegrationTest`](../../work/src/test/java/fun/fengwk/kkstudio/work/DurableWorkStoreIntegrationTest.java) 在真实 PostgreSQL 上覆盖 wake 合并、按 due 领取、租约过期接管与旧租约失权、完成与重排的版本语义、环境路由围栏，以及到期写入发送提示而完成删除不发送、回滚同时撤销行与提示。
