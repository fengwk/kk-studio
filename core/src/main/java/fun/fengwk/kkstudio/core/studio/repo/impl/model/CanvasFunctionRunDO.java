package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class CanvasFunctionRunDO {
  private Long nodeId;
  private String requestId;
  private String status;
  private String stateJson;
  private String error;
  private OffsetDateTime updatedAt;
}
