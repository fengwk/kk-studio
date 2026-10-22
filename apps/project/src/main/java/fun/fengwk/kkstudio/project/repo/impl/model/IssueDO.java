package fun.fengwk.kkstudio.project.repo.impl.model;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** {@code project_issue} 行映射。 */
@Data
public class IssueDO {

  private UUID id;
  private UUID projectId;
  private Long number;
  private String title;
  private String description;
  private String state;
  private String blockedFromState;
  private String blockReason;
  private String pauseReason;
  private String pauseDetail;
  private Long nextRunOrdinal;
  private Long nextActivitySequence;
  private Long version;
  private Instant archivedAt;
  private Instant createdAt;
  private Instant updatedAt;
}
