package fun.fengwk.kkstudio.platform.catalog.modelcatalog;

import static fun.fengwk.kkstudio.platform.catalog.modelcatalog.ModelCatalogFixtures.assertDecimal;
import static fun.fengwk.kkstudio.platform.catalog.modelcatalog.ModelCatalogFixtures.index;
import static fun.fengwk.kkstudio.platform.catalog.modelcatalog.ModelCatalogFixtures.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.share.ai.catalog.AgentModelInputModality;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingConditionDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingOverrideDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelRatePatchDTO;
import fun.fengwk.kkstudio.share.ai.catalog.ModelDefaultCandidateDTO;

import java.util.List;
import java.util.Map;

/** models.dev v2：字段级 overrides、倍率、时间/日期条件、TTL 与拒绝半份价格的契约。 */
class ModelsDevV2AdapterTest {

  private final ModelsDevV2Adapter adapter = new ModelsDevV2Adapter();
  private Map<String, ModelDefaultCandidateDTO> real;
  private Map<String, ModelDefaultCandidateDTO> validation;

  @BeforeEach
  void setUp() {
    real = index(adapter.adapt(resource("models-dev-v2.json")));
    validation = index(adapter.adapt(resource("models-dev-v2-validation.json")));
  }

  @Test
  void mergesFieldWiseOverridesAndPreservesMultiplierConditions() {
    ModelDefaultCandidateDTO candidate = real.get("databricks/databricks-gpt-5-6-sol");
    assertTrue(candidate.isPricingSupported());
    AgentModelPricingDTO pricing = candidate.getPricing();
    assertDecimal("4", pricing.getBase().getInput());
    assertDecimal("20", pricing.getBase().getOutput());
    assertDecimal("0.4", pricing.getBase().getCacheRead());
    assertDecimal("5", pricing.getBase().getCacheWrite());

    // input/output 各自保留原顺序，不再按条件合并。
    assertEquals(2, pricing.getOverrides().size());
    AgentModelPricingOverrideDTO input = pricing.getOverrides().get(0);
    assertEquals(272000, input.getWhen().getContext().getGt());
    assertDecimal("8", input.getRates().getInput());
    assertDecimal("0.8", input.getRates().getCacheRead());
    assertDecimal("10", input.getRates().getCacheWrite());
    assertNull(input.getRates().getOutput());
    AgentModelPricingOverrideDTO output = pricing.getOverrides().get(1);
    assertEquals(272000, output.getWhen().getContext().getGt());
    assertNull(output.getRates().getInput());
    assertDecimal("30", output.getRates().getOutput());
    assertFalse(output.getRates().isReasoningSpecified());

    assertEquals(2, pricing.getMultipliers().size());
    assertEquals(List.of("priority"), pricing.getMultipliers().get(0).getWhen().getServiceTier());
    assertDecimal("2", pricing.getMultipliers().get(0).getFactors().getInput());
    assertDecimal("2", pricing.getMultipliers().get(0).getFactors().getReasoning());
    AgentModelPricingConditionDTO dateRule = pricing.getMultipliers().get(1).getWhen();
    assertEquals("2026-11-22", dateRule.getDate().getGte());
    assertNull(dateRule.getDate().getLt());
    assertDecimal("1.25", pricing.getMultipliers().get(1).getFactors().getInput());
    assertDecimal("1.5", pricing.getMultipliers().get(1).getFactors().getOutput());
    assertNull(pricing.getMultipliers().get(1).getFactors().getReasoning());
  }

  @Test
  void convertsTtlCacheWritesToPositiveSeconds() {
    AgentModelPricingDTO pricing = real.get("opencode/claude-sonnet-4-6").getPricing();
    assertDecimal("0", pricing.getBase().getCacheWrite());
    assertEquals(2, pricing.getBase().getCacheWriteByTtl().size());
    assertDecimal("3.75", pricing.getBase().getCacheWriteByTtl().get("300"));
    assertDecimal("6", pricing.getBase().getCacheWriteByTtl().get("3600"));

    AgentModelPricingDTO day = validation.get("validation/ttl-days").getPricing();
    assertDecimal("4", day.getBase().getCacheWriteByTtl().get("86400"));
  }

  @Test
  void rejectsUnsupportedConditionsUnitsAndDuplicateTtls() {
    assertRejected(real.get("opencode/region-only"));
    assertRejected(real.get("opencode/unsupported-override"));
    assertRejected(real.get("opencode/duplicate-ttl"));
    assertRejected(real.get("opencode/character-priced"));
    assertRejected(validation.get("validation/unknown-input"));
    assertRejected(validation.get("validation/unknown-condition-nested"));
    assertRejected(validation.get("validation/unknown-override-field"));
    assertRejected(validation.get("validation/unknown-factor-field"));
  }

