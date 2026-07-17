package fun.fengwk.kkstudio.share.model.studio;

import lombok.Data;

@Data
public class FunctionRunDTO {
  private String id;
  private String workspaceId;
  private String canvasId;
  private String functionNodeId;
  private String functionId;
  private String functionVersion;
  private String status;
  private String attempt;
  private String retryOfRunId;
  private String idempotencyKey;
  private String revision;
}
