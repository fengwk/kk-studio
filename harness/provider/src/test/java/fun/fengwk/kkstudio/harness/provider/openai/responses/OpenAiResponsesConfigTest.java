package fun.fengwk.kkstudio.harness.provider.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheMode;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;

/** 验证 OpenAI Responses 配置解析与 PromptCacheCapability 映射规则。 */
class OpenAiResponsesConfigTest {

  /** 验证缺省或空白配置解析为 AUTOMATIC 模式，且能力映射为 automatic。 */
  @Test
  void test_defaultAndBlankConfig() {
    OpenAiResponsesConfig defaultConfig = OpenAiResponsesConfig.defaultConfig();
    assertEquals(OpenAiPromptCacheMode.AUTOMATIC, defaultConfig.openAiPromptCacheMode());
    assertEquals(PromptCacheMode.AUTOMATIC, defaultConfig.promptCacheCapability().mode());

    OpenAiResponsesConfig nullConfig = OpenAiResponsesConfig.parse(null);
    assertEquals(OpenAiPromptCacheMode.AUTOMATIC, nullConfig.openAiPromptCacheMode());

    OpenAiResponsesConfig blankConfig = OpenAiResponsesConfig.parse("   \n\t");
    assertEquals(OpenAiPromptCacheMode.AUTOMATIC, blankConfig.openAiPromptCacheMode());

    OpenAiResponsesConfig emptyObjConfig = OpenAiResponsesConfig.parse("{}");
    assertEquals(OpenAiPromptCacheMode.AUTOMATIC, emptyObjConfig.openAiPromptCacheMode());
  }

  /** 验证显式 LEGACY 模式配置解析及其 affinity 能力映射。 */
  @Test
  void test_legacyModeConfig() {
    String json = "{\"openAiPromptCacheMode\": \"LEGACY\"}";
    OpenAiResponsesConfig config = OpenAiResponsesConfig.parse(json);
    assertEquals(OpenAiPromptCacheMode.LEGACY, config.openAiPromptCacheMode());

    PromptCacheCapability cap = config.promptCacheCapability();
    assertEquals(PromptCacheMode.AFFINITY, cap.mode());
    assertTrue(cap.supports(PromptCacheRetention.SHORT));
    assertTrue(cap.supports(PromptCacheRetention.LONG));
    assertFalse(
        cap.supports(null != null ? PromptCacheRetention.NONE : PromptCacheRetention.NONE)
            && cap.supportedBreakpoints().contains(PromptCacheBreakpoint.SYSTEM));
  }

  /** 验证显式 GPT_5_6_EXPLICIT 模式配置解析及其 breakpoints 能力映射。 */
  @Test
  void test_explicitModeConfig() {
    String json = "{\"openAiPromptCacheMode\": \"GPT_5_6_EXPLICIT\"}";
    OpenAiResponsesConfig config = OpenAiResponsesConfig.parse(json);
    assertEquals(OpenAiPromptCacheMode.GPT_5_6_EXPLICIT, config.openAiPromptCacheMode());

    PromptCacheCapability cap = config.promptCacheCapability();
    assertEquals(PromptCacheMode.BREAKPOINTS, cap.mode());
    assertTrue(cap.supports(PromptCacheRetention.SHORT));
    assertFalse(cap.supports(PromptCacheRetention.LONG));
    assertTrue(cap.supportedBreakpoints().contains(PromptCacheBreakpoint.SYSTEM));
    assertTrue(cap.supportedBreakpoints().contains(PromptCacheBreakpoint.CONVERSATION));
    assertFalse(cap.supportedBreakpoints().contains(PromptCacheBreakpoint.TOOLS));
  }

  /** 验证 configJson 中包含未知其他字段时被安全忽略，但已知语法与模式严格生效。 */
  @Test
  void test_ignoreUnknownFields() {
    String json =
        "{\"openAiPromptCacheMode\": \"LEGACY\", \"futureField\": 123, \"extraObject\": {\"k\": \"v\"}}";
    OpenAiResponsesConfig config = OpenAiResponsesConfig.parse(json);
    assertEquals(OpenAiPromptCacheMode.LEGACY, config.openAiPromptCacheMode());
  }

  /** 验证非法 JSON 语法、非对象 JSON、非法模式值被严格拒绝，且异常消息绝不泄漏 config。 */
  @Test
  void test_invalidConfigGuards() {
    // 语法错误
    IllegalArgumentException ex1 =
        assertThrows(
            IllegalArgumentException.class, () -> OpenAiResponsesConfig.parse("{invalid json"));
    assertEquals("invalid provider config JSON", ex1.getMessage());

    // 非对象（数组或原始值）
    IllegalArgumentException ex2 =
        assertThrows(
            IllegalArgumentException.class, () -> OpenAiResponsesConfig.parse("[\"abc\"]"));
    assertEquals("provider config must be a JSON object", ex2.getMessage());

    // 未知模式枚举值
    IllegalArgumentException ex3 =
        assertThrows(
            IllegalArgumentException.class,
            () -> OpenAiResponsesConfig.parse("{\"openAiPromptCacheMode\": \"UNKNOWN_MODE\"}"));
    assertTrue(ex3.getMessage().contains("unsupported openAiPromptCacheMode"));

    // 非文本模式字段
    IllegalArgumentException ex4 =
        assertThrows(
            IllegalArgumentException.class,
            () -> OpenAiResponsesConfig.parse("{\"openAiPromptCacheMode\": 123}"));
    assertTrue(ex4.getMessage().contains("openAiPromptCacheMode must be a string"));
  }

  /** 验证静态门禁 resolvePromptCacheCapability 与实例方法的一致性。 */
  @Test
  void test_resolvePromptCacheCapability() {
    PromptCacheCapability cap =
        OpenAiResponsesConfig.resolvePromptCacheCapability(
            "{\"openAiPromptCacheMode\": \"GPT_5_6_EXPLICIT\"}");
    assertNotNull(cap);
    assertEquals(PromptCacheMode.BREAKPOINTS, cap.mode());
  }
}
