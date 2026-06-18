package fun.fengwk.kkstudio.agent.session;

import fun.fengwk.kkstudio.agent.session.payload.AssistantStartPayload;
import fun.fengwk.kkstudio.agent.session.payload.Payload;
import fun.fengwk.kkstudio.agent.session.payload.ToolEndPayload;
import fun.fengwk.kkstudio.agent.session.repo.SessionEventRepository;
import fun.fengwk.kkstudio.agent.session.repo.SessionRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author fengwk
 */
public class SessionManagerImplTest {

    /**
     * 校验构造器拒绝缺失 repository。
     */
    @Test
    public void testConstructorRejectsNullRepositories() {
        assertThrows(IllegalArgumentException.class, () -> new SessionManagerImpl(null, new InMemorySessionEventRepository()));
        assertThrows(IllegalArgumentException.class, () -> new SessionManagerImpl(new InMemorySessionRepository(), null));
    }

    /**
     * 校验读取 session 时拒绝空白 sessionId。
     */
    @Test
    public void testGetSessionRejectsBlankSessionId() {
        SessionManagerImpl sessionManager = new SessionManagerImpl(new InMemorySessionRepository(), new InMemorySessionEventRepository());

        assertThrows(IllegalArgumentException.class, () -> sessionManager.getSession(null));
        assertThrows(IllegalArgumentException.class, () -> sessionManager.getSession(" "));
    }

    @Test
    public void testLoadBranchEventsProjectsCurrentBranch() {
        InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
        InMemorySessionEventRepository sessionEventRepository = new InMemorySessionEventRepository();
        SessionManagerImpl sessionManager = new SessionManagerImpl(sessionRepository, sessionEventRepository);

        Session session = Session.newSession();
        sessionRepository.save(session);

        SessionEvent event1 = appendEvent(sessionEventRepository, session.getSessionId(), SessionEvent.ROOT_EVENT_ID, "u1");
        SessionEvent event2 = appendEvent(sessionEventRepository, session.getSessionId(), event1.getEventId(), "u2");
        appendEvent(sessionEventRepository, session.getSessionId(), event1.getEventId(), "fork");

        Branch branch = Branch.newBranch(session.getSessionId(), event2.getEventId());

        List<SessionEvent> branchEvents = sessionManager.loadBranchEvents(branch);

        assertEquals(2, branchEvents.size());
        assertEquals(event1.getEventId(), branchEvents.get(0).getEventId());
        assertEquals(event2.getEventId(), branchEvents.get(1).getEventId());
    }

    /**
     * 校验 root branch 是虚拟根，不访问 event repository 并直接返回空列表。
     */
    @Test
    public void testLoadRootBranchReturnsEmptyWithoutLoadingEvents() {
        InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
        InMemorySessionEventRepository sessionEventRepository = new InMemorySessionEventRepository();
        SessionManagerImpl sessionManager = new SessionManagerImpl(sessionRepository, sessionEventRepository);
        Session session = Session.newSession();
        sessionRepository.save(session);

        List<SessionEvent> branchEvents = sessionManager.loadBranchEvents(session.currentBranch());

        assertEquals(List.of(), branchEvents);
        assertEquals(0, sessionEventRepository.listCalls);
    }

    /**
     * 校验 loadBranchEvents 拒绝 null branch。
     */
    @Test
    public void testLoadBranchEventsRejectsNullBranch() {
        SessionManagerImpl sessionManager = new SessionManagerImpl(new InMemorySessionRepository(), new InMemorySessionEventRepository());

        assertThrows(IllegalArgumentException.class, () -> sessionManager.loadBranchEvents(null));
    }

    /**
     * 校验 session 不存在时 loadBranchEvents 直接失败。
     */
    @Test
    public void testLoadBranchEventsFailsWhenSessionMissing() {
        SessionManagerImpl sessionManager = new SessionManagerImpl(new InMemorySessionRepository(), new InMemorySessionEventRepository());

        assertThrows(IllegalArgumentException.class, () -> sessionManager.loadBranchEvents(Branch.newBranch("missing", "ev_1")));
    }

