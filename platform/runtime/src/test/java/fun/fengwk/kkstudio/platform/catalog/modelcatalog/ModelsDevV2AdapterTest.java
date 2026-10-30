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
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelInputModality;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingConditionDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingMultiplierDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingOverrideDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelRatePatchDTO;
import fun.fengwk.kkstudio.share.ai.catalog.ModelDefaultCandidateDTO;

import java.time.Instant;
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
    assertRejected(real.get("opencode/unsupported-override"));
    assertRejected(real.get("opencode/duplicate-ttl"));
    assertRejected(real.get("opencode/character-priced"));
    assertRejected(validation.get("validation/unknown-input"));
    assertRejected(validation.get("validation/unknown-condition-nested"));
    assertRejected(validation.get("validation/unknown-override-field"));
    assertRejected(validation.get("validation/unknown-factor-field"));
  }

  @Test
  void supportsRegionMultiplierByIgnoringAndWarning() {
    ModelDefaultCandidateDTO candidate = real.get("opencode/region-only");
    assertNotNull(candidate);
    assertTrue(candidate.isPricingSupported());
    assertNotNull(candidate.getPricing());
    assertDecimal("1", candidate.getPricing().getBase().getInput());
    assertDecimal("2", candidate.getPricing().getBase().getOutput());
    assertTrue(candidate.getPricing().getMultipliers().isEmpty());
    assertTrue(hasIssue(candidate, "ignored region multiplier"));
  }

  /** 公开定价结构取自 https://models.dev/api/v2.json；忽略 region 后仍按 TTL 与 fast 倍率计价。 */
  @Test
  void parsesClaudeOpusAndCalculatesCostWithoutRegionMultiplier() {
    Map<String, ModelDefaultCandidateDTO> candidates =
        index(adapter.adapt(resource("models-dev-v2-claude-opus-5-5.json")));
    ModelDefaultCandidateDTO opus = candidates.get("anthropic/claude-opus-5-5");
    assertNotNull(opus);
    assertTrue(opus.isPricingSupported());
    assertTrue(hasIssue(opus, "ignored region multiplier"));
    assertTrue(hasIssue(opus, "non-token cost is not included: web_search"));

    AgentModelPricingDTO pricingDto = opus.getPricing();
    assertNotNull(pricingDto);
    assertDecimal("4", pricingDto.getBase().getInput());
    assertDecimal("20", pricingDto.getBase().getOutput());
    assertDecimal("0.2", pricingDto.getBase().getCacheRead());
    assertDecimal("0", pricingDto.getBase().getCacheWrite());
    assertEquals(2, pricingDto.getBase().getCacheWriteByTtl().size());
    assertDecimal("5", pricingDto.getBase().getCacheWriteByTtl().get("300"));
    assertDecimal("8", pricingDto.getBase().getCacheWriteByTtl().get("3600"));

    // fast 倍率保留，region us 倍率整条忽略并不进入 multipliers
    assertEquals(1, pricingDto.getMultipliers().size());
    AgentModelPricingMultiplierDTO fastMultiplier = pricingDto.getMultipliers().get(0);
    assertEquals(List.of("fast"), fastMultiplier.getWhen().getServiceTier());
    assertDecimal("2", fastMultiplier.getFactors().getInput());
    assertDecimal("2", fastMultiplier.getFactors().getOutput());
    assertDecimal("2", fastMultiplier.getFactors().getCacheRead());
    assertDecimal("2", fastMultiplier.getFactors().getCacheWrite());
    assertDecimal("2", fastMultiplier.getFactors().getReasoning());

    // 经唯一 parser 解析为 ModelPricing
    ModelPricing modelPricing = AgentModelRuntimeConfigParser.parsePricing(pricingDto);
    assertNotNull(modelPricing);
    assertEquals(1, modelPricing.multipliers().size());

    // 用量：1M input, 500k output, 2M cache read, 3M cache write (1M at 300s, 2M at 3600s)
    ModelUsage usage =
        new ModelUsage(
            1_000_000L,
            500_000L,
            2_000_000L,
            3_000_000L,
            Map.of(300L, 1_000_000L, 3600L, 2_000_000L),
            null,
            null);

    Instant requestedAt = Instant.parse("2026-10-23T00:00:00Z");
    // 缺少 tier 事实时不能猜测是否命中 fast；忽略 region 不改变其他事实的要求。
    assertNull(ModelCost.calculate(modelPricing, usage, requestedAt, null));

    // standard 按 base 及 TTL 计价，无 region 1.1 倍率。
    ModelCost costStandard = ModelCost.calculate(modelPricing, usage, requestedAt, "standard");
    assertNotNull(costStandard);
    assertDecimal("4", costStandard.input());
    assertDecimal("10", costStandard.output());
    assertDecimal("0.4", costStandard.cacheRead());
    assertDecimal("21", costStandard.cacheWrite());
    assertDecimal("0", costStandard.reasoning());
    assertDecimal("35.4", costStandard.total());

    // fast 仍对所有文本与 TTL 缓存费率生效（x2），不再额外乘 region 1.1。
    ModelCost costFast = ModelCost.calculate(modelPricing, usage, requestedAt, "fast");
    assertNotNull(costFast);
    assertDecimal("8", costFast.input());
    assertDecimal("20", costFast.output());
    assertDecimal("0.8", costFast.cacheRead());
    assertDecimal("42", costFast.cacheWrite());
    assertDecimal("0", costFast.reasoning());
    assertDecimal("70.8", costFast.total());
  }

  /** 组合条件不能通过仅删 region 节点变成无条件加价；必须丢弃整条 multiplier。 */
  @Test
  void skipsMultiplierWhenRegionIsNestedOrMixed() {
    Map<String, ModelDefaultCandidateDTO> candidates =
        index(adapter.adapt(resource("models-dev-v2-region.json")));

    for (String key :
        List.of(
            "provider/nested-not",
            "provider/mixed-all",
            "provider/mixed-any",
            "provider/mixed-top-level")) {
      ModelDefaultCandidateDTO candidate = candidates.get(key);
      assertNotNull(candidate, key);
      assertTrue(candidate.isPricingSupported(), key + " must be pricing supported");
      assertTrue(
          candidate.getPricing().getMultipliers().isEmpty(), key + " multiplier must be skipped");
      assertDecimal("1", candidate.getPricing().getBase().getInput());
      assertDecimal("2", candidate.getPricing().getBase().getOutput());
      assertTrue(
          hasIssue(candidate, "ignored region multiplier"),
          key + " must warn ignored region multiplier");
    }
  }

  /** 地区倍率忽略策略不能遮蔽其他未知条件、非法结构或运行时会拒绝的定义。 */
  @Test
  void rejectsRegionWithUnknownConditionOrInvalidFactor() {
    Map<String, ModelDefaultCandidateDTO> candidates =
        index(adapter.adapt(resource("models-dev-v2-region.json")));
    assertRejected(candidates.get("provider/region-unknown-condition"));
    assertRejected(candidates.get("provider/region-unknown-clause"));
    assertRejected(candidates.get("provider/region-not-unknown"));
    assertRejected(candidates.get("provider/region-negative-factor"));
    assertRejected(candidates.get("provider/region-unknown-factor-field"));
    assertRejected(candidates.get("provider/region-invalid-factor-string"));
    assertRejected(candidates.get("provider/region-context-conflict"));
    assertRejected(candidates.get("provider/region-factor-scale-overflow"));
  }

  /** 例外仅限 multiplier，不悄悄忽略会覆盖基础价的地区 override。 */
  @Test
  void rejectsRegionConditionInOverrides() {
    Map<String, ModelDefaultCandidateDTO> candidates =
        index(adapter.adapt(resource("models-dev-v2-region.json")));
    assertRejected(candidates.get("provider/region-input-override"));
    assertRejected(candidates.get("provider/region-output-override"));
    assertRejected(candidates.get("provider/region-nested-override"));
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
          "too-many":{"cost":{"text":{"input":{"price":1,"overrides":[{"when":{"all":[%s]},"price":2}]},"output":{"price":2}}}},
          "region-too-many":{"cost":{"multipliers":[{"when":{"region":{"equals":"us"},"all":[%s]},"factor":1.1}]}}
        }}}
        """
            .formatted(tooDeep, manyNodes, manyNodes);
    Map<String, ModelDefaultCandidateDTO> candidates = index(adapter.adapt(json));
    assertRejected(candidates.get("p/too-deep"));
    assertRejected(candidates.get("p/too-many"));
    assertRejected(candidates.get("p/region-too-many"));
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

  /** supported 候选的 pricing DTO 必须能经唯一 parser 保存并保持等价定价。 */
  @Test
  void supportedCandidatePricingSurvivesParserConfigRoundTrip() throws Exception {
    AgentModelPricingDTO pricing = real.get("databricks/databricks-gpt-5-6-sol").getPricing();
    JsonMapper mapper = JsonMapper.builder().build();
    String config =
        "{\"limit\":{\"context\":128000,\"output\":8192},"
            + "\"abilities\":{\"tools\":true,\"reasoning\":true,"
            + "\"inputModalities\":[\"TEXT\"]},"
            + "\"defaultVariant\":\"default\","
            + "\"variants\":[{\"id\":\"default\"}],"
            + "\"pricing\":"
            + mapper.writeValueAsString(pricing)
            + "}";

    AgentModelRuntimeConfigParser parser = new AgentModelRuntimeConfigParser(mapper);
    AgentModelConfigDTO decoded = parser.decode(config);
    assertEquals(pricing, decoded.getPricing());
    String encoded = parser.encode(decoded);
    assertEquals(pricing, parser.decode(encoded).getPricing());
    assertEquals(parser.parsePricing(pricing), parser.parse(encoded).pricing());
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
