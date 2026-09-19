package fun.fengwk.kkstudio.harness.provider.openai.responses;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;

import java.util.Set;

/**
 * OpenAI Responses Provider 持久化配置。
 *
 * <p>负责安全解析 configJson，提取 {@code openAiPromptCacheMode} 显式配置， 并提供对应的 {@link
 * PromptCacheCapability}。严格校验 JSON 语法与已知字段类型， 忽略未知字段，且绝不在异常消息或日志中输出 config 内容或凭据。
 */
public record OpenAiResponsesConfig(OpenAiPromptCacheMode openAiPromptCacheMode) {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final String KEY_PROMPT_CACHE_MODE = "openAiPromptCacheMode";

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public OpenAiResponsesConfig {
    openAiPromptCacheMode =
        openAiPromptCacheMode != null ? openAiPromptCacheMode : OpenAiPromptCacheMode.AUTOMATIC;
  }

  /** 返回默认配置。 */
  public static OpenAiResponsesConfig defaultConfig() {
    return new OpenAiResponsesConfig(OpenAiPromptCacheMode.AUTOMATIC);
  }

  /**
   * 解析持久化配置 JSON 字符串。
   *
   * @param configJson 配置 JSON；允许为 null 或空白
   * @return 解析得到的 OpenAiResponsesConfig
   * @throws IllegalArgumentException 当 JSON 格式非法、非 Object 或已知字段值不受支持时抛出
   */
  public static OpenAiResponsesConfig parse(String configJson) {
    if (configJson == null || configJson.isBlank()) {
      return defaultConfig();
    }
    JsonNode root;
    try {
      root = OBJECT_MAPPER.readTree(configJson);
    } catch (Exception e) {
      throw new IllegalArgumentException("invalid provider config JSON");
    }
    if (root == null || !root.isObject()) {
      throw new IllegalArgumentException("provider config must be a JSON object");
    }

    OpenAiPromptCacheMode mode = OpenAiPromptCacheMode.AUTOMATIC;
    JsonNode modeNode = root.get(KEY_PROMPT_CACHE_MODE);
    if (modeNode != null && !modeNode.isNull()) {
      if (!modeNode.isTextual()) {
        throw new IllegalArgumentException("openAiPromptCacheMode must be a string");
      }
      try {
        mode = OpenAiPromptCacheMode.valueOf(modeNode.asText().trim());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("unsupported openAiPromptCacheMode");
      }
    }

    return new OpenAiResponsesConfig(mode);
  }

  /**
   * 基于配置 JSON 得到对应的 {@link PromptCacheCapability}。
   *
   * @param configJson 持久化配置 JSON
   * @return 对应的缓存能力快照
   */
  public static PromptCacheCapability resolvePromptCacheCapability(String configJson) {
    return parse(configJson).promptCacheCapability();
  }

  /** 返回当前配置对应的 {@link PromptCacheCapability}。 */
  public PromptCacheCapability promptCacheCapability() {
    return switch (openAiPromptCacheMode) {
      case AUTOMATIC -> PromptCacheCapability.automatic();
      case LEGACY -> PromptCacheCapability.affinity(
          Set.of(PromptCacheRetention.SHORT, PromptCacheRetention.LONG));
        // 系统指令是本协议顶层 instructions 字符串，input 中没有可打标的 system 内容块，因此不声明 SYSTEM 断点。
      case GPT_5_6_EXPLICIT -> PromptCacheCapability.breakpoints(
          Set.of(PromptCacheRetention.SHORT), Set.of(PromptCacheBreakpoint.CONVERSATION));
    };
  }
}
