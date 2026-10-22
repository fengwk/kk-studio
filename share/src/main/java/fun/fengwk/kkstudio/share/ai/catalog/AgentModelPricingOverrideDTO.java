package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;

/** 条件命中的费率补丁。 */
@Data
public class AgentModelPricingOverrideDTO {

  private String id;

  private AgentModelPricingConditionDTO when;

  private AgentModelRatePatchDTO rates;
}
