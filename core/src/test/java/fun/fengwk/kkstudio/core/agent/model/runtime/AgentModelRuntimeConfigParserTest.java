package fun.fengwk.kkstudio.core.agent.model.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

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
    var parsed = parser.parse(validConfig());

    assertEquals(128000L, parsed.contextWindow());
    assertEquals(8192L, parsed.maxOutputTokens());
    assertEquals(
        Set.of(ModelInputModality.TEXT, ModelInputModality.IMAGE), parsed.inputModalities());
    assertTrue(parsed.tools());
    assertTrue(parsed.reasoning());
    assertEquals("quality", parsed.defaultVariant());
    assertEquals(1, parsed.variants().size());
    assertEquals("quality", parsed.variants().get(0).name());
    assertEquals(4096, parsed.variants().get(0).maxOutputTokens());
    assertEquals(0.4, parsed.variants().get(0).temperature());
    assertEquals(0.8, parsed.variants().get(0).topP());
    assertEquals(20, parsed.variants().get(0).topK());
    assertEquals(0.1, parsed.variants().get(0).frequencyPenalty());
    assertEquals(0.2, parsed.variants().get(0).presencePenalty());
    assertEquals(List.of("done"), parsed.variants().get(0).stopSequences());
    assertEquals("high", parsed.variants().get(0).reasoningEffort());
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

  /** off reasoningEffort is normalized to null. */
  @Test
  void normalizesOffReasoningEffortToNull() {
    String config =
        validConfig().replace("\"reasoningEffort\":\"high\"", "\"reasoningEffort\":\"off\"");
    var parsed = parser.parse(config);
    assertNull(parsed.variants().get(0).reasoningEffort());
  }

  /** Disabled reasoning surface as falsy tools/reasoning booleans on the descriptor. */
  @Test
  void exposesReasoningAndToolsAsBooleans() {
    String config = validConfig().replace("\"reasoning\":true", "\"reasoning\":false");
    var parsed = parser.parse(config);
    assertFalse(parsed.reasoning());
    assertTrue(parsed.tools());
  }

  /** Missing fields, wrong JSON types, unknown enums, and invalid variants fail explicitly. */
  @Test
  void rejectsIncompleteOrMistypedExecutableConfiguration() {
    assertInvalid("{}", "limit");
    assertInvalid(validConfig().replace("128000", "\"128000\""), "limit.context");
    assertInvalid(
        validConfig().replace("[\"TEXT\",\"IMAGE\"]", "[]"), "inputModalities must not be empty");
    assertInvalid(validConfig().replace("\"topP\":0.8", "\"topP\":2"), "topP must be in");
    assertInvalid(
        validConfig()
            .replace("\"reasoningPerMillionTokens\":3.6", "\"reasoningPerMillionTokens\":null"),
        "reasoningPerMillionTokens");
    assertInvalid(
        validConfig().replace("[\"TEXT\",\"IMAGE\"]", "[\"UNKNOWN\"]"),
        "contains unsupported value");
  }

  /** Structural and numeric boundaries reject every shape that could create an unusable model. */
  @Test
  void rejectsInvalidVariantPricingAndJsonBoundaries() {
    assertInvalid("", "configJson must not be blank");
    assertInvalid("not-json", "configJson must be valid JSON");
    assertInvalid("[]", "configJson must be an object");
    assertInvalid(
        validConfig().replace("\"output\":8192", "\"output\":128001"),
        "must not exceed limit.context");
    assertInvalid(
        validConfig()
            .replace(
                "\"variants\":[{\"id\":\"quality\",\"maxOutputTokens\":4096,"
                    + "\"temperature\":0.4,\"topP\":0.8,\"topK\":20,"
                    + "\"frequencyPenalty\":0.1,\"presencePenalty\":0.2,"
                    + "\"stopSequences\":[\"done\"],\"reasoningEffort\":\"high\"}]",
                "\"variants\":[]"),
        "variants must not be empty");
    assertInvalid(
        validConfig().replace("\"variants\":[{", "\"variants\":[1,{"),
        "variants[0] must be an object");
    assertInvalid(
        validConfig()
            .replace(
                "\"stopSequences\":[\"done\"],\"reasoningEffort\":\"high\"}]",
                "\"stopSequences\":[\"done\"],\"reasoningEffort\":\"high\"},{\"id\":\"quality\"}]"),
        "duplicate id");
    assertInvalid(
        validConfig().replace("\"defaultVariant\":\"quality\"", "\"defaultVariant\":\"missing\""),
        "defaultVariant must match");
    assertInvalid(
        validConfig().replace("\"maxOutputTokens\":4096", "\"maxOutputTokens\":999999"),
        "must not exceed model");
    assertInvalid(validConfig().replace("\"topK\":20", "\"topK\":0"), "topK");
    assertInvalid(
        validConfig().replace("\"temperature\":0.4", "\"temperature\":\"hot\""),
        "temperature must be a number");
    assertInvalid(
        validConfig().replace("\"stopSequences\":[\"done\"]", "\"stopSequences\":[1]"),
        "stopSequences must contain");
    assertInvalid(
        validConfig().replace("\"serviceTierMultiplier\":1.25", "\"serviceTierMultiplier\":0"),
        "serviceTierMultiplier must be positive");
    assertInvalid(
        validConfig().replace("\"inputPerMillionTokens\":1.1", "\"inputPerMillionTokens\":-1"),
        "inputPerMillionTokens must not be negative");
  }

  private void assertInvalid(String config, String message) {
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> parser.parse(config));
    assertTrue(error.getMessage().contains(message), error.getMessage());
  }

  private String validConfig() {
    return "{\"limit\":{\"context\":128000,\"output\":8192},"
        + "\"abilities\":{\"tools\":true,\"reasoning\":true,"
        + "\"inputModalities\":[\"TEXT\",\"IMAGE\"]},"
        + "\"defaultVariant\":\"quality\","
        + "\"variants\":[{\"id\":\"quality\",\"maxOutputTokens\":4096,"
        + "\"temperature\":0.4,\"topP\":0.8,\"topK\":20,"
        + "\"frequencyPenalty\":0.1,\"presencePenalty\":0.2,"
        + "\"stopSequences\":[\"done\"],\"reasoningEffort\":\"high\"}],"
        + "\"pricing\":{\"currency\":\"USD\",\"pricingTier\":\"batch\","
        + "\"serviceTier\":\"priority\",\"serviceTierMultiplier\":1.25,"
        + "\"version\":\"2026-07-16\",\"inputPerMillionTokens\":1.1,"
        + "\"outputPerMillionTokens\":2.2,\"cacheReadPerMillionTokens\":0.3,"
        + "\"cacheWritePerMillionTokens\":0.4,"
        + "\"cacheWriteLongPerMillionTokens\":0.5,"
        + "\"reasoningPerMillionTokens\":3.6}}";
  }
}
