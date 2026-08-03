package fun.fengwk.kkstudio.harness.runtime.invocation.model;

/** Durable business status of one Model invocation. */
public enum ModelInvocationStatus {
  READY,
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
