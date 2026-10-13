package fun.fengwk.kkstudio.harness.daemon;

import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelopeCodec;

/**
 * Daemon 失败正文的唯一构造点。
 *
 * <p>只转发调用方给定的本地失败说明与异常类型信息：缺失消息收敛为固定规则、引用按 JSON 转义，绝不把远端正文、协议载荷或凭据拼进正文。
 * 连接会话与调用执行共用同一构造，避免各自实现出不一致的转义或回显边界。
 */
final class DaemonFailurePayload {

  /** 无可用失败消息时的固定规则，不含任何输入取值。 */
  private static final String FALLBACK_MESSAGE = "capability execution failed";

  private static final DaemonEnvelopeCodec CODEC = new DaemonEnvelopeCodec();

  private DaemonFailurePayload() {}

  /** 构造 {@code FAILED}/{@code ERROR} 的正文：{@code {"message": "<safe>"}}。 */
  static String error(Throwable error) {
    return "{\"message\":" + quote(safeMessage(error)) + "}";
  }

  /** 可安全对外说明的失败文本：空消息收敛为固定规则，读取消息本身失败时同样收敛。 */
  static String safeMessage(Throwable error) {
    if (error == null) {
      return FALLBACK_MESSAGE;
    }
    try {
      String message = error.getMessage();
      return message == null || message.isBlank() ? FALLBACK_MESSAGE : message;
    } catch (RuntimeException ignored) {
      return FALLBACK_MESSAGE;
    }
  }

  /** 按 JSON 字符串规则转义；只用于本类自己的安全文本，不承载外部取值。 */
  private static String quote(String value) {
    ObjectNode payload = CODEC.createPayload();
    payload.put("value", value);
    return CODEC.writeJson(payload.get("value"));
  }
}
