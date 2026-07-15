package fun.fengwk.kkstudio.harness.runtime.control;

/** 控制消息在数据库中的持久化生命周期。 */
public enum RunControlStatus {
  /** 已被接受、等待消费。 */
  PENDING,
  /** 已被某次 Turn 消费并写入 Session Entry；终态。 */
  CONSUMED,
  /** 已被显式清除；终态。 */
  CLEARED,
  /** FOLLOW_UP 在没有 active Run 时直接提升为下一次 Run 的起点；终态。 */
  PROMOTED
}