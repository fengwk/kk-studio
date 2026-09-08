package fun.fengwk.kkstudio.harness.provider.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
 * Gemini 异常映射与脱敏器。
 *
 * <p>只读取 HTTP 状态码与 error envelope 中的 status/code 字段进行确定性分类， 绝不向外暴露原始 URL、API 密钥、响应
 * body、底层异常原因或敏感数据。
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
      return mapHttpStatusAndStatus(status, errorStatus);
    }
    return new ProviderException(ProviderErrorKind.TRANSIENT, MSG_TRANSIENT);
  }

  /** 将 SSE 接收到的 error 事件 envelope 进行脱敏映射。 */
  static ProviderException mapSseErrorEnvelope(JsonNode errorEventNode) {
    if (errorEventNode == null || !errorEventNode.isObject()) {
      return new ProviderException(ProviderErrorKind.INVALID_RESPONSE, MSG_INVALID_RESPONSE);
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
    return mapHttpStatusAndStatus(statusCode, errorStatus);
  }

  static ProviderException fromHttp(int statusCode, String errorJson) {
    String errorStatus =
        extractErrorStatus(errorJson != null ? errorJson.getBytes(StandardCharsets.UTF_8) : null);
    return mapHttpStatusAndStatus(statusCode, errorStatus);
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

  static ProviderException mapHttpStatusAndStatus(int statusCode, String errorStatus) {
    String statusUpper = errorStatus != null ? errorStatus.toUpperCase(Locale.ROOT) : "";

    if (statusUpper.contains("UNAUTHENTICATED")
        || statusUpper.contains("PERMISSION_DENIED")
        || statusCode == 401
        || statusCode == 403) {
      return new ProviderException(ProviderErrorKind.AUTHENTICATION, MSG_AUTH);
    }
    if (statusCode == 402) {
      return new ProviderException(ProviderErrorKind.BILLING, MSG_BILLING);
    }
    if (statusUpper.contains("RESOURCE_EXHAUSTED")
        || statusUpper.contains("UNAVAILABLE")
        || statusUpper.contains("INTERNAL")
        || statusUpper.contains("OVERLOADED")
        || statusCode == 429
        || (statusCode >= 500 && statusCode <= 599)) {
      return new ProviderException(ProviderErrorKind.TRANSIENT, MSG_TRANSIENT);
    }
    if (statusUpper.contains("INVALID_ARGUMENT")
        || statusUpper.contains("NOT_FOUND")
        || statusUpper.contains("FAILED_PRECONDITION")
        || (statusCode >= 400 && statusCode < 500)) {
      return new ProviderException(ProviderErrorKind.INVALID_REQUEST, MSG_INVALID_REQUEST);
    }
    if (statusCode != 0) {
      return new ProviderException(ProviderErrorKind.INVALID_RESPONSE, MSG_INVALID_RESPONSE);
    }
    return new ProviderException(ProviderErrorKind.INVALID_RESPONSE, MSG_INVALID_RESPONSE);
  }

  private static String extractErrorStatus(byte[] bodyBytes) {
    if (bodyBytes == null || bodyBytes.length == 0) {
      return null;
    }
    try {
      JsonNode node = OBJECT_MAPPER.readTree(bodyBytes);
      if (node.has("error") && node.get("error").isObject()) {
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
