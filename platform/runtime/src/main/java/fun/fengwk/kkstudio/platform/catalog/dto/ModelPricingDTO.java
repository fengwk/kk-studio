package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;

import java.util.List;

/**
 * 完整模型价格定义：基础费率、按顺序应用的覆盖规则和按顺序应用的倍率规则。
 *
 * <p>所有 decimal 在 wire 上都是十进制字符串；未提供普通价格按 0、未提供倍率按 1、reasoning 缺省继承输出。
 */
@Data
public class ModelPricingDTO {

  private ModelRateSetDTO base;

  private List<ModelPricingOverrideDTO> overrides;

  private List<ModelPricingMultiplierDTO> multipliers;
}
