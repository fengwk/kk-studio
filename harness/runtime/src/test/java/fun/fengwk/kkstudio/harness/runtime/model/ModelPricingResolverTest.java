package fun.fengwk.kkstudio.harness.runtime.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/** 定价规则解析与费用计算的精确性、顺序和缺事实语义。 */
class ModelPricingResolverTest {

  private static final Instant MONDAY_LATE = Instant.parse("2026-08-17T23:00:00Z");
  private static final Instant TUESDAY_EARLY = Instant.parse("2026-08-18T01:00:00Z");

  /** 覆盖规则按声明顺序叠加，倍率使用 BigDecimal 精确相乘，不做浮点近似或分项预舍入。 */
  @Test
  void appliesOverridesInOrderAndMultipliersExactly() {
    ModelPricing pricing =
        new ModelPricing(
            rates("1", "2", "0.5", "3", Map.of()),
            List.of(
                new ModelPricing.Override(
                    "first",
                    contextAtLeast(2_000_000),
                    new ModelPricing.RatePatch(dec("2"), dec("4"), null, null, null, null, false)),
                new ModelPricing.Override(
                    "second",
                    contextAtLeast(2_000_000),
                    new ModelPricing.RatePatch(dec("3"), null, null, null, null, null, false))),
            List.of(
                new ModelPricing.Multiplier(
                    "priority",
                    tier("priority"),
                    new ModelPricing.Factors(dec("1.5"), dec("0.5"), null, null, null)),
                new ModelPricing.Multiplier(
                    "always",
                    ModelPricingCondition.ALWAYS,
                    new ModelPricing.Factors(dec("0.1"), dec("0.1"), null, null, null))));

    ModelCost cost =
        ModelCost.calculate(
            pricing,
            new ModelUsage(1_000_000, 1_000_000, 1_000_000, 0, Map.of(), 0L, 2_000_000L),
            MONDAY_LATE,
            "priority");

    assertEquals(new BigDecimal("0.45"), cost.input());
    assertEquals(new BigDecimal("0.20"), cost.output());
    assertEquals(new BigDecimal("0.5"), cost.cacheRead());
    assertEquals(new BigDecimal("1.15"), cost.total());
  }

