package fun.fengwk.kkstudio.harness.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.provider.transport.TransportErrorKind;
import fun.fengwk.kkstudio.harness.provider.transport.TransportException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;

import java.util.Locale;

/**
 * Anthropic 异常映射与脱敏器。
 *
 * <p>只读取 HTTP 状态码与 error envelope 中的 type 字段进行确定性分类， 绝不向外暴露原始 URL、API 密钥、响应 body、底层异常原因或敏感数据。
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
   * 将传输层异常转换为脱敏的 {@link ProviderException}。
   *
   * @param exception 传输层异常
   * @return 映射后的 ProviderException；若为 CANCELLED、EXECUTOR_REJECTED 或 CALLBACK_FAILED 则返回 null
   *     表示静默并保留 RUNNING 状态
   */
  static ProviderException mapTransportException(TransportException exception) {
    if (exception == null) {
      return new ProviderException(ProviderErrorKind.TRANSIENT, MSG_TRANSIENT);
    }
    if (exception.kind() == TransportErrorKind.CANCELLED
        || exception.kind() == TransportErrorKind.EXECUTOR_REJECTED
        || exception.kind() == TransportErrorKind.CALLBACK_FAILED) {
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
      return mapHttpStatus(status, errorType);
    }
    return new ProviderException(ProviderErrorKind.TRANSIENT, MSG_TRANSIENT);
  }

  /** 将 SSE 接收到的 error 事件 envelope 进行脱敏映射。 */
  static ProviderException mapSseErrorEnvelope(JsonNode errorEventNode) {
    if (errorEventNode == null || !errorEventNode.isObject()) {
      return new ProviderException(ProviderErrorKind.INVALID_RESPONSE, MSG_INVALID_RESPONSE);
    }
    String errorType = null;
    JsonNode errorNode = errorEventNode.get("error");
    if (errorNode != null && errorNode.isObject() && errorNode.has("type")) {
      errorType = errorNode.get("type").asText();
    } else if (errorEventNode.has("type")) {
      errorType = errorEventNode.get("type").asText();
    }
    return mapTypeToException(errorType, 0);
  }

  private static ProviderException mapHttpStatus(int status, String errorType) {
    return mapTypeToException(errorType, status);
  }

  private static ProviderException mapTypeToException(String errorType, int status) {
    String typeLower = errorType != null ? errorType.toLowerCase(Locale.ROOT) : "";

    if (typeLower.contains("auth")
        || typeLower.contains("permission")
        || status == 401
        || status == 403) {
      return new ProviderException(ProviderErrorKind.AUTHENTICATION, MSG_AUTH);
    }
    if (typeLower.contains("billing") || typeLower.contains("credit") || status == 402) {
      return new ProviderException(ProviderErrorKind.BILLING, MSG_BILLING);
    }
    if (typeLower.contains("context_length_exceeded")
        || typeLower.contains("model_context_window_exceeded")
        || (typeLower.contains("context") && typeLower.contains("overflow"))) {
      return new ProviderException(ProviderErrorKind.OVERFLOW, MSG_OVERFLOW);
    }
    if (typeLower.contains("request_too_large") || status == 413) {
      return new ProviderException(ProviderErrorKind.INVALID_REQUEST, MSG_INVALID_REQUEST);
    }
    if (typeLower.contains("overloaded")
        || typeLower.contains("rate_limit")
        || typeLower.contains("api_error")
        || status == 429
        || (status >= 500 && status <= 599)) {
      return new ProviderException(ProviderErrorKind.TRANSIENT, MSG_TRANSIENT);
    }
    if (status >= 400 && status < 500) {
      return new ProviderException(ProviderErrorKind.INVALID_REQUEST, MSG_INVALID_REQUEST);
    }
    if (status >= 500) {
      return new ProviderException(ProviderErrorKind.TRANSIENT, MSG_TRANSIENT);
    }
    return new ProviderException(ProviderErrorKind.INVALID_RESPONSE, MSG_INVALID_RESPONSE);
  }

  private static String extractErrorType(byte[] bodyBytes) {
    if (bodyBytes == null || bodyBytes.length == 0) {
      return null;
    }
    try {
      JsonNode node = OBJECT_MAPPER.readTree(bodyBytes);
      if (node.has("error") && node.get("error").isObject() && node.get("error").has("type")) {
        return node.get("error").get("type").asText();
      }
      if (node.has("type") && node.get("type").isTextual()) {
        return node.get("type").asText();
      }
    } catch (Exception ignored) {
      // 脱敏静默：解析失败不作为二次错误
    }
    return null;
  }
}
