package fun.fengwk.kkstudio.core.agent.runtime.event;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * @author fengwk
 */
public interface EventManager {

    EventSessionStore getEventSessionStore();

    EventStore getEventStore();

    /**
     * 从 HEAD eventId 开始向前构建消息列表
     */
    List<ChatMessage> buildMessageList(String headEventId);

}
