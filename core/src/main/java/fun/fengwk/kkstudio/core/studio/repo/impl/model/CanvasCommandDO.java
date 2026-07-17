package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CanvasCommandDO {
  private Long id;
  private String commandId;
  private Long workspaceId;
  private Long canvasId;
  private Long baseRevision;
  private Long resultRevision;
  private String requestHash;
  private String payloadJson;
  private String resultJson;
  private LocalDateTime createTime;
}
