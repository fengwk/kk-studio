package fun.fengwk.kkstudio.harness.runtime.execution;

/**
 * 执行代次的单向退出证据。
 *
 * <p>未确认、Studio body 与本地资源已退出、远端正向 ACK、以及确定未启动，四者只向前推进。删除事实行不是退出。
 */
public enum ExecutionExitState {
  /** 已登记，但还没有正向退出证据。 */
  UNCONFIRMED,
  /** Studio 执行体及其本地资源的 finally 已完成。不能证明远端进程停止。 */
  STUDIO_EXITED,
  /** 远端执行方对这一具体代次给出了正向 ACK。 */
  REMOTE_ACKNOWLEDGED,
  /** 在发送或进入 body 之前已确定不会启动。 */
  NOT_STARTED;

  /** 终态证据不可被后续确认改写。 */
  public boolean confirmed() {
    return this != UNCONFIRMED;
  }
}
