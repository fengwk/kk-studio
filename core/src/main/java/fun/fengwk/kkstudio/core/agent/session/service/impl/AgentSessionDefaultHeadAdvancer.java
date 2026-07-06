package fun.fengwk.kkstudio.core.agent.session.service.impl;

import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionEventRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionHeadRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionRepository;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSession;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionEvent;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionEventRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionHeadRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionRepository;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSession;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionEvent;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AgentSessionDefaultHeadAdvancer 负责把默认 head 推进到当前会话的最新事件。
 *
 * @author fengwk
 */
@Component
final class AgentSessionDefaultHeadAdvancer {

  private static final Logger log = LoggerFactory.getLogger(AgentSessionDefaultHeadAdvancer.class);
  private static final String DEFAULT_HEAD_NAME = "default";

  private final AgentSessionRepository sessionRepository;
  private final AgentSessionHeadRepository sessionHeadRepository;
  private final AgentSessionEventRepository sessionEventRepository;

  AgentSessionDefaultHeadAdvancer(
      AgentSessionRepository sessionRepository,
      AgentSessionHeadRepository sessionHeadRepository,
      AgentSessionEventRepository sessionEventRepository) {
    this.sessionRepository = requireNonNull(sessionRepository, "sessionRepository");
    this.sessionHeadRepository = requireNonNull(sessionHeadRepository, "sessionHeadRepository");
    this.sessionEventRepository = requireNonNull(sessionEventRepository, "sessionEventRepository");
  }

  void advanceToLatest(String sessionId) {
    List<AgentSessionEvent> allEvents = sessionEventRepository.listBySessionId(sessionId);
    if (log.isDebugEnabled()) {
      log.debug("advance all events count={}", allEvents.size());
      for (AgentSessionEvent event : allEvents) {
        log.debug("event id={} type={}", event.getId(), event.getEventType());
      }
    }
    AgentSessionEvent latestEvent = findLatestEvent(allEvents);
    if (latestEvent == null) {
      return;
    }

    AgentSession session = sessionRepository.getBySessionId(sessionId);
    if (session == null) {
      throw new IllegalStateException("session not found: " + sessionId);
    }
    sessionRepository.compareAndSetCurrentHeadEventId(
        sessionId, session.getCurrentHeadEventId(), latestEvent.getEventId(), LocalDateTime.now());
    sessionHeadRepository.updateHeadEventId(sessionId, DEFAULT_HEAD_NAME, latestEvent.getEventId());
  }

  private AgentSessionEvent findLatestEvent(List<AgentSessionEvent> events) {
    if (events == null || events.isEmpty()) {
      return null;
    }
    AgentSessionEvent latest = events.get(0);
    for (AgentSessionEvent event : events) {
      if (event.getCreateTime() == null) {
        continue;
      }
      if (latest.getCreateTime() == null || event.getCreateTime().isAfter(latest.getCreateTime())) {
        latest = event;
      }
    }
    return latest;
  }

  private static <T> T requireNonNull(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    return value;
  }
}
