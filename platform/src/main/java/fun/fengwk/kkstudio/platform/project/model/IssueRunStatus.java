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
}
