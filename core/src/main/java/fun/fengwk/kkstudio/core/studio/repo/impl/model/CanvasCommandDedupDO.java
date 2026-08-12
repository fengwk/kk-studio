package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/** {@code canvas_command_dedup} 行映射。 */
@Data
public class CanvasCommandDedupDO {
  private UUID canvasId;
  private UUID commandId;
  private String requestHash;
  private Long appliedVersion;
  private OffsetDateTime createdAt;
}
