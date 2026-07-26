package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

/** {@code canvas_link} row mapping: canvas visibility edge between two same-canvas nodes. */
@Data
public class CanvasLinkDO {
  /** Business id. */
  private Long id;

  /** Owning canvas id. */
  private Long canvasId;

  /** Source node id. */
  private Long sourceNodeId;

  /** Target node id. */
  private Long targetNodeId;
}
