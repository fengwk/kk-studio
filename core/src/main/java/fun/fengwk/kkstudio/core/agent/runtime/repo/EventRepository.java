package fun.fengwk.kkstudio.core.agent.runtime.repo;

import fun.fengwk.kkstudio.core.agent.runtime.event.ev.Event;

import java.util.List;

/**
 * @author fengwk
 */
public interface EventRepository {

    String generateEventId();

    void append(Event event);

    Event get(String eventId);

    List<Event> listTree(String treeId);

}
