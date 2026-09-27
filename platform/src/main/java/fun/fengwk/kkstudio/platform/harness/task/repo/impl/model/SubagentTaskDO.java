package fun.fengwk.kkstudio.platform.harness.task.repo.impl.model;

import lombok.Data;

import fun.fengwk.kkstudio.platform.harness.task.SubagentTaskStatus;

import java.time.Instant;
import java.util.UUID;

/** {@code harness_subagent_task} 行对象。 */
@Data
public class SubagentTaskDO {

  private UUID invocationId;
  private UUID parentThreadId;
  private UUID rootThreadId;
  private UUID childSessionId;
  private UUID childThreadId;
  private UUID sourceHeadEntryId;
  private String agent;
  private String prompt;
  private Integer maxTurns;
  private SubagentTaskStatus status;
  private String outcome;
  private String report;
  private String partialResult;
  private String error;
  private Long reminderTurn;
  private Instant settledAt;
  private Instant createdAt;
  private Instant updatedAt;
}
