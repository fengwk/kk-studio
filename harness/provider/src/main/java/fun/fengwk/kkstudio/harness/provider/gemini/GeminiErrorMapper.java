package fun.fengwk.kkstudio.harness.provider.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.provider.ProviderErrorHelper;
import fun.fengwk.kkstudio.harness.provider.transport.TransportErrorKind;
import fun.fengwk.kkstudio.harness.provider.transport.TransportException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;

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

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  static final String MSG_AUTH = "Gemini authentication failed";
  static final String MSG_BILLING = "Gemini billing or quota error";
  static final String MSG_TRANSIENT = "Gemini transient failure";
  static final String MSG_INVALID_REQUEST = "Gemini invalid request";
  static final String MSG_INVALID_RESPONSE = "Gemini invalid response";

  private GeminiErrorMapper() {}

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
      return new ProviderException(ProviderErrorKind.TRANSIENT, "Gemini request timed out");
    }
    if (exception.kind() == TransportErrorKind.IO) {
      return new ProviderException(ProviderErrorKind.TRANSIENT, "Gemini I/O error");
    }
    if (exception.kind() == TransportErrorKind.INVALID_RESPONSE) {
      return new ProviderException(ProviderErrorKind.INVALID_RESPONSE, MSG_INVALID_RESPONSE);
    }
    if (exception.kind() == TransportErrorKind.HTTP_STATUS) {
      int status = exception.statusCode();
      String errorStatus = extractErrorStatus(exception.errorBodyBytes());
      ProviderErrorKind kind = classify(status, errorStatus);
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
    String errorStatus = null;
    int statusCode = 0;
    JsonNode errorNode = errorEventNode.get("error");
    if (errorNode != null && errorNode.isObject()) {
      if (errorNode.has("status") && errorNode.get("status").isTextual()) {
        errorStatus = errorNode.get("status").asText();
      }
      if (errorNode.has("code") && errorNode.get("code").isIntegralNumber()) {
        statusCode = errorNode.get("code").asInt();
      }
    }
    ProviderErrorKind kind = classify(statusCode, errorStatus);
    return new ProviderException(kind, errorEventNode.toString());
  }

  static ProviderException fromHttp(int statusCode, String errorJson) {
    String errorStatus =
        extractErrorStatus(errorJson != null ? errorJson.getBytes(StandardCharsets.UTF_8) : null);
    ProviderErrorKind kind = classify(statusCode, errorStatus);
    String fallback = fallbackForKind(kind);
    String message =
        ProviderErrorHelper.formatHttpErrorMessage(statusCode, errorJson, false, fallback);
    return new ProviderException(kind, message);
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
        || statusUpper.contains("FAILED_PRECONDITION")
        || (statusCode >= 400 && statusCode < 500)) {
      return ProviderErrorKind.INVALID_REQUEST;
    }
    return ProviderErrorKind.INVALID_RESPONSE;
  }

  private static String fallbackForKind(ProviderErrorKind kind) {
    return switch (kind) {
      case AUTHENTICATION -> MSG_AUTH;
      case BILLING -> MSG_BILLING;
      case TRANSIENT -> MSG_TRANSIENT;
      case INVALID_REQUEST -> MSG_INVALID_REQUEST;
      case OVERFLOW, INVALID_RESPONSE, CANCELLED -> MSG_INVALID_RESPONSE;
    };
  }

  private static String extractErrorStatus(byte[] bodyBytes) {
    if (bodyBytes == null || bodyBytes.length == 0) {
      return null;
    }
    try {
      JsonNode node = OBJECT_MAPPER.readTree(bodyBytes);
      if (node != null && node.has("error") && node.get("error").isObject()) {
        JsonNode errNode = node.get("error");
        if (errNode.has("status") && errNode.get("status").isTextual()) {
          return errNode.get("status").asText();
        }
      }
    } catch (Exception ignored) {
      // 忽略解析失败，进入 fallback 状态码分类
    }
    return null;
  }
}
