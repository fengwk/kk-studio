package fun.fengwk.kkstudio.harness.runtime.model.provider;

import java.util.Objects;

/**
 * Provider 流中的一条厂商原生协议事件。
 *
 * <p>与 {@link ProviderStreamEvent} 的规范化增量不同，本类型承载未经规范化的原始协议事实：{@code eventType} 是厂商给出的事件名，{@code
 * data} 是 transport 交给 adapter 的原始事件数据，不做 JSON 反序列化后重写。它属于当前 attempt 的只读观测通道：未知但合法的官方事件、encrypted
 * reasoning 等 attempt-only 内容都经此保留，绝不进入 durable checkpoint、realtime normalized delta 或
 * text/thinking/tool 增量。
 *
 * <p>{@code eventType} 必须非空白，{@code data} 必须非 null。{@link #toString()} 只输出事件名，原生 payload 不进入日志。
 */
public record ProviderProtocolEvent(String eventType, String data) {

  public ProviderProtocolEvent {
    if (eventType == null || eventType.isBlank()) {
      throw new IllegalArgumentException("eventType must not be blank");
    }
    Objects.requireNonNull(data, "data");
  }

  @Override
  public String toString() {
    return "ProviderProtocolEvent[eventType=" + eventType + ", data=<redacted>]";
  }
}
