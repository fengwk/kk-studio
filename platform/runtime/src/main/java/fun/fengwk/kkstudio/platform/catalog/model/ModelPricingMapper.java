package fun.fengwk.kkstudio.platform.catalog.model;

import static fun.fengwk.kkstudio.platform.catalog.model.ModelPricingWireValues.invalid;
import static fun.fengwk.kkstudio.platform.catalog.model.ModelPricingWireValues.parseTtl;
import static fun.fengwk.kkstudio.platform.catalog.model.ModelPricingWireValues.requireDate;
import static fun.fengwk.kkstudio.platform.catalog.model.ModelPricingWireValues.requireMinute;
import static fun.fengwk.kkstudio.platform.catalog.model.ModelPricingWireValues.requireZone;

import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingChangeDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingRuleDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelRateSetDTO;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 已校验的固定三组价格 DTO 到 harness 领域模型的唯一转换入口。 */
final class ModelPricingMapper {

  private static final String PATH = "config.pricing";

  private ModelPricingMapper() {}

  static ModelPricing toModelPricing(ModelPricingDTO pricing) {
    try {
      return new ModelPricing(
          toRateSet(pricing.getBase()),
          toRules(pricing.getRules()),
          toChanges(pricing.getChanges()));
    } catch (IllegalArgumentException error) {
      throw invalid(PATH + " is invalid: " + error.getMessage(), error);
    }
  }

  private static ModelPricing.RateSet toRateSet(ModelRateSetDTO rates) {
    if (rates == null) {
      return ModelPricing.RateSet.ZERO;
    }
    return new ModelPricing.RateSet(
        zeroIfNull(rates.getInput()),
        zeroIfNull(rates.getOutput()),
        zeroIfNull(rates.getCacheRead()),
        rates.getCacheWrite(),
        toTtlRates(rates.getCacheWriteByTtl()),
        rates.getReasoning());
  }

  private static List<ModelPricing.DateChange> toChanges(List<ModelPricingChangeDTO> changes) {
    if (changes == null) {
      return List.of();
    }
    List<ModelPricing.DateChange> result = new ArrayList<>(changes.size());
    for (ModelPricingChangeDTO change : changes) {
      result.add(
          new ModelPricing.DateChange(
              requireDate(change.getFrom(), PATH + ".changes.from"),
              toRateSet(change.getBase()),
              toRules(change.getRules())));
    }
    return result;
  }

  private static List<ModelPricing.Rule> toRules(List<ModelPricingRuleDTO> rules) {
    if (rules == null) {
      return List.of();
    }
    List<ModelPricing.Rule> result = new ArrayList<>(rules.size());
    for (ModelPricingRuleDTO rule : rules) {
      result.add(toRule(rule));
    }
    return result;
  }

  private static ModelPricing.Rule toRule(ModelPricingRuleDTO rule) {
    return switch (rule.getType()) {
      case "input_token_range" -> new ModelPricing.InputTokenRange(
          rule.getMinInputTokens() == null ? 0 : rule.getMinInputTokens(),
          rule.getMaxInputTokensExclusive(),
          rule.getInput(),
          rule.getOutput(),
          rule.getCacheRead(),
          rule.getCacheWrite(),
          rule.isCacheWriteByTtlSpecified() ? toTtlRates(rule.getCacheWriteByTtl()) : null,
          rule.getReasoning(),
          rule.isCacheWriteByTtlSpecified(),
          rule.isReasoningSpecified());
      case "time_multiplier" -> new ModelPricing.TimeMultiplier(
          requireMinute(rule.getStart(), false, PATH + ".rules.start"),
          requireMinute(rule.getEnd(), true, PATH + ".rules.end"),
          requireZone(rule.getTimezone(), PATH + ".rules.timezone"),
          rule.getWeekdays(),
          toFactors(rule));
      case "service_tier_multiplier" -> new ModelPricing.ServiceTierMultiplier(
          rule.getServiceTier(), toFactors(rule));
      default -> throw new IllegalArgumentException("unsupported rule type: " + rule.getType());
    };
  }

  private static ModelPricing.Factors toFactors(ModelPricingRuleDTO rule) {
    return new ModelPricing.Factors(
        rule.getFactor(),
        rule.getInputFactor(),
        rule.getOutputFactor(),
        rule.getCacheReadFactor(),
        rule.getCacheWriteFactor(),
        rule.getReasoningFactor());
  }

  private static Map<Long, BigDecimal> toTtlRates(Map<String, BigDecimal> rates) {
    if (rates == null || rates.isEmpty()) {
      return Map.of();
    }
    Map<Long, BigDecimal> result = new LinkedHashMap<>();
    for (Map.Entry<String, BigDecimal> entry : rates.entrySet()) {
      long ttl = parseTtl(entry.getKey(), PATH + ".base.cacheWriteByTtl");
      if (result.put(ttl, entry.getValue()) != null) {
        throw new IllegalArgumentException("cacheWriteByTtl contains duplicate TTL: " + ttl);
      }
    }
    return Map.copyOf(result);
  }

  private static BigDecimal zeroIfNull(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
