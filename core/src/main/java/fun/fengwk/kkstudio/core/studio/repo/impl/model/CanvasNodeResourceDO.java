package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

@Data
public class CanvasNodeResourceDO {
  private Long canvasId;
  private Long nodeId;
  private Integer resourceIndex;
  private Long resourceId;
}
