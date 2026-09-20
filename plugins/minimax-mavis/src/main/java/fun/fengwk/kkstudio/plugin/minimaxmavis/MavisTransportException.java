package fun.fengwk.kkstudio.plugin.minimaxmavis;

/**
 * 传输或 HTTP 层失败。
 *
 * <p>消息只包含请求上下文、HTTP 状态码与去敏后的服务端摘要，绝不包含请求 URL、header 或 body 原文。
 */
public class MavisTransportException extends MavisException {

  public MavisTransportException(String message) {
    super(message);
  }

  public MavisTransportException(String message, Throwable cause) {
    super(message, cause);
  }
}
