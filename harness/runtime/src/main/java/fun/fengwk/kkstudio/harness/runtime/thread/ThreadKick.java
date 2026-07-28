package fun.fengwk.kkstudio.harness.runtime.thread;

/**
 * 请求本 JVM 最终收敛指定 Thread durable facts 的激活端口。
 *
 * <p>调用不代表已取得 Thread ownership，也不代表 reconcile 已完成；实现可以合并、延迟或拒绝重复 kick。当前 Core 由 recovery scanner 根据
 * PostgreSQL 中的 runnable/lease 事实调用本端口；事务生产者在 durable mutation 提交后通过 ActivationNotifier 发布 wake
 * hint。Recovery 扫描补偿丢失的 hint。
 */
@FunctionalInterface
public interface ThreadKick {
  void kick(long threadId);
}
