package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

/** UTC 日期区间，{@code gte} 闭区间、{@code lt} 开区间，格式 YYYY-MM-DD。 */
@Data
public class ModelPricingDateConditionDTO {

  private @Nullable String gte;

  private @Nullable String lt;
}
