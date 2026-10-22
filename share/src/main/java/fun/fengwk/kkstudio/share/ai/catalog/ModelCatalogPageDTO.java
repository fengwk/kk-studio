package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

/** 有界、来源隔离的模型默认参数候选页。 */
@Data
public class ModelCatalogPageDTO {

  private ModelCatalogSource source;

  private String generation;

  private @Nullable Instant fetchedAt;

  private boolean loading;

  private @Nullable String error;

  private List<ModelDefaultCandidateDTO> items;

  private @Nullable String nextCursor;
}
