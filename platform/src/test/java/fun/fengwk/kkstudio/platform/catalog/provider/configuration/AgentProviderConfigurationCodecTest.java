package fun.fengwk.kkstudio.platform.catalog.provider.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/** Provider timeout 配置的默认、合并和非法持久数据边界。 */
class AgentProviderConfigurationCodecTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final AgentProviderConfigurationCodec codec =
      new AgentProviderConfigurationCodec(objectMapper);

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

    @Override
    public String writeValueAsString(Object value) throws JsonProcessingException {
      throw new JsonProcessingException("write failed") {};
    }
  }
}
