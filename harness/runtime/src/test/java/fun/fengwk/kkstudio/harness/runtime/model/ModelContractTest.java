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
import java.util.List;
import java.util.Map;
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
   * Variant 只承载 id 与可空 reasoningEffort：id 必须无前后空白且非空；effort 为厂商自定义自由字符串，只去除首尾空白并原样保留大小写； 允许
   * max、xhigh 等任意非空且不超过 64 字符的值；空白与超长（>64）被拒绝；{@code off} 是显式关闭、{@code null} 是不声明，二者绝不互相静默映射。
   */
  @Test
  void enforcesVariantIdentityAndReasoningEffort() {
    assertEquals("default", new ModelVariant("default").id());
    assertNull(new ModelVariant("default").reasoningEffort());
    assertEquals("high", new ModelVariant("default", "high").reasoningEffort());
    assertEquals("off", new ModelVariant("default", "off").reasoningEffort());
    // 大小写是厂商原生值，绝不被归一化；仅首尾空白被去除
    assertEquals("OFF", new ModelVariant("default", "  OFF ").reasoningEffort());
    assertEquals("MAX", new ModelVariant("default", "MAX").reasoningEffort());
    assertEquals("xHigh", new ModelVariant("default", "  xHigh ").reasoningEffort());
    assertTrue(new ModelVariant("default", "off").reasoningOff());
    // 只有精确小写 off 是显式关闭；OFF 等原生值不构成 case-insensitive 别名
    assertFalse(new ModelVariant("default", "OFF").reasoningOff());
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

  /** 不同 Provider 用量类别必须分别按其适用单价计费；output 总量包含 reasoning，独立 reasoning 价只影响其子集。 */
  @Test
  void calculatesCostForEveryUsageCategory() {
    ModelPricing pricing =
        new ModelPricing(
            new ModelPricing.RateSet(
                new BigDecimal("2"),
                new BigDecimal("4"),
                new BigDecimal("0.5"),
                new BigDecimal("3"),
                Map.of(),
                new BigDecimal("6")),
            List.of(),
            List.of());
    ModelUsage usage =
        new ModelUsage(800_000, 800_000, 200_000, 100_000, Map.of(), 300_000L, 1_900_000L);

    ModelCost cost = ModelCost.calculate(pricing, usage);

    assertEquals(1_900_000L, usage.providerTotalTokens());
    assertEquals(1_900_000, usage.categorizedTokens());
    assertEquals("USD", cost.currency());
    assertEquals(new BigDecimal("1.6"), cost.input());
    assertEquals(new BigDecimal("2"), cost.output());
    assertEquals(new BigDecimal("0.1"), cost.cacheRead());
    assertEquals(new BigDecimal("0.3"), cost.cacheWrite());
    assertEquals(new BigDecimal("1.8"), cost.reasoning());
    assertEquals(new BigDecimal("5.8"), cost.total());
  }

  /** 精确计算不做分项舍入：真实微费用不会被截断为 0，total 是精确分项之和（先求和后舍入由读取投影负责）。 */
  @Test
  void keepsExactMicroCostWithoutPerCategoryRounding() {
    ModelPricing pricing =
        new ModelPricing(
            new ModelPricing.RateSet(
                new BigDecimal("0.000000000001"),
                BigDecimal.ZERO,
                new BigDecimal("0.000000000001"),
                BigDecimal.ZERO,
                Map.of(),
                null),
            List.of(),
            List.of());
    ModelUsage usage = new ModelUsage(1, 0, 1, 0, Map.of(), 0L, 2L);

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
                new BigDecimal("1.5")));
  }

  /** 任一负用量都会破坏成本计算，必须在公共边界拒绝。 */
  @Test
  void rejectsNegativeUsageCategory() {
    assertThrows(
        IllegalArgumentException.class, () -> new ModelUsage(1, 1, -1, 0, Map.of(), 0L, 1L));
    assertThrows(
        IllegalArgumentException.class, () -> new ModelUsage(1, 1, 1, 1, Map.of(), 1L, -1L));
    assertThrows(
        IllegalArgumentException.class, () -> new ModelUsage(1, 1, 0, 1, Map.of(300L, 2L), 0L, 3L));
  }

  /** categorizedTokens 只累加互斥的输入/输出/缓存读写；reasoning 已包含在 output 中，绝不重复相加。 */
  @Test
  void categorizedTokensSumsOnlyMutuallyExclusiveCategories() {
    ModelUsage usage = new ModelUsage(1, 6, 3, 4, Map.of(), 6L, 100L);

    assertEquals(14, usage.categorizedTokens());
    assertEquals(100L, usage.providerTotalTokens());
    assertEquals(0, usage.nonReasoningOutputTokens());
    assertEquals(86L, usage.providerTotalTokens() - usage.categorizedTokens());
  }

  /** categorizedTokens 在 Long 溢出时必须 fail fast。 */
  @Test
  void categorizedTokensRejectsOverflow() {
    ModelUsage usage = new ModelUsage(Long.MAX_VALUE, 1, 0, 0, Map.of(), 0L, 1L);

    assertThrows(ArithmeticException.class, usage::categorizedTokens);
  }

  /** cacheHit 只看 cacheReadTokens 是否大于 0，不在本 domain 计算命中率。 */
  @Test
  void cacheHitReturnsBooleanFromCacheReadTokens() {
    assertFalse(new ModelUsage(1, 1, 0, 1, Map.of(), 0L, 3L).cacheHit());
    assertTrue(new ModelUsage(1, 1, 1, 1, Map.of(), 0L, 4L).cacheHit());
  }

  /** multiplier 在指定分项上独立应用并全链路精确计算；total 恒等于精确分项之和。 */
  @Test
  void calculatesCostWithMultiplierOnEachCategory() {
    ModelPricing pricing =
        new ModelPricing(
            new ModelPricing.RateSet(
                new BigDecimal("2"),
                new BigDecimal("4"),
                new BigDecimal("0.5"),
                new BigDecimal("3"),
                Map.of(),
                new BigDecimal("6")),
            List.of(),
            List.of(
                new ModelPricing.Multiplier(
                    "priority",
                    ModelPricingCondition.ALWAYS,
                    new ModelPricing.Factors(
                        new BigDecimal("1.5"),
                        new BigDecimal("1.5"),
                        new BigDecimal("1.5"),
                        new BigDecimal("1.5"),
                        new BigDecimal("1.5")))));
    ModelUsage usage =
        new ModelUsage(800_000, 800_000, 200_000, 100_000, Map.of(), 300_000L, 1_900_000L);

    ModelCost cost = ModelCost.calculate(pricing, usage);

    assertEquals(new BigDecimal("2.4"), cost.input());
    assertEquals(new BigDecimal("3.0"), cost.output());
    assertEquals(new BigDecimal("0.15"), cost.cacheRead());
    assertEquals(new BigDecimal("0.45"), cost.cacheWrite());
    assertEquals(new BigDecimal("2.7"), cost.reasoning());
    assertEquals(new BigDecimal("8.70"), cost.total());
    BigDecimal sum =
        cost.input()
            .add(cost.output())
            .add(cost.cacheRead())
            .add(cost.cacheWrite())
            .add(cost.reasoning());
    assertEquals(0, cost.total().compareTo(sum));
  }

  /** pricing snapshot 必须拒绝负价、重复规则 id 与非正 TTL。 */
  @Test
  void pricingRejectsInvalidBaseAndRules() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelPricing(
                new ModelPricing.RateSet(
                    new BigDecimal("-1"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    Map.of(),
                    null),
                List.of(),
                List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelPricing(
                new ModelPricing.RateSet(
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    Map.of(0L, BigDecimal.ONE),
                    null),
                List.of(),
                List.of()));
    ModelPricing.Override override =
        new ModelPricing.Override(
            "same",
            ModelPricingCondition.ALWAYS,
            new ModelPricing.RatePatch(null, null, null, null, null, null, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelPricing(ModelPricing.RateSet.ZERO, List.of(override, override), List.of()));
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
