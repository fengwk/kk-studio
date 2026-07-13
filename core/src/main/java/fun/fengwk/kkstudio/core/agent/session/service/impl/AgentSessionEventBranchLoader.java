package fun.fengwk.kkstudio.core.agent.session.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionEventRepository;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 按 head 沿 parentEventId 严格装载会话分支。 */
@Component
final class AgentSessionEventBranchLoader {

  private final AgentSessionEventRepository sessionEventRepository;

  AgentSessionEventBranchLoader(AgentSessionEventRepository sessionEventRepository) {
    this.sessionEventRepository = requireNonNull(sessionEventRepository, "sessionEventRepository");
  }

  List<AgentSessionEvent> load(String sessionId, String headEventId) {
    if (headEventId == null
        || headEventId.isBlank()
        || AgentSessionEvent.ROOT_EVENT_ID.equals(headEventId)) {
      return List.of();
    }

    List<AgentSessionEvent> allEvents = sessionEventRepository.listBySessionId(sessionId);
    if (allEvents.isEmpty()) {
      throw new IllegalStateException("session has no events: " + sessionId);
    }

    Map<String, AgentSessionEvent> eventById = new HashMap<>();
    for (AgentSessionEvent event : allEvents) {
      eventById.put(event.getEventId(), event);
    }

    List<AgentSessionEvent> branch = new ArrayList<>();
    Set<String> visited = new HashSet<>();
    String cursor = headEventId;
    while (cursor != null && !AgentSessionEvent.ROOT_EVENT_ID.equals(cursor)) {
      if (!visited.add(cursor)) {
        throw new IllegalStateException("cycle detected at event: " + cursor);
      }
      AgentSessionEvent event = eventById.get(cursor);
      if (event == null) {
        throw new IllegalStateException("missing event: " + cursor);
      }
      branch.add(event);
      cursor = event.getParentEventId();
    }
    Collections.reverse(branch);
    return List.copyOf(branch);
  }

  private static <T> T requireNonNull(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    return value;
  }
}
