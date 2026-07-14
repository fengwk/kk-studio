package fun.fengwk.kkstudio.core.harness.task.store.model;

import java.time.LocalDateTime;
import lombok.Data;

/** Database representation of the durable parent Invocation to child Run relation. */
@Data
public class HarnessSubagentTaskDO {
  private Long parentInvocationId;
  private Long parentSessionId;
  private Long childSessionId;
  private Long childRunId;
  private String targetAgent;
  private String workspacePolicy;
  private Integer maxTurns;
  private Long idleTimeoutMillis;
  private String status;
  private String reportJson;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
