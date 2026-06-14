package fun.fengwk.kkstudio.core.agent.runtime.event.ev;

import lombok.Data;

import java.util.Map;

/**
 * @author fengwk
 */
@Data
public class MessageEndEvent extends Event {

    /** assistant message 结束边界。完整内容由本 message 内的 delta/tool_call_end 事件重建。 */
    private Map<String, Object> attributes;

    @Override
    public EventType getEventType() {
        return EventType.message_end;
    }

}
