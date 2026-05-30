package fun.fengwk.kkstudio.core.agent.runtime.event;

import dev.langchain4j.internal.Json;
import fun.fengwk.kkstudio.core.agent.runtime.event.ev.Event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author fengwk
 */
public class EventStoreImpl implements EventStore {

    private final List<String> eventJsonList = Collections.synchronizedList(new ArrayList<>());

    @Override
    public String generateEventId() {
        return "";
    }

    @Override
    public void append(Event event) {
        eventJsonList.add(Json.toJson(event));
    }

    @Override
    public Event get(String eventId) {
        return null;
    }

}
