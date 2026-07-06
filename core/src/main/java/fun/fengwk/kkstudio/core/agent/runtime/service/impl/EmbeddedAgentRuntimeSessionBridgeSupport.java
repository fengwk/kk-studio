package fun.fengwk.kkstudio.core.agent.runtime.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.agent.session.Session;
import fun.fengwk.kkstudio.agent.session.SessionEvent;
import fun.fengwk.kkstudio.agent.session.SessionEventType;
import fun.fengwk.kkstudio.agent.session.payload.AbortPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantDeltaPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantEndPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantErrorPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantStartPayload;
import fun.fengwk.kkstudio.agent.session.payload.Payload;
import fun.fengwk.kkstudio.agent.session.payload.SetAgentInfoPayload;
import fun.fengwk.kkstudio.agent.session.payload.SetModelInfoPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolDeltaPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolEndPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolErrorPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolStartPayload;
import fun.fengwk.kkstudio.agent.session.projection.DefaultSessionEventMessageProjector;
import fun.fengwk.kkstudio.agent.session.projection.SessionEventMessageProjector;
import fun.fengwk.kkstudio.agent.session.projection.SessionEventProjection;
import fun.fengwk.kkstudio.agent.session.repo.SessionEventRepository;
import fun.fengwk.kkstudio.agent.session.repo.SessionRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionEventRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionHeadRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionRepository;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSession;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionEvent;
import fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.agent.session.Session;
import fun.fengwk.kkstudio.agent.session.SessionEvent;
import fun.fengwk.kkstudio.agent.session.SessionEventType;
import fun.fengwk.kkstudio.agent.session.payload.AbortPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantDeltaPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantEndPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantErrorPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantStartPayload;
import fun.fengwk.kkstudio.agent.session.payload.Payload;
import fun.fengwk.kkstudio.agent.session.payload.SetAgentInfoPayload;
import fun.fengwk.kkstudio.agent.session.payload.SetModelInfoPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolDeltaPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolEndPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolErrorPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolStartPayload;
import fun.fengwk.kkstudio.agent.session.projection.DefaultSessionEventMessageProjector;
import fun.fengwk.kkstudio.agent.session.projection.SessionEventMessageProjector;
import fun.fengwk.kkstudio.agent.session.projection.SessionEventProjection;
import fun.fengwk.kkstudio.agent.session.repo.SessionEventRepository;
import fun.fengwk.kkstudio.agent.session.repo.SessionRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionEventRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionHeadRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionRepository;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSession;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionEvent;
import fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.transaction.support.TransactionTemplate;

final class CoreSessionEventMessageProjector implements SessionEventMessageProjector {

  private static final String USER_MESSAGE_EVENT_TYPE = "user_message";

  private final DefaultSessionEventMessageProjector delegate =
      new DefaultSessionEventMessageProjector();

  @Override
  public SessionEventProjection project(List<SessionEvent> branchEvents) {
    return project(branchEvents, false);
  }

  @Override
  public SessionEventProjection projectForRuntime(List<SessionEvent> branchEvents) {
    return project(branchEvents, true);
  }

  private SessionEventProjection project(
      List<SessionEvent> branchEvents, boolean runtimeProjection) {
    if (branchEvents == null) {
      return delegate.project(null);
    }
    List<SessionEvent> filteredEvents =
        branchEvents.stream()
            .filter(
                event ->
                    !(event instanceof BridgedSessionEvent bridgedEvent
                        && USER_MESSAGE_EVENT_TYPE.equals(bridgedEvent.getRawEventType())))
            .toList();
    return runtimeProjection
        ? delegate.projectForRuntime(filteredEvents)
        : delegate.project(filteredEvents);
  }
}

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

final class AgentSessionEventBridge {

  private final ObjectMapper objectMapper;

  AgentSessionEventBridge(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  String serialize(Payload payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("serialize runtime payload failed", e);
    }
  }

  SessionEvent toAgentSessionEvent(AgentSessionEvent coreEvent) {
    SessionEvent agentEvent = new BridgedSessionEvent(coreEvent.getEventType());
    agentEvent.setSessionId(coreEvent.getSessionId());
    agentEvent.setEventId(coreEvent.getEventId());
    agentEvent.setParentEventId(coreEvent.getParentEventId());
    agentEvent.setCreateTime(coreEvent.getCreateTime());

    SessionEventType eventType = resolveEventType(coreEvent.getEventType());
    agentEvent.setEventType(eventType);
    if (eventType != null) {
      agentEvent.setPayload(deserialize(eventType, coreEvent.getPayloadJson()));
    }
    return agentEvent;
  }

  private SessionEventType resolveEventType(String eventType) {
    if (eventType == null || eventType.isBlank()) {
      return null;
    }
    try {
      return SessionEventType.valueOf(eventType);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  private Payload deserialize(SessionEventType eventType, String payloadJson) {
    if (payloadJson == null || payloadJson.isBlank()) {
      return null;
    }
    Class<? extends Payload> payloadType =
        switch (eventType) {
          case set_agent_info -> SetAgentInfoPayload.class;
          case set_model_info -> SetModelInfoPayload.class;
          case assistant_start -> AssistantStartPayload.class;
          case assistant_delta -> AssistantDeltaPayload.class;
          case assistant_end -> AssistantEndPayload.class;
          case assistant_error -> AssistantErrorPayload.class;
          case tool_start -> ToolStartPayload.class;
          case tool_delta -> ToolDeltaPayload.class;
          case tool_end -> ToolEndPayload.class;
          case tool_error -> ToolErrorPayload.class;
          case abort -> AbortPayload.class;
        };
    try {
      return objectMapper.readValue(payloadJson, payloadType);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("deserialize runtime payload failed", e);
    }
  }
}

final class BridgedSessionEvent extends SessionEvent {

  private final String rawEventType;

  BridgedSessionEvent(String rawEventType) {
    this.rawEventType = rawEventType;
  }

  String getRawEventType() {
    return rawEventType;
  }
}
