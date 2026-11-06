package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.util.List;

/** 价格规则条件；同一对象内的字段为 AND。all 缺省/空恒真，any/serviceTier 缺省不约束、显式空列表恒假。 */
@Data
public class ModelPricingConditionDTO {

  private @Nullable ModelPricingContextConditionDTO context;

  private @Nullable List<String> serviceTier;

  private @Nullable ModelPricingDateConditionDTO date;

  private @Nullable ModelPricingTimeConditionDTO time;

  private @Nullable ModelPricingRequestBodyConditionDTO requestBody;

  private @Nullable List<ModelPricingConditionDTO> all;

  private @Nullable List<ModelPricingConditionDTO> any;

  private @Nullable ModelPricingConditionDTO not;
}
