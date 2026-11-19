package fun.fengwk.kkstudio.harness.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 按固定规则组和 UTC 日期计划解析一次请求的费率。 */
public final class ModelPricingResolver {

  private ModelPricingResolver() {}

  static ResolvedRates resolve(
      ModelPricing pricing, ModelUsage usage, Instant requestedAt, String serviceTier) {
    Objects.requireNonNull(pricing, "pricing");
    Objects.requireNonNull(usage, "usage");

    ModelPricing.PriceDefinition definition = definition(pricing, requestedAt);
    if (definition == null) {
      return null;
    }
    ModelPricing.RateSet base = definition.base();
    BigDecimal input = base.input();
    BigDecimal output = base.output();
    BigDecimal cacheRead = base.cacheRead();
    BigDecimal cacheWrite = base.cacheWrite();
    Map<Long, BigDecimal> cacheWriteByTtl = new LinkedHashMap<>(base.cacheWriteByTtl());
    boolean independentReasoning = base.independentReasoning();
    BigDecimal reasoning = base.reasoning();

    for (ModelPricing.Rule rule : definition.rules()) {
      if (rule instanceof ModelPricing.InputTokenRange range
          && range.contains(usage.contextTokens())) {
        if (range.input() != null) {
          input = range.input();
        }
        if (range.output() != null) {
          output = range.output();
        }
        if (range.cacheRead() != null) {
          cacheRead = range.cacheRead();
        }
        if (range.cacheWrite() != null) {
          cacheWrite = range.cacheWrite();
        }
        if (range.cacheWriteByTtlSpecified()) {
          cacheWriteByTtl = new LinkedHashMap<>(range.cacheWriteByTtl());
        }
        if (range.reasoningSpecified()) {
          independentReasoning = range.reasoning() != null;
          reasoning = range.reasoning();
        }
      }
    }

    ModelPricing.Factors timeFactors = null;
    ModelPricing.Factors tierFactors = null;
    boolean requiresTime = false;
    boolean requiresTier = false;
    for (ModelPricing.Rule rule : definition.rules()) {
      if (rule instanceof ModelPricing.TimeMultiplier time) {
        requiresTime = true;
        if (requestedAt != null && time.matches(requestedAt)) {
          timeFactors = time.factors();
        }
      } else if (rule instanceof ModelPricing.ServiceTierMultiplier tier) {
        requiresTier = true;
        if (Objects.equals(tier.serviceTier(), serviceTier)) {
          tierFactors = tier.factors();
        }
      }
    }
    if ((requiresTime && requestedAt == null)
        || (requiresTier && (serviceTier == null || serviceTier.isBlank()))) {
      return null;
    }

    if (tierFactors != null) {
      ModelPricing.Factors factors = tierFactors;
      input = apply(input, factors.input());
      output = apply(output, factors.output());
      cacheRead = apply(cacheRead, factors.cacheRead());
      cacheWrite = apply(cacheWrite, factors.cacheWrite());
      cacheWriteByTtl.replaceAll((ttl, rate) -> apply(rate, factors.cacheWrite()));
      if (independentReasoning) {
        reasoning = apply(reasoning, factors.reasoning());
      }
    }
    if (timeFactors != null) {
      ModelPricing.Factors factors = timeFactors;
      input = apply(input, factors.input());
      output = apply(output, factors.output());
      cacheRead = apply(cacheRead, factors.cacheRead());
      cacheWrite = apply(cacheWrite, factors.cacheWrite());
      cacheWriteByTtl.replaceAll((ttl, rate) -> apply(rate, factors.cacheWrite()));
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

  private static ModelPricing.PriceDefinition definition(
      ModelPricing pricing, Instant requestedAt) {
    if (pricing.changes().isEmpty()) {
      return new ModelPricing.PriceDefinition(pricing.base(), pricing.rules());
    }
    if (requestedAt == null) {
      return null;
    }
    LocalDate date = requestedAt.atZone(ZoneOffset.UTC).toLocalDate();
    ModelPricing.RateSet base = pricing.base();
    var rules = pricing.rules();
    for (ModelPricing.DateChange change : pricing.changes()) {
      if (date.isBefore(change.from())) {
        break;
      }
      base = change.base();
      rules = change.rules();
    }
    return new ModelPricing.PriceDefinition(base, rules);
  }

  /** Resolve rates and matched rule identities for a non-persisting pricing preview. */
  public static PricingPreview preview(
      ModelPricing pricing, long contextTokens, Instant requestedAt, String serviceTier) {
    if (pricing == null) {
      return new PricingPreview(null, null, List.of());
    }
    ModelPricing.PriceDefinition definition = definition(pricing, requestedAt);
    if (definition == null) {
      return new PricingPreview(null, null, List.of());
    }
    List<ModelPricing.Rule> matchedRules = new ArrayList<>();
    for (ModelPricing.Rule rule : definition.rules()) {
      if ((rule instanceof ModelPricing.InputTokenRange range && range.contains(contextTokens))
          || (rule instanceof ModelPricing.TimeMultiplier time
              && requestedAt != null
              && time.matches(requestedAt))
          || (rule instanceof ModelPricing.ServiceTierMultiplier tier
              && Objects.equals(tier.serviceTier(), serviceTier))) {
        matchedRules.add(rule);
      }
    }
    LocalDate activeFrom = null;
    if (requestedAt != null) {
      LocalDate date = requestedAt.atZone(ZoneOffset.UTC).toLocalDate();
      for (ModelPricing.DateChange change : pricing.changes()) {
        if (date.isBefore(change.from())) {
          break;
        }
        activeFrom = change.from();
      }
    }
    ResolvedRates rates =
        resolve(
            pricing,
            new ModelUsage(contextTokens, 0, 0, 0, Map.of(), null, null),
            requestedAt,
            serviceTier);
    return new PricingPreview(rates, activeFrom, List.copyOf(matchedRules));
  }

  private static BigDecimal apply(BigDecimal value, BigDecimal factor) {
    return value == null || factor == null ? value : value.multiply(factor);
  }

  public record ResolvedRates(
      BigDecimal input,
      BigDecimal output,
      BigDecimal cacheRead,
      BigDecimal cacheWrite,
      Map<Long, BigDecimal> cacheWriteByTtl,
      BigDecimal reasoning,
      boolean independentReasoning) {}

  public record PricingPreview(
      ResolvedRates rates, LocalDate activeFrom, List<ModelPricing.Rule> matchedRules) {}
}
