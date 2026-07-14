package fun.fengwk.kkstudio.share.model;

import java.util.List;
import lombok.Data;

/** Structured terminal child report; all durable bigint identifiers are JSON strings. */
@Data
public class SubagentTaskReportDTO {
  private String childSessionId;
  private String childRunId;
  private String status;
  private String finalReport;
  private List<ToolArtifactRefDTO> artifacts;
  private Integer turnCount;
  private Integer toolCount;
  private String workspacePolicy;
  private String workspaceRevision;
}
