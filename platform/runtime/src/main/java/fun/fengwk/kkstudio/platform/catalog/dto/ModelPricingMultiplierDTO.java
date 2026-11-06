package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;

/** 条件命中的价格倍率规则。 */
@Data
public class ModelPricingMultiplierDTO {

  private String id;

  private ModelPricingConditionDTO when;

  private ModelPricingFactorsDTO factors;
}
