/**
 * Durable Work：全部后台调度共用的 PostgreSQL mailbox、唯一 SQL 实现与有界调度骨架。
 *
 * <p>{@link fun.fengwk.kkstudio.work.DurableWorkStore} 每个 (targetType, targetId) 至多维护一行。所有调度跃迁由
 * {@link fun.fengwk.kkstudio.common.work.WorkState} 计算；Store 只在该行的数据库锁与调用方物理事务中读写状态。请求、完成与重排会把
 * {@code durable_work} 提示与状态写入同一事务，提示只降低延迟，丢失时由各 dispatcher 的周期轮询恢复。
 *
 * <p><b>事务边界：</b>生产构造绑定 Spring JDBC，{@link fun.fengwk.kkstudio.work.DurableWorkJdbc#inTransaction}
 * 加入调用方已有事务， 没有外层事务时才开启短事务，绝不使用 {@code REQUIRES_NEW}。Harness 通过自己的事务核心实现该钩子，使 SQL 与业务事实共享同一物理连接并保留
 * JDBC 首错 poisoning；Canvas / Project 的业务写入与 mailbox 变更由各自事务边界保证原子性。{@link
 * fun.fengwk.kkstudio.work.DurableWorkStore#lock} 与 {@link
 * fun.fengwk.kkstudio.work.DurableWorkStore#lockClaimed} 要求调用方已有活动事务。
 *
 * <p>due、lease 有效性与动态截止时间一律以数据库 {@code statement_timestamp()} 为权威；调用方只提供相对 {@link
 * java.time.Duration}，多节点时钟偏差不会提前抢占别人的租约。
 */
package fun.fengwk.kkstudio.work;
