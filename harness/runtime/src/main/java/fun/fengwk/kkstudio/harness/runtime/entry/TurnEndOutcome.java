package fun.fengwk.kkstudio.harness.runtime.entry;

/** 已关闭 Model response turn 的 durable outcome。 */
public enum TurnEndOutcome {
  /** Turn 已按计划完成并持久化关闭。 */
  COMPLETED,

  /** Turn 因不可恢复的错误异常结束。 */
  FAILED,

  /** Turn 收到停止请求并有序终止。 */
  STOPPED,

  /** Turn 被显式取消或因历史截断作废。 */
  CANCELLED
}
