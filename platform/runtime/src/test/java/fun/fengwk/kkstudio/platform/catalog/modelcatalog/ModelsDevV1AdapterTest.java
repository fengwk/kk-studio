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
import fun.fengwk.kkstudio.share.ai.catalog.ModelDefaultCandidateDTO;

import java.util.List;
import java.util.Map;

/** models.dev v1：真实 tier/legacy 结构、缺省归零与非 token 成本的适配契约。 */
class ModelsDevV1AdapterTest {

  private final ModelsDevV1Adapter adapter = new ModelsDevV1Adapter();
  private Map<String, ModelDefaultCandidateDTO> real;
  private Map<String, ModelDefaultCandidateDTO> validation;

  @BeforeEach
  void setUp() {
    real = index(adapter.adapt(resource("models-dev-v1.json")));
    validation = index(adapter.adapt(resource("models-dev-v1-validation.json")));
  }

  @Test
  void normalizesEquivalentTiersAndLegacyContextPrice() {
    ModelDefaultCandidateDTO xai = real.get("xai/grok-4.7");
    assertTrue(xai.isPricingSupported());
    assertEquals(1, xai.getPricing().getOverrides().size());
    assertDecimal("2", xai.getPricing().getBase().getInput());
    assertDecimal("6", xai.getPricing().getBase().getOutput());
    assertDecimal("0.5", xai.getPricing().getBase().getCacheRead());
    assertDecimal("4", xai.getPricing().getOverrides().get(0).getRates().getInput());
    assertDecimal("12", xai.getPricing().getOverrides().get(0).getRates().getOutput());
    assertDecimal("1", xai.getPricing().getOverrides().get(0).getRates().getCacheRead());

    ModelDefaultCandidateDTO legacy = validation.get("validation/tier-gt");
    assertEquals(1, legacy.getPricing().getOverrides().size());
    // 目录 tier 的 size 是上界阈值，命中条件是严格大于。
    assertEquals(1000, legacy.getPricing().getOverrides().get(0).getWhen().getContext().getGt());
    assertNull(legacy.getPricing().getOverrides().get(0).getWhen().getContext().getGte());
  }

  @Test
  void rejectsConflictingLegacyTierAndUnsupportedTierType() {
    ModelDefaultCandidateDTO conflict = real.get("fixture/conflict-tier");
    assertFalse(conflict.isPricingSupported());
    assertNull(conflict.getPricing());
    assertTrue(hasIssue(conflict, "conflicting v1 context tiers"));

    ModelDefaultCandidateDTO unsupported = real.get("fixture/bad-tier");
    assertFalse(unsupported.isPricingSupported());
    assertNull(unsupported.getPricing());
    assertTrue(hasIssue(unsupported, "unsupported v1 tier type"));
  }

  @Test
  void keepsTokenPriceAndReportsNonTokenAudioCost() {
    ModelDefaultCandidateDTO candidate = real.get("neon/gemini-3-flash");
    assertTrue(candidate.isPricingSupported());
    assertDecimal("0.5", candidate.getPricing().getBase().getInput());
    assertDecimal("3", candidate.getPricing().getBase().getOutput());
    assertTrue(hasIssue(candidate, "non-token audio cost"));
  }

  @Test
  void missingPricesBecomeZeroWhileExplicitZerosArePreserved() {
    ModelDefaultCandidateDTO partial = real.get("fixture/partial");
    assertTrue(partial.isPricingSupported());
    assertDecimal("1", partial.getPricing().getBase().getInput());
    assertDecimal("0", partial.getPricing().getBase().getOutput());
    assertDecimal("0", partial.getPricing().getBase().getCacheRead());
    assertDecimal("0", partial.getPricing().getBase().getCacheWrite());
    assertNull(partial.getPricing().getBase().getReasoning());

    ModelDefaultCandidateDTO zeros = real.get("fixture/zeros");
    assertDecimal("0", zeros.getPricing().getBase().getInput());
    assertDecimal("0", zeros.getPricing().getBase().getOutput());
    assertDecimal("0", zeros.getPricing().getBase().getCacheRead());
    assertDecimal("0", zeros.getPricing().getBase().getCacheWrite());

    ModelDefaultCandidateDTO noCost = validation.get("validation/no-cost");
    assertTrue(noCost.isPricingSupported());
    assertDecimal("0", noCost.getPricing().getBase().getInput());
    assertDecimal("0", noCost.getPricing().getBase().getOutput());
  }

  @Test
  void preservesExplicitReasoningZeroAndDoesNotInventVariants() {
    ModelDefaultCandidateDTO reasoning = validation.get("validation/reasoning-zero");
    assertDecimal("0", reasoning.getPricing().getBase().getReasoning());
    assertNull(reasoning.getVariants());
    assertNull(reasoning.getDefaultVariant());
  }

  @Test
  void mapsAbilitiesLimitsAndUnknownModalities() {
    ModelDefaultCandidateDTO capable = validation.get("validation/capable");
    assertEquals("models_dev_v1:validation:capable", capable.getId());
    assertEquals("Capable Model", capable.getName());
    assertEquals(Boolean.FALSE, capable.getAbilities().getTools());
    assertEquals(Boolean.TRUE, capable.getAbilities().getReasoning());
    assertEquals(
        List.of(
            AgentModelInputModality.TEXT,
            AgentModelInputModality.IMAGE,
            AgentModelInputModality.DOCUMENT,
            AgentModelInputModality.AUDIO,
            AgentModelInputModality.VIDEO),
        capable.getAbilities().getInputModalities());
    assertEquals(128000, capable.getLimit().getContext());
    assertEquals(8192, capable.getLimit().getOutput());
    assertTrue(hasIssue(capable, "unsupported input modality"));

    ModelDefaultCandidateDTO missing = validation.get("validation/no-cost");
    assertNull(missing.getAbilities().getTools());
    assertNull(missing.getAbilities().getReasoning());
    assertNull(missing.getLimit());
  }

  @Test
  void rejectsUnknownTokenPriceFields() {
    assertRejected(validation.get("validation/unknown-cost"));
    assertRejected(validation.get("validation/unknown-tier"));
  }

  private static void assertRejected(ModelDefaultCandidateDTO candidate) {
    assertFalse(candidate.isPricingSupported());
    assertNull(candidate.getPricing());
    assertNotNull(candidate.getSupportIssues());
    assertFalse(candidate.getSupportIssues().isEmpty());
  }

  private static boolean hasIssue(ModelDefaultCandidateDTO candidate, String text) {
    return candidate.getSupportIssues().stream().anyMatch(issue -> issue.contains(text));
  }
}
