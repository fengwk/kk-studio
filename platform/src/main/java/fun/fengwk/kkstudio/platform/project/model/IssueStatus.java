package fun.fengwk.kkstudio.platform.project.model;

/**
 * Issue 七态业务状态。
 *
 * <p>{@code BLOCKED} 表示自动推进已停止、等待人处理；它不同于“TODO 的依赖未满足”，也不同于某个 Run 的 WAITING_HUMAN/FAILED/UNKNOWN。
 */
public enum IssueStatus {
  BACKLOG,
  TODO,
  IN_PROGRESS,
  IN_REVIEW,
  BLOCKED,
  DONE,
  CANCELED;

  public boolean isTerminal() {
    return this == DONE || this == CANCELED;
  }

  /** 只有终态 Issue 允许归档；BLOCKED 必须先处理。 */
  public boolean canBeArchived() {
    return isTerminal();
  }

  /** 允许由授权动作取消的非终态阶段。 */
  public boolean isCancelable() {
    return this == BACKLOG
        || this == TODO
        || this == IN_PROGRESS
        || this == IN_REVIEW
        || this == BLOCKED;
  }

  /** 自动派发只发生在 TODO；BLOCKED 绝不参与自动派发。 */
  public boolean isDispatchable() {
    return this == TODO;
  }
}
