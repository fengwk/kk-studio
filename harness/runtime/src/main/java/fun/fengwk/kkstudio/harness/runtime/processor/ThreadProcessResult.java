package fun.fengwk.kkstudio.harness.runtime.processor;

/**
 * 一次 {@link ThreadProcessor#process} 调用的最小 typed 结果。
 *
 * <p>SUSPENDED 表示 Thread 已推进到一个正在运行的 Turn（终端应用后存在在途 Model/Tool invocation，或新 Turn 已 resolve 并 派发
 * MODEL Work），claim 已完成；QUIESCENT 表示当前没有任何可执行动作，claim 已完成；RESCHEDULED 表示临时失败（Resolver 异常 / null /
 * heartbeat 调度失败或 step limit）已按延迟重排 THREAD Work；LOST_OWNERSHIP 表示 claim 已 lost / stale 或提交 CAS
 * 失败，未做任何部分 mutation。
 */
public enum ThreadProcessResult {
  SUSPENDED,
  QUIESCENT,
  RESCHEDULED,
  LOST_OWNERSHIP
}
