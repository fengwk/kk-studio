package fun.fengwk.kkstudio.platform.configsync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelInputModality;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsToolDTO;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试意图：锁定配置同步 YAML 边界的关键不变量——纯标量往返不产生 Java tag、高精度数值保持 BigDecimal、数值/布尔/字符串不互相宽松转换、重复 键与非法
 * tag/别名/非有限浮点被拒绝、未声明字段按 introspection 检出且 Map 动态键不被误删。
 */
class ConfigSyncYamlTest {

  private final ConfigSyncYaml yaml = new ConfigSyncYaml();

  @Test
  void dumpAndParsePreservesScalarTypesWithoutJavaTags() {
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("count", 1800000L);
    document.put("ratio", new BigDecimal("1.25"));
    document.put("enabled", true);
    document.put("protocolOptionsJson", "{\"temperature\":0.5}");
    document.put("nested", Map.of("list", List.of("a", "b")));

    String dumped = yaml.dump(document);

    assertFalse(dumped.contains("!!"), () -> "yaml must not contain java tags: " + dumped);
    Map<String, Object> parsed = yaml.parse(dumped);
    assertInstanceOf(Number.class, parsed.get("count"));
    assertEquals(1800000L, ((Number) parsed.get("count")).longValue());
    assertEquals(
        0, new BigDecimal(parsed.get("ratio").toString()).compareTo(new BigDecimal("1.25")));
    assertEquals(Boolean.TRUE, parsed.get("enabled"));
    assertEquals("{\"temperature\":0.5}", parsed.get("protocolOptionsJson"));
  }

  @Test
  void highPrecisionDecimalRoundTripsWithoutDoubleLoss() {
    BigDecimal precise = new BigDecimal("0.123456789012345678901234567890");
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("ratio", precise);

    Map<String, Object> parsed = yaml.parse(yaml.dump(document));

    Object ratio = parsed.get("ratio");
    assertInstanceOf(BigDecimal.class, ratio);
    assertEquals(0, ((BigDecimal) ratio).compareTo(precise));
    assertEquals(precise.toString(), ratio.toString());
  }

  @Test
  void duplicateKeysAreRejected() {
    assertThrows(AiValidationException.class, () -> yaml.parse("providers: []\nproviders: []\n"));
  }

  @Test
  void javaSpecificTagIsRejected() {
    assertThrows(
        AiValidationException.class,
        () -> yaml.parse("value: !!" + Date.class.getName() + " '2020-01-01'\n"));
  }

  @Test
  void nonObjectRootAndNonStringKeysAreRejected() {
    assertThrows(AiValidationException.class, () -> yaml.parse("- a\n- b\n"));
    assertThrows(AiValidationException.class, () -> yaml.parse("1: a\n"));
  }

  @Test
  void collectionAliasIsRejected() {
    // 集合别名可构造递归结构导致 StackOverflow，必须在解析期安全拒绝。
    assertThrows(AiValidationException.class, () -> yaml.parse("providers: &x []\nmodels: *x\n"));
  }

  @Test
  void recursiveAliasIsRejected() {
    assertThrows(AiValidationException.class, () -> yaml.parse("root: &x\n  self: *x\n"));
  }

  @Test
  void nonFiniteFloatIsRejected() {
    assertThrows(AiValidationException.class, () -> yaml.parse("ratio: .inf\n"));
    assertThrows(AiValidationException.class, () -> yaml.parse("ratio: .nan\n"));
  }

  @Test
  void oversizedInputIsRejectedBeforeParsing() {
    String huge = "providers: " + "[x]".repeat(8 * 1024 * 1024);
    assertThrows(AiValidationException.class, () -> yaml.parse(huge));
  }

  @Test
  void stringIsNotCoercedToNumber() {
    Map<String, Object> node = Map.of("count", "5");
    assertThrows(AiValidationException.class, () -> yaml.convert(node, Sample.class, "sample"));
  }

  @Test
  void decimalStringIsNotCoercedToDecimal() {
    Map<String, Object> node = Map.of("ratio", "1.5");
    assertThrows(AiValidationException.class, () -> yaml.convert(node, Sample.class, "sample"));
  }

  @Test
  void numberIsNotCoercedToString() {
    Map<String, Object> node = Map.of("name", 7);
    assertThrows(AiValidationException.class, () -> yaml.convert(node, Sample.class, "sample"));
  }

