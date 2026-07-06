package fun.fengwk.kkstudio.core.agent.session.service.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author fengwk
 */
@Data
public class AgentSession {

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
