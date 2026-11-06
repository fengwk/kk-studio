package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;

/** 条件命中的费率补丁。 */
@Data
public class ModelPricingOverrideDTO {

  private String id;

  private ModelPricingConditionDTO when;

  private ModelRatePatchDTO rates;
}
