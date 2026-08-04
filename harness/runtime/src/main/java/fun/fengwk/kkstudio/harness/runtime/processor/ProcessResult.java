package fun.fengwk.kkstudio.harness.runtime.processor;

/**
 * 一次 {@link ModelProcessor#process} 调用的最小 typed 结果。
 *
 * <p>每个结果对应 claim 处理后的确定 durable 状态：STARTED 表示本地 execution 正在运行；TERMINATED 表示已在本进程收敛 为终态（admission
 * reject / indeterminate、lease 恢复或 terminal cleanup）；RESCHEDULED 表示 admission 肯定 未开始、Work
 * 已按延迟重排（attempt 不变）；LOST_OWNERSHIP 表示 claim 已 lost / stale，未做任何 mutation。
 */
public enum ProcessResult {
  STARTED,
  TERMINATED,
  RESCHEDULED,
  LOST_OWNERSHIP
}
