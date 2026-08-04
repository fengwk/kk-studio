package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

/** Durable business status of one Tool invocation. */
public enum ToolInvocationStatus {
  WAITING_APPROVAL,
  READY,
  DISPATCHING,
  RUNNING,
  SUCCEEDED,
  FAILED,
  CANCELLED,
  UNKNOWN;

  /** Whether the status is a terminal state. */
  public boolean isTerminal() {
    return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == UNKNOWN;
  }
}
