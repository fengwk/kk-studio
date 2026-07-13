package fun.fengwk.kkstudio.core.agent.runtime.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionEvent;

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
    Class<? extends Payload> payloadClass =
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
      return objectMapper.readValue(payloadJson, payloadClass);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("deserialize runtime payload failed", e);
    }
  }
}
