package fun.fengwk.kkstudio.harness.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 按条件把 {@link ModelPricing} 解析为一次请求的最终费率。 */
final class ModelPricingResolver {

  private ModelPricingResolver() {}

  static ResolvedRates resolve(
      ModelPricing pricing,
      ModelUsage usage,
      Instant requestedAt,
      String serviceTier,
      RequestBodyPricingSnapshot requestBodyPricing) {
    Objects.requireNonNull(pricing, "pricing");
    Objects.requireNonNull(usage, "usage");

    ModelPricing.RateSet base = pricing.base();
    BigDecimal input = base.input();
    BigDecimal output = base.output();
    BigDecimal cacheRead = base.cacheRead();
    BigDecimal cacheWrite = base.cacheWrite();
    Map<Long, BigDecimal> cacheWriteByTtl = new LinkedHashMap<>(base.cacheWriteByTtl());
    boolean independentReasoning = base.independentReasoning();
    BigDecimal reasoning = base.reasoning();

    for (ModelPricing.Override override : pricing.overrides()) {
      Boolean matches =
          matches(override.when(), usage, requestedAt, serviceTier, requestBodyPricing);
      if (matches == null) {
        return null;
      }
      if (!matches) {
        continue;
      }
      ModelPricing.RatePatch patch = override.rates();
      if (patch.input() != null) {
        input = patch.input();
      }
      if (patch.output() != null) {
        output = patch.output();
      }
      if (patch.cacheRead() != null) {
        cacheRead = patch.cacheRead();
      }
      if (patch.cacheWrite() != null) {
        cacheWrite = patch.cacheWrite();
      }
      if (patch.cacheWriteByTtl() != null) {
        cacheWriteByTtl = new LinkedHashMap<>(patch.cacheWriteByTtl());
      }
      if (patch.modifiesReasoning()) {
        independentReasoning = patch.reasoning() != null;
        reasoning = patch.reasoning();
      }
    }

    for (ModelPricing.Multiplier multiplier : pricing.multipliers()) {
      Boolean matches =
          matches(multiplier.when(), usage, requestedAt, serviceTier, requestBodyPricing);
      if (matches == null) {
        return null;
      }
      if (!matches) {
        continue;
      }
      ModelPricing.Factors factors = multiplier.factors();
      input = apply(input, factors.input());
      output = apply(output, factors.output());
      cacheRead = apply(cacheRead, factors.cacheRead());
      cacheWrite = apply(cacheWrite, factors.cacheWrite());
      Map<Long, BigDecimal> scaled = new LinkedHashMap<>();
      for (Map.Entry<Long, BigDecimal> entry : cacheWriteByTtl.entrySet()) {
        scaled.put(entry.getKey(), apply(entry.getValue(), factors.cacheWrite()));
      }
      cacheWriteByTtl = scaled;
      if (independentReasoning) {
        reasoning = apply(reasoning, factors.reasoning());
      }
    }

    BigDecimal reasoningRate = independentReasoning ? reasoning : output;
    return new ResolvedRates(
        input,
        output,
        cacheRead,
        cacheWrite,
        Map.copyOf(cacheWriteByTtl),
        reasoningRate,
        independentReasoning);
  }

  private static Boolean matches(
      ModelPricingCondition condition,
      ModelUsage usage,
      Instant requestedAt,
      String serviceTier,
      RequestBodyPricingSnapshot requestBodyPricing) {
    Boolean all = matchesAll(condition.all(), usage, requestedAt, serviceTier, requestBodyPricing);
    if (Boolean.FALSE.equals(all)) {
      return false;
    }
    Boolean any = matchesAny(condition.any(), usage, requestedAt, serviceTier, requestBodyPricing);
    if (Boolean.FALSE.equals(any)) {
      return false;
    }
    Boolean not = true;
    if (condition.not() != null) {
      Boolean nested =
          matches(condition.not(), usage, requestedAt, serviceTier, requestBodyPricing);
      if (nested == null) {
        return null;
      }
      not = !nested;
    }
    Boolean context = matchesContext(condition.context(), usage.contextTokens());
    Boolean tier = matchesServiceTier(condition.serviceTier(), serviceTier);
    Boolean date = matchesDate(condition.date(), requestedAt);
    Boolean time = matchesTime(condition.time(), requestedAt);
    Boolean requestBody = matchesRequestBody(condition.requestBody(), requestBodyPricing);
    return and(all, any, not, context, tier, date, time, requestBody);
  }

