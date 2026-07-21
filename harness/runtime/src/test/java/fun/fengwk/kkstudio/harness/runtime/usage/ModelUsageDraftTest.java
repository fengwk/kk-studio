package fun.fengwk.kkstudio.harness.runtime.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheMode;
import fun.fengwk.kkstudio.harness.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

class ModelUsageDraftTest {
  private static final ModelUsage USAGE = new ModelUsage(1, 2, 3, 4, 5, 6, 21);

  /** Draft 必须读取最终 request 的 model/control，而不是任何基础请求快照。 */
  @Test
  void freezesFinalIdentityAndCacheControl() {
    ModelPricing pricing = zeroPricing();
    ModelDescriptor model =
        model(301L, 302L, ProviderType.GOOGLE, "gemini-final", pricing, affinityPolicy());
    ProviderRequest finalRequest =
        request(model, ProviderCacheControl.affinity(PromptCacheRetention.LONG, "final-key"));
    ProviderResponse response = response(ModelCost.calculate(pricing, USAGE));

    ModelUsageDraft draft = ModelUsageDraft.from(finalRequest, response);

    assertEquals(301L, draft.providerResourceId());
    assertEquals(302L, draft.modelResourceId());
    assertEquals(ProviderType.GOOGLE, draft.providerType());
    assertEquals("gemini-final", draft.providerModelId());
    assertEquals(PromptCacheMode.AFFINITY, draft.promptCacheMode());
    assertEquals(PromptCacheRetention.LONG, draft.promptCacheRetention());
    assertTrue(draft.cacheEligible());
    assertEquals("final-key", draft.cacheAffinityKey());
    assertEquals(ProviderStopReason.COMPLETED, draft.stopReason());
    assertEquals(USAGE, draft.usage());
    assertEquals(response.cost(), draft.cost());
    assertEquals(pricing, draft.pricing());
    assertEquals("request-1", draft.requestId());
    assertEquals("reported", draft.reportedServiceTier());
    assertEquals("{\"provider\":true}", draft.rawUsageJson());
  }

  /** AUTOMATIC 即使 final control 为 NONE 仍表示 Provider 可报告缓存命中。 */
  @Test
  void automaticWithNoneControlIsEligible() {
    ModelPricing pricing = zeroPricing();
    ModelDescriptor model =
        model(
            1L,
            2L,
            ProviderType.GOOGLE,
            "gemini",
            pricing,
            PromptCachePolicy.automatic(PromptCacheCapability.automatic()));

    ModelUsageDraft draft =
        ModelUsageDraft.from(request(model, ProviderCacheControl.none()), response(zeroCost()));

    assertEquals(PromptCacheMode.AUTOMATIC, draft.promptCacheMode());
    assertEquals(PromptCacheRetention.NONE, draft.promptCacheRetention());
    assertTrue(draft.cacheEligible());
    assertNull(draft.cacheAffinityKey());
  }

  /** UNKNOWN/UNSUPPORTED 不得因为伪造的 final control 被记为可计费缓存事实。 */
  @Test
  void disabledCacheIsNotEligible() {
    ModelPricing pricing = zeroPricing();
    ModelDescriptor model =
        model(1L, 2L, ProviderType.OPENAI, "model", pricing, PromptCachePolicy.disabled());
    ProviderRequest finalRequest =
        request(model, ProviderCacheControl.affinity(PromptCacheRetention.SHORT, "forged-key"));

    ModelUsageDraft draft = ModelUsageDraft.from(finalRequest, response(zeroCost()));

    assertEquals(PromptCacheMode.UNSUPPORTED, draft.promptCacheMode());
    assertEquals(PromptCacheRetention.SHORT, draft.promptCacheRetention());
    assertFalse(draft.cacheEligible());
    assertEquals("forged-key", draft.cacheAffinityKey());
  }

