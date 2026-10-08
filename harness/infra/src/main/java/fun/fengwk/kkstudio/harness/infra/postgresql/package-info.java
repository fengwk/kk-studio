/**
 * 七表 Harness Runtime durable protocol 与 lossy Work wake hints 的 PostgreSQL 适配。
 *
 * <p>Store 只实现 {@link HarnessStore} typed primitives，不承载用例逻辑。事务模型具备严格的单线程约束、首个数据库故障 poisoning 防护、
 * 固定锁 rank 与批量同阶稳定排序，用于拒绝已知锁逆序并收敛数据库死锁路径。Session / Thread 的显示名称随行持久化，重命名通过 {@code updateSession} /
 * {@code updateThread} 原语更新；Session 无 version（不发布 version 提示），Thread 行更新经统一通知总线发布 version 提示。
 *
 * <p>持久化实现维护事务内 EntryPath 局部缓存，结合递增派生与按 session 驱逐，消除深度路径重复 CTE 开销。 Work claim 通过 claim-only
 * 短事务执行，以 {@code FOR UPDATE SKIP LOCKED} 实现行级跳锁，结合 Environment route 路由围栏 实施环境亲和性准入控制；Processor
 * durable mutation 先于 final Work fence 校验提交。
 *
 * <p>事务内提示只经统一通知总线发布：PostgreSQL transport 只把 LISTEN/NOTIFY 当作低延迟有损通道，periodic poll 提供调度兜底，durable
 * snapshot 始终是唯一恢复事实源。
 */
package fun.fengwk.kkstudio.harness.infra.postgresql;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
