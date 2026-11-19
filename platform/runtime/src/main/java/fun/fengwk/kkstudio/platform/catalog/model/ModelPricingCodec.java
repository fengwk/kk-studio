package fun.fengwk.kkstudio.platform.catalog.model;

import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingChangeDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingRuleDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelRateSetDTO;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 模型计价定义的归一化入口与 runtime 转换门面。 */
public final class ModelPricingCodec {

  private ModelPricingCodec() {}

  static ModelPricingDTO normalizeAndValidate(ModelPricingDTO pricing) {
    if (pricing == null) {
      pricing = new ModelPricingDTO();
    }
    ModelPricingValidator.validate(pricing);
    normalizeBase(pricing.getBase(), pricing::setBase);
    if (pricing.getRules() == null) {
      pricing.setRules(List.of());
    }
    normalizeRules(pricing.getRules());
    if (pricing.getChanges() == null) {
      pricing.setChanges(List.of());
    }
    for (ModelPricingChangeDTO change : pricing.getChanges()) {
      if (change != null) {
        if (change.getBase() != null) {
          normalizeBase(change.getBase(), change::setBase);
        }
        if (change.getRules() == null) {
          change.setRules(List.of());
        }
        normalizeRules(change.getRules());
      }
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

  private static void normalizeRules(List<ModelPricingRuleDTO> rules) {
    for (ModelPricingRuleDTO rule : rules) {
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
