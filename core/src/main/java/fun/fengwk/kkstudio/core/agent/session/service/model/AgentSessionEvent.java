package fun.fengwk.kkstudio.core.agent.session.service.model;

import java.time.LocalDateTime;
import lombok.Data;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class AgentSessionEvent {

  public static final String ROOT_EVENT_ID = "root";

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
