package fun.fengwk.kkstudio.platform.ai.catalog.model.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

class AgentModelRuntimeConfigParserTest {

  private final AgentModelRuntimeConfigParser parser =
      new AgentModelRuntimeConfigParser(new ObjectMapper());

  /** 每个可执行字段（含 6 个独立价格）都经严格解析保留下来。 */
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
    assertEquals("quality", parsed.variants().get(0).id());
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

  /** 类型化 DTO 来回往返：decode -> encode -> parse 得到相同的 runtime 快照。 */
  @Test
  void decodeThenEncodeRoundTripsEveryTypedConfigField() {
    AgentModelConfigDTO decoded = parser.decode(validConfig());
    String encoded = parser.encode(decoded);
    var parsed = parser.parse(encoded);
    assertEquals("USD", parsed.pricing().currency());
    assertEquals("quality", parsed.defaultVariant());
    assertEquals(1, parsed.variants().size());
    assertEquals("high", parsed.variants().get(0).reasoningEffort());
  }

  /** off 的 reasoningEffort 规范化为 null。 */
  @Test
  void normalizesOffReasoningEffortToNull() {
    String config =
        validConfig().replace("\"reasoningEffort\":\"high\"", "\"reasoningEffort\":\"off\"");
    var parsed = parser.parse(config);
    assertNull(parsed.variants().get(0).reasoningEffort());
  }

  /** 禁用的 reasoning 在描述符上以 falsy 的 tools/reasoning 布尔形式呈现。 */
  @Test
  void exposesReasoningAndToolsAsBooleans() {
    String config = validConfig().replace("\"reasoning\":true", "\"reasoning\":false");
    var parsed = parser.parse(config);
    assertFalse(parsed.reasoning());
    assertTrue(parsed.tools());
  }

  /** 缺失字段、错误的 JSON 类型、未知枚举与无效 variant 都明确失败。 */
  @Test
  void rejectsIncompleteOrMistypedExecutableConfiguration() {
    assertInvalid("{}", "config.limit is required");
    assertInvalid(validConfig().replace("128000", "\"128000\""), "limit.context");
    assertInvalid(
        validConfig().replace("[\"TEXT\",\"IMAGE\"]", "[]"), "inputModalities must not be empty");
    assertInvalid(validConfig().replace("\"topP\":0.8", "\"topP\":2"), "topP must be in");
    assertInvalid(
        validConfig()
            .replace("\"reasoningPerMillionTokens\":3.6", "\"reasoningPerMillionTokens\":null"),
        "reasoningPerMillionTokens");
    assertInvalid(
        validConfig().replace("[\"TEXT\",\"IMAGE\"]", "[\"UNKNOWN\"]"), "inputModalities[0]");
  }

