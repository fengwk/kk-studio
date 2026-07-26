package fun.fengwk.kkstudio.share.model.studio;

import lombok.Data;

@Data
public class CanvasDocumentDTO {
  private String id;
  private String title;
  private String revision;
  private String homeViewportJson;
}
