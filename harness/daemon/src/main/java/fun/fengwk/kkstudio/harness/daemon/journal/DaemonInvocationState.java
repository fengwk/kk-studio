package fun.fengwk.kkstudio.harness.daemon.journal;

/** Daemon 本地 invocation journal 的最小状态集。 */
public enum DaemonInvocationState {
  /** 本地调用正在执行中。 */
  RUNNING,

  /** 本地调用已成功完成并写入结果。 */
  COMPLETED,

  /** 本地调用执行失败并记录错误。 */
  FAILED,

  /** 本地调用已被主动取消。 */
  CANCELLED;

  /** 当前状态是否已经产生唯一终态结果。 */
  public boolean isTerminal() {
    return this != RUNNING;
  }
}
