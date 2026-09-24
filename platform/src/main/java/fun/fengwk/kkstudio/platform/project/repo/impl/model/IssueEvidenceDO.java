package fun.fengwk.kkstudio.platform.project.repo.impl.model;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
public class IssueEvidenceDO {

  private UUID issueId;
  private UUID blobId;
  private String origin;
  private UUID runId;
  private String name;
  private Instant createdAt;
}
