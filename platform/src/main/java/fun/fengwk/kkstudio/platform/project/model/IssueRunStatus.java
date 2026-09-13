package fun.fengwk.kkstudio.platform.project.model;

public enum IssueRunStatus {
  RUNNING,
  WAITING_HUMAN,
  COMPLETED,
  FAILED,
  CANCELLED,
  UNKNOWN;

  public boolean isActive() {
    return this == RUNNING || this == WAITING_HUMAN;
  }

  public boolean isTerminal() {
    return this == COMPLETED || this == FAILED || this == CANCELLED || this == UNKNOWN;
  }

  public boolean canTransitionTo(IssueRunStatus next) {
    if (next == null) {
      return false;
    }
    if (isTerminal()) {
      return false;
    }
    if (this == RUNNING) {
      return next == WAITING_HUMAN || next.isTerminal();
    }
    if (this == WAITING_HUMAN) {
      return next == RUNNING || next == CANCELLED || next == FAILED || next == UNKNOWN;
    }
    return false;
  }
}
