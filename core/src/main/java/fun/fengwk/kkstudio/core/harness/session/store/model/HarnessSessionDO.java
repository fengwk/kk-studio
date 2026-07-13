package fun.fengwk.kkstudio.core.harness.session.store.model;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class HarnessSessionDO {
  private Long id;
  private Long workspaceId;
  private Long agentDefinitionId;
  private String title;
  private Long leafEntryId;
  private Long activeRunId;
  private Long parentSessionId;
  private Long rootSessionId;
  private Long parentInvocationId;
  private Integer depth;
  private Boolean yoloEnabled;
  private Long version;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
