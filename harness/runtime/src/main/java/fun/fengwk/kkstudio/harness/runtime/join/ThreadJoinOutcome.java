package fun.fengwk.kkstudio.harness.runtime.join;

/** 委派执行终态（与 wire 上的 state 属性一一对应）。 */
public enum ThreadJoinOutcome {
  /** 子执行按计划完成并给出报告。 */
  COMPLETED("completed"),

  /** 子执行失败，结果里分离 error 与可选的 partial_result。 */
  ERROR("error"),

  /** 子执行被取消（父停止或子自中止），结果里分离 error 与可选的 partial_result。 */
  CANCELLED("cancelled");

  private final String wireName;

  ThreadJoinOutcome(String wireName) {
    this.wireName = wireName;
  }

  /** 返回 wire 上的 state 属性值。 */
  public String wireName() {
    return wireName;
  }
}
