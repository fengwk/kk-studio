package fun.fengwk.kkstudio.harness.provider;

import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.provider.transport.TransportErrorKind;
import fun.fengwk.kkstudio.harness.provider.transport.TransportException;

import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Provider 错误消息格式化通用工具（供各协议子包复用）。
 *
 * <p>按客户端所见即所得契约构建 HTTP 状态错误消息：
 *
 * <ul>
 *   <li>直接保留原始响应正文，格式为 {@code HTTP <status>\n<bodyUtf8>}。
 *   <li>响应体缺失时，回退为 {@code HTTP <status>: <fallback>}（状态码大于 0 时）或纯 {@code <fallback>}。
 *   <li>若底层发生错误正文截断，明确追加截断标记 {@value #TRUNCATION_MARKER}。
 * </ul>
 *
 * <p>SSE error envelope 沿用同一分类结果，message 是完整 envelope 的 JSON 文本；带状态码的协议另行提取并保留结构化状态。
 */
public final class ProviderErrorHelper {

  public static final String TRUNCATION_MARKER = " [TRUNCATED]";

  private ProviderErrorHelper() {}

  /**
   * 判断是否属于无需向上抛出异常的静默传输错误类型。
   *
   * <p>只有调用方主动取消保持静默。执行器拒绝与回调失败都是必须交付的终态，不能在这里全局吞掉。
   */
  public static boolean isSilentTransportKind(TransportErrorKind kind) {
    return kind == TransportErrorKind.CANCELLED;
  }

  /**
   * 仅把 100–599 的 HTTP 状态暴露为结构化元数据；越界返回 null，绝不从消息文本推断状态。3xx 等非预期状态同样保留实际值，供上层明确失败而不是当成无 HTTP 的破损响应。
   *
   * @param statusCode 传输层观察到的 HTTP 状态码
   * @return 100–599 时返回该状态，否则返回 null
   */
  public static Integer httpErrorStatus(int statusCode) {
    return statusCode >= 100 && statusCode <= 599 ? statusCode : null;
  }

  /**
   * 格式化 HTTP 状态码与响应体为错误消息。
   *
   * @param statusCode HTTP 状态码
   * @param bodyUtf8 UTF-8 响应正文
   * @param isTruncated 是否被传输层截断
   * @param fallback 正文缺失时的后备消息
   * @return 格式化后的错误消息
   */
  public static String formatHttpErrorMessage(
      int statusCode, String bodyUtf8, boolean isTruncated, String fallback) {
    if (bodyUtf8 == null || bodyUtf8.isEmpty()) {
      return statusCode > 0 ? "HTTP " + statusCode + ": " + fallback : fallback;
    }
    String message = "HTTP " + statusCode + "\n" + bodyUtf8;
    if (isTruncated) {
      return message + TRUNCATION_MARKER;
    }
    return message;
  }

  /**
   * 基于 TransportException 格式化 HTTP 状态错误消息。
   *
   * @param exception 传输异常
   * @param fallback 正文缺失时的后备消息
   * @return 格式化后的错误消息
   */
  public static String formatHttpErrorMessage(TransportException exception, String fallback) {
    if (exception == null) {
      return fallback;
    }
    return formatHttpErrorMessage(
        exception.statusCode(),
        exception.errorBodyUtf8(),
        exception.isErrorBodyTruncated(),
        fallback);
  }

  /**
   * 基于 HTTP 状态码的通用回退分类。
   *
   * @param status HTTP 状态码
   * @return 分类后的错误类别
   */
  public static ProviderErrorKind classifyHttpStatus(int status) {
    if (status == 401 || status == 403) {
      return ProviderErrorKind.AUTHENTICATION;
    }
    if (status == 402) {
      return ProviderErrorKind.BILLING;
    }
    if (status == 413) {
      return ProviderErrorKind.INVALID_REQUEST;
    }
    if (status == 429 || (status >= 500 && status <= 599)) {
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

  /** 各协议 ErrorMapper 的回退错误文案载体。 */
  public record FallbackMessages(
      String authMessage,
      String billingMessage,
      String overflowMessage,
      String invalidRequestMessage,
      String transientMessage,
      String invalidResponseMessage) {

    public String fallbackForKind(ProviderErrorKind kind) {
      return switch (kind) {
        case AUTHENTICATION -> authMessage;
        case BILLING -> billingMessage;
        case OVERFLOW -> overflowMessage;
        case INVALID_REQUEST -> invalidRequestMessage;
        case TRANSIENT -> transientMessage;
        case INVALID_RESPONSE, CANCELLED, NO_AVAILABLE_MODEL -> invalidResponseMessage;
      };
    }
  }

  /**
   * 将传输层异常转换为 {@link ProviderException} 的通用分类骨架。
   *
   * @param exception 传输层异常
   * @param providerPrefix 厂商名称前缀（如 "Anthropic", "OpenAI"）
   * @param httpClassifier HTTP_STATUS 异常的厂商特有分类逻辑
   * @param fallbacks 回退文案载体
   * @return 映射后的 ProviderException；仅 CANCELLED 返回 null
   */
  public static ProviderException mapTransportException(
      TransportException exception,
      String providerPrefix,
      Function<TransportException, ProviderErrorKind> httpClassifier,
      FallbackMessages fallbacks) {
    if (exception == null) {
      return new ProviderException(ProviderErrorKind.TRANSIENT, fallbacks.transientMessage());
    }
    if (isSilentTransportKind(exception.kind())) {
      return null;
    }
    if (exception.kind() == TransportErrorKind.TIMEOUT) {
      return new ProviderException(
          ProviderErrorKind.TRANSIENT, providerPrefix + " request timed out");
    }
    if (exception.kind() == TransportErrorKind.IO) {
      return new ProviderException(ProviderErrorKind.TRANSIENT, providerPrefix + " I/O error");
    }
    if (exception.kind() == TransportErrorKind.INVALID_RESPONSE) {
      return new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, fallbacks.invalidResponseMessage());
    }
    if (exception.kind() == TransportErrorKind.HTTP_STATUS) {
      int status = exception.statusCode();
      ProviderErrorKind kind = httpClassifier.apply(exception);
      String fallback = fallbacks.fallbackForKind(kind);
      String message = formatHttpErrorMessage(exception, fallback);
      return new ProviderException(kind, message, httpErrorStatus(status));
    }
    return new ProviderException(ProviderErrorKind.TRANSIENT, fallbacks.transientMessage());
  }

  /**
   * 将不带 HTTP 状态的 SSE error envelope 进行分类映射。
   *
   * @param errorEventNode SSE error 事件 JSON 节点
   * @param classifier 厂商特有的节点分类逻辑
   * @param invalidResponseMessage 节点为空时的回退文案
   * @return 映射后的 ProviderException
   */
  public static ProviderException mapSseErrorEnvelope(
      JsonNode errorEventNode,
      Function<JsonNode, ProviderErrorKind> classifier,
      String invalidResponseMessage) {
    return mapSseErrorEnvelope(errorEventNode, classifier, node -> 0, invalidResponseMessage);
  }

  /**
   * 将 SSE error envelope 进行分类映射的通用骨架。
   *
   * @param errorEventNode SSE error 事件 JSON 节点
   * @param classifier 厂商特有的节点分类逻辑
   * @param statusCodeExtractor 从 envelope 提取 HTTP 状态码；无状态协议返回 0
   * @param invalidResponseMessage 节点为空时的回退文案
   * @return 映射后的 ProviderException
   */
  public static ProviderException mapSseErrorEnvelope(
      JsonNode errorEventNode,
      Function<JsonNode, ProviderErrorKind> classifier,
      ToIntFunction<JsonNode> statusCodeExtractor,
      String invalidResponseMessage) {
    if (errorEventNode == null) {
      return new ProviderException(ProviderErrorKind.INVALID_RESPONSE, invalidResponseMessage);
    }
    if (!errorEventNode.isObject()) {
      return new ProviderException(ProviderErrorKind.INVALID_RESPONSE, errorEventNode.toString());
    }
    int statusCode = statusCodeExtractor.applyAsInt(errorEventNode);
    ProviderErrorKind kind = classifier.apply(errorEventNode);
    return new ProviderException(kind, errorEventNode.toString(), httpErrorStatus(statusCode));
  }
}
