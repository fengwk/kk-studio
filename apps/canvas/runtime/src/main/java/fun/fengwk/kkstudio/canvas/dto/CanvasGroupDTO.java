package fun.fengwk.kkstudio.canvas.dto;

import lombok.Data;

@Data
public class CanvasGroupDTO {
  private String id;
  private String canvasId;
  private String title;
  private CanvasTransformDTO transform;
}
