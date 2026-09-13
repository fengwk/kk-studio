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
public class Issue {

  private UUID id;
  private UUID projectId;
  private long number;
  private String title;
  private String description;
  private IssueStatus status;
  private String assigneeAgentName;
  private String reviewerAgentName;
  private long version;
  private long specRevision;
  private long inputSequence;
  private Instant archivedAt;
  private Instant createdAt;
  private Instant updatedAt;

  public boolean isArchived() {
    return archivedAt != null;
  }

  public boolean isTerminal() {
    return status != null && status.isTerminal();
  }

  public boolean canBeArchived() {
    return status != null && status.canBeArchived();
  }
}
