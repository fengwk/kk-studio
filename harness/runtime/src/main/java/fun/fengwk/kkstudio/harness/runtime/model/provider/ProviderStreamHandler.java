package fun.fengwk.kkstudio.harness.runtime.model.provider;

import java.util.Objects;

/** Provider 流事件接收器。完成和失败互斥且至多发生一次。 */
public interface ProviderStreamHandler {

  /** 接收一个非终止增量事件。 */
  void onEvent(ProviderStreamEvent event, ProviderStream stream);

  /** 接收完整终止响应与可选的 native replay 状态。迁移新主路径。 */
  default void onComplete(ProviderCompletion completion, ProviderStream stream) {
    Objects.requireNonNull(completion, "completion");
    onComplete(completion.response(), stream);
  }

  /** 接收完整终止响应。旧兼容路径安全默认。 */
  default void onComplete(ProviderResponse response, ProviderStream stream) {
    // 安全默认：单向适配终点，未覆盖时不产生循环调用与栈溢出。
  }

  /** 接收分类后的不可恢复终止错误。 */
  void onError(ProviderException error, ProviderStream stream);
}
