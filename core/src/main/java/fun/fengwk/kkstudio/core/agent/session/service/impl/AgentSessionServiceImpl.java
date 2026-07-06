package fun.fengwk.kkstudio.core.agent.session.service.impl;

import static fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator.nextEventId;
import static fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator.nextHeadId;
import static fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator.nextSessionId;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.core.agent.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.run.service.AgentRunService;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionEventRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionHeadRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionRepository;
import fun.fengwk.kkstudio.core.agent.session.service.AgentSessionService;
import fun.fengwk.kkstudio.core.agent.session.service.converter.AgentSessionConverter;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSession;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionEvent;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionHead;
import fun.fengwk.kkstudio.core.agent.support.AgentIdentifierGenerator;
import fun.fengwk.kkstudio.share.model.AgentSessionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionEventDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionHeadDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionMessageCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionUpdateDTO;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.core.agent.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.run.service.AgentRunService;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionEventRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionHeadRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionRepository;
import fun.fengwk.kkstudio.core.agent.session.service.AgentSessionService;
import fun.fengwk.kkstudio.core.agent.session.service.converter.AgentSessionConverter;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSession;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionEvent;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionHead;
import fun.fengwk.kkstudio.core.agent.support.AgentIdentifierGenerator;
import fun.fengwk.kkstudio.share.model.AgentSessionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionEventDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionHeadDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionMessageCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionUpdateDTO;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author fengwk
 */
@Service
public class AgentSessionServiceImpl implements AgentSessionService {

  private static final String DEFAULT_HEAD_NAME = "default";
  private static final String USER_MESSAGE_EVENT_TYPE = "user_message";
  private static final String TEXT_PAYLOAD_TYPE = "text";

  private final AgentDefinitionRepository agentDefinitionRepository;
  private final AgentSessionRepository agentSessionRepository;
  private final AgentSessionHeadRepository agentSessionHeadRepository;
  private final AgentSessionEventRepository agentSessionEventRepository;
  private final AgentRunService agentRunService;
  private final AgentSessionConverter agentSessionConverter;
  private final ObjectMapper objectMapper;
  private final AgentSessionDefaultHeadAdvancer sessionDefaultHeadAdvancer;
  private final AgentSessionEventBranchLoader sessionEventBranchLoader;

  public AgentSessionServiceImpl(
      AgentDefinitionRepository agentDefinitionRepository,
      AgentSessionRepository agentSessionRepository,
      AgentSessionHeadRepository agentSessionHeadRepository,
      AgentSessionEventRepository agentSessionEventRepository,
      AgentRunService agentRunService,
      AgentSessionConverter agentSessionConverter,
      ObjectMapper objectMapper) {
    this.agentDefinitionRepository = agentDefinitionRepository;
    this.agentSessionRepository = agentSessionRepository;
    this.agentSessionHeadRepository = agentSessionHeadRepository;
    this.agentSessionEventRepository = agentSessionEventRepository;
    this.agentRunService = agentRunService;
    this.agentSessionConverter = agentSessionConverter;
    this.objectMapper = objectMapper;
    this.sessionDefaultHeadAdvancer =
        new AgentSessionDefaultHeadAdvancer(
            agentSessionRepository,
            agentSessionHeadRepository,
            agentSessionEventRepository,
            DEFAULT_HEAD_NAME);
    this.sessionEventBranchLoader =
        new AgentSessionEventBranchLoader(agentSessionEventRepository, USER_MESSAGE_EVENT_TYPE);
  }

  @Override
  public Page<AgentSessionDTO> pageSessions(PageQuery pageQuery) {
    return agentSessionRepository.page(pageQuery).map(agentSessionConverter::convert);
  }

  @Transactional
  @Override
  public AgentSessionDTO createSession(AgentSessionCreateDTO createDTO) {
    validateCreateDTO(createDTO);
    AgentDefinition agent = agentDefinitionRepository.getByName(createDTO.getAgentName());
    if (agent == null) {
      throw new IllegalArgumentException("agent not found: " + createDTO.getAgentName());
    }

    LocalDateTime now = LocalDateTime.now();
    AgentSession session = new AgentSession();
    session.setId(nextSessionId());
    session.setSessionId(AgentIdentifierGenerator.newSessionId());
    session.setAgentId(agent.getId());
    session.setAgentName(agent.getName());
    session.setTitle(createDTO.getTitle());
    session.setStatus("active");
    session.setCurrentHeadEventId(AgentSessionEvent.ROOT_EVENT_ID);
    session.setCreateTime(now);
    session.setUpdateTime(now);
    if (!agentSessionRepository.add(session)) {
      throw new IllegalStateException("create session failed");
    }

    AgentSessionHead sessionHead = new AgentSessionHead();
    sessionHead.setId(nextHeadId());
    sessionHead.setHeadId(AgentIdentifierGenerator.newHeadId());
    sessionHead.setSessionId(session.getSessionId());
    sessionHead.setHeadName(DEFAULT_HEAD_NAME);
    sessionHead.setHeadEventId(AgentSessionEvent.ROOT_EVENT_ID);
    if (!agentSessionHeadRepository.add(sessionHead)) {
      throw new IllegalStateException("create session head failed");
    }

    return agentSessionConverter.convert(session);
  }

