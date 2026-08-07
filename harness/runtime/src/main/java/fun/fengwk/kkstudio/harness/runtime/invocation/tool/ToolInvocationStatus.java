package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

/** 一次 Tool invocation 的 durable 业务状态。 */
public enum ToolInvocationStatus {
  WAITING_APPROVAL,
  READY,
  DISPATCHING,
  RUNNING,
  SUCCEEDED,
  FAILED,
  CANCELLED,
  UNKNOWN;

  /** 该状态是否为 terminal 状态。 */
  public boolean isTerminal() {
    return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == UNKNOWN;
  }
}
