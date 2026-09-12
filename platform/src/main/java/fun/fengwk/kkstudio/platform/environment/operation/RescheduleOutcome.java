package fun.fengwk.kkstudio.platform.environment.operation;

/** definitely-not-sent 状态转换结果。 */
public enum RescheduleOutcome {
  /** 截止时间仍未到达，已重置为 PENDING 并清空认领信息。 */
  RESCHEDULED,
  /** 截止时间已到达，已终结为 FAILED (ENVIRONMENT_UNAVAILABLE_TIMEOUT)。 */
  FAILED_TIMEOUT,
  /** 当前行已不是匹配的 RUNNING 认领状态（可能已被抢占或已终结）。 */
  STALE
}
