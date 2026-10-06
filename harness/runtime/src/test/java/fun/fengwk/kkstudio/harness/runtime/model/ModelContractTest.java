package fun.fengwk.kkstudio.harness.runtime.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/** 模型描述、用量与成本公共契约测试。 */
class ModelContractTest {

  /** 模型描述必须使用非空 name；inputModalities 非空且不可变。 */
  @Test
  void enforcesDescriptorResourceAndIdentityInvariants() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelDescriptor(
                "", "model", "model", Set.of(ModelInputModality.TEXT), true, false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelDescriptor("provider", "", "", Set.of(ModelInputModality.TEXT), true, false));
    assertThrows(
        NullPointerException.class,
        () -> new ModelDescriptor("provider", "model", "model", null, true, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelDescriptor("provider", "model", "model", Set.of(), true, false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelDescriptor(
                "\u2003provider", "model", "model", Set.of(ModelInputModality.TEXT), true, false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelDescriptor(
                "provider/alias", "model", "model", Set.of(ModelInputModality.TEXT), true, false));
    assertDoesNotThrow(
        () ->
            new ModelDescriptor(
                "provider",
                "model/with/slash",
                "model/with/slash",
                Set.of(ModelInputModality.TEXT),
                true,
                false));
  }

  /** inputModalities 必须防御性拷贝：外部集合的后续修改不得影响 descriptor。 */
  @Test
  void descriptorCopiesInputModalities() {
    Set<ModelInputModality> mutable = new HashSet<>();
    mutable.add(ModelInputModality.TEXT);
    ModelDescriptor descriptor =
        new ModelDescriptor("provider", "model", "model", mutable, true, false);
    mutable.add(ModelInputModality.IMAGE);
    assertEquals(Set.of(ModelInputModality.TEXT), descriptor.inputModalities());
  }

  /**
   * Variant 只承载 id 与可空 reasoningEffort：id 必须无前后空白且非空；effort 为厂商自定义字符串，大小写与空白被归一化； 允许 max、xhigh
   * 等任意非空且不超过 64 字符的值；空白与超长（>64）被拒绝；{@code off} 是显式关闭、{@code null} 是不声明，二者绝不互相静默映射。
   */
  @Test
  void enforcesVariantIdentityAndReasoningEffort() {
    assertEquals("default", new ModelVariant("default").id());
    assertNull(new ModelVariant("default").reasoningEffort());
    assertEquals("high", new ModelVariant("default", "high").reasoningEffort());
    assertEquals("off", new ModelVariant("default", "off").reasoningEffort());
    assertEquals("off", new ModelVariant("default", "  OFF ").reasoningEffort());
    assertEquals("max", new ModelVariant("default", "MAX").reasoningEffort());
    assertEquals("xhigh", new ModelVariant("default", "  xHigh ").reasoningEffort());
    assertTrue(new ModelVariant("default", "off").reasoningOff());
    assertFalse(new ModelVariant("default").reasoningOff());
    assertFalse(new ModelVariant("default", "high").reasoningOff());
    assertFalse(new ModelVariant("default", "max").reasoningOff());

    assertThrows(IllegalArgumentException.class, () -> new ModelVariant(" default "));
    assertThrows(IllegalArgumentException.class, () -> new ModelVariant(" "));
    assertThrows(IllegalArgumentException.class, () -> new ModelVariant(null));
    assertThrows(IllegalArgumentException.class, () -> new ModelVariant("default", ""));
    assertThrows(IllegalArgumentException.class, () -> new ModelVariant("default", " "));
    assertThrows(IllegalArgumentException.class, () -> new ModelVariant("default", "   "));
    assertThrows(IllegalArgumentException.class, () -> new ModelVariant("default", "a".repeat(65)));
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
    assertEquals(new BigDecimal("1.6"), cost.input());
    assertEquals(new BigDecimal("2"), cost.output());
    assertEquals(new BigDecimal("0.1"), cost.cacheRead());
    assertEquals(new BigDecimal("0.3"), cost.cacheWrite());
    assertEquals(0, BigDecimal.ZERO.compareTo(cost.cacheWriteLong()));
    assertEquals(new BigDecimal("1.8"), cost.reasoning());
    assertEquals(new BigDecimal("5.8"), cost.total());
  }

  /** 精确计算不做分项舍入：真实微费用不会被截断为 0，total 是精确分项之和（先求和后舍入由读取投影负责）。 */
  @Test
  void keepsExactMicroCostWithoutPerCategoryRounding() {
    ModelPricing pricing =
        new ModelPricing(
            "USD",
            "tier-1",
            "default",
            BigDecimal.ONE,
            "v1",
            new BigDecimal("0.000000000001"),
            BigDecimal.ZERO,
            new BigDecimal("0.000000000001"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO);
    ModelUsage usage = new ModelUsage(1, 0, 1, 0, 0, 0, 2);

    ModelCost cost = ModelCost.calculate(pricing, usage);

    assertEquals(new BigDecimal("0.000000000000000001"), cost.input());
    assertEquals(new BigDecimal("0.000000000000000001"), cost.cacheRead());
    assertEquals(new BigDecimal("0.000000000000000002"), cost.total());
    assertTrue(cost.total().signum() > 0);
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

  /** multiplier 在每个分项上独立应用并全链路精确计算；total 恒等于精确分项之和。 */
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

    assertEquals(new BigDecimal("2.40"), cost.input());
    assertEquals(new BigDecimal("3.0"), cost.output());
    assertEquals(new BigDecimal("0.15"), cost.cacheRead());
    assertEquals(new BigDecimal("0.45"), cost.cacheWrite());
    assertEquals(new BigDecimal("0.00"), cost.cacheWriteLong());
    assertEquals(new BigDecimal("2.70"), cost.reasoning());
    assertEquals(new BigDecimal("8.70"), cost.total());
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

  /** ProviderCacheControl 只保留 retention 与 session key：NONE 时 key 必须为空/null，非 NONE 时 key 必须非空白。 */
  @Test
  void providerCacheControlEnforcesRetentionKeyInvariants() {
    assertEquals(PromptCacheRetention.NONE, ProviderCacheControl.none().retention());
    assertNull(ProviderCacheControl.none().key());
    assertEquals(
        ProviderCacheControl.none(), ProviderCacheControl.session(PromptCacheRetention.NONE, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderCacheControl(PromptCacheRetention.NONE, "key"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderCacheControl(PromptCacheRetention.SHORT, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderCacheControl(PromptCacheRetention.SHORT, "  "));
    ProviderCacheControl control =
        ProviderCacheControl.session(PromptCacheRetention.LONG, "session-1");
    assertEquals(PromptCacheRetention.LONG, control.retention());
    assertEquals("session-1", control.key());
  }

  /** session 工厂在 NONE 时忽略 sessionId 并归一化为 none()。 */
  @Test
  void sessionFactoryNormalizesNoneRetention() {
    assertEquals(
        ProviderCacheControl.none(),
        ProviderCacheControl.session(PromptCacheRetention.NONE, "session-ignored"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ProviderCacheControl.session(PromptCacheRetention.SHORT, "  "));
  }
}
