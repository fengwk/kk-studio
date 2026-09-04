package fun.fengwk.kkstudio.harness.runtime.thread.command;

/** 一条 durable Thread command 的派生生命周期状态。 */
public enum ThreadCommandState {
  /** 命令已持久化入队，等待被后续 Turn 消费。 */
  QUEUED,

  /** 命令已由某个 Turn 消费并记录其归属。 */
  APPLIED,

  /** 命令已随分叉或回退被标记取消。 */
  CANCELLED;

  /** 该 command 是否已到达 terminal 状态。 */
  public boolean isTerminal() {
    return this == APPLIED || this == CANCELLED;
  }
}
