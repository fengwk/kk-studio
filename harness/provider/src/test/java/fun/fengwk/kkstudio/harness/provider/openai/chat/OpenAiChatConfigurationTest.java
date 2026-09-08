package fun.fengwk.kkstudio.harness.provider.openai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheMode;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;

import java.util.Set;

/** 测试意图：验证 OpenAI Chat 配置对象的解析、默认值、严格类型校验与 PromptCacheCapability 派发。 */
class OpenAiChatConfigurationTest {

  @Test
  @DisplayName("null 或空白配置返回默认配置")
  void parseDefaults() {
    OpenAiChatConfiguration config1 = OpenAiChatConfiguration.parse(null);
    assertTrue(config1.includeUsage());
    assertTrue(config1.requireDone());
    assertTrue(config1.mediaTypes().isEmpty());
    assertEquals(OpenAiChatConfiguration.PromptCacheMode.AUTOMATIC, config1.promptCacheMode());
    assertEquals(PromptCacheMode.AUTOMATIC, config1.promptCacheCapability().mode());

    OpenAiChatConfiguration config2 = OpenAiChatConfiguration.parse("   ");
    assertEquals(config1, config2);
  }

  @Test
  @DisplayName("显式配置已知字段与忽略未知字段")
  void parseExplicitFieldsAndIgnoreUnknown() {
    String json =
        """
        {
          "openAiChatIncludeUsage": false,
          "openAiChatRequireDone": false,
          "openAiChatMediaTypes": ["IMAGE", "PDF"],
          "openAiPromptCacheMode": "GPT_5_6_EXPLICIT",
          "unknownField": "should_be_ignored",
          "extraObject": {"k": 1}
        }
        """;
    OpenAiChatConfiguration config = OpenAiChatConfiguration.parse(json);
    assertFalse(config.includeUsage());
    assertFalse(config.requireDone());
    assertEquals(
        Set.of(OpenAiChatConfiguration.MediaType.IMAGE, OpenAiChatConfiguration.MediaType.PDF),
        config.mediaTypes());
    assertEquals(
        OpenAiChatConfiguration.PromptCacheMode.GPT_5_6_EXPLICIT, config.promptCacheMode());

    PromptCacheCapability cap = config.promptCacheCapability();
    assertEquals(PromptCacheMode.BREAKPOINTS, cap.mode());
    assertEquals(Set.of(PromptCacheRetention.SHORT), cap.supportedRetentions());
    assertEquals(
        Set.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.CONVERSATION),
        cap.supportedBreakpoints());
  }

  @Test
  @DisplayName("LEGACY 模式 capability 包含 AFFINITY 与 SHORT+LONG")
  void parseLegacyPromptCacheMode() {
    String json = "{\"openAiPromptCacheMode\": \"LEGACY\"}";
    OpenAiChatConfiguration config = OpenAiChatConfiguration.parse(json);
    assertEquals(OpenAiChatConfiguration.PromptCacheMode.LEGACY, config.promptCacheMode());
    PromptCacheCapability cap = config.promptCacheCapability();
    assertEquals(PromptCacheMode.AFFINITY, cap.mode());
    assertEquals(
        Set.of(PromptCacheRetention.SHORT, PromptCacheRetention.LONG), cap.supportedRetentions());
    assertTrue(cap.supportedBreakpoints().isEmpty());
  }

  @Test
  @DisplayName("非法 JSON 格式拒绝")
  void rejectInvalidJson() {
    assertThrows(ProviderException.class, () -> OpenAiChatConfiguration.parse("not json"));
    assertThrows(ProviderException.class, () -> OpenAiChatConfiguration.parse("[\"array\"]"));
  }

  @Test
  @DisplayName("字段类型不匹配严格拒绝")
  void rejectInvalidFieldTypes() {
    assertThrows(
        ProviderException.class,
        () -> OpenAiChatConfiguration.parse("{\"openAiChatIncludeUsage\": \"true\"}"));
    assertThrows(
        ProviderException.class,
        () -> OpenAiChatConfiguration.parse("{\"openAiChatRequireDone\": 1}"));
    assertThrows(
        ProviderException.class,
        () -> OpenAiChatConfiguration.parse("{\"openAiChatMediaTypes\": \"IMAGE\"}"));
    assertThrows(
        ProviderException.class,
        () -> OpenAiChatConfiguration.parse("{\"openAiChatMediaTypes\": [123]}"));
    assertThrows(
        ProviderException.class,
        () -> OpenAiChatConfiguration.parse("{\"openAiPromptCacheMode\": true}"));
  }

  @Test
  @DisplayName("不支持的枚举值严格拒绝且不泄露敏感配置内容")
  void rejectUnsupportedEnumValuesWithoutEcho() {
    ProviderException exMedia =
        assertThrows(
            ProviderException.class,
            () ->
                OpenAiChatConfiguration.parse(
                    "{\"openAiChatMediaTypes\": [\"SUPER_SECRET_MEDIA\"]}"));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exMedia.kind());
    assertFalse(exMedia.getMessage().contains("SUPER_SECRET_MEDIA"));
    assertNull(exMedia.getCause());

    ProviderException exCache =
        assertThrows(
            ProviderException.class,
            () ->
                OpenAiChatConfiguration.parse(
                    "{\"openAiPromptCacheMode\": \"SUPER_SECRET_CACHE\"]}"));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exCache.kind());
    assertNull(exCache.getCause());

    ProviderException exCacheVal =
        assertThrows(
            ProviderException.class,
            () ->
                OpenAiChatConfiguration.parse(
                    "{\"openAiPromptCacheMode\": \"SUPER_SECRET_CACHE\"}"));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exCacheVal.kind());
    assertFalse(exCacheVal.getMessage().contains("SUPER_SECRET_CACHE"));
    assertNull(exCacheVal.getCause());

    ProviderException exThinking =
        assertThrows(
            ProviderException.class,
            () ->
                OpenAiChatConfiguration.parse(
                    "{\"openAiChatThinkingFormat\": \"SUPER_SECRET_THINKING\"}"));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exThinking.kind());
    assertFalse(exThinking.getMessage().contains("SUPER_SECRET_THINKING"));
    assertNull(exThinking.getCause());
  }

  @Test
  @DisplayName("openAiChatThinkingFormat 解析与默认值测试")
  void parseThinkingFormat() {
    OpenAiChatConfiguration configDefault = OpenAiChatConfiguration.defaults();
    assertEquals(OpenAiChatConfiguration.ThinkingFormat.STANDARD, configDefault.thinkingFormat());
    assertEquals(
        OpenAiChatConfiguration.ThinkingFormat.STANDARD, configDefault.openAiChatThinkingFormat());

    OpenAiChatConfiguration configDeepseek =
        OpenAiChatConfiguration.parse("{\"openAiChatThinkingFormat\": \"DEEPSEEK\"}");
    assertEquals(OpenAiChatConfiguration.ThinkingFormat.DEEPSEEK, configDeepseek.thinkingFormat());

    OpenAiChatConfiguration configStandard =
        OpenAiChatConfiguration.parse("{\"openAiChatThinkingFormat\": \"STANDARD\"}");
    assertEquals(OpenAiChatConfiguration.ThinkingFormat.STANDARD, configStandard.thinkingFormat());

    ProviderException exType =
        assertThrows(
            ProviderException.class,
            () -> OpenAiChatConfiguration.parse("{\"openAiChatThinkingFormat\": 123}"));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exType.kind());
    assertNull(exType.getCause());
  }

  @Test
  @DisplayName("toString 保护与 hashCode/equals")
  void testToStringAndEquals() {
    OpenAiChatConfiguration c1 = OpenAiChatConfiguration.defaults();
    OpenAiChatConfiguration c2 = OpenAiChatConfiguration.parse("{}");
    assertEquals(c1, c2);
    assertEquals(c1.hashCode(), c2.hashCode());
    assertNotNull(c1.toString());
  }
}
