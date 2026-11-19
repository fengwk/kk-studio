package fun.fengwk.kkstudio.platform.catalog.model;

import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingChangeDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingRuleDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelRateSetDTO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 模型计价定义的归一化入口与 runtime 转换门面。 */
public final class ModelPricingCodec {

  private static final Comparator<ModelPricingRuleDTO> RULE_ORDER =
      Comparator.comparingInt(ModelPricingCodec::groupOrder)
          .thenComparingInt(ModelPricingCodec::numericOrder)
          .thenComparing(
              rule ->
                  switch (rule.getType()) {
                    case "service_tier_multiplier" -> rule.getServiceTier();
                    case "time_multiplier" -> rule.getStart();
                    default -> "";
                  });

  private ModelPricingCodec() {}

  static ModelPricingDTO normalizeAndValidate(ModelPricingDTO pricing) {
    ModelPricingValidator.validate(pricing);
    normalizeBase(pricing.getBase(), pricing::setBase);
    pricing.setRules(normalizeRules(pricing.getRules()));
    if (pricing.getChanges() == null) {
      pricing.setChanges(List.of());
    } else {
      pricing.setChanges(
          pricing.getChanges().stream()
              .sorted(Comparator.comparing(change -> LocalDate.parse(change.getFrom())))
              .toList());
    }
    for (ModelPricingChangeDTO change : pricing.getChanges()) {
      normalizeBase(change.getBase(), change::setBase);
      change.setRules(normalizeRules(change.getRules()));
    }
    return pricing;
  }

  /** null 表示模型尚未配置价格；显式全零价格必须由非 null pricing 对象表达。 */
  public static ModelPricing parsePricing(ModelPricingDTO pricing) {
    if (pricing == null) {
      return null;
    }
    return ModelPricingMapper.toModelPricing(normalizeAndValidate(pricing));
  }

  private static List<ModelPricingRuleDTO> normalizeRules(List<ModelPricingRuleDTO> rules) {
    if (rules == null) {
      return List.of();
    }
    for (ModelPricingRuleDTO rule : rules) {
      if (rule.getWeekdays() != null) {
        rule.setWeekdays(rule.getWeekdays().stream().sorted().toList());
      }
      BigDecimal factor = rule.getFactor();
      if (factor == null) {
        continue;
      }
      if (same(rule.getInputFactor(), factor)) {
        rule.setInputFactor(null);
      }
      if (same(rule.getOutputFactor(), factor)) {
        rule.setOutputFactor(null);
      }
      if (same(rule.getCacheReadFactor(), factor)) {
        rule.setCacheReadFactor(null);
      }
      if (same(rule.getCacheWriteFactor(), factor)) {
        rule.setCacheWriteFactor(null);
      }
      if (same(rule.getReasoningFactor(), factor)) {
        rule.setReasoningFactor(null);
      }
    }
    return rules.stream().sorted(RULE_ORDER).toList();
  }

  private static int groupOrder(ModelPricingRuleDTO rule) {
    return switch (rule.getType()) {
      case "input_token_range" -> 0;
      case "service_tier_multiplier" -> 1;
      case "time_multiplier" -> 2;
      default -> throw new IllegalArgumentException("unsupported pricing rule type");
    };
  }

  private static int numericOrder(ModelPricingRuleDTO rule) {
    if (rule.getMinInputTokens() != null) {
      return rule.getMinInputTokens();
    }
    List<Integer> weekdays = rule.getWeekdays();
    return weekdays == null || weekdays.isEmpty() ? 0 : weekdays.getFirst();
  }

  private static boolean same(BigDecimal left, BigDecimal right) {
    return left != null && left.compareTo(right) == 0;
  }

  private static void normalizeBase(ModelRateSetDTO base, Consumer<ModelRateSetDTO> setter) {
    if (base == null) {
      base = new ModelRateSetDTO();
      setter.accept(base);
    }
    if (base.getInput() == null) {
      base.setInput(BigDecimal.ZERO);
    }
    if (base.getOutput() == null) {
      base.setOutput(BigDecimal.ZERO);
    }
    if (base.getCacheRead() == null) {
      base.setCacheRead(BigDecimal.ZERO);
    }
    if (base.getCacheWrite() == null
        && (base.getCacheWriteByTtl() == null || base.getCacheWriteByTtl().isEmpty())) {
      base.setCacheWrite(BigDecimal.ZERO);
    }
    if (base.getCacheWriteByTtl() == null) {
      base.setCacheWriteByTtl(Map.of());
    }
  }
}
