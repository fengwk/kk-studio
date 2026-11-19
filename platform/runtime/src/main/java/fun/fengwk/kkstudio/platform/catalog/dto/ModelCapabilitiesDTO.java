package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;

import java.util.List;

/** Backend-resolved prompt-cache capabilities for a provider endpoint and model id. */
@Data
public class ModelCapabilitiesDTO {

  private List<PromptCacheOptionDTO> promptCacheOptions;

  private @Nullable PromptCacheRetention defaultPromptCacheRetention;

  private boolean automaticCache;
}
