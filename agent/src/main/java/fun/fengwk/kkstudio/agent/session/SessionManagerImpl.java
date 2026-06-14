package fun.fengwk.kkstudio.agent.session;

import fun.fengwk.kkstudio.agent.session.repo.SessionRepository;
import fun.fengwk.kkstudio.agent.session.repo.SessionEventRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SessionManager 的默认实现。
 *
 * 语义说明：
 * - 实现按 sessionId 装载 event 集合，再投影出 branch event 链。
 * - 实现负责协调 repository 与 branch/event 约束。
 *
 * @author fengwk
 */
public class SessionManagerImpl implements SessionManager {

    private final SessionRepository sessionRepository;
    private final SessionEventRepository sessionEventRepository;

    public SessionManagerImpl(SessionRepository sessionRepository, SessionEventRepository sessionEventRepository) {
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

        List<SessionEvent> sessionEvents = sessionEventRepository.listBySessionId(branch.sessionId());
        if (sessionEvents == null || sessionEvents.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, SessionEvent> eventById = new HashMap<>();
        for (SessionEvent event : sessionEvents) {
            if (event != null) {
                eventById.put(event.getEventId(), event);
            }
        }

        List<SessionEvent> branchEvents = new ArrayList<>();
        SessionEvent current = eventById.get(branch.headEventId());
        while (current != null) {
            branchEvents.add(current);
            if (current.getParentEventId() == null || SessionEvent.ROOT_EVENT_ID.equals(current.getParentEventId())) {
                break;
            }
            current = eventById.get(current.getParentEventId());
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
    public boolean compareAndSetCurrentHeadEventId(String sessionId, String expectedHeadEventId, String newHeadEventId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (expectedHeadEventId == null || expectedHeadEventId.isBlank()) {
            throw new IllegalArgumentException("expectedHeadEventId must not be blank");
        }
        if (newHeadEventId == null || newHeadEventId.isBlank()) {
            throw new IllegalArgumentException("newHeadEventId must not be blank");
        }
        return sessionRepository.compareAndSetCurrentHeadEventId(sessionId, expectedHeadEventId, newHeadEventId);
    }

}