  /** 成本校验按 compareTo 比较，等 scale 的 BigDecimal 不得被误判为不一致。 */
  @Test
  void validatesCostSemanticallyAndRejectsMismatch() {
    ModelUsage usage = new ModelUsage(1, 0, 0, 0, 0, 0, 1);
    ModelPricing pricing = pricingWithInputPrice(BigDecimal.ONE);
    ModelDescriptor model =
        model(1L, 2L, ProviderType.OPENAI, "model", pricing, PromptCachePolicy.disabled());
    ModelCost expected = ModelCost.calculate(pricing, usage);
    ModelCost equivalentScale =
        new ModelCost(
            pricing.currency(),
            new BigDecimal("0.000001000000"),
            expected.output(),
            expected.cacheRead(),
            expected.cacheWrite(),
            expected.cacheWriteLong(),
            expected.reasoning(),
            new BigDecimal("0.000001000000"));

    ModelUsageDraft.from(
        request(model, ProviderCacheControl.none()), response(usage, equivalentScale));

    ModelCost mismatch =
        new ModelCost(
            pricing.currency(),
            new BigDecimal("0.000002000000"),
            expected.output(),
            expected.cacheRead(),
            expected.cacheWrite(),
            expected.cacheWriteLong(),
            expected.reasoning(),
            new BigDecimal("0.000002000000"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelUsageDraft.from(
                request(model, ProviderCacheControl.none()), response(usage, mismatch)));
  }

  /** Draft 自身也拒绝 ledger 身份、缓存派生和 metadata 的非法值，并沿用 raw usage JSON 规则。 */
  @Test
  void validatesDraftInvariantsAndRawUsageJson() {
    assertThrows(
        IllegalArgumentException.class, () -> draft(0L, 2L, "model", null, false, null, null));
    assertThrows(
        IllegalArgumentException.class, () -> draft(1L, 0L, "model", null, false, null, null));
    assertThrows(IllegalArgumentException.class, () -> draft(1L, 2L, " ", null, false, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> draft(1L, 2L, "model", PromptCacheRetention.SHORT, true, " ", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> draft(1L, 2L, "model", PromptCacheRetention.NONE, false, "key", null));
    assertThrows(
        IllegalArgumentException.class, () -> draft(1L, 2L, "model", null, true, null, null));
    assertThrows(IllegalArgumentException.class, () -> draftWithMetadata(" ", null, "{}"));
    assertThrows(IllegalArgumentException.class, () -> draftWithMetadata(null, " ", "{}"));
    assertThrows(IllegalArgumentException.class, () -> draftWithMetadata(null, null, " "));
    assertThrows(IllegalArgumentException.class, () -> draftWithMetadata(null, null, "not-json"));
    assertThrows(IllegalArgumentException.class, () -> draftWithMetadata(null, null, "null"));
    assertEquals("{}", draftWithMetadata(null, null, null).rawUsageJson());

    ModelPricing eurPricing =
        new ModelPricing(
            "EUR",
            "tier-1",
            "default",
            BigDecimal.ONE,
            "v1",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelUsageDraft(
                1L,
                2L,
                ProviderType.OPENAI,
                "model",
                PromptCacheMode.UNSUPPORTED,
                PromptCacheRetention.NONE,
                false,
                null,
                ProviderStopReason.COMPLETED,
                USAGE,
                zeroCost(),
                eurPricing,
                null,
                null,
                "{}"));
  }

  private static ModelUsageDraft draft(
      long providerResourceId,
      long modelResourceId,
      String providerModelId,
      PromptCacheRetention retention,
      boolean cacheEligible,
      String cacheAffinityKey,
      String rawUsageJson) {
    return new ModelUsageDraft(
        providerResourceId,
        modelResourceId,
        ProviderType.OPENAI,
        providerModelId,
        PromptCacheMode.UNSUPPORTED,
        retention == null ? PromptCacheRetention.NONE : retention,
        cacheEligible,
        cacheAffinityKey,
        ProviderStopReason.COMPLETED,
        USAGE,
        zeroCost(),
        zeroPricing(),
        null,
        null,
        rawUsageJson);
  }

  private static ModelUsageDraft draftWithMetadata(
      String requestId, String reportedServiceTier, String rawUsageJson) {
    return new ModelUsageDraft(
        1L,
        2L,
        ProviderType.OPENAI,
        "model",
        PromptCacheMode.UNSUPPORTED,
        PromptCacheRetention.NONE,
        false,
        null,
        ProviderStopReason.COMPLETED,
        USAGE,
        zeroCost(),
        zeroPricing(),
        requestId,
        reportedServiceTier,
        rawUsageJson);
  }

  private static ProviderResponse response(ModelCost cost) {
    return response(USAGE, cost);
  }

  private static ProviderResponse response(ModelUsage usage, ModelCost cost) {
    return new ProviderResponse(
        "answer",
        "",
        List.of(),
        ProviderStopReason.COMPLETED,
        usage,
        cost,
        "request-1",
        "reported",
        " {\"provider\":true} ");
  }

  private static ProviderRequest request(ModelDescriptor model, ProviderCacheControl control) {
    return new ProviderRequest(
        model,
        new ModelVariant("default", null, null, null, null, null, null, List.of(), null),
        List.of(),
        List.of(),
        control);
  }

  private static ModelDescriptor model(
      long providerResourceId,
      long modelResourceId,
      ProviderType providerType,
      String modelId,
      ModelPricing pricing,
      PromptCachePolicy policy) {
    return new ModelDescriptor(
        providerResourceId,
        modelResourceId,
        providerType,
        modelId,
        "Model",
        1024,
        256,
        Set.of(ModelInputModality.TEXT),
        true,
        false,
        List.of(),
        pricing,
        policy);
  }

  private static PromptCachePolicy affinityPolicy() {
    return new PromptCachePolicy(
        PromptCacheCapability.affinity(
            Set.of(PromptCacheRetention.SHORT, PromptCacheRetention.LONG)),
        PromptCacheRetention.SHORT);
  }

  private static ModelPricing zeroPricing() {
    return pricingWithInputPrice(BigDecimal.ZERO);
  }

  private static ModelPricing pricingWithInputPrice(BigDecimal inputPrice) {
    return new ModelPricing(
        "USD",
        "tier-1",
        "default",
        BigDecimal.ONE,
        "v1",
        inputPrice,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO);
  }

  private static ModelCost zeroCost() {
    return new ModelCost(
        "USD",
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO);
  }
}
