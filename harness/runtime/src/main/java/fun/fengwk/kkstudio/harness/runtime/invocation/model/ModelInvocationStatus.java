package fun.fengwk.kkstudio.harness.runtime.invocation.model;

/** 一次 Model invocation 的 durable 业务状态。 */
public enum ModelInvocationStatus {
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
