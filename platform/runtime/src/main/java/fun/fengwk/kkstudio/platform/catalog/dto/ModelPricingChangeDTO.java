package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;

import java.util.List;

/** 从 UTC 日期起替换为完整价格定义的调价项。 */
@Data
public class ModelPricingChangeDTO {

  private String from;

  private ModelRateSetDTO base;

  private List<ModelPricingRuleDTO> rules;
}
