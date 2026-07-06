package fun.fengwk.kkstudio.core.agent.run.service.model;

import java.time.LocalDateTime;
import lombok.Data;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class AgentRun {

  private Long id;
  private String runId;
  private String sessionId;
  private String triggerEventId;
  private String status;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
