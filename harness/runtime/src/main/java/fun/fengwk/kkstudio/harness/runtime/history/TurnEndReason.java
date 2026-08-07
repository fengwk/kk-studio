package fun.fengwk.kkstudio.harness.runtime.history;

/** 关闭 Model response turn 的 durable reason。 */
public enum TurnEndReason {
  /** 用户显式 Stop。 */
  USER_STOP,
  /** history normalization 截断。 */
  HISTORY_CUT,
  /** branch-local 取消（如 Stop 后无安全内容时的取消 barrier）。 */
  CANCELLED,
  /** TurnResolver 或 ModelInvocation 确定失败。 */
  TURN_FAILED
}
