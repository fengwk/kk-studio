package fun.fengwk.kkstudio.core.agent.session.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionEventRepository;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * AgentSessionEventBranchLoader 的聚焦行为测试。
 *
 * @author fengwk
 */
public class AgentSessionEventBranchLoaderTest {

  /** root 是事件树的虚拟起点，不包含任何实际事件。 */
  @Test
  public void shouldLoadRootHeadAsEmptyBranch() {
    InMemorySessionEventRepository sessionEventRepository = new InMemorySessionEventRepository();
    AgentSessionEventBranchLoader loader =
        new AgentSessionEventBranchLoader(sessionEventRepository);
    sessionEventRepository.addEvent(event(1L, "ev_user", "root", "rn_1", "user_message"));

    List<AgentSessionEvent> events = loader.load("se_1", "root");

    assertEquals(List.of(), eventIds(events));
  }

  /** 非 root head 只按 parentEventId 回放其严格祖先链。 */
  @Test
  public void shouldLoadStrictParentChain() {
    InMemorySessionEventRepository sessionEventRepository = new InMemorySessionEventRepository();
    AgentSessionEventBranchLoader loader =
        new AgentSessionEventBranchLoader(sessionEventRepository);

    sessionEventRepository.addEvent(event(1L, "ev_user", "root", "rn_1", "user_message"));
    sessionEventRepository.addEvent(event(2L, "ev_start", "ev_user", "rn_1", "assistant_start"));
    sessionEventRepository.addEvent(event(3L, "ev_delta", "ev_start", "rn_1", "assistant_delta"));
    sessionEventRepository.addEvent(event(4L, "ev_other", "root", "rn_2", "user_message"));

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

    @Override
    public int deleteBySessionId(String sessionId) {
      throw new UnsupportedOperationException();
    }

    void addEvent(AgentSessionEvent event) {
      events.add(event);
    }
  }
}
