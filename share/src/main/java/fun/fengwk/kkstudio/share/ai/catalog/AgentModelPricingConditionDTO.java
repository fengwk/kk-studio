package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;

import java.util.List;

/** 价格规则条件；同一对象内的字段为 AND。all 缺省/空恒真，any/serviceTier 缺省不约束、显式空列表恒假。 */
@Data
public class AgentModelPricingConditionDTO {

  private AgentModelPricingContextConditionDTO context;

  private List<String> serviceTier;

  private AgentModelPricingDateConditionDTO date;

  private AgentModelPricingTimeConditionDTO time;

  private List<AgentModelPricingConditionDTO> all;

  private List<AgentModelPricingConditionDTO> any;

  private AgentModelPricingConditionDTO not;
}
