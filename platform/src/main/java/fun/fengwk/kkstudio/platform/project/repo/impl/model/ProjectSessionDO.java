package fun.fengwk.kkstudio.platform.project.repo.impl.model;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
public class ProjectSessionDO {

  private UUID projectId;
  private UUID sessionId;
  private Instant createdAt;
}
