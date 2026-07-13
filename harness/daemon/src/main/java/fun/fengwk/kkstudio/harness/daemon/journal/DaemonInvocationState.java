package fun.fengwk.kkstudio.harness.daemon.journal;

/** Daemon 本地 invocation journal 的最小状态集。 */
public enum DaemonInvocationState {
  RUNNING,
  COMPLETED,
  FAILED,
  CANCELLED;

  /** 当前状态是否已经产生唯一终态结果。 */
  public boolean isTerminal() {
    return this != RUNNING;
  }
}
