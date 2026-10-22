package fun.fengwk.kkstudio.ide.protocol;

import java.util.List;
import java.util.Objects;

/**
 * 反向数据面的文本控制帧（JSON）。
 *
 * <p>三种形态与实施契约一致：HTTP 响应头（{@code HTTP_RESPONSE}）只在 body 之前出现一次；WebSocket 模式成功为 {@code WS_READY}；失败为
 * {@code ERROR}（固定安全文案）。所有形态都隐含「本 exchange/WS 的控制阶段已就绪」。
 */
public sealed interface IdeStreamControlFrame {

  /** HTTP 响应头：状态码 + 已过滤 hop-by-hop 的 header 列表（保序、允许多值）。 */
  record HttpResponse(int status, List<IdeHeader> headers) implements IdeStreamControlFrame {
    public HttpResponse {
      if (status < 100 || status > 599) {
        throw new IllegalArgumentException("HTTP_RESPONSE status must be a valid HTTP status code");
      }
      headers = headers == null ? List.of() : List.copyOf(headers);
      for (IdeHeader header : headers) {
        Objects.requireNonNull(header, "header");
      }
    }
  }

  /** WebSocket 升级成功：之后进入 byte4/5 分段转发。 */
  record WsReady() implements IdeStreamControlFrame {}

  /** 失败信号：只携带固定安全文案，绝不回显上游异常原文。 */
  record Error(String message) implements IdeStreamControlFrame {
    public Error {
      Objects.requireNonNull(message, "message");
      if (message.isBlank() || IdeHeader.containsControlCharacters(message)) {
        throw new IllegalArgumentException("error message must be non-blank printable text");
      }
    }
  }
}
