package fun.fengwk.kkstudio.project.domain;

/**
 * Issue Run 的运行状态。
 *
 * <p>RUNNING 与 WAITING 是活动状态：同一 Issue 只有一个活动主 Run。WAITING 表示已到安全点、停止活动计时并等待 门禁或
 * Invocation；COMPLETED/FAILED/CANCELLED/UNKNOWN 是终态，必须记录结束区间与时间。 停止、取消都不是业务完成，UNKNOWN
 * 未核查也不能当作普通失败清理。
 */
public enum IssueRunStatus {
  /** 正在执行。 */
  RUNNING,

  /** 已安全暂停，等待门禁、问答或审批。 */
  WAITING,

  /** 正常收尾并提交冻结的报告与区间。 */
  COMPLETED,

  /** 执行失败并记录原因。 */
  FAILED,

  /** 被显式停止或取消。 */
  CANCELLED,

  /** 在途副作用不明，必须人工核查后才能清理。 */
  UNKNOWN;

  /** 是否为活动状态（RUNNING/WAITING）。 */
  public boolean isActive() {
    return this == RUNNING || this == WAITING;
  }

  /** 是否为终态。 */
  public boolean isTerminal() {
    return !isActive();
  }
}
