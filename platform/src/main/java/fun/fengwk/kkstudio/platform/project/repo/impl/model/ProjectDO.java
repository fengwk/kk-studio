package fun.fengwk.kkstudio.platform.project.repo.impl.model;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** {@code project} 行映射。 */
@Data
public class ProjectDO {

  private UUID id;
  private String title;
  private String description;
  private String workflowJson;
  private Boolean yoloEnabled;
  private Long nextIssueNumber;
  private Long version;
  private Instant archivedAt;
  private Instant createdAt;
  private Instant updatedAt;
}
