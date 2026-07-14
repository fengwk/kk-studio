package fun.fengwk.kkstudio.share.model;

import lombok.Data;

@Data
public class ToolInvocationDTO {
  private String id;
  private String runId;
  private Integer ordinal;
  private String toolCallId;
  private String toolName;
  private String status;
  private String permissionAction;
  private String permissionDecision;
  private String errorMessage;
}
