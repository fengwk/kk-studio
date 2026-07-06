package fun.fengwk.kkstudio.core.agent.session.service.impl;

import static fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator.nextEventId;
import static fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator.nextHeadId;
import static fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator.nextSessionId;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSession;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionEvent;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionHead;
import fun.fengwk.kkstudio.core.agent.support.AgentIdentifierGenerator;
import fun.fengwk.kkstudio.share.model.AgentSessionCreateDTO;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSession;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionEvent;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionHead;
import fun.fengwk.kkstudio.core.agent.support.AgentIdentifierGenerator;
import fun.fengwk.kkstudio.share.model.AgentSessionCreateDTO;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * AgentSessionMutationFactory 负责组装会话写路径上的持久化对象。
 *
 * @author fengwk
 */
@Component
final class AgentSessionMutationFactory {

  private static final String DEFAULT_HEAD_NAME = "default";
  private static final String STATUS_ACTIVE = "active";
  private static final String USER_MESSAGE_EVENT_TYPE = "user_message";
  private static final String TEXT_PAYLOAD_TYPE = "text";

  private final ObjectMapper objectMapper;

  AgentSessionMutationFactory(ObjectMapper objectMapper) {
    this.objectMapper = requireNonNull(objectMapper, "objectMapper");
  }

  AgentSession newSession(
      AgentDefinition agent, AgentSessionCreateDTO createDTO, LocalDateTime now) {
    requireNonNull(agent, "agent");
    requireNonNull(createDTO, "createDTO");
    requireNonNull(now, "now");

    AgentSession session = new AgentSession();
    session.setId(nextSessionId());
    session.setSessionId(AgentIdentifierGenerator.newSessionId());
    session.setAgentId(agent.getId());
    session.setAgentName(agent.getName());
    session.setTitle(createDTO.getTitle());
    session.setStatus(STATUS_ACTIVE);
    session.setCurrentHeadEventId(AgentSessionEvent.ROOT_EVENT_ID);
    session.setCreateTime(now);
    session.setUpdateTime(now);
    return session;
  }

  AgentSessionHead newDefaultHead(String sessionId) {
    requireNonBlank(sessionId, "sessionId");

    AgentSessionHead sessionHead = new AgentSessionHead();
    sessionHead.setId(nextHeadId());
    sessionHead.setHeadId(AgentIdentifierGenerator.newHeadId());
    sessionHead.setSessionId(sessionId);
    sessionHead.setHeadName(DEFAULT_HEAD_NAME);
    sessionHead.setHeadEventId(AgentSessionEvent.ROOT_EVENT_ID);
    return sessionHead;
  }

  AgentSessionEvent newUserMessageEvent(
      String sessionId,
      String parentEventId,
      String runId,
      String content,
      LocalDateTime createTime) {
    requireNonBlank(sessionId, "sessionId");
    requireNonBlank(parentEventId, "parentEventId");
    requireNonBlank(runId, "runId");
    requireNonNull(content, "content");
    requireNonNull(createTime, "createTime");

    AgentSessionEvent userEvent = new AgentSessionEvent();
    userEvent.setId(nextEventId());
    userEvent.setEventId(AgentIdentifierGenerator.newEventId());
    userEvent.setSessionId(sessionId);
    userEvent.setParentEventId(parentEventId);
    userEvent.setRunId(runId);
    userEvent.setEventType(USER_MESSAGE_EVENT_TYPE);
    userEvent.setPayloadType(TEXT_PAYLOAD_TYPE);
    userEvent.setPayloadJson(serializeMessagePayload(content));
    userEvent.setCreateTime(createTime);
    return userEvent;
  }

  private String serializeMessagePayload(String content) {
    try {
      return objectMapper.writeValueAsString(Map.of("content", content));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("serialize message payload failed", e);
    }
  }

  private static <T> T requireNonNull(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    return value;
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
