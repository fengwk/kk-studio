package fun.fengwk.kkstudio.harness.runtime.model.provider;

import java.util.Objects;

/** Provider 流事件接收器。完成和失败互斥且至多发生一次。 */
public interface ProviderStreamHandler {

  /** 接收一个非终止增量事件。 */
  void onEvent(ProviderStreamEvent event, ProviderStream stream);

  /**
   * 接收一条厂商原生协议事件。默认安全忽略。
   *
   * <p>原生事件是未规范化的 attempt-only 事实，因此与 {@link ProviderStreamEvent} 增量并列但独立：它不进入 durable
   * checkpoint、realtime normalized delta，也不改变 text/thinking/tool 增量的语义。显式实现者可据此无损接收官方协议细节。
   */
  default void onProtocolEvent(ProviderProtocolEvent event, ProviderStream stream) {
    // 安全默认：只需规范化增量的实现者无需改动。
  }

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
