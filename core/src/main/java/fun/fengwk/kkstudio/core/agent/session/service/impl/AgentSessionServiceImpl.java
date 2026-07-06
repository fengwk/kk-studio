package fun.fengwk.kkstudio.core.agent.session.service.impl;

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
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author fengwk
 */
@Service
public class AgentSessionServiceImpl implements AgentSessionService {

  private final AgentDefinitionRepository agentDefinitionRepository;
  private final AgentSessionRepository agentSessionRepository;
  private final AgentSessionHeadRepository agentSessionHeadRepository;
  private final AgentSessionEventRepository agentSessionEventRepository;
  private final AgentRunService agentRunService;
  private final AgentSessionConverter agentSessionConverter;
  private final AgentSessionMutationFactory sessionMutationFactory;
  private final AgentSessionDefaultHeadAdvancer sessionDefaultHeadAdvancer;
  private final AgentSessionEventBranchLoader sessionEventBranchLoader;

  public AgentSessionServiceImpl(
      AgentDefinitionRepository agentDefinitionRepository,
      AgentSessionRepository agentSessionRepository,
      AgentSessionHeadRepository agentSessionHeadRepository,
      AgentSessionEventRepository agentSessionEventRepository,
      AgentRunService agentRunService,
      AgentSessionConverter agentSessionConverter,
      AgentSessionMutationFactory sessionMutationFactory,
      AgentSessionDefaultHeadAdvancer sessionDefaultHeadAdvancer,
      AgentSessionEventBranchLoader sessionEventBranchLoader) {
    this.agentDefinitionRepository = agentDefinitionRepository;
    this.agentSessionRepository = agentSessionRepository;
    this.agentSessionHeadRepository = agentSessionHeadRepository;
    this.agentSessionEventRepository = agentSessionEventRepository;
    this.agentRunService = agentRunService;
    this.agentSessionConverter = agentSessionConverter;
    this.sessionMutationFactory = sessionMutationFactory;
    this.sessionDefaultHeadAdvancer = sessionDefaultHeadAdvancer;
    this.sessionEventBranchLoader = sessionEventBranchLoader;
  }

  @Override
  public Page<AgentSessionDTO> pageSessions(PageQuery pageQuery) {
    return agentSessionRepository.page(pageQuery).map(agentSessionConverter::convert);
  }

  @Transactional
  @Override
  public AgentSessionDTO createSession(AgentSessionCreateDTO createDTO) {
    validateCreateDTO(createDTO);
    AgentDefinition agent = requireAgent(createDTO.getAgentName());

    LocalDateTime now = LocalDateTime.now();
    AgentSession session = sessionMutationFactory.newSession(agent, createDTO, now);
    if (!agentSessionRepository.add(session)) {
      throw new IllegalStateException("create session failed");
    }

    AgentSessionHead sessionHead = sessionMutationFactory.newDefaultHead(session.getSessionId());
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
    String content = requireMessageContent(createDTO);
    AgentSession session = requireSession(sessionId);

    String runId = AgentIdentifierGenerator.newRunId();
    AgentSessionEvent userEvent =
        sessionMutationFactory.newUserMessageEvent(
            session.getSessionId(),
            session.getCurrentHeadEventId(),
            runId,
            content,
            LocalDateTime.now());
    if (!agentSessionEventRepository.add(userEvent)) {
      throw new IllegalStateException("append user message event failed");
    }

    agentRunService.createQueuedRun(runId, sessionId, userEvent.getEventId());
    sessionDefaultHeadAdvancer.advanceToLatest(sessionId);
    return agentSessionConverter.convert(userEvent);
  }

  @Override
  public List<AgentSessionHeadDTO> listHeads(String sessionId) {
    requireSession(sessionId);
    return agentSessionHeadRepository.listBySessionId(sessionId).stream()
        .map(agentSessionConverter::convert)
        .collect(Collectors.toList());
  }

  @Override
  public List<AgentSessionEventDTO> listEvents(String sessionId, String headEventId) {
    AgentSession session = requireSession(sessionId);
    String effectiveHead = headEventId;
    if (effectiveHead == null || effectiveHead.isBlank()) {
      effectiveHead = session.getCurrentHeadEventId();
    }
    return sessionEventBranchLoader.load(sessionId, effectiveHead).stream()
        .map(agentSessionConverter::convert)
        .collect(Collectors.toList());
  }

  @Override
  public List<AgentSessionEventDTO> listEventsAfter(String sessionId, String afterEventId) {
    requireSession(sessionId);
    return agentSessionEventRepository.listBySessionIdAfterEventId(sessionId, afterEventId).stream()
        .map(agentSessionConverter::convert)
        .collect(Collectors.toList());
  }

  private AgentDefinition requireAgent(String agentName) {
    AgentDefinition agent = agentDefinitionRepository.getByName(agentName);
    if (agent == null) {
      throw new IllegalArgumentException("agent not found: " + agentName);
    }
    return agent;
  }

  private AgentSession requireSession(String sessionId) {
    AgentSession session = agentSessionRepository.getBySessionId(sessionId);
    if (session == null) {
      throw new IllegalArgumentException("session not found: " + sessionId);
    }
    return session;
  }

  private String requireMessageContent(AgentSessionMessageCreateDTO createDTO) {
    if (createDTO == null || createDTO.getContent() == null || createDTO.getContent().isBlank()) {
      throw new IllegalArgumentException("content must not be blank");
    }
    return createDTO.getContent();
  }

  private void validateCreateDTO(AgentSessionCreateDTO createDTO) {
    if (createDTO == null
        || createDTO.getAgentName() == null
        || createDTO.getAgentName().isBlank()) {
      throw new IllegalArgumentException("agentName must not be blank");
    }
  }
}
