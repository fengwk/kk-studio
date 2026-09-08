package fun.fengwk.kkstudio.harness.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;

/** 验证 AnthropicConfiguration 的解析、默认值、类型校验、枚举映射与敏感信息脱敏约束。 */
class AnthropicConfigurationTest {

  /** 验证 null、空白字符串或空 JSON 对象默认解析为 ADAPTIVE 模式。 */
  @Test
  @DisplayName("null、空白或空 JSON 对象返回默认 ADAPTIVE 配置")
  void parseDefaults() {
    AnthropicConfiguration cNull = AnthropicConfiguration.parse(null);
    assertEquals(AnthropicThinkingMode.ADAPTIVE, cNull.anthropicThinkingMode());

    AnthropicConfiguration cEmpty = AnthropicConfiguration.parse("");
    assertEquals(AnthropicThinkingMode.ADAPTIVE, cEmpty.anthropicThinkingMode());

    AnthropicConfiguration cBlank = AnthropicConfiguration.parse("   \n\t  ");
    assertEquals(AnthropicThinkingMode.ADAPTIVE, cBlank.anthropicThinkingMode());

    AnthropicConfiguration cObj = AnthropicConfiguration.parse("{}");
    assertEquals(AnthropicThinkingMode.ADAPTIVE, cObj.anthropicThinkingMode());

    AnthropicConfiguration cDefaults = AnthropicConfiguration.defaults();
    assertEquals(AnthropicThinkingMode.ADAPTIVE, cDefaults.anthropicThinkingMode());
    assertEquals(cNull, cDefaults);
  }

  /** 验证显式配置 ADAPTIVE 模式。 */
  @Test
  @DisplayName("显式配置 ADAPTIVE 模式解析正确")
  void parseExplicitAdaptive() {
    String json = "{\"anthropicThinkingMode\": \"ADAPTIVE\"}";
    AnthropicConfiguration config = AnthropicConfiguration.parse(json);
    assertEquals(AnthropicThinkingMode.ADAPTIVE, config.anthropicThinkingMode());
  }

  /** 验证显式配置 BUDGET 模式。 */
  @Test
  @DisplayName("显式配置 BUDGET 模式解析正确")
  void parseExplicitBudget() {
    String json = "{\"anthropicThinkingMode\": \"BUDGET\"}";
    AnthropicConfiguration config = AnthropicConfiguration.parse(json);
    assertEquals(AnthropicThinkingMode.BUDGET, config.anthropicThinkingMode());
  }

  /** 验证配置中的未知字段被安全忽略，不影响已知模式解析。 */
  @Test
  @DisplayName("未知扩展字段被安全忽略")
  void ignoreUnrelatedFields() {
    String json =
        """
        {
          "anthropicThinkingMode": "BUDGET",
          "unknownField": "ignored",
          "extraNumber": 12345,
          "nested": {"key": "val"}
        }
        """;
    AnthropicConfiguration config = AnthropicConfiguration.parse(json);
    assertEquals(AnthropicThinkingMode.BUDGET, config.anthropicThinkingMode());
  }

  /** 验证非法 JSON 语法被确定性拒绝，且不保留外部 cause。 */
  @Test
  @DisplayName("非法 JSON 语法抛出无 cause 的 ProviderException(INVALID_REQUEST)")
  void rejectMalformedJson() {
    ProviderException ex1 =
        assertThrows(ProviderException.class, () -> AnthropicConfiguration.parse("{not valid"));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex1.kind());
    assertNull(ex1.getCause());
    assertEquals("invalid Anthropic configuration JSON", ex1.getMessage());

