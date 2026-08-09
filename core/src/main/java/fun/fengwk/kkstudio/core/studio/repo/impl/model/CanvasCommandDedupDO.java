package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class CanvasCommandDedupDO {
  private Long canvasId;
  private String commandId;
  private String requestHash;
  private Long appliedRevision;
  private OffsetDateTime createdAt;
}