  /** 结构和数值边界会拒绝任何可能导致模型不可用的形态。 */
  @Test
  void rejectsInvalidVariantPricingAndJsonBoundaries() {
    assertInvalid("", "config must not be blank");
    assertInvalid("not-json", "config must be valid JSON");
    assertInvalid("[]", "config is invalid");
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
    assertInvalid(validConfig().replace("\"variants\":[{", "\"variants\":[1,{"), "variants[0]");
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
        "variants[0].temperature");
    assertInvalid(
        validConfig().replace("\"stopSequences\":[\"done\"]", "\"stopSequences\":[1]"),
        "stopSequences[0]");
    assertInvalid(
        validConfig().replace("\"serviceTierMultiplier\":1.25", "\"serviceTierMultiplier\":0"),
        "serviceTierMultiplier must be positive");
    assertInvalid(
        validConfig().replace("\"inputPerMillionTokens\":1.1", "\"inputPerMillionTokens\":-1"),
        "inputPerMillionTokens must not be negative");
  }

  /** 已持久化的 JSON 永远不能被静默修复：null/空白/畸形结构应大声抛出。 */
  @Test
  void rejectsInvalidPersistedJsonShapes() {
    assertInvalid("", "config must not be blank");
    assertInvalid("   ", "config must not be blank");
    assertInvalid("not-json", "config must be valid JSON");
    assertInvalid("null", "config is required");
    assertInvalid("[1,2,3]", "config is invalid");
    assertInvalid("{\"limit\":{}}", "config.limit.context");
    assertInvalid(
        "{\"limit\":{\"context\":1,\"output\":1},\"abilities\":{\"tools\":true,"
            + "\"reasoning\":true,\"inputModalities\":[\"TEXT\"]},"
            + "\"defaultVariant\":\"x\",\"variants\":[],\"pricing\":{"
            + "\"currency\":\"USD\",\"pricingTier\":\"x\",\"serviceTier\":\"x\","
            + "\"serviceTierMultiplier\":1,\"version\":\"v\",\"inputPerMillionTokens\":0,"
            + "\"outputPerMillionTokens\":0,\"cacheReadPerMillionTokens\":0,"
            + "\"cacheWritePerMillionTokens\":0,\"cacheWriteLongPerMillionTokens\":0,"
            + "\"reasoningPerMillionTokens\":0}}",
        "config.variants must not be empty");
  }

  /** 持久化配置解码拒绝未知字段、标量强转与尾随文档。 */
  @Test
  void rejectsUnknownFieldsCoercionAndTrailingJson() {
    assertInvalid(
        validConfig().replace("{\"limit\"", "{\"unknown\":true,\"limit\""), "config.unknown");
    assertInvalid(
        validConfig().replace("\"context\":128000", "\"context\":128000.5"), "limit.context");
    assertInvalid(validConfig() + " {}", "config is invalid");
    assertInvalid(
        validConfig().replace("\"id\":\"quality\"", "\"id\":\" quality \""),
        "surrounding whitespace");
  }

  /** Provider 特定的 penalty 区间可能包含负值；通用契约仅要求有穷性。 */
  @Test
  void acceptsFiniteNegativePenalties() {
    var parsed =
        parser.parse(
            validConfig()
                .replace("\"frequencyPenalty\":0.1", "\"frequencyPenalty\":-0.5")
                .replace("\"presencePenalty\":0.2", "\"presencePenalty\":-1.0"));

    assertEquals(-0.5, parsed.variants().get(0).frequencyPenalty());
    assertEquals(-1.0, parsed.variants().get(0).presencePenalty());
  }

  /** 类型化 DTO 形式拒绝 null 和损坏的子结构；部分子结构会以明确方式失败。 */
  @Test
  void rejectsInvalidTypedConfigDirectly() {
    AgentModelConfigDTO base = parser.decode(validConfig());
    assertNotNull(base);
    AgentModelConfigDTO missingLimit = copy(base, c -> c.setLimit(null));
    IllegalArgumentException limitError =
        assertThrows(IllegalArgumentException.class, () -> parser.parse(missingLimit));
    assertTrue(
        limitError.getMessage().contains("config.limit is required"), limitError.getMessage());

    AgentModelConfigDTO missingAbilities = copy(base, c -> c.setAbilities(null));
    IllegalArgumentException abilitiesError =
        assertThrows(IllegalArgumentException.class, () -> parser.parse(missingAbilities));
    assertTrue(
        abilitiesError.getMessage().contains("config.abilities is required"),
        abilitiesError.getMessage());

    AgentModelConfigDTO missingPricing = copy(base, c -> c.setPricing(null));
    IllegalArgumentException pricingError =
        assertThrows(IllegalArgumentException.class, () -> parser.parse(missingPricing));
    assertTrue(
        pricingError.getMessage().contains("config.pricing is required"),
        pricingError.getMessage());

    AgentModelConfigDTO zeroMultiplier =
        copy(base, c -> c.getPricing().setServiceTierMultiplier(BigDecimal.ZERO));
    IllegalArgumentException multiplierError =
        assertThrows(IllegalArgumentException.class, () -> parser.parse(zeroMultiplier));
    assertTrue(
        multiplierError
            .getMessage()
            .contains("config.pricing.serviceTierMultiplier must be positive"),
        multiplierError.getMessage());
  }

  private static AgentModelConfigDTO copy(
      AgentModelConfigDTO source, Consumer<AgentModelConfigDTO> mutator) {
    try {
      String json = new ObjectMapper().writeValueAsString(source);
      AgentModelConfigDTO clone = new ObjectMapper().readValue(json, AgentModelConfigDTO.class);
      mutator.accept(clone);
      return clone;
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
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
