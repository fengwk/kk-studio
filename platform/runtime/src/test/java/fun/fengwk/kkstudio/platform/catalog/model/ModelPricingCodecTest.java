package fun.fengwk.kkstudio.platform.catalog.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingRuleDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelRateSetDTO;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** DTO 三态经 codec 转为同一运行时 pricing 语义，不将缺字段折成零价。 */
class ModelPricingCodecTest {

  @Test
  void mapsOptionalRangeRatesTtlClearingAndExplicitReasoningNull() {
    ModelPricingDTO dto = pricingWithRange(true, true);

    ModelPricing pricing = ModelPricingCodec.parsePricing(dto);
    ModelPricing.InputTokenRange range = (ModelPricing.InputTokenRange) pricing.rules().getFirst();

    assertEquals(0, range.minInputTokens());
    assertTrue(range.cacheWriteByTtlSpecified());
    assertEquals(Map.of(), range.cacheWriteByTtl());
    assertTrue(range.reasoningSpecified());
    assertNull(range.reasoning());

    ModelCost cost =
        ModelCost.calculate(
            pricing,
            new ModelUsage(
                1_000_000, 1_000, 1_000_000, 1_000_000, Map.of(300L, 1_000_000L), 500L, null));
    assertEquals(new BigDecimal("3"), cost.cacheRead());
    assertEquals(new BigDecimal("2"), cost.cacheWrite());
    assertEquals(BigDecimal.ZERO, cost.reasoning());
  }

  @Test
  void omittedRangeTtlAndReasoningInheritBase() {
    ModelPricing pricing = ModelPricingCodec.parsePricing(pricingWithRange(false, false));
    ModelPricing.InputTokenRange range = (ModelPricing.InputTokenRange) pricing.rules().getFirst();
    assertFalse(range.cacheWriteByTtlSpecified());
    assertFalse(range.reasoningSpecified());

    ModelCost cost =
        ModelCost.calculate(
            pricing,
            new ModelUsage(
                1_000_000, 1_000, 1_000_000, 1_000_000, Map.of(300L, 1_000_000L), 500L, null));
    assertEquals(new BigDecimal("9"), cost.cacheWrite());
    assertEquals(new BigDecimal("0.005"), cost.reasoning());
  }

  @Test
  void rejectsRangeWithoutEitherBoundaryAndRejectsRuleFieldsFromOtherTypes() {
    ModelPricingRuleDTO noBounds = new ModelPricingRuleDTO();
    noBounds.setType("input_token_range");
    noBounds.setInput(BigDecimal.ONE);
    assertThrows(
        IllegalArgumentException.class, () -> ModelPricingCodec.parsePricing(pricing(noBounds)));

    ModelPricingRuleDTO wrongTypeField = new ModelPricingRuleDTO();
    wrongTypeField.setType("service_tier_multiplier");
    wrongTypeField.setServiceTier("fast");
    wrongTypeField.setFactor(BigDecimal.ONE);
    wrongTypeField.setCacheRead(BigDecimal.ONE);
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelPricingCodec.parsePricing(pricing(wrongTypeField)));
  }

  @Test
  void validatesNullRulesAndIllegalTypeFieldsBeforeNormalization() {
    ModelPricingDTO nullRule = new ModelPricingDTO();
    nullRule.setRules(Arrays.asList((ModelPricingRuleDTO) null));
    assertThrows(IllegalArgumentException.class, () -> ModelPricingCodec.parsePricing(nullRule));

    ModelPricingRuleDTO illegalFactor = new ModelPricingRuleDTO();
    illegalFactor.setType("input_token_range");
    illegalFactor.setMaxInputTokensExclusive(100);
    illegalFactor.setInput(BigDecimal.ONE);
    illegalFactor.setFactor(BigDecimal.ONE);
    illegalFactor.setInputFactor(BigDecimal.ONE);
    ModelPricingDTO invalidTypeField = pricing(illegalFactor);
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> ModelPricingCodec.parsePricing(invalidTypeField));
    assertTrue(error.getMessage().contains("is not valid for this type"), error.getMessage());
  }

  @Test
  void missingPricingDiffersFromAnExplicitZeroPrice() {
    assertNull(ModelPricingCodec.parsePricing(null));
    ModelPricing explicitZero = ModelPricingCodec.parsePricing(new ModelPricingDTO());
    assertEquals(BigDecimal.ZERO, explicitZero.base().input());
  }

  private static ModelPricingDTO pricingWithRange(
      boolean ttlSpecified, boolean reasoningSpecified) {
    ModelRateSetDTO base = new ModelRateSetDTO();
    base.setInput(BigDecimal.ONE);
    base.setOutput(new BigDecimal("5"));
    base.setCacheRead(new BigDecimal("2"));
    base.setCacheWrite(new BigDecimal("2"));
    base.setCacheWriteByTtl(Map.of("300", new BigDecimal("9")));
    base.setReasoning(new BigDecimal("10"));

    ModelPricingRuleDTO range = new ModelPricingRuleDTO();
    range.setType("input_token_range");
    range.setMaxInputTokensExclusive(4_000_000);
    range.setCacheRead(new BigDecimal("3"));
    if (ttlSpecified) {
      range.setCacheWriteByTtl(Map.of());
    }
    if (reasoningSpecified) {
      range.setReasoning(null);
    }

    ModelPricingDTO pricing = new ModelPricingDTO();
    pricing.setBase(base);
    pricing.setRules(List.of(range));
    pricing.setChanges(List.of());
    return pricing;
  }

  private static ModelPricingDTO pricing(ModelPricingRuleDTO rule) {
    ModelPricingDTO pricing = new ModelPricingDTO();
    pricing.setRules(List.of(rule));
    return pricing;
  }
}
