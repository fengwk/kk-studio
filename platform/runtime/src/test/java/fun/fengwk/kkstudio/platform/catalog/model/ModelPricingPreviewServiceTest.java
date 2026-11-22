package fun.fengwk.kkstudio.platform.catalog.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingChangeDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingPreviewRequestDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingRuleDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelRateSetDTO;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

class ModelPricingPreviewServiceTest {

  private final ModelPricingPreviewService service = new ModelPricingPreviewService();

  @Test
  void previewsCurrentDateRangeTierAndTimeWithExactRates() {
    ModelPricingPreviewRequestDTO request = new ModelPricingPreviewRequestDTO();
    request.setPricing(pricing());
    request.setInputTokens(250);
    request.setRequestedAt(Instant.parse("2027-01-03T02:00:00Z"));
    request.setServiceTier("fast");

    var preview = service.preview(request);

    assertEquals(new BigDecimal("3.0"), preview.getRates().getInput());
    assertEquals(new BigDecimal("6.0"), preview.getRates().getOutput());
    assertNull(preview.getRates().getCacheWrite());
    assertEquals(Map.of("300", new BigDecimal("2.0")), preview.getRates().getCacheWriteByTtl());
    assertNull(preview.getRates().getReasoning());
    assertEquals("2027-01-01", preview.getActiveFrom());
    assertEquals(
        List.of("input_token_range", "service_tier_multiplier", "time_multiplier"),
        preview.getMatchedRules().stream().map(ModelPricingRuleDTO::getType).toList());
  }

  @Test
  void unknownPricingAndMissingFactsDoNotBecomeZeroPrice() {
    ModelPricingPreviewRequestDTO request = new ModelPricingPreviewRequestDTO();
    request.setInputTokens(10);
    assertNull(service.preview(request).getRates());

    request.setPricing(pricing());
    request.setRequestedAt(null);
    request.setServiceTier(null);
    var missingFacts = service.preview(request);
    assertNull(missingFacts.getRates());
    assertNull(missingFacts.getActiveFrom());
    assertEquals(List.of(), missingFacts.getMatchedRules());
  }

  /**
   * 测试意图：客户端可修正的输入错误必须以 validation 失败返回（HTTP 400），不能是裸 IllegalArgumentException（web 层会落到 500）。与保存路径
   * ModelService.modelMutation 对非法价格的分类一致。
   */
  @Test
  void rejectsClientInputErrorsAsValidationFailures() {
    ModelPricingPreviewRequestDTO negativeTokens = new ModelPricingPreviewRequestDTO();
    negativeTokens.setInputTokens(-1);
    assertEquals(
        DomainErrorCode.VALIDATION,
        assertThrows(DomainException.class, () -> service.preview(negativeTokens)).code());

    ModelPricingPreviewRequestDTO duplicateTier = new ModelPricingPreviewRequestDTO();
    duplicateTier.setInputTokens(10);
    ModelPricingDTO pricing = new ModelPricingDTO();
    pricing.setRules(List.of(tierMultiplier("fast"), tierMultiplier("fast")));
    duplicateTier.setPricing(pricing);
    assertEquals(
        DomainErrorCode.VALIDATION,
        assertThrows(DomainException.class, () -> service.preview(duplicateTier)).code());
  }

  private static ModelPricingDTO pricing() {
    ModelPricingDTO pricing = new ModelPricingDTO();
    ModelRateSetDTO base = new ModelRateSetDTO();
    base.setInput(new BigDecimal("1"));
    base.setOutput(new BigDecimal("2"));
    base.setCacheRead(BigDecimal.ZERO);
    base.setCacheWriteByTtl(Map.of("300", new BigDecimal("2")));
    pricing.setBase(base);
    pricing.setRules(List.of(range(), tierMultiplier("fast"), time()));
    ModelPricingChangeDTO change = new ModelPricingChangeDTO();
    change.setFrom("2027-01-01");
    ModelRateSetDTO changedBase = new ModelRateSetDTO();
    changedBase.setInput(new BigDecimal("2"));
    changedBase.setOutput(new BigDecimal("4"));
    changedBase.setCacheRead(BigDecimal.ZERO);
    changedBase.setCacheWriteByTtl(Map.of("300", new BigDecimal("2")));
    change.setBase(changedBase);
    change.setRules(pricing.getRules());
    pricing.setChanges(List.of(change));
    return pricing;
  }

  private static ModelPricingRuleDTO range() {
    ModelPricingRuleDTO rule = new ModelPricingRuleDTO();
    rule.setType("input_token_range");
    rule.setMinInputTokens(100);
    rule.setMaxInputTokensExclusive(500);
    rule.setInput(new BigDecimal("3"));
    rule.setOutput(new BigDecimal("6"));
    return rule;
  }

  private static ModelPricingRuleDTO tierMultiplier(String serviceTier) {
    ModelPricingRuleDTO rule = new ModelPricingRuleDTO();
    rule.setType("service_tier_multiplier");
    rule.setServiceTier(serviceTier);
    rule.setFactor(new BigDecimal("2"));
    return rule;
  }

  private static ModelPricingRuleDTO time() {
    ModelPricingRuleDTO rule = new ModelPricingRuleDTO();
    rule.setType("time_multiplier");
    rule.setStart("01:00");
    rule.setEnd("03:00");
    rule.setTimezone("UTC");
    rule.setFactor(new BigDecimal("0.5"));
    return rule;
  }
}
