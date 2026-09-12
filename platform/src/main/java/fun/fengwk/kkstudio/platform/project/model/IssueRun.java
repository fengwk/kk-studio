package fun.fengwk.kkstudio.platform.project.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueRun {

  private UUID id;
  private UUID issueId;
  private long ordinal;
  private IssueRunRole role;
  private IssueRunActorType actorType;
  private String agentName;
  private UUID submissionRunId;
  private IssueRunStatus status;
  private IssueRunOutcome outcome;
  private long observedSpecRevision;
  private long observedInputSequence;
  private int continuationCount;
  private int maxContinuations;
  private Instant deadline;
  private String waitingReason;
  private String result;
  private String terminalActionId;
  private long version;
  private Instant createdAt;
  private Instant updatedAt;
  private Instant completedAt;

  public boolean isActive() {
    return status != null && status.isActive();
  }

  public boolean isTerminal() {
    return status != null && status.isTerminal();
  }
}
