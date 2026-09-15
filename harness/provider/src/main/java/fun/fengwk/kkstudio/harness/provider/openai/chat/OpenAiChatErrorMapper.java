package fun.fengwk.kkstudio.harness.provider.openai.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.provider.transport.TransportErrorKind;
import fun.fengwk.kkstudio.harness.provider.transport.TransportException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * OpenAI Chat Completions 异常映射与脱敏器。
 *
 * <p>读取 HTTP 状态码与 error envelope 中的 type/code/param 字段进行确定性分类， 仅在 INVALID_REQUEST
 * 时受控追加安全元数据标识符，绝不向外暴露原始 URL、API 密钥、响应 body、底层异常原因或敏感数据。
 */
final class OpenAiChatErrorMapper {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Pattern SAFE_PARAMETER_PATH =
      Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*(?:\\[\\d+]|[./][a-zA-Z0-9_-]+)*$");
  private static final Set<String> SAFE_ERROR_IDENTIFIERS =
      Set.of(
          "context_length_exceeded",
          "invalid_parameter",
          "invalid_request",
          "invalid_request_error",
          "invalid_value",
          "model_context_window_exceeded",
          "model_not_found",
          "request_too_large",
          "unsupported_model",
          "unsupported_parameter",
          "unsupported_value");
  private static final Set<String> SAFE_REQUEST_PARAMETERS =
      Set.of(
          "max_tokens",
          "messages",
          "model",
          "prompt_cache_key",
          "prompt_cache_options",
          "prompt_cache_retention",
          "reasoning_effort",
          "stream",
          "stream_options",
          "thinking",
          "tools");

  static final String MSG_AUTH = "OpenAI authentication failed";
  static final String MSG_BILLING = "OpenAI billing or quota error";
  static final String MSG_OVERFLOW = "OpenAI request context overflow";
  static final String MSG_TRANSIENT = "OpenAI transient failure";
  static final String MSG_INVALID_REQUEST = "OpenAI invalid request";
  static final String MSG_INVALID_RESPONSE = "OpenAI invalid response";

  private OpenAiChatErrorMapper() {}

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
      ErrorMetadata metadata = extractMetadata(exception.errorBodyBytes());
      return mapStatusAndMetadata(status, metadata);
    }
    return new ProviderException(ProviderErrorKind.TRANSIENT, MSG_TRANSIENT);
  }

  /** 将 SSE 接收到的 error 事件 envelope 进行脱敏映射。 */
  static ProviderException mapSseErrorEnvelope(JsonNode errorEventNode) {
    if (errorEventNode == null || !errorEventNode.isObject()) {
      return new ProviderException(ProviderErrorKind.INVALID_RESPONSE, MSG_INVALID_RESPONSE);
    }
    ErrorMetadata metadata = extractMetadata(errorEventNode);
    return mapStatusAndMetadata(0, metadata);
  }

  private static ProviderException mapStatusAndMetadata(int status, ErrorMetadata metadata) {
    String typeLower = metadata.rawType != null ? metadata.rawType.toLowerCase(Locale.ROOT) : "";
    String codeLower = metadata.rawCode != null ? metadata.rawCode.toLowerCase(Locale.ROOT) : "";

    if (matchesAny(typeLower, codeLower, "auth", "permission") || status == 401 || status == 403) {
      return new ProviderException(ProviderErrorKind.AUTHENTICATION, MSG_AUTH);
    }
    if (matchesAny(typeLower, codeLower, "billing", "insufficient_quota", "quota", "credit")
        || status == 402) {
      return new ProviderException(ProviderErrorKind.BILLING, MSG_BILLING);
    }
    if (matchesAny(typeLower, codeLower, "context_length_exceeded", "model_context_window_exceeded")
        || matchesContextOverflow(typeLower, codeLower)) {
      return new ProviderException(ProviderErrorKind.OVERFLOW, MSG_OVERFLOW);
    }
    if (matchesAny(typeLower, codeLower, "request_too_large") || status == 413) {
      return new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, buildInvalidRequestMessage(metadata));
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
      return new ProviderException(ProviderErrorKind.TRANSIENT, MSG_TRANSIENT);
    }
    if (matchesAny(typeLower, codeLower, "invalid_request") || (status >= 400 && status < 500)) {
      return new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, buildInvalidRequestMessage(metadata));
    }
    if (status >= 500) {
      return new ProviderException(ProviderErrorKind.TRANSIENT, MSG_TRANSIENT);
    }
    return new ProviderException(ProviderErrorKind.INVALID_RESPONSE, MSG_INVALID_RESPONSE);
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

  private static String buildInvalidRequestMessage(ErrorMetadata metadata) {
    if (metadata == null || !metadata.hasSafeMetadata()) {
      return MSG_INVALID_REQUEST;
    }
    List<String> parts = new ArrayList<>(3);
    if (metadata.safeType != null) {
      parts.add("type=" + metadata.safeType);
    }
    if (metadata.safeCode != null) {
      parts.add("code=" + metadata.safeCode);
    }
    if (metadata.safeParam != null) {
      parts.add("param=" + metadata.safeParam);
    }
    return MSG_INVALID_REQUEST + " (" + String.join(", ", parts) + ")";
  }

  private static ErrorMetadata extractMetadata(byte[] bodyBytes) {
    if (bodyBytes == null || bodyBytes.length == 0) {
      return ErrorMetadata.EMPTY;
    }
    try {
      return extractMetadata(OBJECT_MAPPER.readTree(bodyBytes));
    } catch (Exception ignored) {
      // 无法按 JSON 解析则降级为依据状态码处理
      return ErrorMetadata.EMPTY;
    }
  }

  private static ErrorMetadata extractMetadata(JsonNode root) {
    if (root == null || !root.isObject()) {
      return ErrorMetadata.EMPTY;
    }
    JsonNode err = root.get("error");
    JsonNode node = err != null && err.isObject() ? err : root;

    String rawType = getTextField(node, "type");
    String rawCode = getTextField(node, "code");
    String rawParam = getTextField(node, "param");

    return new ErrorMetadata(
        rawType,
        rawCode,
        sanitizeErrorIdentifier(rawType),
        sanitizeErrorIdentifier(rawCode),
        sanitizeParameter(rawParam));
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

  private static String sanitizeErrorIdentifier(String value) {
    if (value == null || value.length() > 128) {
      return null;
    }
    String normalized = value.toLowerCase(Locale.ROOT);
    return SAFE_ERROR_IDENTIFIERS.contains(normalized) ? normalized : null;
  }

  private static String sanitizeParameter(String value) {
    if (value == null || value.isEmpty() || value.length() > 128) {
      return null;
    }
    String normalized = value.startsWith("/") ? value.substring(1) : value;
    if (!SAFE_PARAMETER_PATH.matcher(normalized).matches()) {
      return null;
    }
    String lower = normalized.toLowerCase(Locale.ROOT);
    int separator = lower.length();
    for (char candidate : new char[] {'.', '[', '/'}) {
      int index = lower.indexOf(candidate);
      if (index >= 0) {
        separator = Math.min(separator, index);
      }
    }
    String root = lower.substring(0, separator);
    return SAFE_REQUEST_PARAMETERS.contains(root) ? root : null;
  }

  private record ErrorMetadata(
      String rawType, String rawCode, String safeType, String safeCode, String safeParam) {

    static final ErrorMetadata EMPTY = new ErrorMetadata(null, null, null, null, null);

    boolean hasSafeMetadata() {
      return safeType != null || safeCode != null || safeParam != null;
    }
  }
}
