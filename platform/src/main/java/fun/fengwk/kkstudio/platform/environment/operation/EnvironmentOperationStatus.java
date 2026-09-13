package fun.fengwk.kkstudio.platform.environment.operation;

/** environment_operation 生命周期状态。 */
public enum EnvironmentOperationStatus {
  PENDING,
  RUNNING,
  SUCCEEDED,
  FAILED,
  UNKNOWN,
  CANCELLED;

  /** 是否为终态（终态行不可变且不可覆写）。 */
  public boolean isTerminal() {
    return this == SUCCEEDED || this == FAILED || this == UNKNOWN || this == CANCELLED;
  }
}
