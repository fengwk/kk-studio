package fun.fengwk.kkstudio.harness.provider.transport;

import java.util.Objects;

/**
 * 结构化的 Server-Sent Event。
 *
 * <p>遵循 WHATWG SSE 规范，包含可选的 {@code event} 类型和已解析的 {@code data} 内容。
 */
public record ServerSentEvent(String event, String data) {

  public ServerSentEvent {
    Objects.requireNonNull(data, "data must not be null");
  }

  @Override
  public String toString() {
    return "ServerSentEvent{event="
        + (event == null ? "null" : "'" + event + "'")
        + ", dataLength="
        + data.length()
        + "}";
  }
}
