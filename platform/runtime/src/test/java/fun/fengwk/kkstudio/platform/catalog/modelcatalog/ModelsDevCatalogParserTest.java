package fun.fengwk.kkstudio.platform.catalog.modelcatalog;

import static fun.fengwk.kkstudio.platform.catalog.modelcatalog.ModelCatalogFixtures.assertDecimal;
import static fun.fengwk.kkstudio.platform.catalog.modelcatalog.ModelCatalogFixtures.index;
import static fun.fengwk.kkstudio.platform.catalog.modelcatalog.ModelCatalogFixtures.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelCatalogCandidateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingRuleDTO;
import fun.fengwk.kkstudio.platform.catalog.model.ModelPricingCodec;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** models.dev 的真实目录字段与固定定价规则之间的精确转换边界。 */
class ModelsDevCatalogParserTest {

  private final ModelsDevCatalogParser parser = new ModelsDevCatalogParser();

  @Test
  void mapsContextRangesTierTimeAndPerItemDecimalFactorsExactly() {
    Map<String, ModelCatalogCandidateDTO> candidates =
        index(parser.parse(resource("models-dev-pricing-exact.json")));
    ModelCatalogCandidateDTO candidate = candidates.get("fixture/exact-ranges-and-factors");
    assertTrue(candidate.isPricingSupported());

    ModelPricingDTO pricing = candidate.getPricing();
    assertDecimal("2", pricing.getBase().getInput());
    assertDecimal("5", pricing.getBase().getOutput());
    assertEquals(5, pricing.getRules().size());

    ModelPricingRuleDTO range = rule(pricing, "input_token_range");
    assertEquals(101, range.getMinInputTokens());
    assertNull(range.getMaxInputTokensExclusive());
    assertDecimal("4", range.getInput());
    assertDecimal("10", range.getOutput());

    List<ModelPricingRuleDTO> tiers =
        pricing.getRules().stream()
            .filter(candidateRule -> "service_tier_multiplier".equals(candidateRule.getType()))
            .toList();
    assertEquals(
        List.of("priority", "flex"),
        tiers.stream().map(ModelPricingRuleDTO::getServiceTier).toList());
    ModelPricingRuleDTO tier = tiers.get(0);
    assertDecimal("1", tier.getFactor());
    assertDecimal("2", tier.getInputFactor());
    assertDecimal("5", tier.getOutputFactor());
    assertDecimal("3", tier.getCacheReadFactor());
    assertDecimal("4", tier.getCacheWriteFactor());
    assertDecimal("6", tier.getReasoningFactor());

    ModelPricingRuleDTO time = rule(pricing, "time_multiplier");
    assertEquals("01:00", time.getStart());
    assertEquals("03:00", time.getEnd());
    assertEquals("UTC", time.getTimezone());
    assertEquals(List.of(1), time.getWeekdays());
    assertTrue(
        pricing.getRules().stream()
            .filter(candidateRule -> "time_multiplier".equals(candidateRule.getType()))
            .anyMatch(candidateRule -> List.of(3).equals(candidateRule.getWeekdays())));
    assertDecimal("0.333333333333333333", time.getFactor());

    // These are the same range-then-tier semantics consumed by runtime costing.
    ModelPricing modelPricing = ModelPricingCodec.parsePricing(pricing);
    ModelCost cost =
        ModelCost.calculate(
            modelPricing,
            new ModelUsage(101, 10, 0, 0, Map.of(), null, null),
            Instant.parse("2026-11-24T12:00:00Z"),
            "priority");
    assertDecimal("0.000808", cost.input());
    assertDecimal("0.0005", cost.output());
  }

  @Test
  void importsARealModelsDevTierMultiplierAndTtlRates() {
    ModelCatalogCandidateDTO opus =
        index(parser.parse(resource("models-dev-claude-opus-5-5.json")))
            .get("anthropic/claude-opus-5-5");
    assertTrue(opus.isPricingSupported());
    assertTrue(
        opus.getSupportIssues().stream()
            .anyMatch(issue -> issue.contains("ignored region multiplier")));
    assertDecimal("4", opus.getPricing().getBase().getInput());
    assertDecimal("20", opus.getPricing().getBase().getOutput());
    assertDecimal("5", opus.getPricing().getBase().getCacheWriteByTtl().get("300"));
    assertEquals(1, opus.getPricing().getRules().size());
    ModelPricingRuleDTO fast = opus.getPricing().getRules().get(0);
    assertEquals("service_tier_multiplier", fast.getType());
    assertEquals("fast", fast.getServiceTier());
    assertDecimal("2", fast.getFactor());
  }

