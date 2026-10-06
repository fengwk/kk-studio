package fun.fengwk.kkstudio.harness.provider.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;

/** 验证 OpenAI Responses 配置解析与提示缓存留存档位映射规则。 */
class OpenAiResponsesConfigTest {

  /** 验证缺省、null、空白与空对象配置都解析为 NONE（不下发任何 cache hint）。 */
  @Test
  void test_defaultAndBlankConfig() {
    assertEquals(
        PromptCacheRetention.NONE, OpenAiResponsesConfig.defaultConfig().promptCacheRetention());
    assertEquals(
        PromptCacheRetention.NONE, OpenAiResponsesConfig.parse(null).promptCacheRetention());
    assertEquals(
        PromptCacheRetention.NONE, OpenAiResponsesConfig.parse("   \n\t").promptCacheRetention());
    assertEquals(
        PromptCacheRetention.NONE, OpenAiResponsesConfig.parse("{}").promptCacheRetention());
  }

  /** 验证显式 SHORT / LONG / NONE 留存档位解析。 */
  @Test
  void test_explicitRetentionConfig() {
    assertEquals(
        PromptCacheRetention.SHORT,
        OpenAiResponsesConfig.parse("{\"promptCacheRetention\": \"SHORT\"}")
            .promptCacheRetention());
    assertEquals(
        PromptCacheRetention.LONG,
        OpenAiResponsesConfig.parse("{\"promptCacheRetention\": \"LONG\"}").promptCacheRetention());
    assertEquals(
        PromptCacheRetention.NONE,
        OpenAiResponsesConfig.parse("{\"promptCacheRetention\": \"NONE\"}").promptCacheRetention());
  }

  /** 验证 configJson 中包含未知其他字段时被安全忽略，但已知语法与档位严格生效。 */
  @Test
  void test_ignoreUnknownFields() {
    OpenAiResponsesConfig config =
        OpenAiResponsesConfig.parse(
            "{\"promptCacheRetention\": \"LONG\", \"futureField\": 123, \"extraObject\": {\"k\": \"v\"}}");
    assertEquals(PromptCacheRetention.LONG, config.promptCacheRetention());
  }

  /** 验证非法 JSON 语法、非对象 JSON、非法档位值被严格拒绝，且异常消息绝不泄漏 config。 */
  @Test
  void test_invalidConfigGuards() {
    IllegalArgumentException ex1 =
        assertThrows(
            IllegalArgumentException.class, () -> OpenAiResponsesConfig.parse("{invalid json"));
    assertEquals("invalid provider config JSON", ex1.getMessage());

    IllegalArgumentException ex2 =
        assertThrows(
            IllegalArgumentException.class, () -> OpenAiResponsesConfig.parse("[\"abc\"]"));
    assertEquals("provider config must be a JSON object", ex2.getMessage());

    // 未知档位枚举值（不回显输入值且不保留 cause）
    IllegalArgumentException ex3 =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                OpenAiResponsesConfig.parse("{\"promptCacheRetention\": \"UNKNOWN_SECRET_MODE\"}"));
    assertEquals("unsupported promptCacheRetention", ex3.getMessage());
    assertFalse(ex3.getMessage().contains("UNKNOWN_SECRET_MODE"));
    assertNull(ex3.getCause());

    IllegalArgumentException ex4 =
        assertThrows(
            IllegalArgumentException.class,
            () -> OpenAiResponsesConfig.parse("{\"promptCacheRetention\": 123}"));
    assertTrue(ex4.getMessage().contains("promptCacheRetention must be a string"));
  }

  /** 验证静态门禁 resolvePromptCacheRetention 与实例方法的一致性。 */
  @Test
  void test_resolvePromptCacheRetention() {
    assertEquals(
        PromptCacheRetention.LONG,
        OpenAiResponsesConfig.resolvePromptCacheRetention("{\"promptCacheRetention\": \"LONG\"}"));
    assertEquals(
        PromptCacheRetention.NONE, OpenAiResponsesConfig.resolvePromptCacheRetention("{}"));
  }
}
