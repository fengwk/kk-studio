package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/** {@code canvas_function_run} 行映射。 */
@Data
public class CanvasFunctionRunDO {
  private UUID nodeId;
  private UUID requestId;
  private String status;
  private String stateJson;
  private String error;
  private OffsetDateTime updatedAt;
}
