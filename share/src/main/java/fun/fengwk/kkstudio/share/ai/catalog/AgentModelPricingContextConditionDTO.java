package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;

/** Context token 边界；同一侧只能声明一种边界。 */
@Data
public class AgentModelPricingContextConditionDTO {

  private Integer gt;

  private Integer gte;

  private Integer lt;

  private Integer lte;
}
