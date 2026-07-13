package fun.fengwk.kkstudio.harness.agent;

/** Agent Turn 的取消控制。 */
public interface AgentTurnHandle {

  /** 请求取消当前 Turn；调用必须幂等。 */
  void cancel();

  /** 当前 Turn 是否已经收到取消请求。 */
  boolean isCancelled();
}
