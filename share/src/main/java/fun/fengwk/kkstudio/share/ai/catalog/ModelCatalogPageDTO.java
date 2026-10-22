package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;

import java.time.Instant;
import java.util.List;

/** 有界、来源隔离的模型默认参数候选页。 */
@Data
public class ModelCatalogPageDTO {

  private String source;

  private String generation;

  private Instant fetchedAt;

  private boolean loading;

  private String error;

  private List<ModelDefaultCandidateDTO> items;

  private String nextCursor;
}
