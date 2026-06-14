package fun.fengwk.kkstudio.agent.session;

import fun.fengwk.kkstudio.agent.session.payload.AssistantStartPayload;
import fun.fengwk.kkstudio.agent.session.repo.SessionEventRepository;
import fun.fengwk.kkstudio.agent.session.repo.SessionRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author fengwk
 */
public class SessionManagerImplTest {

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

    private SessionEvent appendEvent(InMemorySessionEventRepository repository, String sessionId, String parentEventId, String userMessage) {
        AssistantStartPayload payload = new AssistantStartPayload();
        payload.setUserMessages(List.of(userMessage));
        SessionEvent event = SessionEvent.newEvent(sessionId, SessionEventType.assistant_start, parentEventId, payload);
        repository.appendEvent(event);
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

        @Override
        public List<SessionEvent> listBySessionId(String sessionId) {
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
