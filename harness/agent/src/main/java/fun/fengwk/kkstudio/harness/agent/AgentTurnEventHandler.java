package fun.fengwk.kkstudio.harness.agent;

import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;

/** Agent Turn 的生命周期事件接收器。完成和失败互斥且至多发生一次。 */
public interface AgentTurnEventHandler {

  /** Turn 已开始，随后才会收到增量或终态事件。 */
  default void onStarted() {}

  /** 接收已规范化的非终止增量。 */
  default void onDelta(ProviderStreamEvent event) {}

  /** 接收经完整性校验后的 Turn 结果。 */
  default void onCompleted(AgentTurnResult result) {}

  /** 接收 Provider、聚合或工具调用校验失败。 */
  default void onFailed(ProviderException error) {}
}
