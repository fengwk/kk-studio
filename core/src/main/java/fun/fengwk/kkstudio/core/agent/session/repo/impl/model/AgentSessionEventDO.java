package fun.fengwk.kkstudio.core.agent.session.repo.impl.model;

import java.time.LocalDateTime;
import lombok.Data;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class AgentSessionEventDO {

  private Long id;
  private String eventId;
  private String sessionId;
  private String parentEventId;
  private String runId;
  private String eventType;
  private String payloadType;
  private String payloadJson;
  private LocalDateTime createTime;
}
