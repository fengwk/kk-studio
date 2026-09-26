package fun.fengwk.kkstudio.harness.provider.anthropic;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Anthropic Messages 适配器持久化配置。
 *
 * <p>支持解析 {@code anthropicThinkingMode}（取值为 {@code ADAPTIVE} 或 {@code BUDGET}）与 {@code
 * anthropicBetaFeatures}（有序且唯一的 {@code anthropic-beta} 能力标识数组）。严格校验 JSON 语法、已知字段类型与 beta
 * 标识取值，忽略未知字段，且绝不在异常消息或日志中输出 config 内容或凭据。
 *
 * <p>wire 模型标识不在此处配置：请求根字段 {@code model} 直接取 {@code ModelDescriptor.modelId()}。
 */
public record AnthropicConfiguration(
    AnthropicThinkingMode anthropicThinkingMode, List<String> anthropicBetaFeatures) {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  public static final String FIELD_ANTHROPIC_THINKING_MODE = "anthropicThinkingMode";
  public static final String FIELD_ANTHROPIC_BETA_FEATURES = "anthropicBetaFeatures";

  /** 运行时强制的官方 interleaved thinking 能力标识：BUDGET 思考实际启用时由编码器并集附加，无法由配置关闭。 */
  public static final String INTERLEAVED_THINKING_BETA = "interleaved-thinking-2025-05-14";

  /**
   * beta 标识合法字符集：官方能力标识只由字母、数字、点、下划线与连字符组成（如 {@code
   * interleaved-thinking-2025-05-14}），天然排除逗号、空白、冒号与控制字符，因此单个标识无法注入额外的头字段分隔符。
   */
  private static final Pattern BETA_TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");

  private static final int MAX_BETA_FEATURE_COUNT = 8;
  private static final int MAX_BETA_FEATURE_LENGTH = 64;
  private static final int MAX_BETA_HEADER_LENGTH = 512;

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public AnthropicConfiguration {
    anthropicThinkingMode =
        anthropicThinkingMode != null ? anthropicThinkingMode : AnthropicThinkingMode.ADAPTIVE;
    anthropicBetaFeatures = normalizeBetaFeatures(anthropicBetaFeatures);
  }

  /** 仅指定思考模式的便捷构造：不声明任何自定义 beta 能力。 */
  public AnthropicConfiguration(AnthropicThinkingMode anthropicThinkingMode) {
    this(anthropicThinkingMode, List.of());
  }

  /** 返回默认配置（ADAPTIVE 模式且无自定义 beta 能力）。 */
  public static AnthropicConfiguration defaults() {
    return new AnthropicConfiguration(AnthropicThinkingMode.ADAPTIVE, List.of());
  }

  /**
   * 解析持久化配置 JSON 字符串。
   *
   * @param configJson 配置 JSON；允许为 null 或空白
   * @return 解析得到的 AnthropicConfiguration
   * @throws ProviderException 当 JSON 格式非法、非 Object、已知字段类型或取值不受支持时抛出（不保留外部 cause 且不回显配置内容）
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

    return new AnthropicConfiguration(
        parseThinkingMode(root), parseBetaFeatures(root.get(FIELD_ANTHROPIC_BETA_FEATURES)));
  }

  private static AnthropicThinkingMode parseThinkingMode(JsonNode root) {
    if (!root.has(FIELD_ANTHROPIC_THINKING_MODE)) {
      return AnthropicThinkingMode.ADAPTIVE;
    }
    JsonNode node = root.get(FIELD_ANTHROPIC_THINKING_MODE);
    if (!node.isTextual()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "field " + FIELD_ANTHROPIC_THINKING_MODE + " must be a string");
    }
    try {
      return AnthropicThinkingMode.valueOf(node.textValue().trim());
    } catch (IllegalArgumentException ex) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "unsupported anthropicThinkingMode value");
    }
  }

  /** 只校验 JSON 类型，标识取值约束统一由 {@link #normalizeBetaFeatures(List)} 在唯一构造入口强制。 */
  private static List<String> parseBetaFeatures(JsonNode node) {
    if (node == null || node.isNull()) {
      return List.of();
    }
    if (!node.isArray()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "field " + FIELD_ANTHROPIC_BETA_FEATURES + " must be a JSON array");
    }
    List<String> features = new ArrayList<>(node.size());
    for (JsonNode item : node) {
      if (!item.isTextual()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            FIELD_ANTHROPIC_BETA_FEATURES + " entries must be strings");
      }
      features.add(item.textValue());
    }
    return features;
  }

  /** 强制 beta 标识的顺序、唯一性与可安全写入 HTTP 头的取值域；错误只报告规则，绝不回显标识内容。 */
  private static List<String> normalizeBetaFeatures(List<String> features) {
    if (features == null || features.isEmpty()) {
      return List.of();
    }
    if (features.size() > MAX_BETA_FEATURE_COUNT) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          FIELD_ANTHROPIC_BETA_FEATURES
              + " must contain at most "
              + MAX_BETA_FEATURE_COUNT
              + " entries");
    }
    List<String> normalized = new ArrayList<>(features.size());
    for (String feature : features) {
      if (feature == null || feature.isBlank() || !BETA_TOKEN_PATTERN.matcher(feature).matches()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            FIELD_ANTHROPIC_BETA_FEATURES + " entries must be non-blank safe header tokens");
      }
      if (feature.length() > MAX_BETA_FEATURE_LENGTH) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            FIELD_ANTHROPIC_BETA_FEATURES
                + " entries must not exceed "
                + MAX_BETA_FEATURE_LENGTH
                + " characters");
      }
      if (normalized.contains(feature)) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            FIELD_ANTHROPIC_BETA_FEATURES + " entries must be unique");
      }
      normalized.add(feature);
    }
    if (String.join(",", normalized).length() > MAX_BETA_HEADER_LENGTH) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          FIELD_ANTHROPIC_BETA_FEATURES
              + " combined length must not exceed "
              + MAX_BETA_HEADER_LENGTH
              + " characters");
    }
    return List.copyOf(normalized);
  }
}
