package fun.fengwk.kkstudio.platform.catalog.model;

import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingChangeDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingDTO;
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
    normalizeBase(pricing.getBase(), pricing::setBase);
    if (pricing.getRules() == null) {
      pricing.setRules(List.of());
    }
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
      }
    }
    ModelPricingValidator.validate(pricing);
    return pricing;
  }

  /** null 表示模型尚未配置价格；显式全零价格必须由非 null pricing 对象表达。 */
  public static ModelPricing parsePricing(ModelPricingDTO pricing) {
    if (pricing == null) {
      return null;
    }
    return ModelPricingMapper.toModelPricing(normalizeAndValidate(pricing));
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
