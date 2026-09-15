package fun.fengwk.kkstudio.harness.provider.openai.responses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.provider.ProviderErrorHelper;
import fun.fengwk.kkstudio.harness.provider.transport.TransportErrorKind;
import fun.fengwk.kkstudio.harness.provider.transport.TransportException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;

import java.util.Locale;

/**
 * OpenAI Responses 异常映射器。
 *
 * <p>读取 HTTP 状态码与 error envelope 进行确定性分类，直接向外保留上游原始 HTTP 响应正文与完整 SSE error envelope， 绝不附加 request
 * URI、请求标头、请求凭证或原始底层异常原因。
 */
final class OpenAiResponsesErrorMapper {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  static final String MSG_AUTH = "OpenAI Responses authentication failed";
  static final String MSG_BILLING = "OpenAI Responses billing or quota error";
  static final String MSG_OVERFLOW = "OpenAI Responses request context overflow";
  static final String MSG_TRANSIENT = "OpenAI Responses transient failure";
  static final String MSG_INVALID_REQUEST = "OpenAI Responses invalid request";
  static final String MSG_INVALID_RESPONSE = "OpenAI Responses invalid response";

  private OpenAiResponsesErrorMapper() {}

  /**
   * 将传输层异常转换为 {@link ProviderException}。
   *
   * @param exception 传输层异常
   * @return 映射后的 ProviderException；若为 CANCELLED、EXECUTOR_REJECTED 或 CALLBACK_FAILED 则返回 null 表示静默
   */
  static ProviderException mapTransportException(TransportException exception) {
    if (exception == null) {
      return new ProviderException(ProviderErrorKind.TRANSIENT, MSG_TRANSIENT);
    }
    if (ProviderErrorHelper.isSilentTransportKind(exception.kind())) {
      return null;
    }
    if (exception.kind() == TransportErrorKind.TIMEOUT) {
      return new ProviderException(
          ProviderErrorKind.TRANSIENT, "OpenAI Responses request timed out");
    }
    if (exception.kind() == TransportErrorKind.IO) {
      return new ProviderException(ProviderErrorKind.TRANSIENT, "OpenAI Responses I/O error");
    }
    if (exception.kind() == TransportErrorKind.INVALID_RESPONSE) {
      return new ProviderException(ProviderErrorKind.INVALID_RESPONSE, MSG_INVALID_RESPONSE);
    }
    if (exception.kind() == TransportErrorKind.HTTP_STATUS) {
      int status = exception.statusCode();
      String errorCodeOrType = extractErrorCodeOrType(exception.errorBodyBytes());
      ProviderErrorKind kind = classify(status, errorCodeOrType);
      String fallback = fallbackForKind(kind);
      String message = ProviderErrorHelper.formatHttpErrorMessage(exception, fallback);
      return new ProviderException(kind, message);
    }
    return new ProviderException(ProviderErrorKind.TRANSIENT, MSG_TRANSIENT);
  }

  /** 将 SSE 接收到的 error 或 failed 事件 envelope 进行映射。 */
  static ProviderException mapSseErrorEnvelope(JsonNode errorEventNode) {
    if (errorEventNode == null) {
      return new ProviderException(ProviderErrorKind.INVALID_RESPONSE, MSG_INVALID_RESPONSE);
    }
    if (!errorEventNode.isObject()) {
      return new ProviderException(ProviderErrorKind.INVALID_RESPONSE, errorEventNode.toString());
    }
    String codeOrType = extractErrorCodeOrType(errorEventNode);
    ProviderErrorKind kind = classify(0, codeOrType);
    return new ProviderException(kind, errorEventNode.toString());
  }

  private static ProviderErrorKind classify(int status, String codeOrType) {
    String lower = codeOrType != null ? codeOrType.toLowerCase(Locale.ROOT) : "";

    if (lower.contains("auth")
        || lower.contains("permission")
        || lower.contains("api_key")
        || status == 401
        || status == 403) {
      return ProviderErrorKind.AUTHENTICATION;
    }
    if (lower.contains("billing")
        || lower.contains("credit")
        || lower.contains("quota")
        || status == 402) {
      return ProviderErrorKind.BILLING;
    }
    if (lower.contains("context_length_exceeded")
        || lower.contains("model_context_window_exceeded")
        || lower.contains("tokens exceeded")
        || (lower.contains("context") && lower.contains("overflow"))) {
      return ProviderErrorKind.OVERFLOW;
    }
    if (lower.contains("request_too_large")
        || lower.contains("invalid_request")
        || status == 400
        || status == 413
        || status == 422) {
      return ProviderErrorKind.INVALID_REQUEST;
    }
    if (lower.contains("overloaded")
        || lower.contains("rate_limit")
        || lower.contains("server_error")
        || lower.contains("tokens exceeded")
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

  private static String extractErrorCodeOrType(byte[] bodyBytes) {
    if (bodyBytes == null || bodyBytes.length == 0) {
      return null;
    }
    try {
      JsonNode node = OBJECT_MAPPER.readTree(bodyBytes);
      return extractErrorCodeOrType(node);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String extractErrorCodeOrType(JsonNode node) {
    if (node == null || !node.isObject()) {
      return null;
    }
    JsonNode errorNode = node.get("error");
    if (errorNode == null && node.has("response")) {
      JsonNode respNode = node.get("response");
      if (respNode.isObject() && respNode.has("error")) {
        errorNode = respNode.get("error");
      }
    }

    if (errorNode != null && errorNode.isObject()) {
      if (errorNode.has("code") && errorNode.get("code").isTextual()) {
        return errorNode.get("code").asText();
      }
      if (errorNode.has("type") && errorNode.get("type").isTextual()) {
        return errorNode.get("type").asText();
      }
      if (errorNode.has("message") && errorNode.get("message").isTextual()) {
        return errorNode.get("message").asText();
      }
    }
    if (node.has("code") && node.get("code").isTextual()) {
      return node.get("code").asText();
    }
    if (node.has("type") && node.get("type").isTextual()) {
      return node.get("type").asText();
    }
    return null;
  }
}