  /** TTL 写价按各自秒数计价；总量必须与 cacheWriteTokens 守恒，倍率同步作用于所有 TTL 价格。 */
  @Test
  void appliesTtlRatesWithoutDoubleChargingUnaliocatedTokens() {
    ModelPricing pricing =
        new ModelPricing(
            rates("0", "0", "0", "10", Map.of(300L, dec("2"), 3600L, dec("4"))),
            List.of(),
            List.of(
                new ModelPricing.Multiplier(
                    "cache",
                    ModelPricingCondition.ALWAYS,
                    new ModelPricing.Factors(null, null, null, dec("2"), null))));
    ModelUsage usage =
        new ModelUsage(0, 0, 0, 1_000_000, Map.of(300L, 600_000L, 3600L, 400_000L), null, null);

    ModelCost cost = ModelCost.calculate(pricing, usage, null, null);

    assertEquals(new BigDecimal("5.6"), cost.cacheWrite());
    assertEquals(new BigDecimal("5.6"), cost.total());
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelUsage(0, 0, 0, 2, Map.of(300L, 1L), null, null));
  }

  /** reasoning=null 继承最终输出价；显式 0 是独立价，只按可信 reasoning 子集计 0，其余输出仍按输出价。 */
  @Test
  void distinguishesInheritedReasoningFromExplicitZero() {
    ModelUsage usage = new ModelUsage(0, 1_000_000, 0, 0, Map.of(), 500_000L, 1_000_000L);

    ModelCost inherited =
        ModelCost.calculate(
            new ModelPricing(rates("0", "10", "0", "0", Map.of()), List.of(), List.of()),
            usage,
            null,
            null);
    assertEquals(new BigDecimal("10"), inherited.output());
    assertEquals(BigDecimal.ZERO, inherited.reasoning());
    assertEquals(new BigDecimal("10"), inherited.total());

    ModelCost explicitZero =
        ModelCost.calculate(
            new ModelPricing(
                new ModelPricing.RateSet(
                    BigDecimal.ZERO,
                    dec("10"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    Map.of(),
                    BigDecimal.ZERO),
                List.of(),
                List.of()),
            usage,
            null,
            null);
    assertEquals(new BigDecimal("5"), explicitZero.output());
    assertEquals(BigDecimal.ZERO, explicitZero.reasoning());
    assertEquals(new BigDecimal("5"), explicitZero.total());

    ModelCost missingSubset =
        ModelCost.calculate(
            new ModelPricing(
                new ModelPricing.RateSet(
                    BigDecimal.ZERO,
                    dec("10"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    Map.of(),
                    dec("2")),
                List.of(),
                List.of()),
            new ModelUsage(0, 1_000_000, 0, 0, Map.of(), null, 1_000_000L),
            null,
            null);
    assertNull(missingSubset);
  }

  /** 日期边界支持单侧缺省；缺失 requestedAt 时返回未知，绝不查询当前时间伪造历史。 */
  @Test
  void matchesOneSidedUtcDatesAndZeroesWhenRequestedAtIsMissing() {
    ModelPricing gte =
        pricingWithOverride(
            "gte", new ModelPricingCondition.DateRange(LocalDate.of(2026, 8, 17), null), "2");
    assertEquals(
        new BigDecimal("2"), calculateInput(gte, Instant.parse("2026-08-17T00:00:00Z"), null));
    assertEquals(
        new BigDecimal("1"), calculateInput(gte, Instant.parse("2026-08-16T23:59:59Z"), null));

    ModelPricing lt =
        pricingWithOverride(
            "lt", new ModelPricingCondition.DateRange(null, LocalDate.of(2026, 8, 18)), "3");
    assertEquals(
        new BigDecimal("3"), calculateInput(lt, Instant.parse("2026-08-17T23:59:59Z"), null));
    assertEquals(
        new BigDecimal("1"), calculateInput(lt, Instant.parse("2026-08-18T00:00:00Z"), null));

    assertNull(ModelCost.calculate(gte, new ModelUsage(1_000_000, 0, 0, 0, Map.of(), null, null)));
    assertEquals(
        new BigDecimal("7"),
        calculateInput(
            pricingWithOverride("empty-date", new ModelPricingCondition.DateRange(null, null), "7"),
            null,
            null));
    assertEquals(
        new BigDecimal("7"),
        calculateInput(
            pricingWithOverride("empty-date", new ModelPricingCondition.DateRange(null, null), "7"),
            MONDAY_LATE,
            null));
  }

  /** 跨午夜时间窗按开始日归属星期，IANA 时区换算后再判断。 */
  @Test
  void attributesCrossMidnightWindowsToTheirStartWeekday() {
    ModelPricingCondition.TimeWindow utcWindow =
        new ModelPricingCondition.TimeWindow(
            LocalTime.of(22, 0), LocalTime.of(2, 0), ZoneId.of("UTC"), List.of(1));
    ModelPricing utc = pricingWithOverride("night", utcWindow, "5");

    assertEquals(new BigDecimal("5"), calculateInput(utc, MONDAY_LATE, null));
    assertEquals(new BigDecimal("5"), calculateInput(utc, TUESDAY_EARLY, null));
    assertEquals(
        new BigDecimal("1"), calculateInput(utc, Instant.parse("2026-08-18T23:00:00Z"), null));
    assertEquals(
        new BigDecimal("1"), calculateInput(utc, Instant.parse("2026-08-17T03:00:00Z"), null));

    ModelPricingCondition.TimeWindow shanghaiWindow =
        new ModelPricingCondition.TimeWindow(
            LocalTime.of(22, 0), LocalTime.of(2, 0), ZoneId.of("Asia/Shanghai"), List.of(1));
    ModelPricing shanghai = pricingWithOverride("shanghai-night", shanghaiWindow, "6");
    assertEquals(
        new BigDecimal("6"), calculateInput(shanghai, Instant.parse("2026-08-17T15:00:00Z"), null));
    assertEquals(
        new BigDecimal("6"), calculateInput(shanghai, Instant.parse("2026-08-17T17:00:00Z"), null));
  }

  /** 内部 00:00-00:00 表示全天，仅按星期限制归属；非午夜相同起止必须被拒绝。 */
  @Test
  void treatsInternalMidnightToMidnightAsAllDay() {
    ModelPricingCondition.TimeWindow allDay =
        new ModelPricingCondition.TimeWindow(
            LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, ZoneId.of("UTC"), List.of(1));
    ModelPricing pricing = pricingWithOverride("all-day", allDay, "5");

    assertEquals(new BigDecimal("5"), calculateInput(pricing, MONDAY_LATE, null));
    assertEquals(
        new BigDecimal("1"), calculateInput(pricing, Instant.parse("2026-08-18T01:00:00Z"), null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelPricingCondition.TimeWindow(
                LocalTime.of(10, 0), LocalTime.of(10, 0), ZoneId.of("UTC"), null));
  }

  /** 22:00-24:00 的 end=24 规范为内部 00:00，仍按开始日的星期归属且不包含次日零点。 */
  @Test
  void resolvesLateEndOfDayWindowAndWeekdayAtStart() {
    ModelPricingCondition.TimeWindow lateWindow =
        new ModelPricingCondition.TimeWindow(
            LocalTime.of(22, 0), LocalTime.MIDNIGHT, ZoneId.of("UTC"), List.of(1));
    ModelPricing pricing = pricingWithOverride("late-end", lateWindow, "5");

    assertEquals(
        new BigDecimal("5"), calculateInput(pricing, Instant.parse("2026-08-17T23:59:00Z"), null));
    assertEquals(
        new BigDecimal("1"), calculateInput(pricing, Instant.parse("2026-08-18T00:00:00Z"), null));
    assertEquals(
        new BigDecimal("1"), calculateInput(pricing, Instant.parse("2026-08-17T21:59:00Z"), null));
  }

  /** service tier 属于必需事实；未提供时整个费用为 0，而不是忽略该规则继续猜价。 */
  @Test
  void returnsZeroWhenServiceTierFactIsMissing() {
    ModelPricing pricing =
        new ModelPricing(
            rates("1", "0", "0", "0", Map.of()),
            List.of(
                new ModelPricing.Override(
                    "priority",
                    tier("priority"),
                    new ModelPricing.RatePatch(dec("9"), null, null, null, null, null, false))),
            List.of());

    assertNull(
        ModelCost.calculate(
            pricing, new ModelUsage(1_000_000, 0, 0, 0, Map.of(), null, null), MONDAY_LATE, null));
    assertEquals(new BigDecimal("9"), calculateInput(pricing, MONDAY_LATE, "priority"));
  }

  /** 缺省 any/serviceTier 不约束；显式空列表按标准布尔语义恒假，不能被折叠为缺省。 */
  @Test
  void distinguishesExplicitEmptyAnyAndServiceTierFromAbsent() {
    ModelPricingCondition emptyAny =
        new ModelPricingCondition(null, null, null, null, null, List.of(), null);
    assertEquals(
        new BigDecimal("1"),
        calculateInput(pricingWithOverride("empty-any", emptyAny, "2"), MONDAY_LATE, null));

    ModelPricingCondition emptyServiceTier =
        new ModelPricingCondition(null, List.of(), null, null, null, null, null);
    ModelPricing emptyTierPricing = pricingWithOverride("empty-tier", emptyServiceTier, "2");
    assertEquals(new BigDecimal("1"), calculateInput(emptyTierPricing, MONDAY_LATE, "priority"));
    assertEquals(new BigDecimal("1"), calculateInput(emptyTierPricing, MONDAY_LATE, null));

    assertEquals(
        new BigDecimal("2"),
        calculateInput(
            pricingWithOverride("absent-any", ModelPricingCondition.ALWAYS, "2"),
            MONDAY_LATE,
            null));
    assertEquals(
        new BigDecimal("2"),
        calculateInput(
            pricingWithOverride(
                "absent-tier",
                new ModelPricingCondition(null, null, null, null, List.of(), null, null),
                "2"),
            MONDAY_LATE,
            "priority"));
  }

  /** not 包装未知事实仍为未知；只有事实已知时才产生确定布尔结果。 */
  @Test
  void keepsUnknownPropagatingThroughNot() {
    ModelPricing pricing =
        pricingWithOverride(
            "not-priority",
            new ModelPricingCondition(null, null, null, null, null, null, tier("priority")),
            "9");

    assertNull(
        ModelCost.calculate(
            pricing, new ModelUsage(1_000_000, 0, 0, 0, Map.of(), null, null), MONDAY_LATE, null));
    assertEquals(new BigDecimal("1"), calculateInput(pricing, MONDAY_LATE, "priority"));
    assertEquals(new BigDecimal("9"), calculateInput(pricing, MONDAY_LATE, "standard"));
  }

  /** AND 中确定 false 必须短路未知分支，避免缺失事实把已可判假的规则误升为 unknown。 */
  @Test
  void shortCircuitsUnknownWhenAndAlreadyFalse() {
    ModelPricingCondition falseContextAndUnknownDate =
        new ModelPricingCondition(
            contextAtLeast(2_000_000).context(),
            null,
            new ModelPricingCondition.DateRange(LocalDate.of(2026, 8, 17), null),
            null,
            null,
            null,
            null);

    assertEquals(
        new BigDecimal("1"),
        calculateInput(
            pricingWithOverride("false-and-unknown", falseContextAndUnknownDate, "9"), null, null));

    ModelPricingCondition falseThenUnknownAll =
        new ModelPricingCondition(
            null,
            null,
            null,
            null,
            List.of(
                contextAtLeast(2_000_000),
                new ModelPricingCondition(
                    null,
                    null,
                    new ModelPricingCondition.DateRange(LocalDate.of(2026, 8, 17), null),
                    null,
                    null,
                    null,
                    null)),
            null,
            null);
    assertEquals(
        new BigDecimal("1"),
        calculateInput(
            pricingWithOverride("all-false-and-unknown", falseThenUnknownAll, "9"), null, null));
  }

  private static ModelPricing pricingWithOverride(
      String id, ModelPricingCondition.DateRange date, String input) {
    return pricingWithOverride(
        id, new ModelPricingCondition(null, null, date, null, null, null, null), input);
  }

  private static ModelPricing pricingWithOverride(
      String id, ModelPricingCondition.TimeWindow time, String input) {
    return pricingWithOverride(
        id, new ModelPricingCondition(null, null, null, time, null, null, null), input);
  }

  private static ModelPricing pricingWithOverride(
      String id, ModelPricingCondition condition, String input) {
    return new ModelPricing(
        rates("1", "0", "0", "0", Map.of()),
        List.of(
            new ModelPricing.Override(
                id,
                condition,
                new ModelPricing.RatePatch(dec(input), null, null, null, null, null, false))),
        List.of());
  }

  private static BigDecimal calculateInput(
      ModelPricing pricing, Instant requestedAt, String serviceTier) {
    return ModelCost.calculate(
            pricing,
            new ModelUsage(1_000_000, 0, 0, 0, Map.of(), null, null),
            requestedAt,
            serviceTier)
        .input();
  }

  private static ModelPricing.RateSet rates(
      String input,
      String output,
      String cacheRead,
      String cacheWrite,
      Map<Long, BigDecimal> cacheWriteByTtl) {
    return new ModelPricing.RateSet(
        dec(input), dec(output), dec(cacheRead), dec(cacheWrite), cacheWriteByTtl, null);
  }

  private static ModelPricingCondition contextAtLeast(int tokens) {
    return new ModelPricingCondition(
        new ModelPricingCondition.Context(null, tokens, null, null),
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static ModelPricingCondition tier(String tier) {
    return new ModelPricingCondition(null, List.of(tier), null, null, null, null, null);
  }

  private static BigDecimal dec(String value) {
    return new BigDecimal(value);
  }
}
