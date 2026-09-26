package fun.fengwk.kkstudio.harness.provider.openai.chat;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * OpenAI Chat Completions 适配器配置解析与校验对象。
 *
 * <p>已知字段严格类型与取值校验，未知字段安全忽略，禁止向外泄露原始配置内容。
 */
public final class OpenAiChatConfiguration {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public static final String FIELD_INCLUDE_USAGE = "openAiChatIncludeUsage";
  public static final String FIELD_REQUIRE_DONE = "openAiChatRequireDone";
  public static final String FIELD_PROMPT_CACHE_MODE = "openAiPromptCacheMode";
  public static final String FIELD_THINKING_FORMAT = "openAiChatThinkingFormat";
  public static final String FIELD_OPENAI_CHAT_THINKING_FORMAT = FIELD_THINKING_FORMAT;

  public enum PromptCacheMode {
    AUTOMATIC,
    LEGACY,
    GPT_5_6_EXPLICIT
  }

  public enum ThinkingFormat {
    STANDARD,
    DEEPSEEK
  }

  private final boolean includeUsage;
  private final boolean requireDone;
  private final PromptCacheMode promptCacheMode;
  private final ThinkingFormat thinkingFormat;

  public OpenAiChatConfiguration(
      boolean includeUsage, boolean requireDone, PromptCacheMode promptCacheMode) {
    this(includeUsage, requireDone, promptCacheMode, ThinkingFormat.STANDARD);
  }

  public OpenAiChatConfiguration(
      boolean includeUsage,
      boolean requireDone,
      PromptCacheMode promptCacheMode,
      ThinkingFormat thinkingFormat) {
    this.includeUsage = includeUsage;
    this.requireDone = requireDone;
    this.promptCacheMode = promptCacheMode == null ? PromptCacheMode.AUTOMATIC : promptCacheMode;
    this.thinkingFormat = thinkingFormat == null ? ThinkingFormat.STANDARD : thinkingFormat;
  }

  public static OpenAiChatConfiguration defaults() {
    return new OpenAiChatConfiguration(
        true, true, PromptCacheMode.AUTOMATIC, ThinkingFormat.STANDARD);
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

    PromptCacheMode cacheMode = PromptCacheMode.AUTOMATIC;
    if (root.has(FIELD_PROMPT_CACHE_MODE)) {
      JsonNode node = root.get(FIELD_PROMPT_CACHE_MODE);
      if (!node.isTextual()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "field " + FIELD_PROMPT_CACHE_MODE + " must be a string");
      }
      String text = node.textValue().trim();
      try {
        cacheMode = PromptCacheMode.valueOf(text);
      } catch (IllegalArgumentException ex) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "unsupported prompt cache mode in " + FIELD_PROMPT_CACHE_MODE);
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

    return new OpenAiChatConfiguration(includeUsage, requireDone, cacheMode, thinkingFormat);
  }

  public boolean includeUsage() {
    return includeUsage;
  }

  public boolean requireDone() {
    return requireDone;
  }

  public PromptCacheMode promptCacheMode() {
    return promptCacheMode;
  }

  public ThinkingFormat thinkingFormat() {
    return thinkingFormat;
  }

  public ThinkingFormat openAiChatThinkingFormat() {
    return thinkingFormat;
  }

  public PromptCacheCapability promptCacheCapability() {
    return switch (promptCacheMode) {
      case AUTOMATIC -> PromptCacheCapability.automatic();
      case LEGACY -> PromptCacheCapability.affinity(
          Set.of(PromptCacheRetention.SHORT, PromptCacheRetention.LONG));
      case GPT_5_6_EXPLICIT -> PromptCacheCapability.breakpoints(
          Set.of(PromptCacheRetention.SHORT),
          EnumSet.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.CONVERSATION));
    };
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
        && promptCacheMode == that.promptCacheMode
        && thinkingFormat == that.thinkingFormat;
  }

  @Override
  public int hashCode() {
    return Objects.hash(includeUsage, requireDone, promptCacheMode, thinkingFormat);
  }

  @Override
  public String toString() {
    return "OpenAiChatConfiguration[includeUsage="
        + includeUsage
        + ", requireDone="
        + requireDone
        + ", promptCacheMode="
        + promptCacheMode
        + ", thinkingFormat="
        + thinkingFormat
        + "]";
  }
}