  @Test
  void defaultsMissingPricesToZeroAndKeepsReasoningInheritance() {
    AgentModelPricingDTO free = real.get("opencode/free").getPricing();
    assertDecimal("0", free.getBase().getInput());
    assertDecimal("0", free.getBase().getOutput());
    assertNull(free.getBase().getReasoning());

    AgentModelPricingDTO noCost = validation.get("validation/no-cost").getPricing();
    assertDecimal("0", noCost.getBase().getInput());
    assertDecimal("0", noCost.getBase().getOutput());
    assertDecimal("0", noCost.getBase().getCacheRead());
    assertDecimal("0", noCost.getBase().getCacheWrite());
    assertNull(noCost.getBase().getReasoning());

    AgentModelPricingDTO nullPrices = validation.get("validation/null-prices").getPricing();
    assertDecimal("0", nullPrices.getBase().getInput());
    assertDecimal("0", nullPrices.getBase().getOutput());
    assertNull(nullPrices.getBase().getReasoning());

    AgentModelPricingDTO missingInput = validation.get("validation/missing-input").getPricing();
    assertDecimal("0", missingInput.getBase().getInput());
    assertDecimal("2", missingInput.getBase().getOutput());

    AgentModelPricingDTO missingOutput = validation.get("validation/missing-output").getPricing();
    assertDecimal("1", missingOutput.getBase().getInput());
    assertDecimal("0", missingOutput.getBase().getOutput());
  }

  @Test
  void preservesExplicitReasoningZeroOnlyWhenDeclared() {
    AgentModelPricingDTO capable = validation.get("validation/capable").getPricing();
    assertDecimal("0", capable.getBase().getReasoning());

    AgentModelPricingDTO inherited = validation.get("validation/missing-capabilities").getPricing();
    assertNull(inherited.getBase().getReasoning());
  }

  @Test
  void keepsTokenPriceWhileReportingNonTokenCosts() {
    ModelDefaultCandidateDTO audio = validation.get("validation/audio-cost");
    assertTrue(audio.isPricingSupported());
    assertDecimal("1", audio.getPricing().getBase().getInput());
    assertTrue(hasIssue(audio, "non-token cost"));

    ModelDefaultCandidateDTO cacheFlags = validation.get("validation/cache-flags");
    assertTrue(cacheFlags.isPricingSupported());
    assertDecimal("1", cacheFlags.getPricing().getBase().getInput());
    assertTrue(hasIssue(cacheFlags, "explicit cache pricing"));
    assertTrue(hasIssue(cacheFlags, "cache storage cost"));

    ModelDefaultCandidateDTO multiplier = validation.get("validation/multiplier-audio");
    assertTrue(multiplier.isPricingSupported());
    assertTrue(hasIssue(multiplier, "non-token multiplier"));
  }

  @Test
  void outputOverrideOnlyResetsReasoningWhenFieldIsDeclared() {
    AgentModelPricingDTO pricing =
        validation.get("validation/output-override-no-reasoning").getPricing();
    AgentModelRatePatchDTO patch = pricing.getOverrides().get(0).getRates();
    assertDecimal("3", patch.getOutput());
    assertFalse(patch.isReasoningSpecified());
  }

  @Test
  void keepsEveryFieldWiseOverrideInSourceOrder() {
    AgentModelPricingDTO pricing = validation.get("validation/override-order").getPricing();
    assertEquals(3, pricing.getOverrides().size());
    assertEquals("override-0", pricing.getOverrides().get(0).getId());
    assertEquals("override-1", pricing.getOverrides().get(1).getId());
    assertEquals("override-2", pricing.getOverrides().get(2).getId());

    // 相同条件不再合并：第三条只补 cacheRead，不能清空前一条已应用的 input。
    assertEquals(100, pricing.getOverrides().get(0).getWhen().getContext().getGt());
    assertDecimal("1", pricing.getOverrides().get(0).getRates().getInput());
    assertNull(pricing.getOverrides().get(0).getRates().getCacheRead());
    assertEquals(200, pricing.getOverrides().get(1).getWhen().getContext().getGte());
    assertDecimal("2", pricing.getOverrides().get(1).getRates().getInput());
    assertEquals(100, pricing.getOverrides().get(2).getWhen().getContext().getGt());
    assertNull(pricing.getOverrides().get(2).getRates().getInput());
    assertDecimal("3", pricing.getOverrides().get(2).getRates().getCacheRead());
  }

  @Test
  void rejectsPricingThatTheRuntimeParserWouldReject() {
    assertRejected(validation.get("validation/context-conflict"));
    assertRejected(validation.get("validation/not-null"));
    assertRejected(validation.get("validation/ttl-overflow"));
    assertRejected(validation.get("validation/precision-overflow"));
    // 单个坏候选不能炸掉整份目录。
    assertTrue(validation.get("validation/capable").isPricingSupported());
  }

