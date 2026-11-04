package fun.fengwk.kkstudio.harness.api.history;

/** 关闭 response turn 的 durable reason。 */
public enum TurnEndReason {
  /** 用户显式 Stop。 */
  USER_STOP,
  /** history normalization 截断。 */
  HISTORY_CUT,
  /** branch-local 取消（如 Stop 后无安全内容时的取消 barrier）。 */
  CANCELLED,
  /** TurnResolver 或 Driver generation 确定失败。 */
  TURN_FAILED,
  /** 生成被 max tokens 截断（LENGTH 无 calls 的 failed turn）。 */
  OUTPUT_TRUNCATED,
  /** 生成内容被 Provider 过滤（FILTERED 的 failed turn）。 */
  CONTENT_FILTERED,
  /** 已提交完整公开结果后，外部 execution/scope/owner 失败；保留结果且不伪造第二个 AssistantError。 */
  EXECUTION_FAILED
}
