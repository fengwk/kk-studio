package fun.fengwk.kkstudio.harness.provider;

import fun.fengwk.kkstudio.harness.provider.transport.TransportErrorKind;
import fun.fengwk.kkstudio.harness.provider.transport.TransportException;

/**
 * Provider 错误消息格式化通用工具（供各协议子包复用）。
 *
 * <p>按客户端所见即所得契约构建 HTTP 状态错误消息：
 *
 * <ul>
 *   <li>直接保留原始响应正文，格式为 {@code HTTP <status>\n<bodyUtf8>}。
 *   <li>响应体缺失时，回退为 {@code HTTP <status>: <fallback>}（状态码大于 0 时）或纯 {@code <fallback>}。
 *   <li>若底层发生错误正文截断，明确追加截断标记 {@value #TRUNCATION_MARKER}。
 * </ul>
 *
 * <p>SSE 错误消息由各映射器直接使用解析后的 JSON 节点字符串，因此本类不提供 SSE 辅助方法。
 */
public final class ProviderErrorHelper {

  public static final String TRUNCATION_MARKER = " [TRUNCATED]";

  private ProviderErrorHelper() {}

  /** 判断是否属于无需向上抛出异常的静默传输错误类型。 */
  public static boolean isSilentTransportKind(TransportErrorKind kind) {
    return kind == TransportErrorKind.CANCELLED
        || kind == TransportErrorKind.EXECUTOR_REJECTED
        || kind == TransportErrorKind.CALLBACK_FAILED;
  }

  /**
   * 格式化 HTTP 状态码与响应体为错误消息。
   *
   * @param statusCode HTTP 状态码
   * @param bodyUtf8 UTF-8 响应正文
   * @param isTruncated 是否被传输层截断
   * @param fallback 正文缺失时的后备消息
   * @return 格式化后的错误消息
   */
  public static String formatHttpErrorMessage(
      int statusCode, String bodyUtf8, boolean isTruncated, String fallback) {
    if (bodyUtf8 == null || bodyUtf8.isEmpty()) {
      return statusCode > 0 ? "HTTP " + statusCode + ": " + fallback : fallback;
    }
    String message = "HTTP " + statusCode + "\n" + bodyUtf8;
    if (isTruncated) {
      return message + TRUNCATION_MARKER;
    }
    return message;
  }

  /**
   * 基于 TransportException 格式化 HTTP 状态错误消息。
   *
   * @param exception 传输异常
   * @param fallback 正文缺失时的后备消息
   * @return 格式化后的错误消息
   */
  public static String formatHttpErrorMessage(TransportException exception, String fallback) {
    if (exception == null) {
      return fallback;
    }
    return formatHttpErrorMessage(
        exception.statusCode(),
        exception.errorBodyUtf8(),
        exception.isErrorBodyTruncated(),
        fallback);
  }
}