  @Test
  void boundsNestedConditionsAndNodeCount() {
    String tooDeep = "{}";
    for (int i = 0; i < 9; i++) {
      tooDeep = "{\"all\":[" + tooDeep + "]}";
    }
    StringBuilder nodes = new StringBuilder();
    for (int i = 0; i < 65; i++) {
      if (i > 0) {
        nodes.append(',');
      }
      nodes.append("{}");
    }
    String manyNodes = nodes.toString();
    String json =
        """
        {"p":{"models":{
          "too-deep":{"cost":{"text":{"input":{"price":1,"overrides":[{"when":%s,"price":2}]},"output":{"price":2}}}},
          "too-many":{"cost":{"text":{"input":{"price":1,"overrides":[{"when":{"all":[%s]},"price":2}]},"output":{"price":2}}}}
        }}}
        """
            .formatted(tooDeep, manyNodes);
    Map<String, ModelDefaultCandidateDTO> candidates = index(adapter.adapt(json));
    assertRejected(candidates.get("p/too-deep"));
    assertRejected(candidates.get("p/too-many"));
  }

  @Test
  void keepsExplicitEndAtMidnightAndDefaultsMissingEndToEndOfDay() {
    AgentModelPricingConditionDTO explicit =
        validation
            .get("validation/utc-hours-lt-midnight")
            .getPricing()
            .getOverrides()
            .get(0)
            .getWhen();
    assertEquals("22:00", explicit.getTime().getStart());
    assertEquals("00:00", explicit.getTime().getEnd());
  }

  @Test
  void keepsOpenEndedDatesWithoutSentinelBounds() {
    AgentModelPricingDTO gteOnly = validation.get("validation/date-gte-only").getPricing();
    assertEquals("2026-01-01", gteOnly.getOverrides().get(0).getWhen().getDate().getGte());
    assertNull(gteOnly.getOverrides().get(0).getWhen().getDate().getLt());

    AgentModelPricingDTO ltOnly = validation.get("validation/date-lt-only").getPricing();
    assertNull(ltOnly.getOverrides().get(0).getWhen().getDate().getGte());
    assertEquals("2027-01-01", ltOnly.getOverrides().get(0).getWhen().getDate().getLt());
  }

  @Test
  void mapsServiceTierAndWeekdayConditions() {
    AgentModelPricingConditionDTO tier =
        validation.get("validation/service-tier-in").getPricing().getOverrides().get(0).getWhen();
    assertEquals(List.of("priority", "flex"), tier.getServiceTier());

    AgentModelPricingConditionDTO time =
        validation.get("validation/utc-days-equals").getPricing().getOverrides().get(0).getWhen();
    assertEquals("01:00", time.getTime().getStart());
    assertEquals("24:00", time.getTime().getEnd());
    assertEquals("UTC", time.getTime().getTimezone());
    assertEquals(List.of(1), time.getTime().getWeekdays());
  }

  @Test
  void mapsWeekdayWindowAndScalarMultiplier() {
    AgentModelPricingConditionDTO day =
        real.get("time-vendor/weekday-day").getPricing().getOverrides().get(0).getWhen();
    assertEquals(List.of(1, 2, 3, 4, 5), day.getTime().getWeekdays());
    assertEquals("00:00", day.getTime().getStart());
    assertEquals("24:00", day.getTime().getEnd());
    assertEquals(2, day.getAll().size());

    AgentModelPricingDTO scalar = validation.get("validation/scalar-multiplier").getPricing();
    assertEquals(1, scalar.getMultipliers().size());
    assertDecimal("1.25", scalar.getMultipliers().get(0).getFactors().getInput());
    assertDecimal("1.25", scalar.getMultipliers().get(0).getFactors().getOutput());
    assertDecimal("1.25", scalar.getMultipliers().get(0).getFactors().getCacheRead());
    assertDecimal("1.25", scalar.getMultipliers().get(0).getFactors().getCacheWrite());
    assertDecimal("1.25", scalar.getMultipliers().get(0).getFactors().getReasoning());
  }

  @Test
  void mapsExplicitFalseAbilitiesAndMissingCapabilities() {
    ModelDefaultCandidateDTO capable = validation.get("validation/capable");
    assertEquals("models_dev_v2:validation:capable", capable.getId());
    assertEquals(Boolean.FALSE, capable.getAbilities().getTools());
    assertEquals(Boolean.TRUE, capable.getAbilities().getReasoning());
    assertEquals(
        List.of(
            AgentModelInputModality.TEXT,
            AgentModelInputModality.IMAGE,
            AgentModelInputModality.DOCUMENT),
        capable.getAbilities().getInputModalities());
    assertEquals(128000, capable.getLimit().getContext());
    assertTrue(hasIssue(capable, "unsupported input modality"));
    assertNull(capable.getVariants());
    assertNull(capable.getDefaultVariant());

    ModelDefaultCandidateDTO missing = validation.get("validation/missing-capabilities");
    assertNull(missing.getAbilities().getTools());
    assertNull(missing.getAbilities().getReasoning());
    assertNull(missing.getLimit());
  }

  private static void assertRejected(ModelDefaultCandidateDTO candidate) {
    assertNotNull(candidate);
    assertFalse(candidate.isPricingSupported());
    assertNull(candidate.getPricing());
    assertNotNull(candidate.getSupportIssues());
    assertFalse(candidate.getSupportIssues().isEmpty());
  }

  private static boolean hasIssue(ModelDefaultCandidateDTO candidate, String text) {
    return candidate.getSupportIssues().stream().anyMatch(issue -> issue.contains(text));
  }
}
