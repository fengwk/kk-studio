package fun.fengwk.kkstudio.platform.environment.operation;

/** 过期操作清扫统计结果。 */
public record DeadlineSweepResult(int expiredPendingCount, int expiredRunningCount) {

  /** 总清扫行数。 */
  public int totalSwept() {
    return expiredPendingCount + expiredRunningCount;
  }
}
