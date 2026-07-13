package fun.fengwk.kkstudio.harness.agent;

import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;

/** Agent Turn 的流式事件接收器。完成和失败互斥且至多发生一次。 */
public interface AgentTurnEventHandler {

  /** 接收 Provider 的非终止增量。 */
  void onProviderEvent(ProviderStreamEvent event);

  /** 接收经完整性校验后的 Turn 结果。 */
  void onComplete(AgentTurnResult result);

  /** 接收 Provider 或 Turn 校验失败。 */
  void onError(ProviderException error);
}