  @Test
  void stringIsNotCoercedToBoolean() {
    Map<String, Object> node = Map.of("enabled", "true");
    assertThrows(AiValidationException.class, () -> yaml.convert(node, Sample.class, "sample"));
  }

  @Test
  void enumOrdinalIsRejected() {
    Map<String, Object> node = Map.of("modality", 0);
    assertThrows(AiValidationException.class, () -> yaml.convert(node, Sample.class, "sample"));
  }

  @Test
  void nestedConversionKeepsProtocolOptionsJsonAsString() {
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("limit", Map.of("context", 1000, "output", 100));
    config.put(
        "abilities", Map.of("tools", true, "reasoning", false, "inputModalities", List.of("TEXT")));
    config.put("defaultVariant", "default");
    config.put("variants", List.of(Map.of("id", "default", "protocolOptionsJson", "{\"a\":1}")));
    Map<String, Object> pricing = new LinkedHashMap<>();
    pricing.put("currency", "USD");
    pricing.put("pricingTier", "t");
    pricing.put("serviceTier", "s");
    pricing.put("serviceTierMultiplier", 1);
    pricing.put("version", "v");
    pricing.put("inputPerMillionTokens", 0);
    pricing.put("outputPerMillionTokens", 0);
    pricing.put("cacheReadPerMillionTokens", 0);
    pricing.put("cacheWritePerMillionTokens", 0);
    pricing.put("cacheWriteLongPerMillionTokens", 0);
    pricing.put("reasoningPerMillionTokens", 0);
    config.put("pricing", pricing);

    AgentModelConfigDTO dto = yaml.convert(config, AgentModelConfigDTO.class, "model.config");
    assertEquals("{\"a\":1}", dto.getVariants().get(0).getProtocolOptionsJson());
    assertEquals(Integer.valueOf(1000), dto.getLimit().getContext());
  }

  @Test
  void removeUnknownPropertiesDetectsNestedUnknownModelField() {
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("limit", Map.of("context", 1000, "output", 100, "bogus", 1));
    List<String> removed = new ArrayList<>();

    Object cleaned =
        yaml.removeUnknownProperties(AgentModelConfigDTO.class, config, "model.config", removed);

    assertEquals(List.of("model.config.limit.bogus"), removed);
    assertTrue(cleaned instanceof Map<?, ?>);
  }

  @Test
  void removeUnknownPropertiesKeepsDynamicMapKeysAndPrunesOnlyUnknownLeaves() {
    Map<String, Object> tool = new LinkedHashMap<>();
    tool.put("defaultYolo", true);
    tool.put("bogus", 1);
    tool.put(
        "permission",
        Map.of("bash", List.of(Map.of("pattern", "*", "action", "allow", "extra", 1))));
    List<String> removed = new ArrayList<>();

    Object cleaned =
        yaml.removeUnknownProperties(SystemSettingsToolDTO.class, tool, "settings.tool", removed);

    assertEquals(List.of("settings.tool.bogus", "settings.tool.permission.bash[0].extra"), removed);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) cleaned;
    assertEquals(Boolean.TRUE, result.get("defaultYolo"));
    assertNotNull(result.get("permission"));
    assertFalse(result.containsKey("bogus"));
  }

  /** 意图：空白输入与 null 节点是边界错误，不能进入解析或强类型转换路径。 */
  @Test
  void blankYamlAndNullConversionAreRejected() {
    assertThrows(AiValidationException.class, () -> yaml.parse(null));
    assertThrows(AiValidationException.class, () -> yaml.parse("   "));
    assertThrows(AiValidationException.class, () -> yaml.convert(null, Sample.class, "sample"));
  }

  /** 意图：嵌套 map 的非字符串键在解析期即被拒绝，不能进入强类型转换。 */
  @Test
  void nestedNonStringKeyIsRejected() {
    assertThrows(AiValidationException.class, () -> yaml.parse("providers:\n  - 1: a\n"));
  }

  /** 意图：六十进制浮点虽被 SnakeYAML 识别为 float，但 BigDecimal 无法表达，必须作为非法 YAML 拒绝而非静默失真。 */
  @Test
  void sexagesimalFloatIsRejected() {
    assertThrows(AiValidationException.class, () -> yaml.parse("ratio: 1:20:30.5\n"));
  }

  /** 严格类型测试用的最小 DTO：公开字段足以让 Jackson introspection 识别。 */
  public static class Sample {

    public Long count;
    public BigDecimal ratio;
    public String name;
    public Boolean enabled;
    public AgentModelInputModality modality;
  }
}
