package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;

import java.util.List;

/** 价格规则条件；同一对象内的字段为 AND，空对象恒真。 */
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