  private static Boolean matchesAll(
      List<ModelPricingCondition> conditions,
      ModelUsage usage,
      Instant requestedAt,
      String serviceTier,
      RequestBodyPricingSnapshot requestBodyPricing) {
    if (conditions == null) {
      return true;
    }
    // 标准布尔空合取：显式 all:[] 与未声明均恒真。
    Boolean result = true;
    for (ModelPricingCondition condition : conditions) {
      Boolean nested = matches(condition, usage, requestedAt, serviceTier, requestBodyPricing);
      if (Boolean.FALSE.equals(nested)) {
        return false;
      }
      if (nested == null) {
        result = null;
      }
    }
    return result;
  }

  private static Boolean matchesAny(
      List<ModelPricingCondition> conditions,
      ModelUsage usage,
      Instant requestedAt,
      String serviceTier,
      RequestBodyPricingSnapshot requestBodyPricing) {
    if (conditions == null) {
      // 未声明 any 时不施加额外约束。
      return true;
    }
    if (conditions.isEmpty()) {
      // 标准布尔空析取：显式 any:[] 恒假。
      return false;
    }
    Boolean result = false;
    for (ModelPricingCondition condition : conditions) {
      Boolean nested = matches(condition, usage, requestedAt, serviceTier, requestBodyPricing);
      if (Boolean.TRUE.equals(nested)) {
        return true;
      }
      if (nested == null) {
        result = null;
      }
    }
    return result;
  }

  private static Boolean matchesContext(ModelPricingCondition.Context context, long tokens) {
    if (context == null) {
      return true;
    }
    if (context.gt() != null && tokens <= context.gt()) {
      return false;
    }
    if (context.gte() != null && tokens < context.gte()) {
      return false;
    }
    if (context.lt() != null && tokens >= context.lt()) {
      return false;
    }
    if (context.lte() != null && tokens > context.lte()) {
      return false;
    }
    return true;
  }

  private static Boolean matchesServiceTier(List<String> tiers, String actualServiceTier) {
    if (tiers == null) {
      return true;
    }
    if (tiers.isEmpty()) {
      return false;
    }
    if (actualServiceTier == null || actualServiceTier.isBlank()) {
      return null;
    }
    return tiers.contains(actualServiceTier);
  }

  private static Boolean matchesRequestBody(
      ModelPricingCondition.RequestBody condition, RequestBodyPricingSnapshot snapshot) {
    if (condition == null) {
      return true;
    }
    if (snapshot == null) {
      return null;
    }
    return snapshot.matches().get(ModelRequestBodyPricing.fingerprint(condition));
  }

  private static Boolean matchesDate(ModelPricingCondition.DateRange date, Instant requestedAt) {
    if (date == null) {
      return true;
    }
    if (date.gte() == null && date.lt() == null) {
      return true;
    }
    if (requestedAt == null) {
      return null;
    }
    LocalDate localDate = requestedAt.atZone(ZoneOffset.UTC).toLocalDate();
    if (date.gte() != null && localDate.isBefore(date.gte())) {
      return false;
    }
    return date.lt() == null || localDate.isBefore(date.lt());
  }

  private static Boolean matchesTime(ModelPricingCondition.TimeWindow time, Instant requestedAt) {
    if (time == null) {
      return true;
    }
    if (requestedAt == null) {
      return null;
    }
    ZonedDateTime local = requestedAt.atZone(time.timezone());
    LocalTime clock = local.toLocalTime();
    LocalTime start = time.start();
    LocalTime end = time.end();
    LocalDate attributionDay = local.toLocalDate();
    if (time.allDay()) {
      // 内部 00:00-00:00 表示全天；wire 的显式形态是 00:00-24:00。
    } else if (start.isBefore(end)) {
      if (clock.isBefore(start) || !clock.isBefore(end)) {
        return false;
      }
    } else {
      // 跨午夜：结束于次日；午夜后的时间仍归属开始日，用于星期判断。
      if (clock.isBefore(start) && !clock.isBefore(end)) {
        return false;
      }
      if (clock.isBefore(end)) {
        attributionDay = attributionDay.minusDays(1);
      }
    }
    return time.weekdays().isEmpty()
        || time.weekdays().contains(attributionDay.getDayOfWeek().getValue());
  }

  private static Boolean and(Boolean... values) {
    Boolean result = true;
    for (Boolean value : values) {
      if (Boolean.FALSE.equals(value)) {
        return false;
      }
      if (value == null) {
        result = null;
      }
    }
    return result;
  }

  private static BigDecimal apply(BigDecimal value, BigDecimal factor) {
    return factor == null ? value : value.multiply(factor);
  }

  record ResolvedRates(
      BigDecimal input,
      BigDecimal output,
      BigDecimal cacheRead,
      BigDecimal cacheWrite,
      Map<Long, BigDecimal> cacheWriteByTtl,
      BigDecimal reasoning,
      boolean independentReasoning) {}
}
