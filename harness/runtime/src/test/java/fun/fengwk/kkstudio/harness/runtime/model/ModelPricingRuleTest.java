package fun.fengwk.kkstudio.harness.runtime.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/** 分时段与长上下文价格：规则按声明顺序取第一条满足的，都不满足时用基础价格。 */
class ModelPricingRuleTest {

  private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

  @Test
  void windowsMatchLocalTimeAndMayWrapPastMidnight() {
    ModelPricingWindow night =
        new ModelPricingWindow(SHANGHAI, LocalTime.of(0, 30), LocalTime.of(8, 30));
    // 上海 00:30 = UTC 前一日 16:30。
    assertTrue(night.contains(Instant.parse("2026-10-18T16:30:00Z")));
    assertFalse(night.contains(Instant.parse("2026-10-19T00:30:00Z")));

    ModelPricingWindow wrapping =
        new ModelPricingWindow(SHANGHAI, LocalTime.of(22, 0), LocalTime.of(6, 0));
    assertTrue(wrapping.contains(Instant.parse("2026-10-18T15:00:00Z"))); // 23:00 local
    assertTrue(wrapping.contains(Instant.parse("2026-10-18T21:59:00Z"))); // 05:59 local
    assertFalse(wrapping.contains(Instant.parse("2026-10-18T22:00:00Z"))); // 06:00 local, exclusive

    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelPricingWindow(SHANGHAI, LocalTime.NOON, LocalTime.NOON));
  }

  @Test
  void effectivePicksTheFirstMatchingRuleOrTheBasePrices() {
    ModelPricingRule offPeak =
        rule(new ModelPricingWindow(SHANGHAI, LocalTime.of(0, 30), LocalTime.of(8, 30)), null, 1);
    ModelPricingRule longContext = rule(null, 200_000L, 4);
    ModelPricing pricing = pricing(List.of(offPeak, longContext));
    Instant offPeakTime = Instant.parse("2026-10-18T17:00:00Z");
    Instant peakTime = Instant.parse("2026-10-19T04:00:00Z");

    assertEquals(BigDecimal.ONE, pricing.effective(offPeakTime, 300_000).inputPerMillionTokens());
    assertEquals(
        BigDecimal.valueOf(4), pricing.effective(peakTime, 200_000).inputPerMillionTokens());
    ModelPricing base = pricing.effective(peakTime, 199_999);
    assertEquals(BigDecimal.TEN, base.inputPerMillionTokens());
    assertTrue(base.rules().isEmpty());

    ModelPricing plain = pricing(List.of());
    assertSame(plain, plain.effective(peakTime, 1));
  }

  @Test
  void rulesNeedAConditionAndNonNegativePrices() {
    assertThrows(IllegalArgumentException.class, () -> rule(null, null, 1));
    assertThrows(IllegalArgumentException.class, () -> rule(null, 0L, 1));
    assertThrows(IllegalArgumentException.class, () -> rule(null, 10L, -1));
  }

  @Test
  void promptTokensCountInputAndEveryCacheCategory() {
    assertEquals(15, new ModelUsage(1, 100, 2, 4, 8, 50, 165).promptTokens());
  }

  private static ModelPricingRule rule(
      ModelPricingWindow window, Long minContextTokens, long input) {
    BigDecimal price = BigDecimal.valueOf(input);
    return new ModelPricingRule(
        window,
        minContextTokens,
        price,
        BigDecimal.ONE,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO);
  }

  private static ModelPricing pricing(List<ModelPricingRule> rules) {
    return new ModelPricing(
        "CNY",
        "standard",
        "default",
        BigDecimal.ONE,
        "1",
        BigDecimal.TEN,
        BigDecimal.TEN,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        rules);
  }
}
