package fun.fengwk.kkstudio.platform.project.repo.impl.model;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** {@code project_issue_work} 行映射。 */
@Data
public class IssueWorkDO {

  private UUID issueId;
  private Long wakeVersion;
  private Instant dueAt;
  private String leaseToken;
  private Instant leaseUntil;
  private Instant createdAt;
  private Instant updatedAt;
}
