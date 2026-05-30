package fun.fengwk.kkstudio.core.agent.runtime.event;

import fun.fengwk.kkstudio.core.agent.runtime.event.ev.Event;

/**
 * @author fengwk
 */
public interface EventStore {

    String generateEventId();

    void append(Event event);

    Event get(String eventId);

}
