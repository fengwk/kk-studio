package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;

import java.util.List;

/** 目录中的单个可填表候选；id 唯一包含 source/provider/model。 */
@Data
public class ModelDefaultCandidateDTO {

  private String id;

  private String provider;

  private String modelId;

  private String name;

  private AgentModelLimitDTO limit;

  private AgentModelAbilitiesDTO abilities;

  /** 完整价格定义；不受支持或格式错误时为 null，不能部分冒充完整价格。 */
  private AgentModelPricingDTO pricing;

  private List<AgentModelVariantDTO> variants;

  private String defaultVariant;

  private List<String> supportIssues;

  private boolean pricingSupported;
}
