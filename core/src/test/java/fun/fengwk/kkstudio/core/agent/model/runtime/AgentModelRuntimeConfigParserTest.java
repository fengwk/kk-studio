package fun.fengwk.kkstudio.core.agent.model.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.ModelCapability;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

class AgentModelRuntimeConfigParserTest {

  private final AgentModelRuntimeConfigParser parser =
      new AgentModelRuntimeConfigParser(new ObjectMapper());

  /** Every executable field, including six independent prices, survives strict parsing. */
  @Test
  void parsesCompleteRuntimeModelWithoutTokenBasedPriceInference() {
    var parsed = parser.parse("[\"TEXT\",\"TOOLS\",\"THINKING\"]", validConfig());

    assertEquals(128000L, parsed.contextWindow());
    assertEquals(8192L, parsed.maxOutputTokens());
    assertEquals(
        Set.of(ModelInputModality.TEXT, ModelInputModality.IMAGE), parsed.inputModalities());
    assertEquals(
        Set.of(ModelCapability.TEXT, ModelCapability.TOOLS, ModelCapability.THINKING),
        parsed.capabilities());
    assertEquals(1, parsed.variants().size());
    assertEquals("quality", parsed.variants().get(0).name());
    assertEquals(4096, parsed.variants().get(0).maxOutputTokens());
    assertEquals(0.4, parsed.variants().get(0).temperature());
    assertEquals(0.8, parsed.variants().get(0).topP());
    assertEquals(20, parsed.variants().get(0).topK());
    assertEquals(0.1, parsed.variants().get(0).frequencyPenalty());
    assertEquals(0.2, parsed.variants().get(0).presencePenalty());
    assertEquals(List.of("done"), parsed.variants().get(0).stopSequences());
    assertEquals("USD", parsed.pricing().currency());
    assertEquals("batch", parsed.pricing().pricingTier());
    assertEquals("priority", parsed.pricing().serviceTier());
    assertEquals(new BigDecimal("1.25"), parsed.pricing().serviceTierMultiplier());
    assertEquals("2026-07-16", parsed.pricing().version());
    assertEquals(new BigDecimal("1.1"), parsed.pricing().inputPerMillionTokens());
    assertEquals(new BigDecimal("2.2"), parsed.pricing().outputPerMillionTokens());
    assertEquals(new BigDecimal("0.3"), parsed.pricing().cacheReadPerMillionTokens());
    assertEquals(new BigDecimal("0.4"), parsed.pricing().cacheWritePerMillionTokens());
    assertEquals(new BigDecimal("0.5"), parsed.pricing().cacheWriteLongPerMillionTokens());
    assertEquals(new BigDecimal("3.6"), parsed.pricing().reasoningPerMillionTokens());
  }

  /** Missing fields, wrong JSON types, unknown enums, and invalid variants fail explicitly. */
  @Test
  void rejectsIncompleteOrMistypedExecutableConfiguration() {
    assertInvalid("[]", "{}", "contextWindow");
    assertInvalid("[]", validConfig().replace("128000", "\"128000\""), "contextWindow");
    assertInvalid("[\"UNKNOWN\"]", validConfig(), "unsupported value");
    assertInvalid(
        "[]",
        validConfig().replace("[\"TEXT\",\"IMAGE\"]", "[]"),
        "inputModalities must not be empty");
    assertInvalid("[]", validConfig().replace("\"topP\":0.8", "\"topP\":2"), "topP must be in");
    assertInvalid(
        "[]",
        validConfig()
            .replace("\"reasoningPerMillionTokens\":3.6", "\"reasoningPerMillionTokens\":null"),
        "reasoningPerMillionTokens");
    assertInvalid("{}", validConfig(), "capabilitiesJson must be an array");
  }

  /** Structural and numeric boundaries reject every shape that could create an unusable model. */
  @Test
  void rejectsInvalidVariantPricingAndJsonBoundaries() {
    assertInvalid("", validConfig(), "capabilitiesJson must not be blank");
    assertInvalid("[]", "not-json", "configJson must be valid JSON");
    assertInvalid("[]", "[]", "configJson must be an object");
    assertInvalid(
        "[]",
        validConfig().replace("\"maxOutputTokens\":8192", "\"maxOutputTokens\":128001"),
        "must not exceed contextWindow");
    assertInvalid(
        "[]",
        validConfig().replace("\"variants\":[{", "\"variants\":[] ,\"ignored\":[{"),
        "variants must not be empty");
    assertInvalid(
        "[]",
        validConfig().replace("\"variants\":[{", "\"variants\":[1,{"),
        "variants[0] must be an object");
    assertInvalid(
        "[]",
        validConfig()
            .replace(
                "\"stopSequences\":[\"done\"]}],",
                "\"stopSequences\":[\"done\"]},{\"name\":\"quality\"}],"),
        "duplicate name");
    assertInvalid(
        "[]",
        validConfig().replace("\"maxOutputTokens\":4096", "\"maxOutputTokens\":999999"),
        "must not exceed model");
    assertInvalid("[]", validConfig().replace("\"topK\":20", "\"topK\":0"), "topK");
    assertInvalid(
        "[]",
        validConfig().replace("\"temperature\":0.4", "\"temperature\":\"hot\""),
        "temperature must be a number");
    assertInvalid(
        "[]",
        validConfig().replace("\"stopSequences\":[\"done\"]", "\"stopSequences\":[1]"),
        "stopSequences must contain");
    assertInvalid(
        "[]",
        validConfig().replace("\"serviceTierMultiplier\":1.25", "\"serviceTierMultiplier\":0"),
        "serviceTierMultiplier must be positive");
    assertInvalid(
        "[]",
        validConfig().replace("\"inputPerMillionTokens\":1.1", "\"inputPerMillionTokens\":-1"),
        "inputPerMillionTokens must not be negative");
  }

  private void assertInvalid(String capabilities, String config, String message) {
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> parser.parse(capabilities, config));
    assertTrue(error.getMessage().contains(message), error.getMessage());
  }

  private String validConfig() {
    return "{\"contextWindow\":128000,\"maxOutputTokens\":8192,"
        + "\"inputModalities\":[\"TEXT\",\"IMAGE\"],"
        + "\"variants\":[{\"name\":\"quality\",\"maxOutputTokens\":4096,"
        + "\"temperature\":0.4,\"topP\":0.8,\"topK\":20,"
        + "\"frequencyPenalty\":0.1,\"presencePenalty\":0.2,"
        + "\"stopSequences\":[\"done\"]}],"
        + "\"pricing\":{\"currency\":\"USD\",\"pricingTier\":\"batch\","
        + "\"serviceTier\":\"priority\",\"serviceTierMultiplier\":1.25,"
        + "\"version\":\"2026-07-16\",\"inputPerMillionTokens\":1.1,"
        + "\"outputPerMillionTokens\":2.2,\"cacheReadPerMillionTokens\":0.3,"
        + "\"cacheWritePerMillionTokens\":0.4,"
        + "\"cacheWriteLongPerMillionTokens\":0.5,"
        + "\"reasoningPerMillionTokens\":3.6}}";
  }
}
