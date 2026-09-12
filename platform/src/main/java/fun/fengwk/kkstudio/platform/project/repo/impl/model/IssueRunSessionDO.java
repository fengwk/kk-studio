package fun.fengwk.kkstudio.platform.project.repo.impl.model;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
public class IssueRunSessionDO {

  private UUID runId;
  private UUID sessionId;
  private Instant createdAt;
}
