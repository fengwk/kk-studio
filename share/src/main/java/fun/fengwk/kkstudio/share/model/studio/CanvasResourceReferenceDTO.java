package fun.fengwk.kkstudio.share.model.studio;

import lombok.Data;

@Data
public class CanvasResourceReferenceDTO {
  private String id;
  private String canvasId;
  private String targetNodeId;
  private String targetPath;
  private String visibilityLinkId;
  private String dependencySourceNodeId;
  private String selectorType;
  private String selectorJson;
  private String revision;
}
