package fun.fengwk.kkstudio.harness.runtime.model.provider;

/** Provider 流事件接收器。完成和失败互斥且至多发生一次。 */
public interface ProviderStreamHandler {

  /** 接收一个非终止增量事件。 */
  void onEvent(ProviderStreamEvent event, ProviderStream stream);

  /** 接收完整终止响应与可选的 native replay 状态。 */
  default void onComplete(ProviderCompletion completion, ProviderStream stream) {
    onComplete(completion.response(), stream);
  }

  /** 接收完整终止响应。 */
  default void onComplete(ProviderResponse response, ProviderStream stream) {
    onComplete(new ProviderCompletion(response, null), stream);
  }

  /** 接收分类后的不可恢复终止错误。 */
  void onError(ProviderException error, ProviderStream stream);
}
