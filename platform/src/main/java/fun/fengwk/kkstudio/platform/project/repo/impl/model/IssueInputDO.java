package fun.fengwk.kkstudio.platform.project.repo.impl.model;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
public class IssueInputDO {

  private UUID issueId;
  private Long sequence;
  private String kind;
  private String body;
  private String idempotencyKey;
  private Instant createdAt;
}
