package fun.fengwk.kkstudio.platform.project.repo.impl.model;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
public class IssueAgentSessionDO {

  private UUID id;
  private UUID issueId;
  private String agentName;
  private UUID sessionId;
  private UUID threadId;
  private Instant createdAt;
  private Instant updatedAt;
}