  @Test
  void schedulesDateRatesAndDateConditionedTierRulesAsCompleteDefinitions() {
    ModelPricingDTO pricing =
        index(parser.parse(resource("models-dev-pricing-exact.json")))
            .get("fixture/date-rate-and-tier-changes")
            .getPricing();
    assertDecimal("2", pricing.getBase().getInput());
    assertEquals(3, pricing.getChanges().size());
    assertEquals("2027-01-01", pricing.getChanges().get(0).getFrom());
    assertDecimal("2.5", pricing.getChanges().get(0).getBase().getInput());
    assertDecimal("7.5", pricing.getChanges().get(0).getBase().getOutput());
    assertTrue(pricing.getChanges().get(0).getRules().isEmpty());
    assertEquals("2027-02-01", pricing.getChanges().get(1).getFrom());
    assertEquals("2027-03-01", pricing.getChanges().get(2).getFrom());
    assertDecimal("2", pricing.getChanges().get(1).getRules().get(0).getFactor());
    assertDecimal("6", pricing.getChanges().get(2).getRules().get(0).getFactor());

    ModelPricing modelPricing = ModelPricingCodec.parsePricing(pricing);
    ModelCost cost =
        ModelCost.calculate(
            modelPricing,
            new ModelUsage(1_000_000, 0, 0, 0, Map.of(), null, null),
            Instant.parse("2027-02-15T12:00:00Z"),
            "priority");
    assertDecimal("5", cost.input());
  }

  @Test
  void mapsAllContextFeeOverridesAndExplicitReasoningNull() {
    ModelCatalogCandidateDTO candidate =
        index(parser.parse(resource("models-dev-pricing-exact.json")))
            .get("fixture/context-overrides-all-token-fees");
    assertTrue(candidate.isPricingSupported());

    ModelPricingRuleDTO range = rule(candidate.getPricing(), "input_token_range");
    assertEquals(101, range.getMinInputTokens());
    assertNull(range.getMaxInputTokensExclusive());
    assertDecimal("4", range.getInput());
    assertDecimal("10", range.getOutput());
    assertDecimal("0.75", range.getCacheRead());
    assertEquals(
        Map.of("300", new BigDecimal("1"), "3600", new BigDecimal("2")),
        range.getCacheWriteByTtl());
    assertTrue(range.isCacheWriteByTtlSpecified());
    assertTrue(range.isReasoningSpecified());
    assertNull(range.getReasoning());

    ModelPricing modelPricing = ModelPricingCodec.parsePricing(candidate.getPricing());
    ModelCost cost =
        ModelCost.calculate(
            modelPricing,
            new ModelUsage(1_000_000, 1_000_000, 0, 0, Map.of(), 500_000L, null),
            Instant.parse("2026-11-23T12:00:00Z"),
            null);
    assertDecimal("4", cost.input());
    assertDecimal("10", cost.output());
    assertDecimal("0", cost.reasoning());
  }

  @Test
  void combinesOverlappingTierAndUtcTimeFactorsExactly() {
    ModelPricingDTO pricing =
        index(parser.parse(resource("models-dev-pricing-exact.json")))
            .get("fixture/overlapping-tier-and-time-products")
            .getPricing();
    assertTrue(
        pricing.getRules().stream().anyMatch(rule -> "priority".equals(rule.getServiceTier())));
    ModelPricingRuleDTO tier =
        pricing.getRules().stream()
            .filter(rule -> "priority".equals(rule.getServiceTier()))
            .findFirst()
            .orElseThrow();
    assertDecimal("6", tier.getFactor());

    List<ModelPricingRuleDTO> times =
        pricing.getRules().stream()
            .filter(rule -> "time_multiplier".equals(rule.getType()))
            .toList();
    assertEquals(3, times.size());
    assertDecimal("0.5", timeFactor(times, "01:00", "02:00"));
    assertDecimal("0.1", timeFactor(times, "02:00", "03:00"));
    assertDecimal("0.2", timeFactor(times, "03:00", "04:00"));

    ModelCost cost =
        ModelCost.calculate(
            ModelPricingCodec.parsePricing(pricing),
            new ModelUsage(1_000_000, 0, 0, 0, Map.of(), null, null),
            Instant.parse("2026-11-23T02:30:00Z"),
            "priority");
    assertDecimal("0.6", cost.input());
  }

