package fun.fengwk.kkstudio.harness.runtime.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheMode;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 模型描述、用量与成本公共契约测试。 */
class ModelContractTest {

  /** 模型描述必须使用非空 name；inputModalities 非空且不可变；pricing 不可为空。 */
  @Test
  void enforcesDescriptorResourceAndIdentityInvariants() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelDescriptor(
                "", "model", Set.of(ModelInputModality.TEXT), true, false, pricing()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelDescriptor(
                "provider", "", Set.of(ModelInputModality.TEXT), true, false, pricing()));
    assertThrows(
        NullPointerException.class,
        () -> new ModelDescriptor("provider", "model", null, true, false, pricing()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelDescriptor("provider", "model", Set.of(), true, false, pricing()));
    assertThrows(
        NullPointerException.class,
        () ->
            new ModelDescriptor(
                "provider", "model", Set.of(ModelInputModality.TEXT), true, false, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelDescriptor(
                "\u2003provider",
                "model",
                Set.of(ModelInputModality.TEXT),
                true,
                false,
                pricing()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelDescriptor(
                "provider/alias",
                "model",
                Set.of(ModelInputModality.TEXT),
                true,
                false,
                pricing()));
    assertDoesNotThrow(
        () ->
            new ModelDescriptor(
                "provider",
                "model/with/slash",
                Set.of(ModelInputModality.TEXT),
                true,
                false,
                pricing()));
  }

  /** inputModalities 必须防御性拷贝：外部集合的后续修改不得影响 descriptor。 */
  @Test
  void descriptorCopiesInputModalities() {
    Set<ModelInputModality> mutable = new HashSet<>();
    mutable.add(ModelInputModality.TEXT);
    ModelDescriptor descriptor =
        new ModelDescriptor("provider", "model", mutable, true, false, pricing());
    mutable.add(ModelInputModality.IMAGE);
    assertEquals(Set.of(ModelInputModality.TEXT), descriptor.inputModalities());
  }

  /** Variant 标识与数值必须可稳定下发；惩罚项允许厂商支持的负值，但拒绝非有限数。 */
  @Test
  void enforcesVariantIdentityAndFiniteSamplingValues() {
    ModelVariant variant =
        new ModelVariant("default", null, 0.2, 0.9, null, -0.5, -1.0, List.of("END"), null);
    assertEquals("default", variant.id());
    assertEquals(-0.5, variant.frequencyPenalty());
    assertEquals(-1.0, variant.presencePenalty());

    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelVariant(" default ", null, null, null, null, null, null, List.of(), null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelVariant("default", null, Double.NaN, null, null, null, null, List.of(), null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelVariant(
                "default",
                null,
                null,
                null,
                null,
                Double.POSITIVE_INFINITY,
                null,
                List.of(),
                null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelVariant("default", null, null, null, null, null, null, List.of(" "), null));
  }

  /** 不同 Provider 用量类别必须分别按其适用单价计费。 */
  @Test
  void calculatesCostForEveryUsageCategory() {
    ModelPricing pricing =
        new ModelPricing(
            "USD",
            "tier-1",
            "default",
            BigDecimal.ONE,
            "v1",
            new BigDecimal("2"),
            new BigDecimal("4"),
            new BigDecimal("0.5"),
            new BigDecimal("3"),
            new BigDecimal("3.5"),
            new BigDecimal("6"));
    ModelUsage usage = new ModelUsage(800_000, 500_000, 200_000, 100_000, 0, 300_000, 1_900_000);

    ModelCost cost = ModelCost.calculate(pricing, usage);

    assertEquals(1_900_000, usage.totalTokens());
    assertEquals(1_900_000, usage.categorizedTokens());
    assertEquals("USD", cost.currency());
    assertEquals(new BigDecimal("5.800000000000"), cost.amount());
  }

  /** cost 构造要求 total 等于分项之和；任何不等都必须在公共边界拒绝。 */
  @Test
  void rejectsCostWhenTotalDoesNotEqualCategorySum() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelCost(
                "USD",
                BigDecimal.ONE,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("1.5")));
  }

  /** 任一负用量都会破坏成本计算，必须在公共边界拒绝。 */
  @Test
  void rejectsNegativeUsageCategory() {
    assertThrows(IllegalArgumentException.class, () -> new ModelUsage(1, 1, -1, 0, 0, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> new ModelUsage(1, 1, 1, 1, 1, 1, -1));
  }

  /** categorizedTokens 必须按 addExact 累加前六类。 */
  @Test
  void categorizedTokensSumsExactlyTheFirstSixCategories() {
    ModelUsage usage = new ModelUsage(1, 2, 3, 4, 5, 6, 100);

    assertEquals(21, usage.categorizedTokens());
    assertEquals(100, usage.totalTokens());
    assertEquals(79, usage.totalTokens() - usage.categorizedTokens());
  }

  /** categorizedTokens 在 Long 溢出时必须 fail fast。 */
  @Test
  void categorizedTokensRejectsOverflow() {
    ModelUsage usage = new ModelUsage(Long.MAX_VALUE, 1, 0, 0, 0, 0, 1);

    assertThrows(ArithmeticException.class, usage::categorizedTokens);
  }

  /** cacheHit 只看 cacheReadTokens 是否大于 0，不在本 domain 计算命中率。 */
  @Test
  void cacheHitReturnsBooleanFromCacheReadTokens() {
    assertFalse(new ModelUsage(1, 1, 0, 1, 0, 0, 3).cacheHit());
    assertTrue(new ModelUsage(1, 1, 1, 1, 0, 0, 4).cacheHit());
  }

  /** multiplier 在分项金额上独立应用，避免分项舍入差让 total 校验失败。 */
  @Test
  void calculatesCostWithTierMultiplierOnEachCategory() {
    ModelPricing pricing =
        new ModelPricing(
            "USD",
            "tier-1",
            "priority",
            new BigDecimal("1.5"),
            "v1",
            new BigDecimal("2"),
            new BigDecimal("4"),
            new BigDecimal("0.5"),
            new BigDecimal("3"),
            new BigDecimal("3.5"),
            new BigDecimal("6"));
    ModelUsage usage = new ModelUsage(800_000, 500_000, 200_000, 100_000, 0, 300_000, 1_900_000);

    ModelCost cost = ModelCost.calculate(pricing, usage);

    assertEquals(new BigDecimal("2.400000000000"), cost.input());
    assertEquals(new BigDecimal("3.000000000000"), cost.output());
    assertEquals(new BigDecimal("0.150000000000"), cost.cacheRead());
    assertEquals(new BigDecimal("0.450000000000"), cost.cacheWrite());
    assertEquals(new BigDecimal("0.000000000000"), cost.cacheWriteLong());
    assertEquals(new BigDecimal("2.700000000000"), cost.reasoning());
    assertEquals(new BigDecimal("8.700000000000"), cost.total());
    BigDecimal sum =
        cost.input()
            .add(cost.output())
            .add(cost.cacheRead())
            .add(cost.cacheWrite())
            .add(cost.cacheWriteLong())
            .add(cost.reasoning());
    assertEquals(0, cost.total().compareTo(sum));
  }

  /** pricing snapshot 必须拒绝空字符串或非正 multiplier。 */
  @Test
  void pricingRejectsBlankMetadataAndNonPositiveMultiplier() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelPricing(
                "USD",
                "",
                "default",
                BigDecimal.ONE,
                "v1",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelPricing(
                "USD",
                "tier-1",
                "default",
                BigDecimal.ZERO,
                "v1",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelPricing(
                "USD",
                "tier-1",
                "default",
                new BigDecimal("-1"),
                "v1",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO));
  }

  /** UNKNOWN/UNSUPPORTED/AUTOMATIC 不支持非 NONE retention，但 supports(NONE) 始终为 true。 */
  @Test
  void capabilityNonExplicitModesOnlySupportNone() {
    PromptCacheCapability automatic = PromptCacheCapability.automatic();
    PromptCacheCapability unsupported = PromptCacheCapability.unsupported();
    PromptCacheCapability unknown = PromptCacheCapability.unknown();
    assertFalse(automatic.supports(PromptCacheRetention.SHORT));
    assertFalse(unsupported.supports(PromptCacheRetention.LONG));
    assertFalse(unknown.supports(PromptCacheRetention.SHORT));
    assertTrue(automatic.supports(PromptCacheRetention.NONE));
    assertTrue(unsupported.supports(PromptCacheRetention.NONE));
    assertTrue(unknown.supports(PromptCacheRetention.NONE));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PromptCacheCapability(
                PromptCacheMode.AUTOMATIC, Set.of(PromptCacheRetention.SHORT), Set.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PromptCacheCapability(
                PromptCacheMode.UNSUPPORTED, Set.of(), Set.of(PromptCacheBreakpoint.SYSTEM)));
  }

  /** AFFINITY 必须至少一个非 NONE retention，且 breakpoints 必须为空。 */
  @Test
  void capabilityAffinityRequiresRetentionAndForbidsBreakpoints() {
    assertThrows(IllegalArgumentException.class, () -> PromptCacheCapability.affinity(Set.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> PromptCacheCapability.affinity(Set.of(PromptCacheRetention.NONE)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PromptCacheCapability(
                PromptCacheMode.AFFINITY,
                Set.of(PromptCacheRetention.SHORT),
                Set.of(PromptCacheBreakpoint.SYSTEM)));
    PromptCacheCapability capability =
        PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT));
    assertTrue(capability.supports(PromptCacheRetention.SHORT));
    assertTrue(capability.supports(PromptCacheRetention.NONE));
    assertFalse(capability.supports(PromptCacheRetention.LONG));
  }

  /** BREAKPOINTS 必须至少一个非 NONE retention 与至少一个 breakpoint。 */
  @Test
  void capabilityBreakpointsRequiresBothRetentionAndBreakpoint() {
    assertThrows(
        IllegalArgumentException.class,
        () -> PromptCacheCapability.breakpoints(Set.of(PromptCacheRetention.SHORT), Set.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PromptCacheCapability.breakpoints(
                Set.of(PromptCacheRetention.NONE), Set.of(PromptCacheBreakpoint.SYSTEM)));
    PromptCacheCapability capability =
        PromptCacheCapability.breakpoints(
            Set.of(PromptCacheRetention.SHORT, PromptCacheRetention.LONG),
            Set.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS));
    assertTrue(capability.supports(PromptCacheRetention.SHORT));
    assertEquals(
        Set.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS),
        capability.supportedBreakpoints());
  }

  /** PromptCachePolicy 必须拒绝 capability 不支持的 retention。 */
  @Test
  void policyRejectsUnsupportedRetention() {
    PromptCacheCapability capability =
        PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT));
    assertThrows(
        IllegalArgumentException.class,
        () -> PromptCachePolicy.of(capability, PromptCacheRetention.LONG));
    assertEquals(
        PromptCacheRetention.SHORT, PromptCachePolicy.affinityShort(capability).retention());
  }

