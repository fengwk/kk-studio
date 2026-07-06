package fun.fengwk.kkstudio.core.agent.runtime.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.agent.session.SessionEvent;
import fun.fengwk.kkstudio.agent.session.repo.SessionEventRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionEventRepository;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionEvent;
import fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.agent.session.SessionEvent;
import fun.fengwk.kkstudio.agent.session.repo.SessionEventRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionEventRepository;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionEvent;
import fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator;
import java.util.List;

final class CoreSessionEventRepositoryAdapter implements SessionEventRepository {

  private final String runId;
  private final AgentSessionEventRepository agentSessionEventRepository;
  private final AgentSessionEventBridge eventBridge;

  CoreSessionEventRepositoryAdapter(
      String runId,
      AgentSessionEventRepository agentSessionEventRepository,
      ObjectMapper objectMapper) {
    this.runId = runId;
    this.agentSessionEventRepository = agentSessionEventRepository;
    this.eventBridge = new AgentSessionEventBridge(objectMapper);
  }

  @Override
  public List<SessionEvent> listBySessionId(String sessionId) {
    return agentSessionEventRepository.listBySessionId(sessionId).stream()
        .map(eventBridge::toAgentSessionEvent)
        .toList();
  }

  @Override
  public void appendEvent(SessionEvent event) {
    AgentSessionEvent coreEvent = new AgentSessionEvent();
    coreEvent.setId(AgentIdGenerator.nextEventId());
    coreEvent.setEventId(event.getEventId());
    coreEvent.setSessionId(event.getSessionId());
    coreEvent.setParentEventId(event.getParentEventId());
    coreEvent.setRunId(runId);
    coreEvent.setEventType(event.getEventType().name());
    coreEvent.setPayloadType(event.getEventType().name());
    coreEvent.setPayloadJson(eventBridge.serialize(event.getPayload()));
    coreEvent.setCreateTime(event.getCreateTime());
    if (!agentSessionEventRepository.add(coreEvent)) {
      throw new IllegalStateException("append runtime session event failed");
    }
  }
}
