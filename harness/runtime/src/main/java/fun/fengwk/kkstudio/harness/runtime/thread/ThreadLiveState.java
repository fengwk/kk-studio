package fun.fengwk.kkstudio.harness.runtime.thread;

/** Thread 当前 live context 的中立分类，不携带 Driver/Model 私有状态。 */
public enum ThreadLiveState {
  IDLE_OR_HISTORICAL,
  CONTINUATION_DUE,
  DRIVER_ACTIVE,
  DRIVER_RESPONSE_PENDING,
  TOOL_ACTIVE,
  TOOL_TERMINAL_PENDING;

  public boolean isLive() {
    return this != IDLE_OR_HISTORICAL;
  }
}
