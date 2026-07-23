package fun.fengwk.kkstudio.harness.runtime.model.worker;

import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;

/** ModelExecutor 向 ModelWorker 交付的 Provider-neutral 流回调。 */
public interface ModelExecutionListener {

  /** 交付一个非 terminal Provider delta。 */
  void onDelta(ProviderStreamEvent delta);

  /** 交付完整且唯一的 terminal Provider response。 */
  void onComplete(ProviderResponse response);

  /** 交付分类后的 terminal Provider failure。 */
  void onError(ProviderException error);
}
