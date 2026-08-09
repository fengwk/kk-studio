package fun.fengwk.kkstudio.share.studio;

import lombok.Data;

@Data
public class CanvasGroupDTO {
  private String id;
  private String canvasId;
  private String title;
  private CanvasTransformDTO transform;
}
