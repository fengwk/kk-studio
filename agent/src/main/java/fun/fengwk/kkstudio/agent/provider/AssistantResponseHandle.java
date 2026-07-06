package fun.fengwk.kkstudio.agent.provider;

/**
 * AssistantResponseHandle 表示一次 assistant 流的取消句柄。
 *
 * @author fengwk
 */
public interface AssistantResponseHandle {

  /** 请求取消当前 assistant 流。 */
  void cancel();

  /** 返回当前流是否已进入取消状态。 */
  boolean isCancelled();
}
