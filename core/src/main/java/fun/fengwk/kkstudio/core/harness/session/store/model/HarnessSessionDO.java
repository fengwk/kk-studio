package fun.fengwk.kkstudio.core.harness.session.store.model;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class HarnessSessionDO {
  private Long id;
  private String sessionId;
  private Long workspaceId;
  private String parentSessionId;
  private String leafEntryId;
  private LocalDateTime createTime;
}
