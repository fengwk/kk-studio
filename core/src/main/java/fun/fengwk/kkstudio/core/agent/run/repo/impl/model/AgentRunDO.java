package fun.fengwk.kkstudio.core.agent.run.repo.impl.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author fengwk
 */
@Data
public class AgentRunDO {

  private Long id;
  private String runId;
  private String sessionId;
  private String triggerEventId;
  private String status;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
