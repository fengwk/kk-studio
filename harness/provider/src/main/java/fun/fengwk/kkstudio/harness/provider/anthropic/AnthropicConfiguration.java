package fun.fengwk.kkstudio.harness.provider.anthropic;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;

/**
 * Anthropic Messages 适配器持久化配置。
 *
 * <p>支持解析 {@code anthropicThinkingMode}（取值为 {@code ADAPTIVE} 或 {@code BUDGET}）。 严格校验 JSON
 * 语法与已知字段类型，忽略未知字段，且绝不在异常消息或日志中输出 config 内容或凭据。
 */
public record AnthropicConfiguration(AnthropicThinkingMode anthropicThinkingMode) {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  public static final String FIELD_ANTHROPIC_THINKING_MODE = "anthropicThinkingMode";

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public AnthropicConfiguration {
    anthropicThinkingMode =
        anthropicThinkingMode != null ? anthropicThinkingMode : AnthropicThinkingMode.ADAPTIVE;
  }

  /** 返回默认配置（ADAPTIVE 模式）。 */
  public static AnthropicConfiguration defaults() {
    return new AnthropicConfiguration(AnthropicThinkingMode.ADAPTIVE);
  }

  /**
   * 解析持久化配置 JSON 字符串。
   *
   * @param configJson 配置 JSON；允许为 null 或空白
   * @return 解析得到的 AnthropicConfiguration
   * @throws ProviderException 当 JSON 格式非法、非 Object 或已知字段值不受支持时抛出（不保留外部 cause 且不回显配置内容）
   */
  public static AnthropicConfiguration parse(String configJson) {
    if (configJson == null || configJson.isBlank()) {
      return defaults();
    }
    JsonNode root;
    try {
      root = OBJECT_MAPPER.readTree(configJson);
    } catch (JsonProcessingException exception) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "invalid Anthropic configuration JSON");
    }
    if (root == null || !root.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "Anthropic configuration must be a JSON object");
    }

    AnthropicThinkingMode mode = AnthropicThinkingMode.ADAPTIVE;
    if (root.has(FIELD_ANTHROPIC_THINKING_MODE)) {
      JsonNode node = root.get(FIELD_ANTHROPIC_THINKING_MODE);
      if (!node.isTextual()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "field " + FIELD_ANTHROPIC_THINKING_MODE + " must be a string");
      }
      String text = node.textValue().trim();
      try {
        mode = AnthropicThinkingMode.valueOf(text);
      } catch (IllegalArgumentException ex) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "unsupported anthropicThinkingMode value");
      }
    }

    return new AnthropicConfiguration(mode);
  }
}