  /** Policy factories 必须保留 capability mode，并拒绝与工厂语义不匹配的 mode。 */
  @Test
  void policyFactoriesValidateModesAndRetentions() {
    PromptCacheCapability automatic = PromptCacheCapability.automatic();
    PromptCacheCapability breakpoints =
        PromptCacheCapability.breakpoints(
            Set.of(PromptCacheRetention.SHORT, PromptCacheRetention.LONG),
            Set.of(PromptCacheBreakpoint.SYSTEM));

    assertEquals(automatic, PromptCachePolicy.automatic(automatic).capability());
    assertEquals(
        PromptCacheRetention.SHORT, PromptCachePolicy.breakpointsShort(breakpoints).retention());
    assertEquals(
        PromptCacheRetention.LONG, PromptCachePolicy.breakpointsLong(breakpoints).retention());
    assertEquals(
        PromptCacheRetention.LONG,
        PromptCachePolicy.of(
                PromptCacheCapability.affinity(Set.of(PromptCacheRetention.LONG)),
                PromptCacheRetention.LONG)
            .retention());
    assertThrows(
        IllegalArgumentException.class,
        () -> PromptCachePolicy.automatic(PromptCacheCapability.unsupported()));
    assertThrows(
        IllegalArgumentException.class,
        () -> PromptCachePolicy.breakpointsShort(PromptCacheCapability.automatic()));
  }

