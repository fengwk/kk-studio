package fun.fengwk.kkstudio.share.studio;

import lombok.Data;

@Data
public class CanvasFunctionRunDTO {
  private String nodeId;
  private String requestId;
  private String status;
  private String stage;
  private String error;
  private String updatedAt;
}
