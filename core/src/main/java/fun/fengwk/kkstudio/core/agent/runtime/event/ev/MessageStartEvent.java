package fun.fengwk.kkstudio.core.agent.runtime.event.ev;

import dev.langchain4j.data.message.UserMessage;
import lombok.Data;

import java.util.List;

/**
 * @author fengwk
 */
@Data
public class MessageStartEvent extends Event {

    // TODO 可以抽象为新的消息数据结构和 langchain4j 解耦
    private List<UserMessage> messageList;

    @Override
    public EventType getEventType() {
        return EventType.message_start;
    }

}
