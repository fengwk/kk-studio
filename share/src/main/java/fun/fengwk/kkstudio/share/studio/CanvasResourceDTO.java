package fun.fengwk.kkstudio.share.studio;

import lombok.Data;

@Data
public class CanvasResourceDTO {
  private String id;
  private String canvasId;
  private String kind;
  private String mediaType;
  private String name;
  private String size;
  private String textContent;
  private String metadataJson;
  private String createdAt;
}
