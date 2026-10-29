package fun.fengwk.kkstudio.harness.runtime.driver;

/** Driver 当前 generation 的中立生命周期状态。 */
public enum GenerationStatus {
  /** 已创建，等待首次尝试。 */
  READY,

  /** 请求已派发，等待响应。 */
  RUNNING,

  /** 上一次尝试失败后等待重试。 */
  WAITING_RETRY,

  /** 已成功结束。 */
  SUCCEEDED,

  /** 已因明确错误失败。 */
  FAILED,

  /** 已主动取消。 */
  CANCELLED,

  /** 结果无法确定或执行被安全阻断。 */
  BLOCKED
}
