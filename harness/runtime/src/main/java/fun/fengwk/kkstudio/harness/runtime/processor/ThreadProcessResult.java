package fun.fengwk.kkstudio.harness.runtime.processor;

/** 一次 {@link ThreadProcessor#process} 调用的最小 typed 结果。 */
public enum ThreadProcessResult {
  /** 当前 Claim 的 THREAD Work 已消费完成，后续动作交由同事务请求驱动。 */
  COMPLETED,

  /** 遇到临时失败，THREAD Work 已按延迟安全重排。 */
  RESCHEDULED,

  /** Claim 已失效、所有权丢失或提交围栏未通过，规划结果未被提交。 */
  LOST_OWNERSHIP
}