  @Test
  void normalizesDisjointAllAnyAndNotConditionBranches() {
    ModelPricingDTO pricing =
        index(parser.parse(resource("models-dev-pricing-exact.json")))
            .get("fixture/boolean-condition-normalization")
            .getPricing();
    assertEquals(1, pricing.getChanges().size());
    assertEquals("2028-01-01", pricing.getChanges().get(0).getFrom());

    List<ModelPricingRuleDTO> baseTiers =
        pricing.getRules().stream()
            .filter(rule -> "service_tier_multiplier".equals(rule.getType()))
            .toList();
    assertEquals(
        List.of("fast", "flex"),
        baseTiers.stream().map(ModelPricingRuleDTO::getServiceTier).sorted().toList());
    assertEquals(
        6,
        pricing.getRules().stream()
            .filter(rule -> "time_multiplier".equals(rule.getType()))
            .count());
    assertTrue(
        pricing.getRules().stream()
            .filter(rule -> "time_multiplier".equals(rule.getType()))
            .allMatch(rule -> rule.getStart().equals("00:00") && rule.getEnd().equals("24:00")));
    assertEquals(
        "priority",
        pricing.getChanges().get(0).getRules().stream()
            .filter(rule -> "service_tier_multiplier".equals(rule.getType()))
            .map(ModelPricingRuleDTO::getServiceTier)
            .findFirst()
            .orElseThrow());

    ModelCost cost =
        ModelCost.calculate(
            ModelPricingCodec.parsePricing(pricing),
            new ModelUsage(1_000_000, 0, 0, 0, Map.of(), null, null),
            Instant.parse("2028-01-05T12:00:00Z"),
            "flex");
    assertDecimal("12", cost.input());
  }

  @Test
  void rejectsJointAndOverlappingBooleanPriceConditionsAsWholePrices() {
    Map<String, ModelCatalogCandidateDTO> candidates =
        index(parser.parse(resource("models-dev-pricing-exact.json")));
    assertRejected(candidates.get("fixture/unrepresentable-joint-tier-time"));
    assertRejected(candidates.get("fixture/unrepresentable-overlapping-any"));
    assertRejected(candidates.get("fixture/unrepresentable-negated-tier"));
  }

  @Test
  void importsContextCacheFeesAndRejectsUnknownOverrideShapes() {
    Map<String, ModelCatalogCandidateDTO> candidates =
        index(parser.parse(resource("models-dev-pricing-exact.json")));
    ModelCatalogCandidateDTO cacheOverride =
        candidates.get("fixture/unrepresentable-cache-override");
    assertTrue(cacheOverride.isPricingSupported());
    ModelPricingRuleDTO cacheRange = rule(cacheOverride.getPricing(), "input_token_range");
    assertDecimal("4", cacheRange.getInput());
    assertDecimal("0.5", cacheRange.getCacheRead());

    // The real long-context price is imported as one complete multi-fee context range.
    ModelCatalogCandidateDTO databricks =
        index(parser.parse(resource("models-dev.json"))).get("databricks/databricks-gpt-5-6-sol");
    assertTrue(databricks.isPricingSupported());
    ModelPricingRuleDTO longContext = rule(databricks.getPricing(), "input_token_range");
    assertEquals(272001, longContext.getMinInputTokens());
    assertDecimal("8", longContext.getInput());
    assertDecimal("30", longContext.getOutput());
    assertDecimal("0.8", longContext.getCacheRead());
    assertDecimal("10", longContext.getCacheWrite());
  }

  @Test
  void legacySimpleTokenPricesAndTtlRemainSupported() {
    Map<String, ModelCatalogCandidateDTO> candidates =
        index(parser.parse(resource("models-dev.json")));
    ModelCatalogCandidateDTO sonnet = candidates.get("opencode/claude-sonnet-4-6");
    assertTrue(sonnet.isPricingSupported());
    assertDecimal("3.75", sonnet.getPricing().getBase().getCacheWriteByTtl().get("300"));
    assertDecimal("6", sonnet.getPricing().getBase().getCacheWriteByTtl().get("3600"));

    ModelCatalogCandidateDTO unsupported = candidates.get("opencode/unsupported-override");
    assertRejected(unsupported);
  }

  private static ModelPricingRuleDTO rule(ModelPricingDTO pricing, String type) {
    return pricing.getRules().stream()
        .filter(candidate -> type.equals(candidate.getType()))
        .findFirst()
        .orElseThrow();
  }

  private static BigDecimal timeFactor(List<ModelPricingRuleDTO> rules, String start, String end) {
    return rules.stream()
        .filter(rule -> start.equals(rule.getStart()) && end.equals(rule.getEnd()))
        .findFirst()
        .orElseThrow()
        .getFactor();
  }

  private static void assertRejected(ModelCatalogCandidateDTO candidate) {
    assertFalse(candidate.isPricingSupported());
    assertNull(candidate.getPricing());
    assertTrue(candidate.getSupportIssues() != null && !candidate.getSupportIssues().isEmpty());
  }
}
