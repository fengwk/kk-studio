package fun.fengwk.kkstudio.core.agent.runtime.event.ev;

import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class ToolCallEndEvent extends Event {

    private String id;
    private String result;

    @Override
    public EventType getEventType() {
        return EventType.tool_call_end;
    }

}