  @Override
  public AgentSessionDTO getSession(String sessionId) {
    AgentSession session = requireSession(sessionId);
    return agentSessionConverter.convert(session);
  }

  @Override
  public AgentSessionDTO updateSession(String sessionId, AgentSessionUpdateDTO updateDTO) {
    requireSession(sessionId);
    if (updateDTO == null) {
      throw new IllegalArgumentException("updateDTO must not be null");
    }
    String title =
        updateDTO.getTitle() == null || updateDTO.getTitle().isBlank()
            ? null
            : updateDTO.getTitle().trim();
    if (!agentSessionRepository.updateTitleBySessionId(sessionId, title, LocalDateTime.now())) {
      throw new IllegalArgumentException("session not found: " + sessionId);
    }
    return agentSessionConverter.convert(agentSessionRepository.getBySessionId(sessionId));
  }

  @Override
  public void deleteSession(String sessionId) {
    requireSession(sessionId);
    if (!agentSessionRepository.deleteBySessionId(sessionId)) {
      throw new IllegalArgumentException("session not found: " + sessionId);
    }
  }

  @Transactional
  @Override
  public AgentSessionEventDTO createMessage(
      String sessionId, AgentSessionMessageCreateDTO createDTO) {
    if (createDTO == null || createDTO.getContent() == null || createDTO.getContent().isBlank()) {
      throw new IllegalArgumentException("content must not be blank");
    }
    requireSession(sessionId);

    String runId = AgentIdentifierGenerator.newRunId();
    AgentSessionEvent userEvent = new AgentSessionEvent();
    userEvent.setId(nextEventId());
    userEvent.setEventId(AgentIdentifierGenerator.newEventId());
    userEvent.setSessionId(sessionId);
    userEvent.setParentEventId(
        agentSessionRepository.getBySessionId(sessionId).getCurrentHeadEventId());
    userEvent.setRunId(runId);
    userEvent.setEventType(USER_MESSAGE_EVENT_TYPE);
    userEvent.setPayloadType(TEXT_PAYLOAD_TYPE);
    userEvent.setPayloadJson(serializeMessagePayload(createDTO.getContent()));
    userEvent.setCreateTime(LocalDateTime.now());
    if (!agentSessionEventRepository.add(userEvent)) {
      throw new IllegalStateException("append user message event failed");
    }

    agentRunService.createQueuedRun(runId, sessionId, userEvent.getEventId());
    sessionDefaultHeadAdvancer.advanceToLatest(sessionId);
    return convertEvent(userEvent);
  }

  @Override
  public List<AgentSessionHeadDTO> listHeads(String sessionId) {
    requireSession(sessionId);
    return agentSessionHeadRepository.listBySessionId(sessionId).stream()
        .map(this::convertHead)
        .collect(Collectors.toList());
  }

  @Override
  public List<AgentSessionEventDTO> listEvents(String sessionId, String headEventId) {
    AgentSession session = requireSession(sessionId);
    String effectiveHead = headEventId;
    if (effectiveHead == null || effectiveHead.isBlank()) {
      effectiveHead = session.getCurrentHeadEventId();
    }
    return convertEvents(sessionEventBranchLoader.load(sessionId, effectiveHead));
  }

  @Override
  public List<AgentSessionEventDTO> listEventsAfter(String sessionId, String afterEventId) {
    requireSession(sessionId);
    return convertEvents(
        agentSessionEventRepository.listBySessionIdAfterEventId(sessionId, afterEventId));
  }

  private List<AgentSessionEventDTO> convertEvents(List<AgentSessionEvent> events) {
    List<AgentSessionEventDTO> result = new ArrayList<>(events.size());
    for (AgentSessionEvent event : events) {
      result.add(convertEvent(event));
    }
    return result;
  }

  private AgentSessionEventDTO convertEvent(AgentSessionEvent event) {
    AgentSessionEventDTO dto = new AgentSessionEventDTO();
    dto.setEventId(event.getEventId());
    dto.setSessionId(event.getSessionId());
    dto.setParentEventId(event.getParentEventId());
    dto.setRunId(event.getRunId());
    dto.setEventType(event.getEventType());
    dto.setPayloadType(event.getPayloadType());
    dto.setPayloadJson(event.getPayloadJson());
    dto.setCreateTime(event.getCreateTime());
    return dto;
  }

  private AgentSession requireSession(String sessionId) {
    AgentSession session = agentSessionRepository.getBySessionId(sessionId);
    if (session == null) {
      throw new IllegalArgumentException("session not found: " + sessionId);
    }
    return session;
  }

  private void validateCreateDTO(AgentSessionCreateDTO createDTO) {
    if (createDTO == null
        || createDTO.getAgentName() == null
        || createDTO.getAgentName().isBlank()) {
      throw new IllegalArgumentException("agentName must not be blank");
    }
  }

  private String serializeMessagePayload(String content) {
    try {
      return objectMapper.writeValueAsString(Map.of("content", content));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("serialize message payload failed", e);
    }
  }

  private AgentSessionHeadDTO convertHead(AgentSessionHead head) {
    AgentSessionHeadDTO headDTO = new AgentSessionHeadDTO();
    headDTO.setHeadId(head.getHeadId());
    headDTO.setSessionId(head.getSessionId());
    headDTO.setHeadName(head.getHeadName());
    headDTO.setHeadEventId(head.getHeadEventId());
    return headDTO;
  }
}
