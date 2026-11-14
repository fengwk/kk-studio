package fun.fengwk.kkstudio.platform.catalog.model;

import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelRateSetDTO;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 模型计价定义的归一化入口与运行时转换门面。
 *
 * <p>校验规则在 {@link ModelPricingValidator}，wire 值解析在 {@link ModelPricingWireValues}，领域模型转换在 {@link
 * ModelPricingMapper}；本类只保留 entry point 与 canonical 归一，使目录候选、运行时配置和持久化回读共用同一顺序。
 */
public final class ModelPricingCodec {

  private ModelPricingCodec() {}

  /** pricing 缺省归一为完整零定义；归一后与目录候选共用同一套校验。 */
  static ModelPricingDTO normalizeAndValidate(ModelPricingDTO pricing) {
    if (pricing == null) {
      pricing = new ModelPricingDTO();
    }
    normalize(pricing);
    ModelPricingValidator.validate(pricing);
    return pricing;
  }

  /** 直接解析单个 pricing DTO；null/缺省即完整零定义。调用方与 {@code parse(config)} 使用同一转换，避免目录校验等旁路漂移。 */
  public static ModelPricing parsePricing(ModelPricingDTO pricing) {
    if (pricing == null) {
      return ModelPricing.ZERO;
    }
    return toModelPricing(normalizeAndValidate(pricing));
  }

  /** 仅供配置 codec 转换已经归一化并校验过的计价定义。 */
  static ModelPricing toModelPricing(ModelPricingDTO pricing) {
    return ModelPricingMapper.toModelPricing(pricing);
  }

  /**
   * 归一化为 canonical pricing DTO：base 普通价格缺失写 0、TTL 缺失写空 map、规则数组缺失写空列表。
   *
   * <p>base.reasoning 缺省仍表示继承 output；rate patch 的 null/缺省仍表示不修改，不能在此默认成 0。
   */
  private static void normalize(ModelPricingDTO pricing) {
    ModelRateSetDTO base = pricing.getBase();
    if (base == null) {
      base = new ModelRateSetDTO();
      pricing.setBase(base);
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
    if (base.getCacheWrite() == null) {
      base.setCacheWrite(BigDecimal.ZERO);
    }
    if (base.getCacheWriteByTtl() == null) {
      base.setCacheWriteByTtl(Map.of());
    }
    if (pricing.getOverrides() == null) {
      pricing.setOverrides(List.of());
    }
    if (pricing.getMultipliers() == null) {
      pricing.setMultipliers(List.of());
    }
  }
}
