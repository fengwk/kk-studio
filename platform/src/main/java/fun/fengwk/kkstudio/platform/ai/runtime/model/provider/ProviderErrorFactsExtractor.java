package fun.fengwk.kkstudio.platform.ai.runtime.model.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.exception.HttpException;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ContextPressureFacts;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.util.Objects;

/**
 * 从 Provider 异常链抽取 {@link ContextPressureFacts} 的集中提取器。
 *
 * <p>只依赖 LangChain4j 公共类型（{@link HttpException#statusCode()}）与异常消息中的标准 JSON body，不绑定任何单个 Provider
 * 的私有类。最多遍历 8 层 cause：优先把首个 {@link HttpException} 的 status 作为 {@code httpStatus}；从消息内嵌 JSON 的
 * {@code error.code} / {@code error.type} 抽取 error code/type；拼接整条 cause 链的非空消息作为 {@code
 * errorMessage}。抽取失败一律降级为 null，绝不抛出。
 */
public final class ProviderErrorFactsExtractor {

  static final int MAX_CAUSE_DEPTH = 8;

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private ProviderErrorFactsExtractor() {}

  /** 抽取异常链的 context-pressure 事实；{@code providerType} 必须非空。 */
  public static ContextPressureFacts extract(ProviderType providerType, Throwable error) {
    Objects.requireNonNull(providerType, "providerType");
    Integer httpStatus = null;
    String codeOrType = null;
    StringBuilder messages = new StringBuilder();
    Throwable current = error;
    for (int depth = 0;
        current != null && depth < MAX_CAUSE_DEPTH;
        depth++, current = current.getCause()) {
      if (httpStatus == null && current instanceof HttpException httpException) {
        httpStatus = httpException.statusCode();
      }
      if (codeOrType == null) {
        codeOrType = extractErrorCodeOrType(current.getMessage());
      }
      if (current.getMessage() != null) {
        if (messages.length() > 0) {
          messages.append(' ');
        }
        messages.append(current.getMessage());
      }
    }
    return new ContextPressureFacts(
        providerType,
        httpStatus,
        codeOrType,
        messages.length() == 0 ? null : messages.toString(),
        null,
        null,
        null);
  }

  private static String extractErrorCodeOrType(String message) {
    if (message == null) {
      return null;
    }
    int brace = message.indexOf('{');
    if (brace < 0) {
      return null;
    }
    JsonNode root;
    try {
      root = OBJECT_MAPPER.readTree(message.substring(brace));
    } catch (JsonProcessingException malformed) {
      return null;
    }
    if (!root.isObject()) {
      return null;
    }
    JsonNode error = root.findValue("error");
    if (error == null || !error.isObject()) {
      return null;
    }
    String code = text(error.get("code"));
    if (code != null) {
      return code;
    }
    return text(error.get("type"));
  }

  private static String text(JsonNode node) {
    if (node == null || !node.isTextual()) {
      return null;
    }
    String value = node.asText();
    return value.isBlank() ? null : value;
  }
}
