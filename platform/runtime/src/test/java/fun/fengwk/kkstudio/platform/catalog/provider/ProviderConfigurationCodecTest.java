package fun.fengwk.kkstudio.platform.catalog.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/** Provider timeout 配置的默认、合并和非法持久数据边界。 */
class ProviderConfigurationCodecTest {

  private final ObjectMapper objectMapper = JsonMapper.builder().build();
  private final ProviderConfigurationCodec codec = new ProviderConfigurationCodec(objectMapper);

  @Test
  void missingTimeoutsUseProductDefaults() {
    assertEquals(ModelCallTimeoutPolicy.DEFAULT, codec.readTimeoutPolicy(null));
    assertEquals(ModelCallTimeoutPolicy.DEFAULT, codec.readTimeoutPolicy("{}"));
  }

  @Test
  void mergeNormalizesTimeoutsAndPreservesRetryConfiguration() throws Exception {
    String config =
        codec.mergeTimeoutPolicy(
            "{\"modelHttpRetryStatusCodes\":[429]}", Duration.ofMinutes(2).toMillis(), 3_000L);

    JsonNode node = objectMapper.readTree(config);
    assertEquals(3, node.size());
    assertEquals(List.of(429), codec.readHttpRetryStatusCodes(config));
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
    // 测试意图：损坏的持久配置可能含敏感值；语法失败只保留稳定错误，不挂载可能携带原文的
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
    ProviderConfigurationCodec failingCodec =
        new ProviderConfigurationCodec(new FailingWriteObjectMapper());

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> failingCodec.mergeTimeoutPolicy("{}", 1_000L, 2_000L));

    assertEquals("cannot encode provider configuration", error.getMessage());
  }

  @Test
  void rejectsUnknownFieldsOnReadsAndWritesWithoutExposingInput() {
    // 损坏配置不能在任一读取或合并路径被忽略、保留或泄露。
    String corrupt = "{\"modelCallTimeoutMillis\":1000,\"unknown\":\"private-value\"}";
    List<Runnable> operations =
        List.of(
            () -> codec.readTimeoutPolicy(corrupt),
            () -> codec.readHttpRetryStatusCodes(corrupt),
            () -> codec.mergeTimeoutPolicy(corrupt, 1000L, 2000L),
            () -> codec.mergeHttpRetryStatusCodes(corrupt, List.of(429)),
            () -> codec.mergeHttpRetryStatusCodes(corrupt, null));
    for (Runnable operation : operations) {
      IllegalArgumentException error = assertThrows(IllegalArgumentException.class, operation::run);
      assertEquals("persisted provider configJson has unknown field", error.getMessage());
      assertNull(error.getCause());
    }
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
  void mergesOverrideWithClearAndReplaceSemantics() throws Exception {
    String existing = "{\"modelCallTimeoutMillis\":1000,\"modelHttpRetryStatusCodes\":[429]}";

    // null：清除覆盖（继承系统名单）。
    String cleared = codec.mergeHttpRetryStatusCodes(existing, null);
    assertNull(codec.readHttpRetryStatusCodes(cleared));
    assertEquals(1000L, objectMapper.readTree(cleared).path("modelCallTimeoutMillis").asLong());

    // 数组：完全替代。
    String replaced = codec.mergeHttpRetryStatusCodes(existing, List.of(500, 503));
    assertEquals(List.of(500, 503), codec.readHttpRetryStatusCodes(replaced));

    // 空数组：明确禁用。
    String disabled = codec.mergeHttpRetryStatusCodes(existing, List.of());
    assertEquals(List.of(), codec.readHttpRetryStatusCodes(disabled));
  }

  @Test
  void rejectsInvalidOverrideOnMerge() {
    assertThrows(
        IllegalArgumentException.class, () -> codec.mergeHttpRetryStatusCodes("{}", List.of(399)));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.mergeHttpRetryStatusCodes("{}", List.of(429, 429)));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.mergeHttpRetryStatusCodes("{}", Arrays.asList(429, null)));
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
