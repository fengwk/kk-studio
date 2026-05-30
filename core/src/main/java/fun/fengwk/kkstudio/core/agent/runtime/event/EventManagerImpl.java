package fun.fengwk.kkstudio.core.agent.runtime.event;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * @author fengwk
 */
public class EventManagerImpl implements EventManager {

    @Override
    public EventSessionStore getEventSessionStore() {
        return null;
    }

    @Override
    public EventStore getEventStore() {
        return null;
    }

    @Override
    public List<ChatMessage> buildMessageList(String headEventId) {
        return List.of();
    }

}
