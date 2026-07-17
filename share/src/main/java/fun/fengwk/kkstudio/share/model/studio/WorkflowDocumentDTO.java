package fun.fengwk.kkstudio.share.model.studio;

import lombok.Data;

@Data
public class WorkflowDocumentDTO {
  private String id;
  private String workspaceId;
  private String name;
  private String lifecycle;
  private String publishedVersionId;
  private String revision;
}
