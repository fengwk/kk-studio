package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.Instant;

/** Share projection of one durable parent task invocation to child thread relation. */
@Data
public class SubagentTaskDTO {
  private String parentInvocationId;
  private String parentSessionId;
  private String parentThreadId;
  private String childSessionId;
  private String childThreadId;
  private String targetAgent;
  private String workingCopyPolicy;
  private String workingCopyRevision;
  private Integer maxTurns;
  private String status;
  private SubagentTaskReportDTO report;
  private Instant createTime;
  private Instant updateTime;
}
