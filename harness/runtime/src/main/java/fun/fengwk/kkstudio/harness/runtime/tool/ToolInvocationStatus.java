package fun.fengwk.kkstudio.harness.runtime.tool;

/** ToolInvocation 持久状态机。 */
public enum ToolInvocationStatus {
  PREPARING,
  WAITING_APPROVAL,
  QUEUED,
  RUNNING,
  SUCCEEDED,
  FAILED,
  CANCEL_REQUESTED,
  CANCELLED,
  UNKNOWN;

  public boolean isTerminal() {
    return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == UNKNOWN;
  }

  public boolean isClaimable() {
    return this == QUEUED;
  }

  public boolean canTransitionTo(ToolInvocationStatus target) {
    if (target == null || target == this) {
      return false;
    }
    return switch (this) {
      case PREPARING -> target == WAITING_APPROVAL
          || target == QUEUED
          || target == FAILED
          || target == CANCEL_REQUESTED;
      case WAITING_APPROVAL -> target == QUEUED || target == FAILED || target == CANCEL_REQUESTED;
      case QUEUED -> target == RUNNING || target == CANCEL_REQUESTED;
      case RUNNING -> target == SUCCEEDED
          || target == FAILED
          || target == CANCEL_REQUESTED
          || target == UNKNOWN;
      case CANCEL_REQUESTED -> target == SUCCEEDED
          || target == FAILED
          || target == CANCELLED
          || target == UNKNOWN;
      case SUCCEEDED, FAILED, CANCELLED, UNKNOWN -> false;
    };
  }

  public void requireTransitionTo(ToolInvocationStatus target) {
    if (!canTransitionTo(target)) {
      throw new IllegalStateException(
          "invalid tool invocation transition: " + this + " -> " + target);
    }
  }
}
