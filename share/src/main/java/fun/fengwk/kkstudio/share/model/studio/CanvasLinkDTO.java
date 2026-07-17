package fun.fengwk.kkstudio.share.model.studio;

import lombok.Data;

@Data
public class CanvasLinkDTO {
  private String id;
  private String canvasId;
  private String sourceNodeId;
  private String targetNodeId;
  private String revision;
}