    ProviderException ex2 =
        assertThrows(
            ProviderException.class,
            () ->
                AnthropicConfiguration.parse(
                    "{\"anthropicThinkingMode\":\"ADAPTIVE\", \"anthropicThinkingMode\":\"BUDGET\"}"));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex2.kind());
    assertNull(ex2.getCause());
    assertEquals("invalid Anthropic configuration JSON", ex2.getMessage());

    ProviderException ex3 =
        assertThrows(
            ProviderException.class,
            () ->
                AnthropicConfiguration.parse("{\"anthropicThinkingMode\":\"ADAPTIVE\"} trailing"));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex3.kind());
    assertNull(ex3.getCause());
    assertEquals("invalid Anthropic configuration JSON", ex3.getMessage());
  }

  /** 验证顶层非 JSON Object 格式被确定性拒绝，且不保留外部 cause。 */
  @Test
  @DisplayName("顶层非 JSON Object 抛出无 cause 的 ProviderException(INVALID_REQUEST)")
  void rejectNonObject() {
    ProviderException exArray =
        assertThrows(ProviderException.class, () -> AnthropicConfiguration.parse("[\"ADAPTIVE\"]"));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exArray.kind());
    assertNull(exArray.getCause());
    assertEquals("Anthropic configuration must be a JSON object", exArray.getMessage());

    ProviderException exString =
        assertThrows(ProviderException.class, () -> AnthropicConfiguration.parse("\"ADAPTIVE\""));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exString.kind());
    assertNull(exString.getCause());
    assertEquals("Anthropic configuration must be a JSON object", exString.getMessage());

    ProviderException exNumber =
        assertThrows(ProviderException.class, () -> AnthropicConfiguration.parse("123"));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exNumber.kind());
    assertNull(exNumber.getCause());
    assertEquals("Anthropic configuration must be a JSON object", exNumber.getMessage());

    ProviderException exBool =
        assertThrows(ProviderException.class, () -> AnthropicConfiguration.parse("true"));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exBool.kind());
    assertNull(exBool.getCause());
    assertEquals("Anthropic configuration must be a JSON object", exBool.getMessage());
  }

  /** 验证 anthropicThinkingMode 字段类型非字符串时被确定性拒绝。 */
  @Test
  @DisplayName("字段类型非字符串抛出无 cause 的 ProviderException(INVALID_REQUEST)")
  void rejectInvalidFieldTypes() {
    ProviderException exNum =
        assertThrows(
            ProviderException.class,
            () -> AnthropicConfiguration.parse("{\"anthropicThinkingMode\": 123}"));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exNum.kind());
    assertNull(exNum.getCause());
    assertEquals("field anthropicThinkingMode must be a string", exNum.getMessage());

    ProviderException exBool =
        assertThrows(
            ProviderException.class,
            () -> AnthropicConfiguration.parse("{\"anthropicThinkingMode\": true}"));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exBool.kind());
    assertNull(exBool.getCause());
    assertEquals("field anthropicThinkingMode must be a string", exBool.getMessage());

    ProviderException exArr =
        assertThrows(
            ProviderException.class,
            () -> AnthropicConfiguration.parse("{\"anthropicThinkingMode\": [\"BUDGET\"]}"));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exArr.kind());
    assertNull(exArr.getCause());
    assertEquals("field anthropicThinkingMode must be a string", exArr.getMessage());

    ProviderException exObj =
        assertThrows(
            ProviderException.class,
            () -> AnthropicConfiguration.parse("{\"anthropicThinkingMode\": {\"m\": \"BUDGET\"}}"));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exObj.kind());
    assertNull(exObj.getCause());
    assertEquals("field anthropicThinkingMode must be a string", exObj.getMessage());
  }

  /** 验证不支持的模式枚举值被确定性拒绝，且绝不回显输入内容或凭据。 */
  @Test
  @DisplayName("不支持的模式枚举值拒绝且不泄露输入值")
  void rejectUnsupportedEnumValuesWithoutEchoing() {
    String sensitiveValue = "sk-ant-api03-TOP-SECRET-VALUE";
    String json = "{\"anthropicThinkingMode\": \"" + sensitiveValue + "\"}";

    ProviderException ex =
        assertThrows(ProviderException.class, () -> AnthropicConfiguration.parse(json));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
    assertNull(ex.getCause());
    assertEquals("unsupported anthropicThinkingMode value", ex.getMessage());
    assertFalse(ex.getMessage().contains(sensitiveValue));

    // 小写 adaptive
    ProviderException exLower =
        assertThrows(
            ProviderException.class,
            () -> AnthropicConfiguration.parse("{\"anthropicThinkingMode\": \"adaptive\"}"));
    assertEquals("unsupported anthropicThinkingMode value", exLower.getMessage());

    // ENABLED
    ProviderException exEnabled =
        assertThrows(
            ProviderException.class,
            () -> AnthropicConfiguration.parse("{\"anthropicThinkingMode\": \"ENABLED\"}"));
    assertEquals("unsupported anthropicThinkingMode value", exEnabled.getMessage());
  }

  /** 验证紧凑构造函数对 null 参数回退为 ADAPTIVE 模式。 */
  @Test
  @DisplayName("构造函数参数为 null 时回退为 ADAPTIVE")
  void compactConstructorNullFallback() {
    AnthropicConfiguration config = new AnthropicConfiguration(null);
    assertEquals(AnthropicThinkingMode.ADAPTIVE, config.anthropicThinkingMode());
  }

  /** 验证 equals、hashCode 与 toString。 */
  @Test
  @DisplayName("验证 equals、hashCode 与 toString 行为")
  void testEqualsHashCodeToString() {
    AnthropicConfiguration adaptive1 = AnthropicConfiguration.defaults();
    AnthropicConfiguration adaptive2 =
        AnthropicConfiguration.parse("{\"anthropicThinkingMode\": \"ADAPTIVE\"}");
    AnthropicConfiguration budget =
        AnthropicConfiguration.parse("{\"anthropicThinkingMode\": \"BUDGET\"}");

    assertEquals(adaptive1, adaptive2);
    assertEquals(adaptive1.hashCode(), adaptive2.hashCode());
    assertNotEquals(adaptive1, budget);
    assertNotEquals(adaptive1, null);
    assertNotEquals(adaptive1, "other");

    assertNotNull(adaptive1.toString());
    assertTrue(adaptive1.toString().contains("ADAPTIVE"));
    assertTrue(budget.toString().contains("BUDGET"));
  }

  /** 验证 AnthropicThinkingMode 枚举值的完整性。 */
  @Test
  @DisplayName("验证 AnthropicThinkingMode 枚举值的完整性")
  void testEnumValues() {
    assertEquals(2, AnthropicThinkingMode.values().length);
    assertEquals(AnthropicThinkingMode.ADAPTIVE, AnthropicThinkingMode.valueOf("ADAPTIVE"));
    assertEquals(AnthropicThinkingMode.BUDGET, AnthropicThinkingMode.valueOf("BUDGET"));
  }
}
