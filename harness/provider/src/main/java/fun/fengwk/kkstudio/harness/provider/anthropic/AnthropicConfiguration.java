package fun.fengwk.kkstudio.harness.provider.anthropic;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Anthropic Messages 适配器持久化配置。
 *
 * <p>支持解析 {@code anthropicThinkingMode}（取值为 {@code ADAPTIVE} 或 {@code BUDGET}） 以及可选的 {@code
 * modelAliases}（logical model name -> wire model id）。 严格校验 JSON 语法与已知字段类型，忽略未知字段，且绝不在异常消息或日志中输出
 * config 内容或凭据。
 */
public record AnthropicConfiguration(
    AnthropicThinkingMode anthropicThinkingMode, Map<String, String> modelAliases) {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  public static final String FIELD_ANTHROPIC_THINKING_MODE = "anthropicThinkingMode";
  public static final String FIELD_MODEL_ALIASES = "modelAliases";

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public AnthropicConfiguration {
    anthropicThinkingMode =
        anthropicThinkingMode != null ? anthropicThinkingMode : AnthropicThinkingMode.ADAPTIVE;
    if (modelAliases == null || modelAliases.isEmpty()) {
      modelAliases = Map.of();
    } else {
      for (Map.Entry<String, String> entry : modelAliases.entrySet()) {
        String key = entry.getKey();
        String val = entry.getValue();
        if (key == null || key.isBlank() || !key.strip().equals(key)) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "field "
                  + FIELD_MODEL_ALIASES
                  + " keys must be non-empty strings without surrounding whitespace");
        }
        if (val == null || val.isBlank() || !val.strip().equals(val)) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "field "
                  + FIELD_MODEL_ALIASES
                  + " values must be non-empty strings without surrounding whitespace");
        }
      }
      modelAliases = Map.copyOf(modelAliases);
    }
  }

  public AnthropicConfiguration(AnthropicThinkingMode anthropicThinkingMode) {
    this(anthropicThinkingMode, Map.of());
  }

  /** 返回默认配置（ADAPTIVE 模式，空 modelAliases）。 */
  public static AnthropicConfiguration defaults() {
    return new AnthropicConfiguration(AnthropicThinkingMode.ADAPTIVE, Map.of());
  }

  /**
   * 将逻辑模型名解析为 wire 模型标识。
   *
   * @param logicalModelName 逻辑模型名称
   * @return 若存在别名映射则返回 wire 模型标识，否则返回原逻辑模型名称
   */
  public String resolveModelName(String logicalModelName) {
    if (logicalModelName == null) {
      return null;
    }
    return modelAliases.getOrDefault(logicalModelName, logicalModelName);
  }

  /** 避免配置对象进入诊断上下文时回显任意 model alias key/value。 */
  @Override
  public String toString() {
    return "AnthropicConfiguration[anthropicThinkingMode="
        + anthropicThinkingMode
        + ", modelAliasesSize="
        + modelAliases.size()
        + "]";
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

    Map<String, String> modelAliases = Map.of();
    if (root.has(FIELD_MODEL_ALIASES)) {
      JsonNode node = root.get(FIELD_MODEL_ALIASES);
      if (!node.isObject()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "field " + FIELD_MODEL_ALIASES + " must be a JSON object");
      }
      Map<String, String> aliases = new LinkedHashMap<>();
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> entry = fields.next();
        String key = entry.getKey();
        if (key.isBlank() || !key.strip().equals(key)) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "field "
                  + FIELD_MODEL_ALIASES
                  + " keys must be non-empty strings without surrounding whitespace");
        }
        JsonNode valueNode = entry.getValue();
        if (!valueNode.isTextual()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "field " + FIELD_MODEL_ALIASES + " values must be strings");
        }
        String val = valueNode.textValue();
        if (val.isBlank() || !val.strip().equals(val)) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "field "
                  + FIELD_MODEL_ALIASES
                  + " values must be non-empty strings without surrounding whitespace");
        }
        aliases.put(key, val);
      }
      modelAliases = aliases;
    }

    return new AnthropicConfiguration(mode, modelAliases);
  }
}
