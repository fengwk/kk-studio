package fun.fengwk.kkstudio.platform.configsync;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试意图：锁定配置同步 YAML 边界的关键不变量——纯标量往返不产生 Java tag、数值/布尔/JSON 字符串保持类型、重复键与非法 tag
 * 被拒绝、嵌套结构到强类型 DTO 的转换不会静默改写 protocolOptionsJson。
 */
class ConfigSyncYamlTest {

  private final ConfigSyncYaml yaml = new ConfigSyncYaml(new ObjectMapper());

  @Test
  void dumpAndParsePreservesScalarTypesWithoutJavaTags() {
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("count", 1800000L);
    document.put("ratio", new BigDecimal("1.25"));
    document.put("enabled", true);
    document.put("protocolOptionsJson", "{\"temperature\":0.5}");
    document.put("nested", Map.of("list", List.of("a", "b")));

    String dumped = yaml.dump(document);

    assertTrue(!dumped.contains("!!"), () -> "yaml must not contain java tags: " + dumped);
    Map<String, Object> parsed = yaml.parse(dumped);
    assertInstanceOf(Number.class, parsed.get("count"));
    assertEquals(1800000L, ((Number) parsed.get("count")).longValue());
    assertEquals(0, new BigDecimal(parsed.get("ratio").toString()).compareTo(new BigDecimal("1.25")));
    assertEquals(Boolean.TRUE, parsed.get("enabled"));
    assertEquals("{\"temperature\":0.5}", parsed.get("protocolOptionsJson"));
  }

  @Test
  void duplicateKeysAreRejected() {
    assertThrows(
        AiValidationException.class,
        () -> yaml.parse("providers: []\nproviders: []\n"));
  }

  @Test
  void javaSpecificTagIsRejected() {
    assertThrows(
        AiValidationException.class,
        () -> yaml.parse("value: !!java.util.Date '2020-01-01'\n"));
  }

  @Test
  void nonObjectRootAndNonStringKeysAreRejected() {
    assertThrows(AiValidationException.class, () -> yaml.parse("- a\n- b\n"));
    assertThrows(AiValidationException.class, () -> yaml.parse("1: a\n"));
  }

  @Test
  void nestedConversionKeepsProtocolOptionsJsonAsString() {
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("limit", Map.of("context", 1000, "output", 100));
    config.put(
        "abilities",
        Map.of("tools", true, "reasoning", false, "inputModalities", List.of("TEXT")));
    config.put("defaultVariant", "default");
    config.put(
        "variants",
        List.of(Map.of("id", "default", "protocolOptionsJson", "{\"a\":1}")));
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
}
