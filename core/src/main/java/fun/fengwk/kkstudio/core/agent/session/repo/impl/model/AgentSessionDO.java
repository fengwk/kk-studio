package fun.fengwk.kkstudio.core.agent.session.repo.impl.model;

import java.time.LocalDateTime;
import lombok.Data;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class AgentSessionDO {

  private Long id;
  private String sessionId;
  private Long agentId;
  private String agentName;
  private String title;
  private String status;
  private String currentHeadEventId;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
