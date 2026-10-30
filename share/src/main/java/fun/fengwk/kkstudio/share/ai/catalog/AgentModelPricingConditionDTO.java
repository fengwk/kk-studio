package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.util.List;

/** 价格规则条件；同一对象内的字段为 AND。all 缺省/空恒真，any/serviceTier 缺省不约束、显式空列表恒假。 */
@Data
public class AgentModelPricingConditionDTO {

  private @Nullable AgentModelPricingContextConditionDTO context;

  private @Nullable List<String> serviceTier;

  private @Nullable AgentModelPricingDateConditionDTO date;

  private @Nullable AgentModelPricingTimeConditionDTO time;

  private @Nullable AgentModelPricingRequestBodyConditionDTO requestBody;

  private @Nullable List<AgentModelPricingConditionDTO> all;

  private @Nullable List<AgentModelPricingConditionDTO> any;

  private @Nullable AgentModelPricingConditionDTO not;
}
