package fun.fengwk.kkstudio.core.agent.session.service.impl;

import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionEventRepository;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionEventRepository;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AgentSessionEventBranchLoader 负责按 head 装载会话分支，并在需要时补回并行的 user_message。
 *
 * @author fengwk
 */
final class AgentSessionEventBranchLoader {

  private static final Logger log = LoggerFactory.getLogger(AgentSessionEventBranchLoader.class);

  private static final Comparator<AgentSessionEvent> EVENT_ORDER =
      Comparator.comparingLong(event -> event.getId() == null ? 0L : event.getId());

  private final AgentSessionEventRepository sessionEventRepository;
  private final String userMessageEventType;

  AgentSessionEventBranchLoader(
      AgentSessionEventRepository sessionEventRepository, String userMessageEventType) {
    this.sessionEventRepository = requireNonNull(sessionEventRepository, "sessionEventRepository");
    this.userMessageEventType = requireNonBlank(userMessageEventType, "userMessageEventType");
  }

  List<AgentSessionEvent> load(String sessionId, String headEventId) {
    List<AgentSessionEvent> allEvents = sessionEventRepository.listBySessionId(sessionId);
    if (headEventId == null
        || headEventId.isBlank()
        || AgentSessionEvent.ROOT_EVENT_ID.equals(headEventId)) {
      return allEvents.stream()
          .filter(event -> userMessageEventType.equals(event.getEventType()))
          .sorted(EVENT_ORDER)
          .collect(Collectors.toList());
    }

    List<AgentSessionEvent> branch = loadBranchEvents(sessionId, allEvents, headEventId);
    return mergeParallelUserMessages(allEvents, branch);
  }

  private List<AgentSessionEvent> loadBranchEvents(
      String sessionId, List<AgentSessionEvent> allEvents, String headEventId) {
    if (allEvents.isEmpty()) {
      throw new IllegalStateException("session has no events: " + sessionId);
    }
    Map<String, AgentSessionEvent> eventById = new HashMap<>();
    for (AgentSessionEvent event : allEvents) {
      eventById.put(event.getEventId(), event);
    }
    if (!eventById.containsKey(headEventId)) {
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
      AgentSessionEvent event = eventById.get(cursor);
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

  private List<AgentSessionEvent> mergeParallelUserMessages(
      List<AgentSessionEvent> allEvents, List<AgentSessionEvent> branch) {
    String runId = branch.isEmpty() ? null : branch.get(branch.size() - 1).getRunId();
    if (runId == null) {
      return branch;
    }

    Set<String> branchEventIds = new HashSet<>();
    for (AgentSessionEvent event : branch) {
      branchEventIds.add(event.getEventId());
    }

    List<AgentSessionEvent> merged = new ArrayList<>();
    for (AgentSessionEvent event : allEvents) {
      if (userMessageEventType.equals(event.getEventType())
          && runId.equals(event.getRunId())
          && !branchEventIds.contains(event.getEventId())) {
        merged.add(event);
      }
    }
    merged.addAll(branch);
    merged.sort(EVENT_ORDER);
    return merged;
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
