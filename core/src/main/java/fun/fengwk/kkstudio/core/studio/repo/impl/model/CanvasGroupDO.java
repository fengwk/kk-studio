package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

@Data
public class CanvasGroupDO {
  private Long id;
  private Long canvasId;
  private String title;
  private Double x;
  private Double y;
  private Double width;
  private Double height;
}
