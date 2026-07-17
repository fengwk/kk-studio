package fun.fengwk.kkstudio.harness.agent;

/** Agent Turn 的取消控制与终态可见性。 */
public interface AgentTurnHandle {

  /** 请求取消当前 Turn；调用必须幂等。 */
  void cancel();

  /** 当前 Turn 是否已经收到取消请求。 */
  boolean isCancelled();

  /**
   * 当前 Turn 是否已完成终态回调交付（成功完成、失败或取消）。
   *
   * <p>只有终态 handler 返回后才为 {@code true}；生命周期轮询可据此安全释放 active slot，而无需等待心跳 lease 失败。
   */
  boolean isDone();
}
