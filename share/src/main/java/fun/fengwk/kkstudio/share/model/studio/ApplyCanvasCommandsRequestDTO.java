package fun.fengwk.kkstudio.share.model.studio;

import lombok.Data;

@Data
public class ApplyCanvasCommandsRequestDTO {
  private String baseRevision;
  private String commandId;
  private String requestHash;
  private String commandsJson;
}
