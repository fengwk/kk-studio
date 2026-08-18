package fun.fengwk.kkstudio.harness.runtime.processor;

/**
 * 一次 {@link ThreadProcessor#process} 调用的最小 typed 结果。
 *
 * <p>COMPLETED 表示本 claim 已消费：一个 durable action 已在该调用内完成，claim 的 THREAD Work 已完成（无新 wake 时删除行，
 * 有同事务请求的新 wake 时清除 lease 并保留行），后续 action 一律由同事务 {@code requestWork} 驱动；RESCHEDULED 表示临时失败
 * （Resolver 异常 / null / heartbeat 调度失败）已按延迟重排 THREAD Work；LOST_OWNERSHIP 表示 claim 已 lost / stale 或
 * 提交 CAS 失败，未做任何部分 mutation。dispatcher 不解释本结果。
 */
public enum ThreadProcessResult {
  COMPLETED,
  RESCHEDULED,
  LOST_OWNERSHIP
}
