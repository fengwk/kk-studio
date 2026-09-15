package fun.fengwk.kkstudio.harness.provider.openai.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.provider.ProviderErrorHelper;
import fun.fengwk.kkstudio.harness.provider.transport.TransportErrorKind;
import fun.fengwk.kkstudio.harness.provider.transport.TransportException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;

import java.util.Locale;

/**
 * OpenAI Chat Completions 异常映射器。
 *
 * <p>读取 HTTP 状态码与 error payload 进行确定性分类，直接向外保留上游原始 HTTP 响应正文与完整 SSE error envelope， 绝不附加 request
 * URI、请求标头、请求凭证或原始底层异常原因。
 */
final class OpenAiChatErrorMapper {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  static final String MSG_AUTH = "OpenAI authentication failed";
  static final String MSG_BILLING = "OpenAI billing or quota error";
  static final String MSG_OVERFLOW = "OpenAI request context overflow";
  static final String MSG_TRANSIENT = "OpenAI transient failure";
  static final String MSG_INVALID_REQUEST = "OpenAI invalid request";
  static final String MSG_INVALID_RESPONSE = "OpenAI invalid response";

  private OpenAiChatErrorMapper() {}

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
      return new ProviderException(ProviderErrorKind.TRANSIENT, "OpenAI request timed out");
    }
    if (exception.kind() == TransportErrorKind.IO) {
      return new ProviderException(ProviderErrorKind.TRANSIENT, "OpenAI I/O error");
    }
    if (exception.kind() == TransportErrorKind.INVALID_RESPONSE) {
      return new ProviderException(ProviderErrorKind.INVALID_RESPONSE, MSG_INVALID_RESPONSE);
    }
    if (exception.kind() == TransportErrorKind.HTTP_STATUS) {
      int status = exception.statusCode();
      String[] typeAndCode = extractTypeAndCode(exception.errorBodyBytes());
      ProviderErrorKind kind = classify(status, typeAndCode[0], typeAndCode[1]);
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
    String[] typeAndCode = extractTypeAndCode(errorEventNode);
    ProviderErrorKind kind = classify(0, typeAndCode[0], typeAndCode[1]);
    return new ProviderException(kind, errorEventNode.toString());
  }

  private static ProviderErrorKind classify(int status, String rawType, String rawCode) {
    String typeLower = rawType != null ? rawType.toLowerCase(Locale.ROOT) : "";
    String codeLower = rawCode != null ? rawCode.toLowerCase(Locale.ROOT) : "";

    if (matchesAny(typeLower, codeLower, "auth", "permission") || status == 401 || status == 403) {
      return ProviderErrorKind.AUTHENTICATION;
    }
    if (matchesAny(typeLower, codeLower, "billing", "insufficient_quota", "quota", "credit")
        || status == 402) {
      return ProviderErrorKind.BILLING;
    }
    if (matchesAny(typeLower, codeLower, "context_length_exceeded", "model_context_window_exceeded")
        || matchesContextOverflow(typeLower, codeLower)) {
      return ProviderErrorKind.OVERFLOW;
    }
    if (matchesAny(typeLower, codeLower, "request_too_large") || status == 413) {
      return ProviderErrorKind.INVALID_REQUEST;
    }
    if (matchesAny(
            typeLower,
            codeLower,
            "overloaded",
            "rate_limit",
            "rate_limit_exceeded",
            "server_error",
            "transient")
        || status == 429
        || (status >= 500 && status <= 599)) {
      return ProviderErrorKind.TRANSIENT;
    }
    if (matchesAny(typeLower, codeLower, "invalid_request") || (status >= 400 && status < 500)) {
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

  private static boolean matchesAny(String typeLower, String codeLower, String... patterns) {
    for (String pattern : patterns) {
      if (typeLower.contains(pattern) || codeLower.contains(pattern)) {
        return true;
      }
    }
    return false;
  }

  private static boolean matchesContextOverflow(String typeLower, String codeLower) {
    return (typeLower.contains("context") && typeLower.contains("overflow"))
        || (codeLower.contains("context") && codeLower.contains("overflow"));
  }

  private static String[] extractTypeAndCode(byte[] bodyBytes) {
    if (bodyBytes == null || bodyBytes.length == 0) {
      return new String[] {null, null};
    }
    try {
      return extractTypeAndCode(OBJECT_MAPPER.readTree(bodyBytes));
    } catch (Exception ignored) {
      return new String[] {null, null};
    }
  }

  private static String[] extractTypeAndCode(JsonNode root) {
    if (root == null || !root.isObject()) {
      return new String[] {null, null};
    }
    JsonNode err = root.get("error");
    JsonNode node = err != null && err.isObject() ? err : root;

    String rawType = getTextField(node, "type");
    String rawCode = getTextField(node, "code");
    return new String[] {rawType, rawCode};
  }

  private static String getTextField(JsonNode node, String fieldName) {
    if (node != null && node.has(fieldName)) {
      JsonNode fieldNode = node.get(fieldName);
      if (fieldNode != null && fieldNode.isTextual()) {
        return fieldNode.asText();
      }
    }
    return null;
  }
}
