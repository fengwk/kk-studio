package fun.fengwk.kkstudio.core.agent.session.service.converter;

import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSession;
import fun.fengwk.kkstudio.share.model.AgentSessionDTO;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSession;
import fun.fengwk.kkstudio.share.model.AgentSessionDTO;
import org.springframework.stereotype.Component;

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
    sessionDTO.setStatus(session.getStatus());
    sessionDTO.setCurrentHeadEventId(session.getCurrentHeadEventId());
    sessionDTO.setCreateTime(session.getCreateTime());
    sessionDTO.setUpdateTime(session.getUpdateTime());
    return sessionDTO;
  }
}