    /**
     * 校验非 root head 在空 event tree 中不可恢复。
     */
    @Test
    public void testLoadBranchEventsFailsWhenNonRootHeadHasNoEvents() {
        InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
        Session session = Session.newSession();
        sessionRepository.save(session);
        SessionManagerImpl sessionManager = new SessionManagerImpl(sessionRepository, new InMemorySessionEventRepository());

        assertThrows(IllegalStateException.class,
            () -> sessionManager.loadBranchEvents(Branch.newBranch(session.getSessionId(), "ev_missing")));
    }

    /**
     * 校验 head event 缺失时失败。
     */
    @Test
    public void testLoadBranchEventsFailsWhenHeadMissing() {
        InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
        InMemorySessionEventRepository sessionEventRepository = new InMemorySessionEventRepository();
        Session session = Session.newSession();
        sessionRepository.save(session);
        appendEvent(sessionEventRepository, session.getSessionId(), SessionEvent.ROOT_EVENT_ID, "u1");
        SessionManagerImpl sessionManager = new SessionManagerImpl(sessionRepository, sessionEventRepository);

        assertThrows(IllegalStateException.class,
            () -> sessionManager.loadBranchEvents(Branch.newBranch(session.getSessionId(), "ev_missing")));
    }

    /**
     * 校验 parent 链断裂时失败。
     */
    @Test
    public void testLoadBranchEventsFailsWhenParentMissing() {
        InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
        InMemorySessionEventRepository sessionEventRepository = new InMemorySessionEventRepository();
        Session session = Session.newSession();
        sessionRepository.save(session);
        SessionEvent event = rawEvent(session.getSessionId(), "ev_1", "ev_missing", new AssistantStartPayload());
        sessionEventRepository.appendEvent(event);
        SessionManagerImpl sessionManager = new SessionManagerImpl(sessionRepository, sessionEventRepository);

        assertThrows(IllegalStateException.class,
            () -> sessionManager.loadBranchEvents(Branch.newBranch(session.getSessionId(), "ev_1")));
    }

    /**
     * 校验 parentEventId 为 null 时按 root 处理，兼容历史数据。
     */
    @Test
    public void testLoadBranchEventsTreatsNullParentAsRoot() {
        InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
        InMemorySessionEventRepository sessionEventRepository = new InMemorySessionEventRepository();
        Session session = Session.newSession();
        sessionRepository.save(session);
        SessionEvent event = rawEvent(session.getSessionId(), "ev_1", null, new AssistantStartPayload());
        sessionEventRepository.appendEvent(event);
        SessionManagerImpl sessionManager = new SessionManagerImpl(sessionRepository, sessionEventRepository);

        List<SessionEvent> branchEvents = sessionManager.loadBranchEvents(Branch.newBranch(session.getSessionId(), "ev_1"));

        assertEquals(List.of(event), branchEvents);
    }

    /**
     * 校验 parent 链成环时失败，避免无限回溯。
     */
    @Test
    public void testLoadBranchEventsFailsWhenCycleDetected() {
        InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
        InMemorySessionEventRepository sessionEventRepository = new InMemorySessionEventRepository();
        Session session = Session.newSession();
        sessionRepository.save(session);
        sessionEventRepository.appendEvent(rawEvent(session.getSessionId(), "ev_1", "ev_2", new AssistantStartPayload()));
        sessionEventRepository.appendEvent(rawEvent(session.getSessionId(), "ev_2", "ev_1", new AssistantStartPayload()));
        SessionManagerImpl sessionManager = new SessionManagerImpl(sessionRepository, sessionEventRepository);

        assertThrows(IllegalStateException.class,
            () -> sessionManager.loadBranchEvents(Branch.newBranch(session.getSessionId(), "ev_1")));
    }

    @Test
    public void testAppendEventReturnsAdvancedBranch() {
        InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
        InMemorySessionEventRepository sessionEventRepository = new InMemorySessionEventRepository();
        SessionManagerImpl sessionManager = new SessionManagerImpl(sessionRepository, sessionEventRepository);

        Session session = Session.newSession();
        sessionRepository.save(session);
        Branch branch = session.currentBranch();

        SessionEvent event = SessionEvent.newEvent(session.getSessionId(), SessionEventType.assistant_start, branch.headEventId(), new AssistantStartPayload());

        Branch nextBranch = sessionManager.appendEvent(branch, event);

        assertEquals(branch.sessionId(), nextBranch.sessionId());
        assertEquals(event.getEventId(), nextBranch.headEventId());
        assertEquals(1, sessionEventRepository.events.size());
    }

