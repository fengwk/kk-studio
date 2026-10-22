package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;

/** 条件命中的价格倍率规则。 */
@Data
public class AgentModelPricingMultiplierDTO {

  private String id;

  private AgentModelPricingConditionDTO when;

  private AgentModelPricingFactorsDTO factors;
}
