package fun.fengwk.kkstudio.harness.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.provider.ProviderErrorHelper;
import fun.fengwk.kkstudio.harness.provider.transport.TransportErrorKind;
import fun.fengwk.kkstudio.harness.provider.transport.TransportException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;

import java.util.Locale;

/**
 * Anthropic 异常映射器。
 *
 * <p>读取 HTTP 状态码与 error envelope 进行确定性分类，直接向外保留上游原始 HTTP 响应正文与完整 SSE error envelope， 绝不附加 request
 * URI、请求标头、请求凭证或原始底层异常原因。
 */
final class AnthropicErrorMapper {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  static final String MSG_AUTH = "Anthropic authentication failed";
  static final String MSG_BILLING = "Anthropic billing or quota error";
  static final String MSG_OVERFLOW = "Anthropic request context overflow";
  static final String MSG_TRANSIENT = "Anthropic transient failure";
  static final String MSG_INVALID_REQUEST = "Anthropic invalid request";
  static final String MSG_INVALID_RESPONSE = "Anthropic invalid response";

  private AnthropicErrorMapper() {}

  /**
   * 将传输层异常转换为 {@link ProviderException}。
   *
   * @param exception 传输层异常
   * @return 映射后的 ProviderException；若为 CANCELLED、EXECUTOR_REJECTED 或 CALLBACK_FAILED 则返回 null
   *     表示静默并保留 RUNNING 状态
   */
  static ProviderException mapTransportException(TransportException exception) {
    if (exception == null) {
      return new ProviderException(ProviderErrorKind.TRANSIENT, MSG_TRANSIENT);
    }
    if (ProviderErrorHelper.isSilentTransportKind(exception.kind())) {
      return null;
    }
    if (exception.kind() == TransportErrorKind.TIMEOUT) {
      return new ProviderException(ProviderErrorKind.TRANSIENT, "Anthropic request timed out");
    }
    if (exception.kind() == TransportErrorKind.IO) {
      return new ProviderException(ProviderErrorKind.TRANSIENT, "Anthropic I/O error");
    }
    if (exception.kind() == TransportErrorKind.INVALID_RESPONSE) {
      return new ProviderException(ProviderErrorKind.INVALID_RESPONSE, MSG_INVALID_RESPONSE);
    }
    if (exception.kind() == TransportErrorKind.HTTP_STATUS) {
      int status = exception.statusCode();
      String errorType = extractErrorType(exception.errorBodyBytes());
      ProviderErrorKind kind = classify(status, errorType);
      String fallback = fallbackForKind(kind);
      String message = ProviderErrorHelper.formatHttpErrorMessage(exception, fallback);
      return new ProviderException(kind, message);
    }
    return new ProviderException(ProviderErrorKind.TRANSIENT, MSG_TRANSIENT);
  }

  /** 将 SSE 接收到的 error 事件 envelope 进行映射。 */
  static ProviderException mapSseErrorEnvelope(JsonNode errorEventNode) {
    if (errorEventNode == null) {
      return new ProviderException(ProviderErrorKind.INVALID_RESPONSE, MSG_INVALID_RESPONSE);
    }
    if (!errorEventNode.isObject()) {
      return new ProviderException(ProviderErrorKind.INVALID_RESPONSE, errorEventNode.toString());
    }
    String errorType = extractErrorType(errorEventNode);
    ProviderErrorKind kind = classify(0, errorType);
    return new ProviderException(kind, errorEventNode.toString());
  }

  private static ProviderErrorKind classify(int status, String errorType) {
    String typeLower = errorType != null ? errorType.toLowerCase(Locale.ROOT) : "";

    if (typeLower.contains("auth")
        || typeLower.contains("permission")
        || status == 401
        || status == 403) {
      return ProviderErrorKind.AUTHENTICATION;
    }
    if (typeLower.contains("billing") || typeLower.contains("credit") || status == 402) {
      return ProviderErrorKind.BILLING;
    }
    if (typeLower.contains("context_length_exceeded")
        || typeLower.contains("model_context_window_exceeded")
        || (typeLower.contains("context") && typeLower.contains("overflow"))) {
      return ProviderErrorKind.OVERFLOW;
    }
    if (typeLower.contains("request_too_large") || status == 413) {
      return ProviderErrorKind.INVALID_REQUEST;
    }
    if (typeLower.contains("overloaded")
        || typeLower.contains("rate_limit")
        || typeLower.contains("api_error")
        || status == 429
        || (status >= 500 && status <= 599)) {
      return ProviderErrorKind.TRANSIENT;
    }
    if (status >= 400 && status < 500) {
      return ProviderErrorKind.INVALID_REQUEST;
    }
    if (status >= 500) {
      return ProviderErrorKind.TRANSIENT;
    }
    return ProviderErrorKind.INVALID_RESPONSE;
  }

  private static String fallbackForKind(ProviderErrorKind kind) {
    return switch (kind) {
      case AUTHENTICATION -> MSG_AUTH;
      case BILLING -> MSG_BILLING;
      case OVERFLOW -> MSG_OVERFLOW;
      case INVALID_REQUEST -> MSG_INVALID_REQUEST;
      case TRANSIENT -> MSG_TRANSIENT;
      case INVALID_RESPONSE, CANCELLED -> MSG_INVALID_RESPONSE;
    };
  }

  private static String extractErrorType(byte[] bodyBytes) {
    if (bodyBytes == null || bodyBytes.length == 0) {
      return null;
    }
    try {
      JsonNode node = OBJECT_MAPPER.readTree(bodyBytes);
      return extractErrorType(node);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String extractErrorType(JsonNode node) {
    if (node == null || !node.isObject()) {
      return null;
    }
    if (node.has("error") && node.get("error").isObject() && node.get("error").has("type")) {
      return node.get("error").get("type").asText();
    }
    if (node.has("type") && node.get("type").isTextual()) {
      return node.get("type").asText();
    }
    return null;
  }
}
