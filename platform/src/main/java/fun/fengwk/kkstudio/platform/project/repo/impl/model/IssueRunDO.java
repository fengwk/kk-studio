package fun.fengwk.kkstudio.platform.project.repo.impl.model;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** {@code project_issue_run} 行映射。 */
@Data
public class IssueRunDO {

  private UUID id;
  private UUID issueId;
  private Long ordinal;
  private String state;
  private UUID sessionId;
  private UUID threadId;
  private String status;
  private UUID startEntryId;
  private UUID endEntryId;
  private UUID finalAnswerEntryId;
  private String nextState;
  private Long observedActivitySequence;
  private Long remainingExecutionMs;
  private Instant activeSince;
  private String error;
  private Long version;
  private Instant startedAt;
  private Instant endedAt;
}
