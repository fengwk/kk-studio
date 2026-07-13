package fun.fengwk.kkstudio.core.agent.session.service.converter;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSession;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionEvent;
import fun.fengwk.kkstudio.share.model.AgentSessionDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionEventDTO;

/**
 * @author fengwk
 */
@Component
public class AgentSessionConverter {

  public AgentSessionDTO convert(AgentSession session) {
    if (session == null) {
      return null;
    }
    AgentSessionDTO sessionDTO = new AgentSessionDTO();
    sessionDTO.setSessionId(session.getSessionId());
    sessionDTO.setAgentId(session.getAgentId());
    sessionDTO.setAgentName(session.getAgentName());
    sessionDTO.setTitle(session.getTitle());
    sessionDTO.setCreateTime(session.getCreateTime());
    sessionDTO.setUpdateTime(session.getUpdateTime());
    return sessionDTO;
  }

  public AgentSessionEventDTO convert(AgentSessionEvent event) {
    if (event == null) {
      return null;
    }
    AgentSessionEventDTO eventDTO = new AgentSessionEventDTO();
    eventDTO.setEventId(event.getEventId());
    eventDTO.setSessionId(event.getSessionId());
    eventDTO.setParentEventId(event.getParentEventId());
    eventDTO.setRunId(event.getRunId());
    eventDTO.setEventType(event.getEventType());
    eventDTO.setPayloadJson(event.getPayloadJson());
    eventDTO.setCreateTime(event.getCreateTime());
    return eventDTO;
  }
}
