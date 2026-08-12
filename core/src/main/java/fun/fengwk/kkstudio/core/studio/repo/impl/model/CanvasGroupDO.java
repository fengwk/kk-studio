package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

import java.util.UUID;

/** {@code canvas_group} 行映射。 */
@Data
public class CanvasGroupDO {
  private UUID id;
  private UUID canvasId;
  private String title;
  private Double x;
  private Double y;
  private Double width;
  private Double height;
}