  /** ProviderCacheControl 在 NONE 时不允许携带 key 或 breakpoints。 */
  @Test
  void providerCacheControlNoneForbidsKeyAndBreakpoints() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderCacheControl(PromptCacheRetention.NONE, "key", Set.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProviderCacheControl(
                PromptCacheRetention.NONE, null, Set.of(PromptCacheBreakpoint.SYSTEM)));
    assertEquals(PromptCacheRetention.NONE, ProviderCacheControl.none().retention());
  }

  /** ProviderCacheControl 非 NONE 时 key 必须非空白，breakpoints 由 affinity/breakpoints 工厂显式决定。 */
  @Test
  void providerCacheControlEnabledValidatesKeyAndBreakpoints() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ProviderCacheControl.affinity(PromptCacheRetention.SHORT, ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> ProviderCacheControl.affinity(PromptCacheRetention.NONE, "key"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ProviderCacheControl.breakpoints(PromptCacheRetention.SHORT, "key", Set.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ProviderCacheControl.breakpoints(
                PromptCacheRetention.NONE, "key", Set.of(PromptCacheBreakpoint.SYSTEM)));
    ProviderCacheControl affinity =
        ProviderCacheControl.affinity(PromptCacheRetention.SHORT, "model-1");
    assertEquals(PromptCacheRetention.SHORT, affinity.retention());
    assertEquals("model-1", affinity.affinityKey());
    assertTrue(affinity.breakpoints().isEmpty());
    ProviderCacheControl breakpoints =
        ProviderCacheControl.breakpoints(
            PromptCacheRetention.LONG, "model-2", Set.of(PromptCacheBreakpoint.SYSTEM));
    assertEquals(Set.of(PromptCacheBreakpoint.SYSTEM), breakpoints.breakpoints());
  }

  private ModelPricing pricing() {
    return new ModelPricing(
        "USD",
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
  }
}
