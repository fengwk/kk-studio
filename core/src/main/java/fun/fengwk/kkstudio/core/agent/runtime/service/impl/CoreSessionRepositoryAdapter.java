package fun.fengwk.kkstudio.core.agent.runtime.service.impl;

import fun.fengwk.kkstudio.agent.session.Session;
import fun.fengwk.kkstudio.agent.session.repo.SessionRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionRepository;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSession;

import java.time.LocalDateTime;

final class CoreSessionRepositoryAdapter implements SessionRepository {

  private final AgentSessionRepository agentSessionRepository;

  CoreSessionRepositoryAdapter(AgentSessionRepository agentSessionRepository) {
    this.agentSessionRepository = agentSessionRepository;
  }

  @Override
  public Session getSession(String sessionId) {
    AgentSession session = agentSessionRepository.getBySessionId(sessionId);
    if (session == null) {
      return null;
    }
    Session agentSession = new Session();
    agentSession.setSessionId(session.getSessionId());
    agentSession.setCurrentHeadEventId(session.getCurrentHeadEventId());
    return agentSession;
  }

  @Override
  public boolean compareAndSetCurrentHeadEventId(
      String sessionId, String expectedHeadEventId, String newHeadEventId) {
    return agentSessionRepository.compareAndSetCurrentHeadEventId(
        sessionId, expectedHeadEventId, newHeadEventId, LocalDateTime.now());
  }
}
