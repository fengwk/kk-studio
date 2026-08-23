package fun.fengwk.kkstudio.platform.catalog.provider.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;

import java.time.Duration;

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
  void reportsInternalFailureWhenCanonicalConfigurationCannotSerialize() {
    AgentProviderConfigurationCodec failingCodec =
        new AgentProviderConfigurationCodec(new FailingWriteObjectMapper());

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> failingCodec.mergeTimeoutPolicy("{}", 1_000L, 2_000L));

    assertEquals("cannot encode provider configuration", error.getMessage());
  }

  private static final class FailingWriteObjectMapper extends ObjectMapper {

    @Override
    public String writeValueAsString(Object value) throws JsonProcessingException {
      throw new JsonProcessingException("write failed") {};
    }
  }
}
