package fun.fengwk.kkstudio.harness.runtime.thread.command;

/** 一条 durable Thread command 的派生生命周期状态。 */
public enum ThreadCommandState {
  QUEUED,
  APPLIED,
  CANCELLED;

  /** 该 command 是否已到达 terminal 状态。 */
  public boolean isTerminal() {
    return this == APPLIED || this == CANCELLED;
  }
}
