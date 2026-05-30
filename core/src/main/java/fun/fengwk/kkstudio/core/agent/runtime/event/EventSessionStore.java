package fun.fengwk.kkstudio.core.agent.runtime.event;

/**
 * @author fengwk
 */
public interface EventSessionStore {

    EventSession newSession(String headEventId);

    EventSession get(String sessionId);

    boolean casHeadEventId(String sessionId, String oldHeadEventId, String newHeadEventId);

}
