package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;
import org.jspecify.annotations.Nullable;

/** Context token 边界；同一侧只能声明一种边界。 */
@Data
public class AgentModelPricingContextConditionDTO {

  private @Nullable Integer gt;

  private @Nullable Integer gte;

  private @Nullable Integer lt;

  private @Nullable Integer lte;
}
