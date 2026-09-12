package fun.fengwk.kkstudio.platform.project.repo.impl.model;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
public class IssueControllerWorkDO {

  private UUID issueId;
  private Long wakeVersion;
  private Instant dueAt;
  private String leaseToken;
  private Instant leaseUntil;
  private Instant updatedAt;
}