    /**
     * 校验 appendEvent 拒绝 null 参数。
     */
    @Test
    public void testAppendEventRejectsNullArguments() {
        SessionManagerImpl sessionManager = new SessionManagerImpl(new InMemorySessionRepository(), new InMemorySessionEventRepository());
        Branch branch = Branch.newBranch("se_1", SessionEvent.ROOT_EVENT_ID);

        assertThrows(IllegalArgumentException.class, () -> sessionManager.appendEvent(null, rawEvent("se_1", "ev_1", SessionEvent.ROOT_EVENT_ID, new AssistantStartPayload())));
        assertThrows(IllegalArgumentException.class, () -> sessionManager.appendEvent(branch, null));
    }

    /**
     * 校验 appendEvent 在写 repository 前拒绝不完整事件。
     */
    @Test
    public void testAppendEventRejectsIncompleteEventBeforeWrite() {
        InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
        InMemorySessionEventRepository sessionEventRepository = new InMemorySessionEventRepository();
        SessionManagerImpl sessionManager = new SessionManagerImpl(sessionRepository, sessionEventRepository);
        Session session = Session.newSession();
        sessionRepository.save(session);
        SessionEvent invalid = rawEvent(session.getSessionId(), " ", session.currentBranch().headEventId(), new AssistantStartPayload());

        assertThrows(IllegalArgumentException.class, () -> sessionManager.appendEvent(session.currentBranch(), invalid));
        assertEquals(0, sessionEventRepository.events.size());
    }

    /**
     * 校验 appendEvent 拒绝 payload 类型与 eventType 不匹配的事件。
     */
    @Test
    public void testAppendEventRejectsPayloadTypeMismatch() {
        InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
        InMemorySessionEventRepository sessionEventRepository = new InMemorySessionEventRepository();
        SessionManagerImpl sessionManager = new SessionManagerImpl(sessionRepository, sessionEventRepository);
        Session session = Session.newSession();
        sessionRepository.save(session);
        SessionEvent invalid = rawEvent(session.getSessionId(), "ev_1", session.currentBranch().headEventId(), new ToolEndPayload());
        invalid.setEventType(SessionEventType.assistant_start);

        assertThrows(IllegalArgumentException.class, () -> sessionManager.appendEvent(session.currentBranch(), invalid));
        assertEquals(0, sessionEventRepository.events.size());
    }

    /**
     * 校验 appendEvent 拒绝 session 与 parent 不匹配。
     */
    @Test
    public void testAppendEventRejectsSessionAndParentMismatch() {
        InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
        InMemorySessionEventRepository sessionEventRepository = new InMemorySessionEventRepository();
        SessionManagerImpl sessionManager = new SessionManagerImpl(sessionRepository, sessionEventRepository);
        Session session = Session.newSession();
        sessionRepository.save(session);

        assertThrows(IllegalArgumentException.class,
            () -> sessionManager.appendEvent(session.currentBranch(), rawEvent("other", "ev_1", SessionEvent.ROOT_EVENT_ID, new AssistantStartPayload())));
        assertThrows(IllegalArgumentException.class,
            () -> sessionManager.appendEvent(session.currentBranch(), rawEvent(session.getSessionId(), "ev_2", "ev_wrong", new AssistantStartPayload())));
        assertEquals(0, sessionEventRepository.events.size());
    }

    @Test
    public void testCompareAndSetCurrentHeadEventId() {
        InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
        Session session = Session.newSession();
        sessionRepository.save(session);
        SessionManagerImpl sessionManager = new SessionManagerImpl(sessionRepository, new InMemorySessionEventRepository());

        assertTrue(sessionManager.compareAndSetCurrentHeadEventId(session.getSessionId(), SessionEvent.ROOT_EVENT_ID, "ev_1"));
        assertEquals("ev_1", sessionRepository.getSession(session.getSessionId()).getCurrentHeadEventId());
        assertFalse(sessionManager.compareAndSetCurrentHeadEventId(session.getSessionId(), SessionEvent.ROOT_EVENT_ID, "ev_2"));
    }

