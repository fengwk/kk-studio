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

import java.util.HashMap;
import java.util.Map;

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

  /** 意图：验证默认配置与无 modelAliases 配置返回空 map，且解析模型名返回原逻辑名。 */
  @Test
  @DisplayName("默认配置与无 modelAliases 返回空 map 并回退到逻辑模型名")
  void defaultModelAliases() {
    AnthropicConfiguration defaults = AnthropicConfiguration.defaults();
    assertNotNull(defaults.modelAliases());
    assertTrue(defaults.modelAliases().isEmpty());
    assertEquals("claude-3-5-sonnet", defaults.resolveModelName("claude-3-5-sonnet"));
    assertNull(defaults.resolveModelName(null));

    AnthropicConfiguration fromJson = AnthropicConfiguration.parse("{}");
    assertTrue(fromJson.modelAliases().isEmpty());
    assertEquals("MiniMax-M3", fromJson.resolveModelName("MiniMax-M3"));
  }

  /** 意图：验证合法 modelAliases 解析正确，并可通过 resolveModelName 返回对应 wire 模型标识。 */
  @Test
  @DisplayName("合法 modelAliases 映射解析与别名查找生效")
  void parseValidModelAliases() {
    String json =
        """
        {
          "anthropicThinkingMode": "BUDGET",
          "modelAliases": {
            "MiniMax-M3": "claude-fable-5-dd-3M-xaMiniM",
            "logical-model-2": "wire-model-2"
          }
        }
        """;
    AnthropicConfiguration config = AnthropicConfiguration.parse(json);
    assertEquals(AnthropicThinkingMode.BUDGET, config.anthropicThinkingMode());
    assertEquals(2, config.modelAliases().size());
    assertEquals("claude-fable-5-dd-3M-xaMiniM", config.resolveModelName("MiniMax-M3"));
    assertEquals("wire-model-2", config.resolveModelName("logical-model-2"));
    assertEquals("unmapped-model", config.resolveModelName("unmapped-model"));
    assertNull(config.resolveModelName(null));

    // 返回的 map 不可变
    assertThrows(
        UnsupportedOperationException.class,
        () -> config.modelAliases().put("illegal", "mutation"));
  }

  /** 意图：验证 modelAliases 非 JSON Object 时被确定性拒绝，且无 cause。 */
  @Test
  @DisplayName("modelAliases 字段非 object 抛出无 cause 的 ProviderException(INVALID_REQUEST)")
  void rejectNonObjectModelAliases() {
    String[] invalidJson = {
      "{\"modelAliases\": \"not-an-object\"}",
      "{\"modelAliases\": 12345}",
      "{\"modelAliases\": true}",
      "{\"modelAliases\": [\"item1\", \"item2\"]}"
    };

    for (String json : invalidJson) {
      ProviderException ex =
          assertThrows(ProviderException.class, () -> AnthropicConfiguration.parse(json));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
      assertNull(ex.getCause());
      assertEquals("field modelAliases must be a JSON object", ex.getMessage());
    }
  }

  /** 意图：验证 modelAliases key 包含空白、前后空白或为空时被安全拒绝且不泄露键内容。 */
  @Test
  @DisplayName("modelAliases key 为空或含前后空白时拒绝且不泄露键内容")
  void rejectInvalidModelAliasesKeys() {
    String sensitiveKey = "sk-sensitive-key-12345";
    String[] testJsons = {
      "{\"modelAliases\": {\"\": \"wire-id\"}}",
      "{\"modelAliases\": {\"   \": \"wire-id\"}}",
      "{\"modelAliases\": {\" " + sensitiveKey + "\": \"wire-id\"}}",
      "{\"modelAliases\": {\"" + sensitiveKey + " \": \"wire-id\"}}",
      "{\"modelAliases\": {\"\\n" + sensitiveKey + "\": \"wire-id\"}}"
    };

    for (String json : testJsons) {
      ProviderException ex =
          assertThrows(ProviderException.class, () -> AnthropicConfiguration.parse(json));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
      assertNull(ex.getCause());
      assertEquals(
          "field modelAliases keys must be non-empty strings without surrounding whitespace",
          ex.getMessage());
      assertFalse(ex.getMessage().contains(sensitiveKey));
    }
  }

  /** 意图：验证 modelAliases value 非字符串时被确定性拒绝且无 cause。 */
  @Test
  @DisplayName("modelAliases value 非字符串抛出稳定异常")
  void rejectNonStringModelAliasesValues() {
    String[] testJsons = {
      "{\"modelAliases\": {\"logical\": 12345}}",
      "{\"modelAliases\": {\"logical\": true}}",
      "{\"modelAliases\": {\"logical\": [\"item\"]}}",
      "{\"modelAliases\": {\"logical\": {\"nested\": \"val\"}}}"
    };

    for (String json : testJsons) {
      ProviderException ex =
          assertThrows(ProviderException.class, () -> AnthropicConfiguration.parse(json));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
      assertNull(ex.getCause());
      assertEquals("field modelAliases values must be strings", ex.getMessage());
    }
  }

  /** 意图：验证 modelAliases value 为空或含前后空白时被安全拒绝且不泄露值内容。 */
  @Test
  @DisplayName("modelAliases value 为空或含前后空白时拒绝且不泄露值内容")
  void rejectInvalidModelAliasesValues() {
    String sensitiveVal = "wire-super-secret-target";
    String[] testJsons = {
      "{\"modelAliases\": {\"logical\": \"\"}}",
      "{\"modelAliases\": {\"logical\": \"   \"}}",
      "{\"modelAliases\": {\"logical\": \" " + sensitiveVal + "\"}}",
      "{\"modelAliases\": {\"logical\": \"" + sensitiveVal + " \"}}",
      "{\"modelAliases\": {\"logical\": \"\\t" + sensitiveVal + "\"}}"
    };

    for (String json : testJsons) {
      ProviderException ex =
          assertThrows(ProviderException.class, () -> AnthropicConfiguration.parse(json));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
      assertNull(ex.getCause());
      assertEquals(
          "field modelAliases values must be non-empty strings without surrounding whitespace",
          ex.getMessage());
      assertFalse(ex.getMessage().contains(sensitiveVal));
    }
  }

  /** 意图：验证构造函数直接传入非法映射时同样进行严格校验且抛出脱敏异常。 */
  @Test
  @DisplayName("构造函数参数校验 modelAliases 合法性")
  void constructorValidatesModelAliases() {
    // 允许 null 或 empty map
    AnthropicConfiguration cNull = new AnthropicConfiguration(AnthropicThinkingMode.BUDGET, null);
    assertTrue(cNull.modelAliases().isEmpty());

    AnthropicConfiguration cEmpty =
        new AnthropicConfiguration(AnthropicThinkingMode.BUDGET, Map.of());
    assertTrue(cEmpty.modelAliases().isEmpty());

    // key 带前后空白
    Map<String, String> badKeyMap = new HashMap<>();
    badKeyMap.put(" badKey", "validValue");
    ProviderException exKey =
        assertThrows(
            ProviderException.class,
            () -> new AnthropicConfiguration(AnthropicThinkingMode.ADAPTIVE, badKeyMap));
    assertEquals(
        "field modelAliases keys must be non-empty strings without surrounding whitespace",
        exKey.getMessage());

    // value 带前后空白
    Map<String, String> badValMap = new HashMap<>();
    badValMap.put("validKey", "badValue ");
    ProviderException exVal =
        assertThrows(
            ProviderException.class,
            () -> new AnthropicConfiguration(AnthropicThinkingMode.ADAPTIVE, badValMap));
    assertEquals(
        "field modelAliases values must be non-empty strings without surrounding whitespace",
        exVal.getMessage());
  }

  /** 意图：验证包含 modelAliases 时的值语义，以及 toString 不回显任意配置 key/value。 */
  @Test
  @DisplayName("包含 modelAliases 的值语义与安全 toString")
  void testEqualsAndHashCodeWithModelAliases() {
    String logicalModelName = "sensitive-logical-model";
    String wireModelName = "sensitive-wire-model";
    AnthropicConfiguration config1 =
        AnthropicConfiguration.parse(
            "{\"modelAliases\":{\""
                + logicalModelName
                + "\":\""
                + wireModelName
                + "\"},\"anthropicThinkingMode\":\"BUDGET\"}");
    AnthropicConfiguration config2 =
        new AnthropicConfiguration(
            AnthropicThinkingMode.BUDGET, Map.of(logicalModelName, wireModelName));
    AnthropicConfiguration configDiffAlias =
        new AnthropicConfiguration(
            AnthropicThinkingMode.BUDGET, Map.of(logicalModelName, "other-id"));

    assertEquals(config1, config2);
    assertEquals(config1.hashCode(), config2.hashCode());
    assertNotEquals(config1, configDiffAlias);
    assertTrue(config1.toString().contains("modelAliasesSize=1"));
    assertFalse(config1.toString().contains(logicalModelName));
    assertFalse(config1.toString().contains(wireModelName));
  }
}
