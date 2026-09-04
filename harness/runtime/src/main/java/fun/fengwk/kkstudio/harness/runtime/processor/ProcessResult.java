package fun.fengwk.kkstudio.harness.runtime.processor;

/** 一次 {@link ModelProcessor#process} 或 {@link ToolProcessor#process} 调用的最小 typed 结果。 */
public enum ProcessResult {
  /** 本地 execution 已在 Gateway 成功启动并开始运行。 */
  STARTED,

  /** 调用已处于终态，并在本次处理内完成对应 Work。 */
  TERMINATED,

  /** 本次处理未留下活跃本地 execution，Work 已按延迟重排以继续重试。 */
  RESCHEDULED,

  /** 当前处理不再持有可继续执行的 Claim，后续不得据此推进。 */
  LOST_OWNERSHIP
}
