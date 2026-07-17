package fun.fengwk.kkstudio.share.model.studio;

import lombok.Data;

@Data
public class CanvasNodeDTO {
  private String id;
  private String canvasId;
  private String kind;
  private String nodeType;
  private int nodeTypeVersion;
  private String name;
  private String parentGroupId;
  private double x;
  private double y;
  private double width;
  private double height;
  private double rotation;
  private String zIndex;
  private boolean locked;
  private boolean hidden;
  private String validity;
  private String revision;
  private String dataJson;
}
