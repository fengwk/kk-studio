package fun.fengwk.kkstudio.platform.catalog.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

/** 新 pricing contract 的 decimal 字符串与 null TTL 普通写价 wire 语义。 */
class ModelPricingDtoJsonTest {

  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void roundTripsTypedRulesChangesAndTtlOnlyWriteRate() throws Exception {
    String json =
        "{\"base\":{\"input\":\"1.25\",\"output\":\"2.5\","
            + "\"cacheWriteByTtl\":{\"300\":\"0.5\"}},"
            + "\"rules\":[{\"type\":\"input_token_range\",\"minInputTokens\":200000,"
            + "\"maxInputTokensExclusive\":300000,\"input\":\"3\"},"
            + "{\"type\":\"service_tier_multiplier\",\"serviceTier\":\"fast\","
            + "\"factor\":\"2\",\"cacheReadFactor\":\"1\"}],"
            + "\"changes\":[{\"from\":\"2027-01-01\",\"base\":{\"input\":\"4\","
            + "\"output\":\"8\",\"cacheWrite\":\"1\"},\"rules\":[]}]}";

    ModelPricingDTO decoded = mapper.readValue(json, ModelPricingDTO.class);
    assertNull(decoded.getBase().getCacheWrite());
    assertEquals(new BigDecimal("0.5"), decoded.getBase().getCacheWriteByTtl().get("300"));
    assertEquals("input_token_range", decoded.getRules().get(0).getType());
    assertEquals(new BigDecimal("3"), decoded.getRules().get(0).getInput());
    assertEquals("2027-01-01", decoded.getChanges().get(0).getFrom());
    assertEquals(new BigDecimal("4"), decoded.getChanges().get(0).getBase().getInput());

    ModelPricingDTO roundTripped =
        mapper.readValue(mapper.writeValueAsString(decoded), ModelPricingDTO.class);
    assertNull(roundTripped.getBase().getCacheWrite());
    assertEquals(decoded.getRules(), roundTripped.getRules());
    assertEquals(decoded.getChanges(), roundTripped.getChanges());
  }

  @Test
  void rejectsJsonNumbersForRulePrices() {
    assertThrows(
        JacksonException.class,
        () ->
            mapper.readValue(
                "{\"type\":\"time_multiplier\",\"start\":\"01:00\",\"end\":\"02:00\","
                    + "\"timezone\":\"UTC\",\"factor\":1.25}",
                ModelPricingRuleDTO.class));
  }
}
