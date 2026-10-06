package fun.fengwk.kkstudio.harness.provider.openai.responses;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;

/**
 * OpenAI Responses Provider 持久化配置。
 *
 * <p>只表达一个稳定事实：本 Provider 的提示缓存留存档位 {@link PromptCacheRetention}。缺省为 {@link
 * PromptCacheRetention#NONE}，即不下发任何 cache hint；{@code SHORT}/{@code LONG} 由适配器映射为协议 {@code
 * prompt_cache_retention} 值。严格校验 JSON 语法与字段类型，忽略未知字段，且绝不在异常消息或日志中输出 config 内容或凭据。
 */
public record OpenAiResponsesConfig(PromptCacheRetention promptCacheRetention) {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final String KEY_PROMPT_CACHE_RETENTION = "promptCacheRetention";

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public OpenAiResponsesConfig {
    promptCacheRetention =
        promptCacheRetention != null ? promptCacheRetention : PromptCacheRetention.NONE;
  }

  /** 返回默认配置：不启用 Provider 端提示缓存。 */
  public static OpenAiResponsesConfig defaultConfig() {
    return new OpenAiResponsesConfig(PromptCacheRetention.NONE);
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

    PromptCacheRetention retention = PromptCacheRetention.NONE;
    JsonNode retentionNode = root.get(KEY_PROMPT_CACHE_RETENTION);
    if (retentionNode != null && !retentionNode.isNull()) {
      if (!retentionNode.isTextual()) {
        throw new IllegalArgumentException("promptCacheRetention must be a string");
      }
      try {
        retention = PromptCacheRetention.valueOf(retentionNode.asText().trim());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("unsupported promptCacheRetention");
      }
    }

    return new OpenAiResponsesConfig(retention);
  }

  /**
   * 基于配置 JSON 解析提示缓存留存档位。
   *
   * @param configJson 持久化配置 JSON
   * @return 对应的 PromptCacheRetention
   */
  public static PromptCacheRetention resolvePromptCacheRetention(String configJson) {
    return parse(configJson).promptCacheRetention();
  }
}
