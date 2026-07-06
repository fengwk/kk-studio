package fun.fengwk.kkstudio.core.agent.session.repo;

import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionEvent;
import java.util.List;

import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionEvent;
import java.util.List;

/**
 * @author fengwk
 */
public interface AgentSessionEventRepository {

  boolean add(AgentSessionEvent sessionEvent);

  List<AgentSessionEvent> listBySessionId(String sessionId);

  List<AgentSessionEvent> listBySessionIdAfterEventId(String sessionId, String afterEventId);
}
