package fun.fengwk.kkstudio.platform.studio.repo.impl.model;

import lombok.Data;

import java.util.UUID;

/** {@code canvas_node} 行映射。 */
@Data
public class CanvasNodeDO {
  private UUID id;
  private UUID canvasId;
  private String name;
  private Double x;
  private Double y;
  private Double width;
  private Double height;
  private UUID groupId;
  private String modelKey;
  private String functionConfigJson;
}
