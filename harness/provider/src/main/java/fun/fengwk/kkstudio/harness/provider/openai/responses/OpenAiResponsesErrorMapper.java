package fun.fengwk.kkstudio.harness.provider.openai.responses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.provider.transport.TransportErrorKind;
import fun.fengwk.kkstudio.harness.provider.transport.TransportException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;

import java.util.Locale;

/**
 * OpenAI Responses 异常映射与脱敏器。
 *
 * <p>只读取 HTTP 状态码与 error envelope 中的 code/message/type 字段进行确定性分类， 绝不向外暴露原始 URL、API 密钥、响应
 * body、底层异常堆栈或敏感数据。
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
   * 将传输层异常转换为脱敏的 {@link ProviderException}。
   *
   * @param exception 传输层异常
   * @return 映射后的 ProviderException；若为 CANCELLED、EXECUTOR_REJECTED 或 CALLBACK_FAILED 则返回 null 表示静默
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
      return mapStatusAndType(status, errorCodeOrType);
    }
    return new ProviderException(ProviderErrorKind.TRANSIENT, MSG_TRANSIENT);
  }

  /** 将 SSE 接收到的 error 或 failed 事件 envelope 进行脱敏映射。 */
  static ProviderException mapSseErrorEnvelope(JsonNode errorEventNode) {
    if (errorEventNode == null || !errorEventNode.isObject()) {
      return new ProviderException(ProviderErrorKind.INVALID_RESPONSE, MSG_INVALID_RESPONSE);
    }
    String codeOrType = null;
    JsonNode errorNode = errorEventNode.get("error");
    if (errorNode == null && errorEventNode.has("response")) {
      JsonNode respNode = errorEventNode.get("response");
      if (respNode.isObject() && respNode.has("error")) {
        errorNode = respNode.get("error");
      }
    }

    if (errorNode != null && errorNode.isObject()) {
      if (errorNode.has("code") && errorNode.get("code").isTextual()) {
        codeOrType = errorNode.get("code").asText();
      } else if (errorNode.has("type") && errorNode.get("type").isTextual()) {
        codeOrType = errorNode.get("type").asText();
      } else if (errorNode.has("message") && errorNode.get("message").isTextual()) {
        codeOrType = errorNode.get("message").asText();
      }
    } else if (errorEventNode.has("code") && errorEventNode.get("code").isTextual()) {
      codeOrType = errorEventNode.get("code").asText();
    } else if (errorEventNode.has("type") && errorEventNode.get("type").isTextual()) {
      codeOrType = errorEventNode.get("type").asText();
    }

    return mapStatusAndType(0, codeOrType);
  }

  private static ProviderException mapStatusAndType(int status, String codeOrType) {
    String lower = codeOrType != null ? codeOrType.toLowerCase(Locale.ROOT) : "";

    if (lower.contains("auth")
        || lower.contains("permission")
        || lower.contains("api_key")
        || status == 401
        || status == 403) {
      return new ProviderException(ProviderErrorKind.AUTHENTICATION, MSG_AUTH);
    }
    if (lower.contains("billing")
        || lower.contains("credit")
        || lower.contains("quota")
        || status == 402) {
      return new ProviderException(ProviderErrorKind.BILLING, MSG_BILLING);
    }
    if (lower.contains("context_length_exceeded")
        || lower.contains("model_context_window_exceeded")
        || lower.contains("tokens exceeded")
        || (lower.contains("context") && lower.contains("overflow"))) {
      return new ProviderException(ProviderErrorKind.OVERFLOW, MSG_OVERFLOW);
    }
    if (lower.contains("request_too_large")
        || lower.contains("invalid_request")
        || status == 400
        || status == 413
        || status == 422) {
      return new ProviderException(ProviderErrorKind.INVALID_REQUEST, MSG_INVALID_REQUEST);
    }
    if (lower.contains("overloaded")
        || lower.contains("rate_limit")
        || lower.contains("server_error")
        || lower.contains("tokens exceeded")
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

  private static String extractErrorCodeOrType(byte[] bodyBytes) {
    if (bodyBytes == null || bodyBytes.length == 0) {
      return null;
    }
    try {
      JsonNode node = OBJECT_MAPPER.readTree(bodyBytes);
      if (node != null && node.isObject()) {
        JsonNode errorNode = node.get("error");
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
      }
    } catch (Exception ignored) {
      // 保持脱敏，不打印非法响应
    }
    return null;
  }
}
