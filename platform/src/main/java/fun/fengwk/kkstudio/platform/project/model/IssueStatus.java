package fun.fengwk.kkstudio.platform.project.model;

public enum IssueStatus {
  BACKLOG,
  TODO,
  IN_PROGRESS,
  IN_REVIEW,
  DONE,
  CANCELED;

  public boolean isTerminal() {
    return this == DONE || this == CANCELED;
  }

  public boolean canBeArchived() {
    return isTerminal();
  }
}
