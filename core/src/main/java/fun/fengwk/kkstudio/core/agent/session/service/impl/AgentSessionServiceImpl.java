package fun.fengwk.kkstudio.core.agent.session.service.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

/**
 * @author fengwk
 */
@AllArgsConstructor
@Service
public class AgentSessionServiceImpl implements AgentSessionService {

  private final AgentSessionRepository agentSessionRepository;
  private final AgentSessionHeadRepository agentSessionHeadRepository;
  private final AgentSessionEventRepository agentSessionEventRepository;
  private final AgentRunService agentRunService;
  private final AgentSessionConverter agentSessionConverter;
  private final AgentSessionMutationFactory sessionMutationFactory;
  private final AgentSessionDefaultHeadAdvancer sessionDefaultHeadAdvancer;
  private final AgentSessionEventBranchLoader sessionEventBranchLoader;
  private final AgentSessionRequestSupport sessionRequestSupport;

  @Override
  public Page<AgentSessionDTO> pageSessions(PageQuery pageQuery) {
    return agentSessionRepository.page(pageQuery).map(agentSessionConverter::convert);
  }

  @Transactional
  @Override
  public AgentSessionDTO createSession(AgentSessionCreateDTO createDTO) {
    AgentDefinition agent = sessionRequestSupport.requireAgent(createDTO);

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
    AgentSession session = sessionRequestSupport.requireSession(sessionId);
    return agentSessionConverter.convert(session);
  }

  @Override
  public AgentSessionDTO updateSession(String sessionId, AgentSessionUpdateDTO updateDTO) {
    AgentSession session = sessionRequestSupport.requireSession(sessionId);
    String title = sessionRequestSupport.normalizeTitle(updateDTO);
    if (!agentSessionRepository.updateTitleBySessionId(
        session.getSessionId(), title, LocalDateTime.now())) {
      throw new IllegalStateException("update session failed: " + session.getSessionId());
    }
    return agentSessionConverter.convert(
        agentSessionRepository.getBySessionId(session.getSessionId()));
  }

  @Override
  public void deleteSession(String sessionId) {
    AgentSession session = sessionRequestSupport.requireSession(sessionId);
    if (!agentSessionRepository.deleteBySessionId(session.getSessionId())) {
      throw new IllegalStateException("delete session failed: " + session.getSessionId());
    }
  }

  @Transactional
  @Override
  public AgentSessionEventDTO createMessage(
      String sessionId, AgentSessionMessageCreateDTO createDTO) {
    String content = sessionRequestSupport.requireMessageContent(createDTO);
    AgentSession session = sessionRequestSupport.requireSession(sessionId);

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

    agentRunService.createQueuedRun(runId, session.getSessionId(), userEvent.getEventId());
    sessionDefaultHeadAdvancer.advanceToLatest(session.getSessionId());
    return agentSessionConverter.convert(userEvent);
  }

  @Override
  public List<AgentSessionHeadDTO> listHeads(String sessionId) {
    AgentSession session = sessionRequestSupport.requireSession(sessionId);
    return agentSessionHeadRepository.listBySessionId(session.getSessionId()).stream()
        .map(agentSessionConverter::convert)
        .toList();
  }

  @Override
  public List<AgentSessionEventDTO> listEvents(String sessionId, String headEventId) {
    AgentSession session = sessionRequestSupport.requireSession(sessionId);
    String effectiveHead = sessionRequestSupport.resolveHeadEventId(session, headEventId);
    return sessionEventBranchLoader.load(session.getSessionId(), effectiveHead).stream()
        .map(agentSessionConverter::convert)
        .toList();
  }

  @Override
  public List<AgentSessionEventDTO> listEventsAfter(String sessionId, String afterEventId) {
    AgentSession session = sessionRequestSupport.requireSession(sessionId);
    return agentSessionEventRepository
        .listBySessionIdAfterEventId(session.getSessionId(), afterEventId)
        .stream()
        .map(agentSessionConverter::convert)
        .toList();
  }
}
