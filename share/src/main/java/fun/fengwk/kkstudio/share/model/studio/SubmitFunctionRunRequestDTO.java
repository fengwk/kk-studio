package fun.fengwk.kkstudio.share.model.studio;

import lombok.Data;

@Data
public class SubmitFunctionRunRequestDTO {
  private String workspaceId;
  private String canvasId;
  private String functionNodeId;
  private String functionId;
  private String functionVersion;
  private String configSnapshotJson;
  private String configRevision;
  private String idempotencyKey;
}
