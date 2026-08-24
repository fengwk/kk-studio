package fun.fengwk.kkstudio.canvas.infra.postgresql;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/** {@code canvas_function_run} 行映射。 */
@Data
public class CanvasFunctionRunDO {
  private UUID nodeId;
  private UUID requestId;
  private String status;
  private int attempt;
  private OffsetDateTime availableAt;
  private String leaseToken;
  private OffsetDateTime leaseUntil;
  private String stateJson;
  private String error;
  private OffsetDateTime updatedAt;
  private OffsetDateTime createdAt;
}
