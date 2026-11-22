package fun.fengwk.kkstudio.platform.catalog.model;

import static fun.fengwk.kkstudio.platform.catalog.model.ModelPricingWireValues.invalid;
import static fun.fengwk.kkstudio.platform.catalog.model.ModelPricingWireValues.parseTtl;
import static fun.fengwk.kkstudio.platform.catalog.model.ModelPricingWireValues.requireDate;
import static fun.fengwk.kkstudio.platform.catalog.model.ModelPricingWireValues.requireMinute;
import static fun.fengwk.kkstudio.platform.catalog.model.ModelPricingWireValues.requireOptionalNonNegative;
import static fun.fengwk.kkstudio.platform.catalog.model.ModelPricingWireValues.requireZone;

import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingChangeDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingRuleDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelRateSetDTO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 固定三组 pricing wire contract 的结构/数值校验，再以领域构造器校验互斥条件。 */
final class ModelPricingValidator {

  private static final String PATH = "config.pricing";
  private static final Set<String> RATE_FIELDS =
      Set.of(
          "factor",
          "inputFactor",
          "outputFactor",
          "cacheReadFactor",
          "cacheWriteFactor",
          "reasoningFactor");

  private ModelPricingValidator() {}

  static void validate(ModelPricingDTO pricing) {
    validateRateSet(pricing.getBase(), PATH + ".base");
    validateRules(pricing.getRules(), PATH + ".rules");
    List<ModelPricingChangeDTO> changes = pricing.getChanges();
    if (changes != null) {
      if (changes.size() > ModelPricing.MAX_RULES) {
        throw invalid(PATH + ".changes must not exceed " + ModelPricing.MAX_RULES + " entries");
      }
      Set<LocalDate> dates = new HashSet<>();
      for (int i = 0; i < changes.size(); i++) {
        ModelPricingChangeDTO change = changes.get(i);
        if (change == null) {
          throw invalid(PATH + ".changes[" + i + "] must be an object");
        }
        LocalDate date = requireDate(change.getFrom(), PATH + ".changes[" + i + "].from");
        if (!dates.add(date)) {
          throw invalid(PATH + ".changes.from dates must be unique");
        }
        if (change.getBase() == null) {
          throw invalid(PATH + ".changes[" + i + "].base must be an object");
        }
        validateRateSet(change.getBase(), PATH + ".changes[" + i + "].base");
        validateRules(change.getRules(), PATH + ".changes[" + i + "].rules");
      }
    }
    try {
      ModelPricingMapper.toModelPricing(pricing);
    } catch (IllegalArgumentException error) {
      throw invalid(PATH + " is invalid: " + error.getMessage(), error);
    }
  }

  private static void validateRateSet(ModelRateSetDTO rates, String path) {
    if (rates == null) {
      return;
    }
    requireOptionalNonNegative(rates.getInput(), path + ".input");
    requireOptionalNonNegative(rates.getOutput(), path + ".output");
    requireOptionalNonNegative(rates.getCacheRead(), path + ".cacheRead");
    requireOptionalNonNegative(rates.getCacheWrite(), path + ".cacheWrite");
    requireOptionalNonNegative(rates.getReasoning(), path + ".reasoning");
    Map<String, BigDecimal> ttlRates = rates.getCacheWriteByTtl();
    if (ttlRates == null) {
      return;
    }
    validateTtlRates(ttlRates, path + ".cacheWriteByTtl");
  }

  private static void validateTtlRates(Map<String, BigDecimal> ttlRates, String path) {
    if (ttlRates.size() > ModelPricing.MAX_RULES) {
      throw invalid(path + " must not exceed " + ModelPricing.MAX_RULES + " entries");
    }
    Set<Long> ttls = new HashSet<>();
    for (Map.Entry<String, BigDecimal> entry : ttlRates.entrySet()) {
      long ttl = parseTtl(entry.getKey(), path);
      if (!ttls.add(ttl)) {
        throw invalid(path + " contains duplicate TTL: " + ttl);
      }
      String ratePath = path + "." + ttl;
      BigDecimal rate = entry.getValue();
      if (rate == null) {
        throw invalid(ratePath + " is required");
      }
      requireOptionalNonNegative(rate, ratePath);
    }
  }

