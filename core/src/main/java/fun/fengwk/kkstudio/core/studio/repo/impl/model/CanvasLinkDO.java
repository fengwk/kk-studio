package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

@Data
public class CanvasLinkDO {
  private Long canvasId;
  private Long sourceNodeId;
  private Long targetNodeId;
}
