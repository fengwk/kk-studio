package fun.fengwk.kkstudio.core.agent.session.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionEventRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionHeadRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionRepository;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSession;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionEvent;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionHead;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionEventRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionHeadRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionRepository;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSession;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionEvent;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionHead;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * AgentSessionDefaultHeadAdvancer 的聚焦行为测试。
 *
 * @author fengwk
 */
public class AgentSessionDefaultHeadAdvancerTest {

  /** 校验会按 createTime 选择最新事件并同时推进 session 与默认 head。 */
  @Test
  public void shouldAdvanceDefaultHeadToLatestEvent() {
    InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
    InMemorySessionHeadRepository sessionHeadRepository = new InMemorySessionHeadRepository();
    InMemorySessionEventRepository sessionEventRepository = new InMemorySessionEventRepository();
    AgentSessionDefaultHeadAdvancer advancer =
        new AgentSessionDefaultHeadAdvancer(
            sessionRepository, sessionHeadRepository, sessionEventRepository, "default");

    AgentSession session = new AgentSession();
    session.setSessionId("se_1");
    session.setCurrentHeadEventId("root");
    sessionRepository.sessions.put(session.getSessionId(), session);

    sessionEventRepository.addEvent(
        event(
            session.getSessionId(), 1L, "ev_old", "rn_1", LocalDateTime.of(2026, 1, 1, 10, 0, 0)));
    sessionEventRepository.addEvent(
        event(
            session.getSessionId(), 2L, "ev_new", "rn_1", LocalDateTime.of(2026, 1, 1, 10, 1, 0)));

    advancer.advanceToLatest(session.getSessionId());

    assertEquals("root", sessionRepository.lastExpectedCurrentHeadEventId);
    assertEquals("ev_new", sessionRepository.lastCurrentHeadEventId);
    assertNotNull(sessionRepository.lastUpdateTime);
    assertEquals(
        "ev_new", sessionRepository.sessions.get(session.getSessionId()).getCurrentHeadEventId());
    assertEquals("default", sessionHeadRepository.lastHeadName);
    assertEquals("ev_new", sessionHeadRepository.lastHeadEventId);
  }

  /** 校验没有事件时不会触发任何 head 更新。 */
  @Test
  public void shouldIgnoreAdvanceWhenSessionHasNoEvents() {
    InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
    InMemorySessionHeadRepository sessionHeadRepository = new InMemorySessionHeadRepository();
    InMemorySessionEventRepository sessionEventRepository = new InMemorySessionEventRepository();
    AgentSessionDefaultHeadAdvancer advancer =
        new AgentSessionDefaultHeadAdvancer(
            sessionRepository, sessionHeadRepository, sessionEventRepository, "default");

    AgentSession session = new AgentSession();
    session.setSessionId("se_2");
    session.setCurrentHeadEventId("root");
    sessionRepository.sessions.put(session.getSessionId(), session);

    advancer.advanceToLatest(session.getSessionId());

    assertNull(sessionRepository.lastCurrentHeadEventId);
    assertNull(sessionHeadRepository.lastHeadEventId);
  }

  private static AgentSessionEvent event(
      String sessionId, Long id, String eventId, String runId, LocalDateTime createTime) {
    AgentSessionEvent event = new AgentSessionEvent();
    event.setId(id);
    event.setEventId(eventId);
    event.setSessionId(sessionId);
    event.setParentEventId("root");
    event.setRunId(runId);
    event.setEventType("assistant_delta");
    event.setPayloadType("text");
    event.setPayloadJson("{}");
    event.setCreateTime(createTime);
    return event;
  }

  private static class InMemorySessionRepository implements AgentSessionRepository {

    private final Map<String, AgentSession> sessions = new HashMap<>();
    private String lastExpectedCurrentHeadEventId;
    private String lastCurrentHeadEventId;
    private LocalDateTime lastUpdateTime;

    @Override
    public Page<AgentSession> page(PageQuery pageQuery) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean add(AgentSession session) {
      sessions.put(session.getSessionId(), session);
      return true;
    }

    @Override
    public AgentSession getBySessionId(String sessionId) {
      return sessions.get(sessionId);
    }

    @Override
    public boolean updateTitleBySessionId(
        String sessionId, String title, LocalDateTime updateTime) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean deleteBySessionId(String sessionId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean updateCurrentHeadEventId(
        String sessionId, String currentHeadEventId, LocalDateTime updateTime) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean compareAndSetCurrentHeadEventId(
        String sessionId,
        String expectedCurrentHeadEventId,
        String currentHeadEventId,
        LocalDateTime updateTime) {
      lastExpectedCurrentHeadEventId = expectedCurrentHeadEventId;
      lastCurrentHeadEventId = currentHeadEventId;
      lastUpdateTime = updateTime;
      AgentSession session = sessions.get(sessionId);
      if (session != null) {
        session.setCurrentHeadEventId(currentHeadEventId);
        session.setUpdateTime(updateTime);
      }
      return true;
    }
  }

  private static class InMemorySessionHeadRepository implements AgentSessionHeadRepository {

    private String lastHeadName;
    private String lastHeadEventId;

    @Override
    public boolean add(AgentSessionHead sessionHead) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<AgentSessionHead> listBySessionId(String sessionId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean updateHeadEventId(String sessionId, String headName, String headEventId) {
      lastHeadName = headName;
      lastHeadEventId = headEventId;
      return true;
    }
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
