package fun.fengwk.kkstudio.harness.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/** 固定三组定价与 UTC 价格切换的精确解析契约。 */
class ModelPricingResolverTest {

  @Test
  void resolvesInputTierAndTimeGroupsInDefinedOrder() {
    ModelPricing pricing =
        new ModelPricing(
            rates("1", "4", "0.1", "0.2", Map.of(300L, dec("0.3")), "8"),
            List.of(
                new ModelPricing.InputTokenRange(
                    1_200_000, 1_300_000, dec("2"), dec("6"), null, null, null, null, false, false),
                new ModelPricing.ServiceTierMultiplier(
                    "fast", factors("2", "3", null, null, null, null)),
                new ModelPricing.TimeMultiplier(
                    60,
                    240,
                    ZoneOffset.UTC,
                    List.of(1, 2, 3, 4, 5),
                    factors("0.5", null, null, null, null, null))),
            List.of());
    ModelUsage usage = new ModelUsage(1_250_000, 1_000_000, 0, 0, Map.of(), 500_000L, null);

    ModelCost cost =
        ModelCost.calculate(pricing, usage, Instant.parse("2027-01-04T02:00:00Z"), "fast");

    assertEquals(dec("3.75"), cost.input());
    assertEquals(dec("3.0"), cost.output());
    assertEquals(dec("0.00"), cost.cacheRead());
    assertEquals(dec("0"), cost.cacheWrite());
    assertEquals(dec("4.0"), cost.reasoning());
    assertEquals(dec("10.75"), cost.total());
  }

  @Test
  void selectsCompletePriceChangeOnUtcDateAndRequiresDateFactForPlans() {
    ModelPricing pricing =
        new ModelPricing(
            rates("1", "2", "0", "0", Map.of(), null),
            List.of(),
            List.of(
                new ModelPricing.DateChange(
                    LocalDate.parse("2027-01-01"),
                    rates("3", "4", "0", "0", Map.of(), null),
                    List.of(
                        new ModelPricing.ServiceTierMultiplier(
                            "fast", factors("2", null, null, null, null, null))))));
    ModelUsage usage = new ModelUsage(1_000_000, 1_000_000, 0, 0, Map.of(), null, null);

    ModelCost before =
        ModelCost.calculate(pricing, usage, Instant.parse("2026-12-31T23:59:59Z"), "standard");
    ModelCost firstChangeInstant =
        ModelCost.calculate(pricing, usage, Instant.parse("2027-01-01T00:00:00Z"), "fast");

    assertEquals(dec("1"), before.input());
    assertEquals(dec("2"), before.output());
    assertEquals(dec("6"), firstChangeInstant.input());
    assertEquals(dec("8"), firstChangeInstant.output());
    assertNull(ModelCost.calculate(pricing, usage, null, "fast"));
  }

  @Test
  void inputRangesInheritFeesAndRetainExplicitNullAndEmptyTtlOverrides() {
    ModelPricing base =
        new ModelPricing(
            rates("1", "5", "2", "2", Map.of(300L, dec("9")), "10"), List.of(), List.of());
    ModelPricing.InputTokenRange clearTtlsAndFollowOutput =
        new ModelPricing.InputTokenRange(
            0, 4_000_000, null, null, dec("3"), null, Map.of(), null, true, true);
    ModelPricing cleared =
        new ModelPricing(base.base(), List.of(clearTtlsAndFollowOutput), List.of());
    ModelUsage usage =
        new ModelUsage(
            1_000_000, 1_000, 1_000_000, 1_000_000, Map.of(300L, 1_000_000L), 500L, null);

    ModelCost clearedCost = ModelCost.calculate(cleared, usage);
    assertEquals(dec("3"), clearedCost.cacheRead());
    assertEquals(dec("2"), clearedCost.cacheWrite());
    assertEquals(dec("0"), clearedCost.reasoning());
    assertEquals(dec("6.005"), clearedCost.total());

    ModelPricing.InputTokenRange inheritTtlsAndReasoning =
        new ModelPricing.InputTokenRange(
            0, 4_000_000, dec("1"), null, null, null, null, null, false, false);
    ModelPricing inherited =
        new ModelPricing(base.base(), List.of(inheritTtlsAndReasoning), List.of());
    ModelCost inheritedCost = ModelCost.calculate(inherited, usage);
    assertEquals(dec("2"), inheritedCost.cacheRead());
    assertEquals(dec("9"), inheritedCost.cacheWrite());
    assertEquals(dec("0.005"), inheritedCost.reasoning());
  }

  @Test
  void rejectsOverlappingRulesWithinEachFixedGroup() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelPricing(
                ModelPricing.RateSet.ZERO,
                List.of(
                    new ModelPricing.InputTokenRange(
                        10, 20, dec("1"), null, null, null, null, null, false, false),
                    new ModelPricing.InputTokenRange(
                        19, 30, null, dec("2"), null, null, null, null, false, false)),
                List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelPricing(
                ModelPricing.RateSet.ZERO,
                List.of(
                    new ModelPricing.ServiceTierMultiplier(
                        "fast", factors("2", null, null, null, null, null)),
                    new ModelPricing.ServiceTierMultiplier(
                        "fast", factors("3", null, null, null, null, null))),
                List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelPricing(
                ModelPricing.RateSet.ZERO,
                List.of(
                    new ModelPricing.TimeMultiplier(
                        60,
                        180,
                        ZoneOffset.UTC,
                        List.of(1),
                        factors("2", null, null, null, null, null)),
                    new ModelPricing.TimeMultiplier(
                        120,
                        240,
                        ZoneOffset.UTC,
                        List.of(1),
                        factors("3", null, null, null, null, null))),
                List.of()));
  }

  @Test
  void missingRequiredFactsReturnUnknownAndUnassignedTtlWritesAreNotFree() {
    ModelPricing pricing =
        new ModelPricing(
            rates("1", "2", "0", null, Map.of(300L, dec("2")), null),
            List.of(
                new ModelPricing.ServiceTierMultiplier(
                    "fast", factors("2", null, null, null, null, null))),
            List.of());

    assertNull(
        ModelCost.calculate(
            pricing,
            new ModelUsage(0, 0, 0, 1_000_000, Map.of(300L, 1_000_000L), null, null),
            null,
            null));
    assertNull(
        ModelCost.calculate(
            pricing, new ModelUsage(0, 0, 0, 1_000_000, Map.of(), null, null), null, "fast"));
    assertEquals(
        dec("4"),
        ModelCost.calculate(
                pricing,
                new ModelUsage(0, 0, 0, 1_000_000, Map.of(300L, 1_000_000L), null, null),
                null,
                "fast")
            .cacheWrite());
  }

  private static ModelPricing.RateSet rates(
      String input,
      String output,
      String cacheRead,
      String cacheWrite,
      Map<Long, BigDecimal> cacheWriteByTtl,
      String reasoning) {
    return new ModelPricing.RateSet(
        dec(input), dec(output), dec(cacheRead), dec(cacheWrite), cacheWriteByTtl, dec(reasoning));
  }

  private static ModelPricing.Factors factors(
      String factor,
      String input,
      String output,
      String cacheRead,
      String cacheWrite,
      String reasoning) {
    return new ModelPricing.Factors(
        dec(factor), dec(input), dec(output), dec(cacheRead), dec(cacheWrite), dec(reasoning));
  }

  private static BigDecimal dec(String value) {
    return value == null ? null : new BigDecimal(value);
  }
}
