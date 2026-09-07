package fun.fengwk.kkstudio.harness.provider.transport;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Transport 层安全异常。
 *
 * <p>安全契约：
 *
 * <ul>
 *   <li>对象 {@link #toString()}、{@link #getMessage()} 与原因异常链中严格禁止包含 request URI、 Authorization/API
 *       Key、请求 body 或远端错误 body。
 *   <li>错误 body 字节数组仅供协议层通过 {@link #errorBodyBytes()} 读取，且执行防御性拷贝。
 *   <li>响应头仅保留过滤掉认证/凭据等敏感字段后的安全视图。
 * </ul>
 */
public final class TransportException extends RuntimeException {

  private static final byte[] EMPTY_BYTES = new byte[0];
  private static final Set<String> SENSITIVE_HEADERS =
      Set.of(
          "authorization",
          "proxy-authorization",
          "cookie",
          "set-cookie",
          "x-api-key",
          "api-key",
          "token",
          "x-auth-token");

  private final TransportErrorKind kind;
  private final int statusCode;
  private final byte[] errorBodyBytes;
  private final Map<String, List<String>> safeHeaders;

  public TransportException(
      TransportErrorKind kind,
      String safeMessage,
      int statusCode,
      byte[] errorBodyBytes,
      Map<String, List<String>> headers,
      Throwable cause) {
    super(sanitizeMessage(safeMessage), sanitizeCause(cause));
    this.kind = Objects.requireNonNull(kind, "kind must not be null");
    this.statusCode = statusCode;
    this.errorBodyBytes = errorBodyBytes == null ? EMPTY_BYTES : errorBodyBytes.clone();
    this.safeHeaders = sanitizeHeaders(headers);
  }

  public TransportException(TransportErrorKind kind, String safeMessage) {
    this(kind, safeMessage, 0, null, null, null);
  }

  public TransportException(TransportErrorKind kind, String safeMessage, Throwable cause) {
    this(kind, safeMessage, 0, null, null, cause);
  }

  public TransportException(
      TransportErrorKind kind,
      String safeMessage,
      int statusCode,
      byte[] errorBodyBytes,
      Map<String, List<String>> headers) {
    this(kind, safeMessage, statusCode, errorBodyBytes, headers, null);
  }

  public TransportErrorKind kind() {
    return kind;
  }

  public int statusCode() {
    return statusCode;
  }

  /**
   * 返回截断后的错误响应正文字节数组副本（防御性拷贝）。
   *
   * <p>该内容绝对不会出现在 {@link #toString()} 或 {@link #getMessage()} 中。
   */
  public byte[] errorBodyBytes() {
    return errorBodyBytes.clone();
  }

  /** 返回清洗后的安全响应头映射。 */
  public Map<String, List<String>> safeHeaders() {
    return safeHeaders;
  }

  @Override
  public String toString() {
    String msg = getMessage();
    return "TransportException[kind="
        + kind
        + (statusCode != 0 ? ", statusCode=" + statusCode : "")
        + (msg != null ? ", message=" + msg : "")
        + "]";
  }

  private static String sanitizeMessage(String message) {
    if (message == null) {
      return null;
    }
    // 移除可能存在的 URI 参数或格式化控制符
    return message.replace('\r', ' ').replace('\n', ' ');
  }

  private static Throwable sanitizeCause(Throwable cause) {
    if (cause == null) {
      return null;
    }
    if (cause instanceof TransportException) {
      return cause;
    }
    String safeMsg = cause.getClass().getSimpleName();
    return new SafeCauseException(safeMsg, sanitizeCause(cause.getCause()));
  }

  private static Map<String, List<String>> sanitizeHeaders(Map<String, List<String>> headers) {
    if (headers == null || headers.isEmpty()) {
      return Map.of();
    }
    Map<String, List<String>> clean = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    headers.forEach(
        (name, values) -> {
          if (name != null && !SENSITIVE_HEADERS.contains(name.toLowerCase())) {
            clean.put(name, values == null ? List.of() : List.copyOf(values));
          }
        });
    return Collections.unmodifiableMap(clean);
  }

  private static final class SafeCauseException extends RuntimeException {
    SafeCauseException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
