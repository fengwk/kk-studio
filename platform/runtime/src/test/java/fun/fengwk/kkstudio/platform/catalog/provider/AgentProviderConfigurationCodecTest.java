package fun.fengwk.kkstudio.platform.catalog.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/** Provider timeout 配置的默认、合并和非法持久数据边界。 */
class AgentProviderConfigurationCodecTest {

  private final ObjectMapper objectMapper = JsonMapper.builder().build();
  private final AgentProviderConfigurationCodec codec =
      new AgentProviderConfigurationCodec(objectMapper);

  /** 断言写出文本的数值保真：严格读取方按 BigDecimal 读回，避免测试自身再经 double 折叠。 */
  private static final ObjectMapper bigDecimalMapper =
      JsonMapper.builder().enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  @Test
  void missingTimeoutsUseProductDefaults() {
    assertEquals(ModelCallTimeoutPolicy.DEFAULT, codec.readTimeoutPolicy(null));
    assertEquals(ModelCallTimeoutPolicy.DEFAULT, codec.readTimeoutPolicy("{}"));
  }

  @Test
  void mergeNormalizesTimeoutsAndPreservesExtensionConfiguration() throws Exception {
    String config =
        codec.mergeTimeoutPolicy(
            "{\"extensionOption\":true}", Duration.ofMinutes(2).toMillis(), 3_000L);

    JsonNode node = objectMapper.readTree(config);
    assertEquals(true, node.path("extensionOption").asBoolean());
    assertEquals(Duration.ofMinutes(2).toMillis(), node.path("modelCallTimeoutMillis").asLong());
    assertEquals(3_000L, node.path("modelCallIdleTimeoutMillis").asLong());
    assertEquals(
        new ModelCallTimeoutPolicy(Duration.ofMinutes(2), Duration.ofSeconds(3)),
        codec.readTimeoutPolicy(config));
  }

  @Test
  void rejectsMalformedOrNonPositiveTimeouts() {
    assertThrows(IllegalArgumentException.class, () -> codec.readTimeoutPolicy("[]"));
    assertThrows(IllegalArgumentException.class, () -> codec.readTimeoutPolicy("{"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.readTimeoutPolicy("{\"modelCallTimeoutMillis\":\"120000\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.readTimeoutPolicy("{\"modelCallIdleTimeoutMillis\":0}"));
    assertThrows(IllegalArgumentException.class, () -> codec.mergeTimeoutPolicy("{}", -1L, null));
  }

  @Test
  void malformedConfigurationDoesNotRetainParserCause() {
    // 测试意图：持久配置可能含敏感扩展值；语法失败只保留稳定错误，不挂载可能携带原文的
    // Jackson cause。
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> codec.readTimeoutPolicy("{\"credential\":\"sensitive-value\""));

    assertEquals("persisted provider configJson must be valid JSON", error.getMessage());
    assertNull(error.getCause());
  }

  @Test
  void reportsInternalFailureWhenCanonicalConfigurationCannotSerialize() {
    AgentProviderConfigurationCodec failingCodec =
        new AgentProviderConfigurationCodec(new FailingWriteObjectMapper());

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> failingCodec.mergeTimeoutPolicy("{}", 1_000L, 2_000L));

    assertEquals("cannot encode provider configuration", error.getMessage());
  }

  @Test
  void isProtocolConfigEqualIgnoresTimeoutsAndChecksStructuralEquality() {
    assertTrue(codec.isProtocolConfigEqual(null, "{}"));
    assertTrue(
        codec.isProtocolConfigEqual(
            "{\"modelCallTimeoutMillis\":1000}", "{\"modelCallIdleTimeoutMillis\":500}"));
    assertTrue(
        codec.isProtocolConfigEqual(
            "{\"custom\":\"value\",\"modelCallTimeoutMillis\":1000}",
            "{\"custom\":\"value\",\"modelCallIdleTimeoutMillis\":2000}"));
    // HTTP 重试覆盖不是协议参数：变化不能触发 connection generation 轮换。
    assertTrue(
        codec.isProtocolConfigEqual(
            "{\"modelHttpRetryStatusCodes\":[429]}", "{\"modelHttpRetryStatusCodes\":[500,503]}"));
    assertTrue(codec.isProtocolConfigEqual("{}", "{\"modelHttpRetryStatusCodes\":[429]}"));
    assertFalse(codec.isProtocolConfigEqual("{\"custom\":\"value1\"}", "{\"custom\":\"value2\"}"));
    assertFalse(codec.isProtocolConfigEqual("{\"extra\":1}", "{}"));
  }

  @Test
  void isProtocolConfigEqualDistinguishesHighPrecisionDecimalsAndExtremes() {
    // 宿主 mapper 的默认 profile 会把这两份不同精度的小数折叠成同一个 double，从而误判为等价。
    assertFalse(
        codec.isProtocolConfigEqual(
            "{\"nested\":{\"ratio\":0.12345678901234567890123456789}}",
            "{\"nested\":{\"ratio\":0.123456789012345678901234567891}}"));
    // 1e309 在 double 中退化为 Infinity：极值必须保持可区分。
    assertFalse(codec.isProtocolConfigEqual("{\"limit\":1e309}", "{\"limit\":1e308}"));
    assertTrue(codec.isProtocolConfigEqual("{\"limit\":1e309}", "{\"limit\":1e309}"));
    assertTrue(
        codec.isProtocolConfigEqual(
            "{\"ratio\":0.12345678901234567890123456789}",
            "{\"ratio\":0.12345678901234567890123456789}"));
    // 同值不同 scale 仍是同一数值：等价判断沿用迁移前的数值语义（规范化尾零后比较）。
    assertTrue(codec.isProtocolConfigEqual("{\"ratio\":1.0}", "{\"ratio\":1.00}"));
    // 但标量类型不同（整数 token 与小数 token）依旧不等，与迁移前一致。
    assertFalse(codec.isProtocolConfigEqual("{\"ratio\":1.0}", "{\"ratio\":1}"));
  }

