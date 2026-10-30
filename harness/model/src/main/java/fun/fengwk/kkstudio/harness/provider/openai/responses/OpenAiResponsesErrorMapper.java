package fun.fengwk.kkstudio.harness.provider.openai.responses;

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
 * OpenAI Responses 异常映射器。
 *
 * <p>读取 HTTP 状态码与 error envelope 进行确定性分类，直接向外保留上游原始 HTTP 响应正文与完整 SSE error envelope， 绝不附加 request
 * URI、请求标头、请求凭证或原始底层异常原因。
 */
final class OpenAiResponsesErrorMapper {

  private static final ObjectMapper OBJECT_MAPPER =
      JsonMapper.builder().enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  static final String MSG_AUTH = "OpenAI Responses authentication failed";
  static final String MSG_BILLING = "OpenAI Responses billing or quota error";
  static final String MSG_OVERFLOW = "OpenAI Responses request context overflow";
  static final String MSG_TRANSIENT = "OpenAI Responses transient failure";
  static final String MSG_INVALID_REQUEST = "OpenAI Responses invalid request";
  static final String MSG_INVALID_RESPONSE = "OpenAI Responses invalid response";

  private static final ProviderErrorHelper.FallbackMessages FALLBACK_MESSAGES =
      new ProviderErrorHelper.FallbackMessages(
          MSG_AUTH,
          MSG_BILLING,
          MSG_OVERFLOW,
          MSG_INVALID_REQUEST,
          MSG_TRANSIENT,
          MSG_INVALID_RESPONSE);

  private OpenAiResponsesErrorMapper() {}

  /**
   * 将传输层异常转换为 {@link ProviderException}。
   *
   * @param exception 传输层异常
   * @return 映射后的 ProviderException；仅 CANCELLED（调用线程被中断）返回 null
   */
  static ProviderException mapTransportException(TransportException exception) {
    return ProviderErrorHelper.mapTransportException(
        exception, "OpenAI Responses", OpenAiResponsesErrorMapper::classifyHttp, FALLBACK_MESSAGES);
  }

  /** 将 SSE 接收到的 error 或 failed 事件 envelope 进行映射。 */
  static ProviderException mapSseErrorEnvelope(JsonNode errorEventNode) {
    return ProviderErrorHelper.mapSseErrorEnvelope(
        errorEventNode, node -> classify(0, extractErrorCodeOrType(node)), MSG_INVALID_RESPONSE);
  }

  private static ProviderErrorKind classifyHttp(TransportException exception) {
    int status = exception.statusCode();
    String errorCodeOrType = extractErrorCodeOrType(exception.errorBodyBytes());
    return classify(status, errorCodeOrType);
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
    return ProviderErrorHelper.classifyHttpStatus(status);
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
      if (errorNode.has("code") && errorNode.get("code").isString()) {
        return errorNode.get("code").asString("");
      }
      if (errorNode.has("type") && errorNode.get("type").isString()) {
        return errorNode.get("type").asString("");
      }
      if (errorNode.has("message") && errorNode.get("message").isString()) {
        return errorNode.get("message").asString("");
      }
    }
    if (node.has("code") && node.get("code").isString()) {
      return node.get("code").asString("");
    }
    if (node.has("type") && node.get("type").isString()) {
      return node.get("type").asString("");
    }
    return null;
  }
}
