package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;

/** A prompt-cache mode and its known write cost for the selected provider/model. */
@Data
public class PromptCacheOptionDTO {

  private PromptCacheRetention retention;

  private @Nullable Long ttlSeconds;

  private @Nullable Double writeMultiplier;
}