  @Test
  void mergePreservesHighPrecisionAndExtremeExtensionValues() {
    // 两个 merge 接口都必须原样搬运 Provider 扩展值：不得经 double 折叠，也不得把极值写成 Infinity/0。
    String existing =
        "{\"extension\":{\"ratio\":0.12345678901234567890123456789,\"limit\":1e309,\"tiny\":1e-324}}";

    String mergedTimeout = codec.mergeTimeoutPolicy(existing, 1_000L, 2_000L);
    assertExtensionValuesPreserved(mergedTimeout);
    assertEquals(
        new ModelCallTimeoutPolicy(Duration.ofSeconds(1), Duration.ofSeconds(2)),
        codec.readTimeoutPolicy(mergedTimeout));

    String mergedRetry = codec.mergeHttpRetryStatusCodes(mergedTimeout, true, List.of(429, 500));
    assertExtensionValuesPreserved(mergedRetry);
    assertEquals(List.of(429, 500), codec.readHttpRetryStatusCodes(mergedRetry));

    String preserved = codec.mergeHttpRetryStatusCodes(mergedRetry, false, null);
    assertExtensionValuesPreserved(preserved);
    assertEquals(List.of(429, 500), codec.readHttpRetryStatusCodes(preserved));
  }

  private static void assertExtensionValuesPreserved(String configJson) {
    JsonNode extension = bigDecimalMapper.readTree(configJson).path("extension");
    assertEquals(
        new BigDecimal("0.12345678901234567890123456789"), extension.path("ratio").decimalValue());
    assertEquals(0, extension.path("limit").decimalValue().compareTo(new BigDecimal("1e309")));
    assertEquals(0, extension.path("tiny").decimalValue().compareTo(new BigDecimal("1e-324")));
    // 极值在文本上就是数值 token，不是 Infinity/字符串。
    assertTrue(extension.path("limit").isNumber());
    assertFalse(extension.path("tiny").isNull());
  }

  @Test
  void readsAbsentOverrideAsInheritAndEmptyAsDisable() {
    assertNull(codec.readHttpRetryStatusCodes(null));
    assertNull(codec.readHttpRetryStatusCodes("{}"));
    assertNull(codec.readHttpRetryStatusCodes("{\"modelHttpRetryStatusCodes\":null}"));
    assertEquals(List.of(), codec.readHttpRetryStatusCodes("{\"modelHttpRetryStatusCodes\":[]}"));
    assertEquals(
        List.of(408, 429, 500),
        codec.readHttpRetryStatusCodes("{\"modelHttpRetryStatusCodes\":[408,429,500]}"));
  }

  @Test
  void rejectsStructuredOverrideThatIsNotAValidStatusList() {
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.readHttpRetryStatusCodes("{\"modelHttpRetryStatusCodes\":429}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.readHttpRetryStatusCodes("{\"modelHttpRetryStatusCodes\":[\"429\"]}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.readHttpRetryStatusCodes("{\"modelHttpRetryStatusCodes\":[429.5]}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.readHttpRetryStatusCodes("{\"modelHttpRetryStatusCodes\":[399]}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.readHttpRetryStatusCodes("{\"modelHttpRetryStatusCodes\":[600]}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.readHttpRetryStatusCodes("{\"modelHttpRetryStatusCodes\":[429,429]}"));
  }

  @Test
  void mergesOverrideWithPreserveClearAndReplaceSemantics() throws Exception {
    String existing = "{\"modelCallTimeoutMillis\":1000,\"modelHttpRetryStatusCodes\":[429]}";

    // 未提供字段：保留既有覆盖。
    String preserved = codec.mergeHttpRetryStatusCodes(existing, false, null);
    assertEquals(List.of(429), codec.readHttpRetryStatusCodes(preserved));

    // 显式 null：清除覆盖（继承系统名单）。
    String cleared = codec.mergeHttpRetryStatusCodes(existing, true, null);
    assertNull(codec.readHttpRetryStatusCodes(cleared));
    assertEquals(1000L, objectMapper.readTree(cleared).path("modelCallTimeoutMillis").asLong());

    // 数组：完全替代。
    String replaced = codec.mergeHttpRetryStatusCodes(existing, true, List.of(500, 503));
    assertEquals(List.of(500, 503), codec.readHttpRetryStatusCodes(replaced));

    // 空数组：明确禁用。
    String disabled = codec.mergeHttpRetryStatusCodes(existing, true, List.of());
    assertEquals(List.of(), codec.readHttpRetryStatusCodes(disabled));
  }

  @Test
  void rejectsInvalidOverrideOnMerge() {
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.mergeHttpRetryStatusCodes("{}", true, List.of(399)));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.mergeHttpRetryStatusCodes("{}", true, List.of(429, 429)));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.mergeHttpRetryStatusCodes("{}", true, Arrays.asList(429, null)));
  }

  private static final class FailingWriteObjectMapper extends ObjectMapper {

    FailingWriteObjectMapper() {
      super(new JsonFactory());
    }

    @Override
    public String writeValueAsString(Object value) throws JacksonException {
      throw new JacksonException("write failed") {};
    }
  }
}
