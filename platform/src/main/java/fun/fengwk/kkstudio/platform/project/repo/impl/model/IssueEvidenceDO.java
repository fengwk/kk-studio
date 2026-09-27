package fun.fengwk.kkstudio.platform.project.repo.impl.model;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** {@code project_issue_evidence} 数据对象。 */
@Data
public class IssueEvidenceDO {

  private UUID issueId;
  private UUID blobId;
  private String actorAgentName;
  private UUID runId;
  private String name;
  private Instant createdAt;
}
