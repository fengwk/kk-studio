package fun.fengwk.kkstudio.platform.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.util.List;

/** Authoritative resolved rates and the rules that matched the supplied facts. */
@Data
public class ModelPricingPreviewDTO {

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable ModelRateSetDTO rates;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String activeFrom;

  private List<ModelPricingRuleDTO> matchedRules;
}
