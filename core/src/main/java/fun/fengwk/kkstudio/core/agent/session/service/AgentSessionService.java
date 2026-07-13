package fun.fengwk.kkstudio.core.agent.session.service;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.share.model.AgentSessionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionEventDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionMessageCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionUpdateDTO;

import java.util.List;

/**
 * @author fengwk
 */
public interface AgentSessionService {

  Page<AgentSessionDTO> pageSessions(PageQuery pageQuery);

  AgentSessionDTO createSession(AgentSessionCreateDTO createDTO);

  AgentSessionDTO getSession(String sessionId);

  AgentSessionDTO updateSession(String sessionId, AgentSessionUpdateDTO updateDTO);

  void deleteSession(String sessionId);

  AgentSessionEventDTO createMessage(String sessionId, AgentSessionMessageCreateDTO createDTO);

  List<AgentSessionEventDTO> listEvents(String sessionId, String headEventId);

  List<AgentSessionEventDTO> listEventsAfter(String sessionId, String afterEventId);
}
