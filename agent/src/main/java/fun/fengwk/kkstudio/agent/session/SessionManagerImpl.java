package fun.fengwk.kkstudio.agent.session;

import fun.fengwk.kkstudio.agent.session.repo.SessionEventRepository;
import fun.fengwk.kkstudio.agent.session.repo.SessionRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SessionManager 的默认实现。
 *
 * <p>语义说明： - 实现按 sessionId 装载 event 集合，再投影出 branch event 链。 - 实现负责协调 repository 与 branch/event 约束。
 *
 * @author fengwk
 */
public class SessionManagerImpl implements SessionManager {

  private final SessionRepository sessionRepository;
  private final SessionEventRepository sessionEventRepository;

  public SessionManagerImpl(
      SessionRepository sessionRepository, SessionEventRepository sessionEventRepository) {
    if (sessionRepository == null) {
      throw new IllegalArgumentException("sessionRepository must not be null");
    }
    if (sessionEventRepository == null) {
      throw new IllegalArgumentException("sessionEventRepository must not be null");
    }
    this.sessionRepository = sessionRepository;
    this.sessionEventRepository = sessionEventRepository;
  }

  @Override
  public Session getSession(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    return sessionRepository.getSession(sessionId);
  }

  @Override
  public List<SessionEvent> loadBranchEvents(Branch branch) {
    if (branch == null) {
      throw new IllegalArgumentException("branch must not be null");
    }

    Session session = sessionRepository.getSession(branch.sessionId());
    if (session == null) {
      throw new IllegalArgumentException("session not found: " + branch.sessionId());
    }
    if (SessionEvent.ROOT_EVENT_ID.equals(branch.headEventId())) {
      return Collections.emptyList();
    }

    List<SessionEvent> sessionEvents = sessionEventRepository.listBySessionId(branch.sessionId());
    if (sessionEvents == null || sessionEvents.isEmpty()) {
      throw new IllegalStateException("branch head not found: " + branch.headEventId());
    }

    Map<String, SessionEvent> eventById = new HashMap<>();
    for (SessionEvent event : sessionEvents) {
      eventById.put(event.getEventId(), event);
    }

    List<SessionEvent> branchEvents = new ArrayList<>();
    SessionEvent current = eventById.get(branch.headEventId());
    if (current == null) {
      throw new IllegalStateException("branch head not found: " + branch.headEventId());
    }
    Set<String> visitedEventIds = new HashSet<>();
    while (current != null) {
      if (!visitedEventIds.add(current.getEventId())) {
        throw new IllegalStateException("cycle detected in branch events: " + current.getEventId());
      }
      branchEvents.add(current);
      if (current.getParentEventId() == null
          || SessionEvent.ROOT_EVENT_ID.equals(current.getParentEventId())) {
        break;
      }
      current = eventById.get(current.getParentEventId());
      if (current == null) {
        throw new IllegalStateException(
            "parent event not found: "
                + branchEvents.get(branchEvents.size() - 1).getParentEventId());
      }
    }
    Collections.reverse(branchEvents);
    return List.copyOf(branchEvents);
  }

  @Override
  public Branch appendEvent(Branch branch, SessionEvent event) {
    if (branch == null) {
      throw new IllegalArgumentException("branch must not be null");
    }
    if (event == null) {
      throw new IllegalArgumentException("event must not be null");
    }
    SessionEventValidator.validateCompleteEvent(event);
    if (!branch.sessionId().equals(event.getSessionId())) {
      throw new IllegalArgumentException("branch.sessionId does not match event.sessionId");
    }
    if (!branch.headEventId().equals(event.getParentEventId())) {
      throw new IllegalArgumentException("branch.headEventId does not match event.parentEventId");
    }

    sessionEventRepository.appendEvent(event);
    return branch.next(event.getEventId());
  }

  @Override
  public boolean compareAndSetCurrentHeadEventId(
      String sessionId, String expectedHeadEventId, String newHeadEventId) {
    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    if (expectedHeadEventId == null || expectedHeadEventId.isBlank()) {
      throw new IllegalArgumentException("expectedHeadEventId must not be blank");
    }
    if (newHeadEventId == null || newHeadEventId.isBlank()) {
      throw new IllegalArgumentException("newHeadEventId must not be blank");
    }
    return sessionRepository.compareAndSetCurrentHeadEventId(
        sessionId, expectedHeadEventId, newHeadEventId);
  }
}
