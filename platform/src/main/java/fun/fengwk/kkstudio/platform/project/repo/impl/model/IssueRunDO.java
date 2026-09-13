package fun.fengwk.kkstudio.platform.project.repo.impl.model;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
public class IssueRunDO {

  private UUID id;
  private UUID issueId;
  private Long ordinal;
  private String role;
  private String actorType;
  private String agentName;
  private UUID submissionRunId;
  private String status;
  private String outcome;
  private Long observedSpecRevision;
  private Long observedInputSequence;
  private Integer continuationCount;
  private Integer maxContinuations;
  private Instant deadline;
  private String waitingReason;
  private String result;
  private String terminalActionId;
  private Long version;
  private Instant createdAt;
  private Instant updatedAt;
  private Instant completedAt;
}
