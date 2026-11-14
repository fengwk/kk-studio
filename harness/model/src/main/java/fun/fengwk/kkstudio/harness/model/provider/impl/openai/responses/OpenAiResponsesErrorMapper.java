package fun.fengwk.kkstudio.harness.model.provider.impl.openai.responses;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderErrorHelper;
import fun.fengwk.kkstudio.harness.model.provider.impl.transport.TransportException;

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
  static final String MSG_CONTEXT_LIMIT = "OpenAI Responses request context limit exceeded";
  static final String MSG_REQUEST_BODY_LIMIT = "OpenAI Responses request body limit exceeded";
  static final String MSG_TRANSIENT = "OpenAI Responses transient failure";
  static final String MSG_INVALID_REQUEST = "OpenAI Responses invalid request";
  static final String MSG_INVALID_RESPONSE = "OpenAI Responses invalid response";

  private static final ProviderErrorHelper.FallbackMessages FALLBACK_MESSAGES =
      new ProviderErrorHelper.FallbackMessages(
          MSG_AUTH,
          MSG_BILLING,
          MSG_CONTEXT_LIMIT,
          MSG_REQUEST_BODY_LIMIT,
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
    return ProviderErrorHelper.classify(
        status,
        containsAny(lower, "auth", "permission", "api_key"),
        containsAny(lower, "billing", "credit", "quota"),
        containsAny(
                lower,
                "context_length_exceeded",
                "model_context_window_exceeded",
                "tokens exceeded")
            || (lower.contains("context") && lower.contains("overflow")),
        lower.contains("request_too_large"),
        lower.contains("invalid_request") || status == 400 || status == 422,
        containsAny(lower, "overloaded", "rate_limit", "server_error", "tokens exceeded"),
        false,
        false);
  }

  private static boolean containsAny(String value, String... needles) {
    for (String needle : needles) {
      if (value.contains(needle)) {
        return true;
      }
    }
    return false;
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
    String fromError = firstText(errorNode(node), "code", "type", "message");
    return fromError != null ? fromError : firstText(node, "code", "type");
  }

  /** 同时接受顶层 error 与 Responses 事件信封里的 response.error。 */
  private static JsonNode errorNode(JsonNode node) {
    JsonNode errorNode = node.get("error");
    if (errorNode != null) {
      return errorNode;
    }
    JsonNode responseNode = node.get("response");
    return responseNode != null && responseNode.isObject() ? responseNode.get("error") : null;
  }

  private static String firstText(JsonNode node, String... fields) {
    if (node == null || !node.isObject()) {
      return null;
    }
    for (String field : fields) {
      JsonNode value = node.get(field);
      if (value != null && value.isString()) {
        return value.asString("");
      }
    }
    return null;
  }
}
