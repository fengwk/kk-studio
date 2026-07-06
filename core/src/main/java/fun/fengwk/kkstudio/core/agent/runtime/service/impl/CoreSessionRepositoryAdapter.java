package fun.fengwk.kkstudio.core.agent.runtime.service.impl;

import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.agent.session.Session;
import fun.fengwk.kkstudio.agent.session.repo.SessionRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionHeadRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionRepository;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSession;

import java.time.LocalDateTime;

final class CoreSessionRepositoryAdapter implements SessionRepository {

  private static final String DEFAULT_HEAD_NAME = "default";

  private final AgentSessionRepository agentSessionRepository;
  private final AgentSessionHeadRepository agentSessionHeadRepository;
  private final TransactionTemplate transactionTemplate;

  CoreSessionRepositoryAdapter(
      AgentSessionRepository agentSessionRepository,
      AgentSessionHeadRepository agentSessionHeadRepository,
      TransactionTemplate transactionTemplate) {
    this.agentSessionRepository = agentSessionRepository;
    this.agentSessionHeadRepository = agentSessionHeadRepository;
    this.transactionTemplate = transactionTemplate;
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
    Boolean updated =
        transactionTemplate.execute(
            status -> {
              LocalDateTime now = LocalDateTime.now();
              if (!agentSessionRepository.compareAndSetCurrentHeadEventId(
                  sessionId, expectedHeadEventId, newHeadEventId, now)) {
                return false;
              }
              if (!agentSessionHeadRepository.updateHeadEventId(
                  sessionId, DEFAULT_HEAD_NAME, newHeadEventId)) {
                throw new IllegalStateException("update session head failed");
              }
              return true;
            });
    return Boolean.TRUE.equals(updated);
  }
}
