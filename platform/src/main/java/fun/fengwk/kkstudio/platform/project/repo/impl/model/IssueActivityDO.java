package fun.fengwk.kkstudio.platform.project.repo.impl.model;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
public class IssueActivityDO {

  private UUID issueId;
  private Long sequence;
  private String kind;
  private String actorType;
  private String actorAgentName;
  private String targetRole;
  private UUID runId;
  private UUID submissionRunId;
  private String decision;
  private String body;
  private String idempotencyKey;
  private Instant createdAt;
}
