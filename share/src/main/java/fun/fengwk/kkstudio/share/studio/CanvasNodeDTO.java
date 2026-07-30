package fun.fengwk.kkstudio.share.studio;

import lombok.Data;

@Data
public class CanvasNodeDTO {
  private String id;
  private String kind;
  private String nodeType;
  private String name;
  private double x;
  private double y;
  private double width;
  private double height;
  private String dataJson;
}
