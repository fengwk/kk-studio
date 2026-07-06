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
import fun.fengwk.kkstudio.core.agent.runtime.service.AgentRunRuntimeService;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.core.agent.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.run.service.AgentRunService;
import fun.fengwk.kkstudio.core.agent.runtime.service.AgentRunRuntimeService;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Service
public class AgentSessionServiceImpl implements AgentSessionService {

  private static final Logger log = LoggerFactory.getLogger(AgentSessionServiceImpl.class);

  private static final String DEFAULT_HEAD_NAME = "default";
  private static final String USER_MESSAGE_EVENT_TYPE = "user_message";
  private static final String TEXT_PAYLOAD_TYPE = "text";

  private final AgentDefinitionRepository agentDefinitionRepository;
  private final AgentSessionRepository agentSessionRepository;
  private final AgentSessionHeadRepository agentSessionHeadRepository;
  private final AgentSessionEventRepository agentSessionEventRepository;
  private final AgentRunService agentRunService;
  private final AgentRunRuntimeService agentRunRuntimeService;
  private final AgentSessionConverter agentSessionConverter;
  private final ObjectMapper objectMapper;

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
    advanceHeadToLatest(sessionId);
    return convertEvent(userEvent);
  }

  private void advanceHeadToLatest(String sessionId) {
    doAdvanceHeadToLatest(sessionId);
  }

  private void doAdvanceHeadToLatest(String sessionId) {
    List<AgentSessionEvent> all = agentSessionEventRepository.listBySessionId(sessionId);
    if (log.isDebugEnabled()) {
      log.debug("advance all events count={}", all.size());
      for (AgentSessionEvent event : all) {
        log.debug("event id={} type={}", event.getId(), event.getEventType());
      }
    }
    if (all.isEmpty()) {
      return;
    }
    AgentSessionEvent latest = all.get(0);
    for (AgentSessionEvent e : all) {
      if (e.getCreateTime() == null) {
        continue;
      }
      if (latest.getCreateTime() == null || e.getCreateTime().isAfter(latest.getCreateTime())) {
        latest = e;
      }
    }
    agentSessionRepository.compareAndSetCurrentHeadEventId(
        sessionId,
        agentSessionRepository.getBySessionId(sessionId).getCurrentHeadEventId(),
        latest.getEventId(),
        LocalDateTime.now());
    // also push the default head's head_event_id
    agentSessionHeadRepository.updateHeadEventId(sessionId, DEFAULT_HEAD_NAME, latest.getEventId());
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
    List<AgentSessionEvent> branch = loadBranchEvents(sessionId, effectiveHead);
    // 合并当前 head 对应 run 的 user_message（如果 user_message 不在 branch chain 里）。
    // 历史同步流下 user_message.parentEventId=root 而 agent 事件链也是 root，user_message
    // 与 agent 链平行存在，所以 listEvents 需要单独 merge 它。
    // 现在异步流下 user_message.parentEventId 已经被 head 提前推到 user_message 自身，
    // agent 事件链把 user_message 作为祖先包含进去，这里就要去重以免出现两次。
    String runId = branch.isEmpty() ? null : branch.get(branch.size() - 1).getRunId();
    Set<String> branchEventIds = new HashSet<>();
    for (AgentSessionEvent e : branch) {
      branchEventIds.add(e.getEventId());
    }
    List<AgentSessionEvent> events = new ArrayList<>();
    if (runId != null) {
      for (AgentSessionEvent e : agentSessionEventRepository.listBySessionId(sessionId)) {
        if (USER_MESSAGE_EVENT_TYPE.equals(e.getEventType())
            && runId.equals(e.getRunId())
            && !branchEventIds.contains(e.getEventId())) {
          events.add(e);
        }
      }
    }
    events.addAll(branch);
    events.sort(
        (a, b) -> {
          long ai = a.getId() == null ? 0 : a.getId();
          long bi = b.getId() == null ? 0 : b.getId();
          return Long.compare(ai, bi);
        });
    List<AgentSessionEventDTO> result = new ArrayList<>(events.size());
    for (AgentSessionEvent event : events) {
      result.add(convertEvent(event));
    }
    return result;
  }

  @Override
  public List<AgentSessionEventDTO> listEventsAfter(String sessionId, String afterEventId) {
    requireSession(sessionId);
    List<AgentSessionEvent> events =
        agentSessionEventRepository.listBySessionIdAfterEventId(sessionId, afterEventId);
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
      return objectMapper.writeValueAsString(
          new HashMap<String, Object>() {
            {
              put("content", content);
            }
          });
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("serialize message payload failed", e);
    }
  }

  private List<AgentSessionEvent> loadUserMessagesOnly(String sessionId) {
    return agentSessionEventRepository.listBySessionId(sessionId).stream()
        .filter(e -> USER_MESSAGE_EVENT_TYPE.equals(e.getEventType()))
        .sorted(
            (a, b) ->
                Long.compare(a.getId() == null ? 0 : a.getId(), b.getId() == null ? 0 : b.getId()))
        .collect(Collectors.toList());
  }

  private List<AgentSessionEvent> loadBranchEvents(String sessionId, String headEventId) {
    if (headEventId == null
        || headEventId.isBlank()
        || AgentSessionEvent.ROOT_EVENT_ID.equals(headEventId)) {
      return loadUserMessagesOnly(sessionId);
    }
    List<AgentSessionEvent> all = agentSessionEventRepository.listBySessionId(sessionId);
    if (all.isEmpty()) {
      throw new IllegalStateException("session has no events: " + sessionId);
    }
    Map<String, AgentSessionEvent> byId = new HashMap<>();
    for (AgentSessionEvent event : all) {
      byId.put(event.getEventId(), event);
    }
    if (!byId.containsKey(headEventId)) {
      throw new IllegalStateException("head event not found: " + headEventId);
    }
    List<AgentSessionEvent> branch = new ArrayList<>();
    Set<String> visited = new HashSet<>();
    String cursor = headEventId;
    if (log.isDebugEnabled()) {
      log.debug("loadBranchEvents walking from {}", cursor);
    }
    while (cursor != null) {
      if (!visited.add(cursor)) {
        throw new IllegalStateException("cycle detected at event: " + cursor);
      }
      AgentSessionEvent event = byId.get(cursor);
      if (event == null) {
        throw new IllegalStateException("missing event: " + cursor);
      }
      branch.add(event);
      cursor = event.getParentEventId();
      if (AgentSessionEvent.ROOT_EVENT_ID.equals(cursor)) {
        break;
      }
    }
    Collections.reverse(branch);
    if (log.isDebugEnabled()) {
      log.debug("loadBranchEvents branch size={}", branch.size());
    }
    return branch;
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
