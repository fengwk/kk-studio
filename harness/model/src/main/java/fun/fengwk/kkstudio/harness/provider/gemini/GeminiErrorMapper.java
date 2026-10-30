package fun.fengwk.kkstudio.harness.provider.gemini;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.provider.ProviderErrorHelper;
import fun.fengwk.kkstudio.harness.provider.transport.TransportException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/**
 * Gemini 异常映射器。
 *
 * <p>读取 HTTP 状态码与 error payload 进行确定性分类，直接向外保留上游原始 HTTP 响应正文与完整 SSE error envelope， 绝不附加 request
 * URI、请求标头、请求凭证或原始底层异常原因。
 */
final class GeminiErrorMapper {

  private static final ObjectMapper OBJECT_MAPPER =
      JsonMapper.builder().enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  static final String MSG_AUTH = "Gemini authentication failed";
  static final String MSG_BILLING = "Gemini billing or quota error";
  static final String MSG_TRANSIENT = "Gemini transient failure";
  static final String MSG_INVALID_REQUEST = "Gemini invalid request";
  static final String MSG_INVALID_RESPONSE = "Gemini invalid response";

  private static final ProviderErrorHelper.FallbackMessages FALLBACK_MESSAGES =
      new ProviderErrorHelper.FallbackMessages(
          MSG_AUTH,
          MSG_BILLING,
          MSG_INVALID_RESPONSE,
          MSG_INVALID_REQUEST,
          MSG_TRANSIENT,
          MSG_INVALID_RESPONSE);

  private GeminiErrorMapper() {}

  /**
   * 将传输层异常转换为 {@link ProviderException}。
   *
   * @param exception 传输层异常
   * @return 映射后的 ProviderException；仅 CANCELLED（调用线程被中断）返回 null
   */
  static ProviderException mapTransportException(TransportException exception) {
    return ProviderErrorHelper.mapTransportException(
        exception, "Gemini", GeminiErrorMapper::classifyHttp, FALLBACK_MESSAGES);
  }

  /** 将 SSE 接收到的 error 事件 envelope 进行映射。 */
  static ProviderException mapSseErrorEnvelope(JsonNode errorEventNode) {
    return ProviderErrorHelper.mapSseErrorEnvelope(
        errorEventNode,
        node -> classify(extractStatusCode(node), extractErrorStatus(node)),
        GeminiErrorMapper::extractStatusCode,
        MSG_INVALID_RESPONSE);
  }

  static ProviderException fromHttp(int statusCode, String errorJson) {
    String errorStatus =
        extractErrorStatus(errorJson != null ? errorJson.getBytes(StandardCharsets.UTF_8) : null);
    ProviderErrorKind kind = classify(statusCode, errorStatus);
    String message =
        ProviderErrorHelper.formatHttpErrorMessage(
            statusCode, errorJson, false, FALLBACK_MESSAGES.fallbackForKind(kind));
    return new ProviderException(kind, message, ProviderErrorHelper.httpErrorStatus(statusCode));
  }

  static ProviderException fromThrowable(Throwable throwable) {
    if (throwable instanceof TransportException te) {
      return mapTransportException(te);
    }
    if (throwable instanceof TimeoutException || throwable instanceof SocketTimeoutException) {
      return new ProviderException(ProviderErrorKind.TRANSIENT, "Gemini request timed out");
    }
    if (throwable instanceof ConnectException || throwable instanceof IOException) {
      return new ProviderException(ProviderErrorKind.TRANSIENT, "Gemini network error");
    }
    if (throwable instanceof CancellationException) {
      return new ProviderException(ProviderErrorKind.CANCELLED, "Gemini request cancelled");
    }
    return new ProviderException(ProviderErrorKind.TRANSIENT, MSG_TRANSIENT);
  }

  private static ProviderErrorKind classifyHttp(TransportException exception) {
    int status = exception.statusCode();
    String errorStatus = extractErrorStatus(exception.errorBodyBytes());
    return classify(status, errorStatus);
  }

  private static ProviderErrorKind classify(int statusCode, String errorStatus) {
    String statusUpper = errorStatus != null ? errorStatus.toUpperCase(Locale.ROOT) : "";

    if (statusUpper.contains("UNAUTHENTICATED")
        || statusUpper.contains("PERMISSION_DENIED")
        || statusCode == 401
        || statusCode == 403) {
      return ProviderErrorKind.AUTHENTICATION;
    }
    if (statusCode == 402) {
      return ProviderErrorKind.BILLING;
    }
    if (statusUpper.contains("RESOURCE_EXHAUSTED")
        || statusUpper.contains("UNAVAILABLE")
        || statusUpper.contains("INTERNAL")
        || statusUpper.contains("OVERLOADED")
        || statusCode == 429
        || (statusCode >= 500 && statusCode <= 599)) {
      return ProviderErrorKind.TRANSIENT;
    }
    if (statusUpper.contains("INVALID_ARGUMENT")
        || statusUpper.contains("NOT_FOUND")
        || statusUpper.contains("FAILED_PRECONDITION")) {
      return ProviderErrorKind.INVALID_REQUEST;
    }
    // Gemini 只把标准 5xx（500-599）视为可重试；范围外状态沿用原 fallback，避免未知状态被重试。
    if (statusCode >= 600) {
      return ProviderErrorKind.INVALID_RESPONSE;
    }
    return ProviderErrorHelper.classifyHttpStatus(statusCode);
  }

  private static int extractStatusCode(JsonNode errorEventNode) {
    if (errorEventNode != null && errorEventNode.has("error")) {
      JsonNode errorNode = errorEventNode.get("error");
      if (errorNode.isObject()
          && errorNode.has("code")
          && errorNode.get("code").isIntegralNumber()) {
        return errorNode.get("code").asInt(0);
      }
    }
    return 0;
  }

  private static String extractErrorStatus(JsonNode errorEventNode) {
    if (errorEventNode != null && errorEventNode.has("error")) {
      JsonNode errorNode = errorEventNode.get("error");
      if (errorNode.isObject() && errorNode.has("status") && errorNode.get("status").isString()) {
        return errorNode.get("status").asString("");
      }
    }
    return null;
  }

  private static String extractErrorStatus(byte[] bodyBytes) {
    if (bodyBytes == null || bodyBytes.length == 0) {
      return null;
    }
    try {
      JsonNode node = OBJECT_MAPPER.readTree(bodyBytes);
      return extractErrorStatus(node);
    } catch (Exception ignored) {
      // 忽略解析失败，进入 fallback 状态码分类
      return null;
    }
  }
}
