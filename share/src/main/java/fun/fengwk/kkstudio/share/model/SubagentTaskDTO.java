package fun.fengwk.kkstudio.share.model;

import java.time.Instant;
import lombok.Data;

/** Share projection of one durable parent task invocation to child run relation. */
@Data
public class SubagentTaskDTO {
  private String parentInvocationId;
  private String parentSessionId;
  private String childSessionId;
  private String childRunId;
  private String targetAgent;
  private String workingCopyPolicy;
  private String workingCopyRevision;
  private Integer maxTurns;
  private Long idleTimeoutMillis;
  private String status;
  private SubagentTaskReportDTO report;
  private Instant createTime;
  private Instant updateTime;
}