    /**
     * 校验 session 缺失时 CAS 返回 false 而不是创建新 session。
     */
    @Test
    public void testCompareAndSetCurrentHeadEventIdReturnsFalseWhenSessionMissing() {
        SessionManagerImpl sessionManager = new SessionManagerImpl(new InMemorySessionRepository(), new InMemorySessionEventRepository());

        assertFalse(sessionManager.compareAndSetCurrentHeadEventId("missing", SessionEvent.ROOT_EVENT_ID, "ev_1"));
    }

    /**
     * 校验 CAS 更新默认 head 时拒绝空白参数。
     */
    @Test
    public void testCompareAndSetCurrentHeadEventIdRejectsBlankArguments() {
        SessionManagerImpl sessionManager = new SessionManagerImpl(new InMemorySessionRepository(), new InMemorySessionEventRepository());

        assertThrows(IllegalArgumentException.class, () -> sessionManager.compareAndSetCurrentHeadEventId(null, "ev_1", "ev_2"));
        assertThrows(IllegalArgumentException.class, () -> sessionManager.compareAndSetCurrentHeadEventId(" ", "ev_1", "ev_2"));
        assertThrows(IllegalArgumentException.class, () -> sessionManager.compareAndSetCurrentHeadEventId("se_1", null, "ev_2"));
        assertThrows(IllegalArgumentException.class, () -> sessionManager.compareAndSetCurrentHeadEventId("se_1", " ", "ev_2"));
        assertThrows(IllegalArgumentException.class, () -> sessionManager.compareAndSetCurrentHeadEventId("se_1", "ev_1", null));
        assertThrows(IllegalArgumentException.class, () -> sessionManager.compareAndSetCurrentHeadEventId("se_1", "ev_1", " "));
    }

    private SessionEvent appendEvent(InMemorySessionEventRepository repository, String sessionId, String parentEventId, String userMessage) {
        AssistantStartPayload payload = new AssistantStartPayload();
        payload.setUserMessages(List.of(userMessage));
        SessionEvent event = SessionEvent.newEvent(sessionId, SessionEventType.assistant_start, parentEventId, payload);
        repository.appendEvent(event);
        return event;
    }

    private SessionEvent rawEvent(String sessionId, String eventId, String parentEventId, Payload payload) {
        SessionEvent event = new SessionEvent();
        event.setSessionId(sessionId);
        event.setEventId(eventId);
        event.setEventType(SessionEventType.assistant_start);
        event.setParentEventId(parentEventId);
        event.setPayload(payload);
        event.setCreateTime(LocalDateTime.now());
        return event;
    }

    private static class InMemorySessionRepository implements SessionRepository {

        private final Map<String, Session> sessionById = new HashMap<>();

        @Override
        public Session getSession(String sessionId) {
            return sessionById.get(sessionId);
        }

        @Override
        public boolean compareAndSetCurrentHeadEventId(String sessionId, String expectedHeadEventId, String newHeadEventId) {
            Session session = sessionById.get(sessionId);
            if (session == null) {
                return false;
            }
            if (!expectedHeadEventId.equals(session.getCurrentHeadEventId())) {
                return false;
            }
            session.setCurrentHeadEventId(newHeadEventId);
            return true;
        }

        private void save(Session session) {
            sessionById.put(session.getSessionId(), session);
        }

    }

    private static class InMemorySessionEventRepository implements SessionEventRepository {

        private final List<SessionEvent> events = new ArrayList<>();
        private int listCalls;

        @Override
        public List<SessionEvent> listBySessionId(String sessionId) {
            listCalls++;
            return events.stream()
                .filter(event -> sessionId.equals(event.getSessionId()))
                .toList();
        }

        @Override
        public void appendEvent(SessionEvent event) {
            events.add(event);
        }

    }

}
