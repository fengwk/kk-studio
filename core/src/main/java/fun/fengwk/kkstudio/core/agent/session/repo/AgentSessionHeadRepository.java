package fun.fengwk.kkstudio.core.agent.session.repo;

import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionHead;

import java.util.List;

/**
 * @author fengwk
 */
public interface AgentSessionHeadRepository {

  boolean add(AgentSessionHead sessionHead);

  List<AgentSessionHead> listBySessionId(String sessionId);

  boolean updateHeadEventId(String sessionId, String headName, String headEventId);
}
