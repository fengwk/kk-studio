package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

@Data
public class CanvasNodeDO {
  private Long id;
  private Long canvasId;
  private String name;
  private String nameNormalized;
  private Double x;
  private Double y;
  private Double width;
  private Double height;
  private Long groupId;
  private String modelKey;
  private String functionConfigJson;
}
