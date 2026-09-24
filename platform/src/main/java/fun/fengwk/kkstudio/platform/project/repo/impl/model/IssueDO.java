package fun.fengwk.kkstudio.platform.project.repo.impl.model;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
public class IssueDO {

  private UUID id;
  private UUID projectId;
  private Long number;
  private String title;
  private String description;
  private String status;
  private String assigneeAgentName;
  private String reviewerAgentName;
  private Long version;
  private Instant archivedAt;
  private Instant createdAt;
  private Instant updatedAt;
}
