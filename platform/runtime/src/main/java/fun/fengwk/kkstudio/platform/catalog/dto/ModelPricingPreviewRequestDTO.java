package fun.fengwk.kkstudio.platform.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/** Inputs for an authoritative, non-persisting price preview. */
@Data
public class ModelPricingPreviewRequestDTO {

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable ModelPricingDTO pricing;

  private int inputTokens;

  private @Nullable Instant requestedAt;

  private @Nullable String serviceTier;
}
