package fun.fengwk.kkstudio.harness.provider.anthropic;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.provider.ProviderErrorHelper;
import fun.fengwk.kkstudio.harness.provider.transport.TransportException;

import java.util.Locale;

/**
 * Anthropic 异常映射器。
 *
 * <p>读取 HTTP 状态码与 error envelope 进行确定性分类，直接向外保留上游原始 HTTP 响应正文与完整 SSE error envelope， 绝不附加 request
 * URI、请求标头、请求凭证或原始底层异常原因。
 */
final class AnthropicErrorMapper {

  private static final ObjectMapper OBJECT_MAPPER =
      JsonMapper.builder().enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  static final String MSG_AUTH = "Anthropic authentication failed";
  static final String MSG_BILLING = "Anthropic billing or quota error";
  static final String MSG_OVERFLOW = "Anthropic request context overflow";
  static final String MSG_TRANSIENT = "Anthropic transient failure";
  static final String MSG_INVALID_REQUEST = "Anthropic invalid request";
  static final String MSG_INVALID_RESPONSE = "Anthropic invalid response";

  private static final ProviderErrorHelper.FallbackMessages FALLBACK_MESSAGES =
      new ProviderErrorHelper.FallbackMessages(
          MSG_AUTH,
          MSG_BILLING,
          MSG_OVERFLOW,
          MSG_INVALID_REQUEST,
          MSG_TRANSIENT,
          MSG_INVALID_RESPONSE);

  private AnthropicErrorMapper() {}

  /**
   * 将传输层异常转换为 {@link ProviderException}。
   *
   * @param exception 传输层异常
   * @return 映射后的 ProviderException；仅 CANCELLED（调用线程被中断）返回 null
   */
  static ProviderException mapTransportException(TransportException exception) {
    return ProviderErrorHelper.mapTransportException(
        exception, "Anthropic", AnthropicErrorMapper::classifyHttp, FALLBACK_MESSAGES);
  }

  /** 将 SSE 接收到的 error 事件 envelope 进行映射。 */
  static ProviderException mapSseErrorEnvelope(JsonNode errorEventNode) {
    return ProviderErrorHelper.mapSseErrorEnvelope(
        errorEventNode, node -> classify(0, extractErrorType(node)), MSG_INVALID_RESPONSE);
  }

  private static ProviderErrorKind classifyHttp(TransportException exception) {
    int status = exception.statusCode();
    String errorType = extractErrorType(exception.errorBodyBytes());
    return classify(status, errorType);
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
    return ProviderErrorHelper.classifyHttpStatus(status);
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
      return node.get("error").get("type").asString("");
    }
    if (node.has("type") && node.get("type").isString()) {
      return node.get("type").asString("");
    }
    return null;
  }
}
