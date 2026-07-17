package fun.fengwk.kkstudio.share.model.studio;

import lombok.Data;

@Data
public class CanvasDocumentDTO {
  private String id;
  private String workspaceId;
  private String title;
  private int schemaVersion;
  private String revision;
  private String lifecycle;
  private String homeViewportJson;
}
