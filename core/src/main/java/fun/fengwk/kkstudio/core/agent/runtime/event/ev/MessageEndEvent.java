package fun.fengwk.kkstudio.core.agent.runtime.event.ev;

import fun.fengwk.kkstudio.core.agent.runtime.tool.ToolCallRequest;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * @author fengwk
 */
@Data
public class MessageEndEvent extends Event {

    private String text;
    private String thinking;
    private List<ToolCallRequest> toolCallRequests;
    private Map<String, Object> attributes;

    @Override
    public EventType getEventType() {
        return EventType.message_delta;
    }

}
