package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

import java.util.UUID;

/** {@code canvas_link} 行映射。 */
@Data
public class CanvasLinkDO {
  private UUID canvasId;
  private UUID sourceNodeId;
  private UUID targetNodeId;
}
