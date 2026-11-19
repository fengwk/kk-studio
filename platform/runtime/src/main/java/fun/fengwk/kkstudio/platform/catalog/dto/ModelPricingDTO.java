package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;

import java.util.List;

/** 完整模型价格计划：调价前价格定义与按 UTC 日期生效的价格变更。 */
@Data
public class ModelPricingDTO {

  private ModelRateSetDTO base;

  private List<ModelPricingRuleDTO> rules;

  private List<ModelPricingChangeDTO> changes;
}