  private static void validateRules(List<ModelPricingRuleDTO> rules, String path) {
    if (rules == null) {
      return;
    }
    if (rules.size() > ModelPricing.MAX_RULES) {
      throw invalid(path + " must not exceed " + ModelPricing.MAX_RULES + " rules");
    }
    for (int index = 0; index < rules.size(); index++) {
      ModelPricingRuleDTO rule = rules.get(index);
      String rulePath = path + "[" + index + "]";
      if (rule == null) {
        throw invalid(rulePath + " must be an object");
      }
      switch (rule.getType() == null ? "" : rule.getType()) {
        case "input_token_range" -> validateRange(rule, rulePath);
        case "time_multiplier" -> validateTime(rule, rulePath);
        case "service_tier_multiplier" -> validateTier(rule, rulePath);
        default -> throw invalid(
            rulePath
                + ".type must be input_token_range, time_multiplier or service_tier_multiplier");
      }
    }
  }

  private static void validateRange(ModelPricingRuleDTO rule, String path) {
    requireOnly(
        rule,
        path,
        Set.of(
            "type",
            "minInputTokens",
            "maxInputTokensExclusive",
            "input",
            "output",
            "cacheRead",
            "cacheWrite",
            "cacheWriteByTtl",
            "reasoning"));
    if (rule.getMinInputTokens() == null && rule.getMaxInputTokensExclusive() == null) {
      throw invalid(path + " must specify at least one range boundary");
    }
    int minInput = rule.getMinInputTokens() == null ? 0 : rule.getMinInputTokens();
    if (minInput < 0) {
      throw invalid(path + ".minInputTokens must be a non-negative integer");
    }
    if (rule.getMaxInputTokensExclusive() != null
        && rule.getMaxInputTokensExclusive() <= minInput) {
      throw invalid(path + ".maxInputTokensExclusive must exceed minInputTokens");
    }
    if (rule.getInput() == null
        && rule.getOutput() == null
        && rule.getCacheRead() == null
        && rule.getCacheWrite() == null
        && !rule.isCacheWriteByTtlSpecified()
        && !rule.isReasoningSpecified()) {
      throw invalid(path + " must override at least one fee rate");
    }
    requireOptionalNonNegative(rule.getInput(), path + ".input");
    requireOptionalNonNegative(rule.getOutput(), path + ".output");
    requireOptionalNonNegative(rule.getCacheRead(), path + ".cacheRead");
    requireOptionalNonNegative(rule.getCacheWrite(), path + ".cacheWrite");
    if (rule.isCacheWriteByTtlSpecified()) {
      if (rule.getCacheWriteByTtl() == null) {
        throw invalid(path + ".cacheWriteByTtl must be an object");
      }
      validateTtlRates(rule.getCacheWriteByTtl(), path + ".cacheWriteByTtl");
    }
    if (rule.isReasoningSpecified()) {
      requireOptionalNonNegative(rule.getReasoning(), path + ".reasoning");
    }
  }

  private static void validateTime(ModelPricingRuleDTO rule, String path) {
    requireOnly(
        rule, path, union(Set.of("type", "start", "end", "timezone", "weekdays"), RATE_FIELDS));
    requireMinute(rule.getStart(), false, path + ".start");
    requireMinute(rule.getEnd(), true, path + ".end");
    requireZone(rule.getTimezone(), path + ".timezone");
    validateWeekdays(rule.getWeekdays(), path + ".weekdays");
    validateFactors(rule, path);
  }

  private static void validateTier(ModelPricingRuleDTO rule, String path) {
    requireOnly(rule, path, union(Set.of("type", "serviceTier"), RATE_FIELDS));
    if (rule.getServiceTier() == null || rule.getServiceTier().isBlank()) {
      throw invalid(path + ".serviceTier must be a non-blank string");
    }
    validateFactors(rule, path);
  }

