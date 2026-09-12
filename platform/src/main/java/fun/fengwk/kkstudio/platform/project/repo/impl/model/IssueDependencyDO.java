package fun.fengwk.kkstudio.platform.project.repo.impl.model;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
public class IssueDependencyDO {

  private UUID issueId;
  private UUID dependsOnIssueId;
  private UUID projectId;
  private Instant createdAt;
}
