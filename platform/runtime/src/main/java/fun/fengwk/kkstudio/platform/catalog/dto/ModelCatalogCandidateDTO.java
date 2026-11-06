package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.util.List;

/** 目录中的单个可填表候选；id 唯一包含 source/provider/model。 */
@Data
public class ModelCatalogCandidateDTO {

  private String id;

  private String provider;

  private String modelId;

  private String name;

  private @Nullable ModelCatalogLimitDTO limit;

  private ModelCatalogAbilitiesDTO abilities;

  /** 完整价格定义；不受支持或格式错误时为 null，不能部分冒充完整价格。 */
  private @Nullable ModelPricingDTO pricing;

  private @Nullable List<ModelVariantDTO> variants;

  private @Nullable String defaultVariant;

  private List<String> supportIssues;

  private boolean pricingSupported;
}
