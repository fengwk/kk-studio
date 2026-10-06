package fun.fengwk.kkstudio.harness.provider.openai.chat;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;

import java.util.Objects;

/**
 * OpenAI Chat Completions 适配器配置解析与校验对象。
 *
 * <p>已知字段严格类型与取值校验，未知字段安全忽略，禁止向外泄露原始配置内容。提示缓存只由一个与运行时一致的留存档位字段 {@code promptCacheRetention}（{@link
 * PromptCacheRetention}）表达，不再有协议私有的 capability/policy/mode；缓存 key 由运行时按 Session 提供。
 */
public final class OpenAiChatConfiguration {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public static final String FIELD_INCLUDE_USAGE = "openAiChatIncludeUsage";
  public static final String FIELD_REQUIRE_DONE = "openAiChatRequireDone";
  public static final String FIELD_PROMPT_CACHE_RETENTION = "promptCacheRetention";
  public static final String FIELD_THINKING_FORMAT = "openAiChatThinkingFormat";

  public enum ThinkingFormat {
    STANDARD,
    DEEPSEEK
  }

  private final boolean includeUsage;
  private final boolean requireDone;
  private final PromptCacheRetention promptCacheRetention;
  private final ThinkingFormat thinkingFormat;

  public OpenAiChatConfiguration(
      boolean includeUsage, boolean requireDone, PromptCacheRetention promptCacheRetention) {
    this(includeUsage, requireDone, promptCacheRetention, ThinkingFormat.STANDARD);
  }

  public OpenAiChatConfiguration(
      boolean includeUsage,
      boolean requireDone,
      PromptCacheRetention promptCacheRetention,
      ThinkingFormat thinkingFormat) {
    this.includeUsage = includeUsage;
    this.requireDone = requireDone;
    this.promptCacheRetention =
        promptCacheRetention == null ? PromptCacheRetention.NONE : promptCacheRetention;
    this.thinkingFormat = thinkingFormat == null ? ThinkingFormat.STANDARD : thinkingFormat;
  }

  public static OpenAiChatConfiguration defaults() {
    return new OpenAiChatConfiguration(true, true, PromptCacheRetention.NONE);
  }

  public static OpenAiChatConfiguration parse(String configJson) {
    if (configJson == null || configJson.isBlank()) {
      return defaults();
    }
    JsonNode root;
    try {
      root = OBJECT_MAPPER.readTree(configJson);
    } catch (JsonProcessingException exception) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "invalid OpenAI chat configuration JSON");
    }
    if (!root.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "OpenAI chat configuration must be a JSON object");
    }

    boolean includeUsage = true;
    if (root.has(FIELD_INCLUDE_USAGE)) {
      JsonNode node = root.get(FIELD_INCLUDE_USAGE);
      if (!node.isBoolean()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "field " + FIELD_INCLUDE_USAGE + " must be a boolean");
      }
      includeUsage = node.booleanValue();
    }

    boolean requireDone = true;
    if (root.has(FIELD_REQUIRE_DONE)) {
      JsonNode node = root.get(FIELD_REQUIRE_DONE);
      if (!node.isBoolean()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "field " + FIELD_REQUIRE_DONE + " must be a boolean");
      }
      requireDone = node.booleanValue();
    }

    PromptCacheRetention promptCacheRetention = PromptCacheRetention.NONE;
    if (root.has(FIELD_PROMPT_CACHE_RETENTION)) {
      JsonNode node = root.get(FIELD_PROMPT_CACHE_RETENTION);
      if (!node.isTextual()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "field " + FIELD_PROMPT_CACHE_RETENTION + " must be a string");
      }
      String text = node.textValue().trim();
      try {
        promptCacheRetention = PromptCacheRetention.valueOf(text);
      } catch (IllegalArgumentException ex) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "unsupported value in " + FIELD_PROMPT_CACHE_RETENTION);
      }
    }

    ThinkingFormat thinkingFormat = ThinkingFormat.STANDARD;
    if (root.has(FIELD_THINKING_FORMAT)) {
      JsonNode node = root.get(FIELD_THINKING_FORMAT);
      if (!node.isTextual()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "field " + FIELD_THINKING_FORMAT + " must be a string");
      }
      String text = node.textValue().trim();
      try {
        thinkingFormat = ThinkingFormat.valueOf(text);
      } catch (IllegalArgumentException ex) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "unsupported thinking format in " + FIELD_THINKING_FORMAT);
      }
    }

    return new OpenAiChatConfiguration(
        includeUsage, requireDone, promptCacheRetention, thinkingFormat);
  }

  public boolean includeUsage() {
    return includeUsage;
  }

  public boolean requireDone() {
    return requireDone;
  }

  public PromptCacheRetention promptCacheRetention() {
    return promptCacheRetention;
  }

  public ThinkingFormat thinkingFormat() {
    return thinkingFormat;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof OpenAiChatConfiguration that)) {
      return false;
    }
    return includeUsage == that.includeUsage
        && requireDone == that.requireDone
        && promptCacheRetention == that.promptCacheRetention
        && thinkingFormat == that.thinkingFormat;
  }

  @Override
  public int hashCode() {
    return Objects.hash(includeUsage, requireDone, promptCacheRetention, thinkingFormat);
  }

  @Override
  public String toString() {
    return "OpenAiChatConfiguration[includeUsage="
        + includeUsage
        + ", requireDone="
        + requireDone
        + ", promptCacheRetention="
        + promptCacheRetention
        + ", thinkingFormat="
        + thinkingFormat
        + "]";
  }
}