  private static void validateFactors(ModelPricingRuleDTO rule, String path) {
    if (rule.getFactor() == null) {
      throw invalid(path + ".factor is required");
    }
    requireOptionalNonNegative(rule.getFactor(), path + ".factor");
    requireOptionalNonNegative(rule.getInputFactor(), path + ".inputFactor");
    requireOptionalNonNegative(rule.getOutputFactor(), path + ".outputFactor");
    requireOptionalNonNegative(rule.getCacheReadFactor(), path + ".cacheReadFactor");
    requireOptionalNonNegative(rule.getCacheWriteFactor(), path + ".cacheWriteFactor");
    requireOptionalNonNegative(rule.getReasoningFactor(), path + ".reasoningFactor");
  }

  private static void validateWeekdays(List<Integer> weekdays, String path) {
    if (weekdays == null) {
      return;
    }
    if (weekdays.size() > 7) {
      throw invalid(path + " must not exceed 7 values");
    }
    Set<Integer> unique = new HashSet<>();
    for (Integer weekday : weekdays) {
      if (weekday == null || weekday < 1 || weekday > 7 || !unique.add(weekday)) {
        throw invalid(path + " must contain unique ISO weekdays from 1 to 7");
      }
    }
  }

  private static void requireOnly(ModelPricingRuleDTO rule, String path, Set<String> allowed) {
    if (!allowed.contains("minInputTokens") && rule.getMinInputTokens() != null) {
      throw invalid(path + ".minInputTokens is not valid for this type");
    }
    if (!allowed.contains("maxInputTokensExclusive") && rule.getMaxInputTokensExclusive() != null) {
      throw invalid(path + ".maxInputTokensExclusive is not valid for this type");
    }
    if (!allowed.contains("input") && rule.getInput() != null) {
      throw invalid(path + ".input is not valid for this type");
    }
    if (!allowed.contains("output") && rule.getOutput() != null) {
      throw invalid(path + ".output is not valid for this type");
    }
    if (!allowed.contains("cacheRead") && rule.getCacheRead() != null) {
      throw invalid(path + ".cacheRead is not valid for this type");
    }
    if (!allowed.contains("cacheWrite") && rule.getCacheWrite() != null) {
      throw invalid(path + ".cacheWrite is not valid for this type");
    }
    if (!allowed.contains("cacheWriteByTtl")
        && (rule.isCacheWriteByTtlSpecified() || rule.getCacheWriteByTtl() != null)) {
      throw invalid(path + ".cacheWriteByTtl is not valid for this type");
    }
    if (!allowed.contains("reasoning")
        && (rule.isReasoningSpecified() || rule.getReasoning() != null)) {
      throw invalid(path + ".reasoning is not valid for this type");
    }
    if (!allowed.contains("start") && rule.getStart() != null) {
      throw invalid(path + ".start is not valid for this type");
    }
    if (!allowed.contains("end") && rule.getEnd() != null) {
      throw invalid(path + ".end is not valid for this type");
    }
    if (!allowed.contains("timezone") && rule.getTimezone() != null) {
      throw invalid(path + ".timezone is not valid for this type");
    }
    if (!allowed.contains("weekdays") && rule.getWeekdays() != null) {
      throw invalid(path + ".weekdays is not valid for this type");
    }
    if (!allowed.contains("serviceTier") && rule.getServiceTier() != null) {
      throw invalid(path + ".serviceTier is not valid for this type");
    }
    for (String field : RATE_FIELDS) {
      BigDecimal value = factor(rule, field);
      if (!allowed.contains(field) && value != null) {
        throw invalid(path + "." + field + " is not valid for this type");
      }
    }
  }

  private static BigDecimal factor(ModelPricingRuleDTO rule, String field) {
    return switch (field) {
      case "factor" -> rule.getFactor();
      case "inputFactor" -> rule.getInputFactor();
      case "outputFactor" -> rule.getOutputFactor();
      case "cacheReadFactor" -> rule.getCacheReadFactor();
      case "cacheWriteFactor" -> rule.getCacheWriteFactor();
      case "reasoningFactor" -> rule.getReasoningFactor();
      default -> throw new IllegalStateException("unknown factor field");
    };
  }

  private static Set<String> union(Set<String> left, Set<String> right) {
    Set<String> result = new HashSet<>(left);
    result.addAll(right);
    return Set.copyOf(result);
  }
}
