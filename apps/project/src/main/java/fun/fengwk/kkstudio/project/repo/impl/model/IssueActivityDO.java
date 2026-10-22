package fun.fengwk.kkstudio.project.repo.impl.model;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** {@code project_issue_activity} 行映射。 */
@Data
public class IssueActivityDO {

  private UUID issueId;
  private Long sequence;
  private String kind;
  private String actorType;
  private String actorAgentName;
  private UUID runId;
  private String body;
  private String data;
  private String idempotencyKey;
  private String requestHash;
  private Instant createdAt;
}
