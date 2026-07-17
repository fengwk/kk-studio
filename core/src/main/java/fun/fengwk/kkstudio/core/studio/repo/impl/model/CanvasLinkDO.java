package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CanvasLinkDO {
  private Long id;
  private Long canvasId;
  private Long sourceNodeId;
  private Long targetNodeId;
  private Long revision;
  private LocalDateTime createTime;
}
