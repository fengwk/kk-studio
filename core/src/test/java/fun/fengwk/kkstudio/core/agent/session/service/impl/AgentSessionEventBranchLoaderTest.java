package fun.fengwk.kkstudio.core.agent.session.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionEventRepository;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionEventRepository;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * AgentSessionEventBranchLoader 的聚焦行为测试。
 *
 * @author fengwk
 */
public class AgentSessionEventBranchLoaderTest {

  /** 校验 root head 只返回按事件 id 排序的 user_message。 */
  @Test
  public void shouldLoadRootHeadAsOrderedUserMessagesOnly() {
    InMemorySessionEventRepository sessionEventRepository = new InMemorySessionEventRepository();
    AgentSessionEventBranchLoader loader =
        new AgentSessionEventBranchLoader(sessionEventRepository);

    sessionEventRepository.addEvent(event(2L, "ev_assistant", "root", "rn_1", "assistant_start"));
    sessionEventRepository.addEvent(event(3L, "ev_user_2", "root", "rn_2", "user_message"));
    sessionEventRepository.addEvent(event(1L, "ev_user_1", "root", "rn_1", "user_message"));

    List<AgentSessionEvent> events = loader.load("se_1", "root");

    assertEquals(List.of("ev_user_1", "ev_user_2"), eventIds(events));
  }

  /** 校验会把与分支并行、同一 run 的 user_message 合并回结果中。 */
  @Test
  public void shouldMergeParallelUserMessageIntoBranch() {
    InMemorySessionEventRepository sessionEventRepository = new InMemorySessionEventRepository();
    AgentSessionEventBranchLoader loader =
        new AgentSessionEventBranchLoader(sessionEventRepository);

    sessionEventRepository.addEvent(event(1L, "ev_user", "root", "rn_1", "user_message"));
    sessionEventRepository.addEvent(event(2L, "ev_start", "root", "rn_1", "assistant_start"));
    sessionEventRepository.addEvent(event(3L, "ev_delta", "ev_start", "rn_1", "assistant_delta"));

    List<AgentSessionEvent> events = loader.load("se_1", "ev_delta");

    assertEquals(List.of("ev_user", "ev_start", "ev_delta"), eventIds(events));
  }

  /** 校验循环分支会被及时拒绝，避免返回破损的事件链。 */
  @Test
  public void shouldRejectCyclicBranch() {
    InMemorySessionEventRepository sessionEventRepository = new InMemorySessionEventRepository();
    AgentSessionEventBranchLoader loader =
        new AgentSessionEventBranchLoader(sessionEventRepository);

    sessionEventRepository.addEvent(event(1L, "ev_a", "ev_b", "rn_1", "assistant_start"));
    sessionEventRepository.addEvent(event(2L, "ev_b", "ev_a", "rn_1", "assistant_delta"));

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> loader.load("se_1", "ev_a"));

    assertEquals("cycle detected at event: ev_a", error.getMessage());
  }

  private static AgentSessionEvent event(
      Long id, String eventId, String parentEventId, String runId, String eventType) {
    AgentSessionEvent event = new AgentSessionEvent();
    event.setId(id);
    event.setEventId(eventId);
    event.setSessionId("se_1");
    event.setParentEventId(parentEventId);
    event.setRunId(runId);
    event.setEventType(eventType);
    event.setPayloadType("text");
    event.setPayloadJson("{}");
    return event;
  }

  private static List<String> eventIds(List<AgentSessionEvent> events) {
    List<String> ids = new ArrayList<>();
    for (AgentSessionEvent event : events) {
      ids.add(event.getEventId());
    }
    return ids;
  }

  private static class InMemorySessionEventRepository implements AgentSessionEventRepository {

    private final List<AgentSessionEvent> events = new ArrayList<>();

    @Override
    public boolean add(AgentSessionEvent sessionEvent) {
      events.add(sessionEvent);
      return true;
    }

    @Override
    public List<AgentSessionEvent> listBySessionId(String sessionId) {
      List<AgentSessionEvent> result = new ArrayList<>();
      for (AgentSessionEvent event : events) {
        if (sessionId.equals(event.getSessionId())) {
          result.add(event);
        }
      }
      return result;
    }

    @Override
    public List<AgentSessionEvent> listBySessionIdAfterEventId(
        String sessionId, String afterEventId) {
      throw new UnsupportedOperationException();
    }

    void addEvent(AgentSessionEvent event) {
      events.add(event);
    }
  }
}
