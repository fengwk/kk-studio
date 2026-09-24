package fun.fengwk.kkstudio.platform.project.repo.impl.model;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
public class ProjectDO {

  private UUID id;
  private String title;
  private String description;
  private Boolean yoloEnabled;
  private Integer maxReviewRejections;
  private Long nextIssueNumber;
  private Long version;
  private Instant archivedAt;
  private Instant createdAt;
  private Instant updatedAt;
}
