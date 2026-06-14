package fun.fengwk.kkstudio.core.agent.runtime.repo;

import fun.fengwk.kkstudio.core.agent.runtime.event.ev.Event;

/**
 * @author fengwk
 */
public interface EventRepository {

    String generateEventId();

    /**
     * 追加事件。调用方会把该方法和 session head CAS 放在同一事务内执行，实现必须参与当前事务。
     */
    void append(Event event);

    Event get(String eventId);

}
