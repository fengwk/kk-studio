package fun.fengwk.kkstudio.core.agent.runtime.event.ev;

import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class ToolCallStartEvent extends Event {

    private String id;
    private String name;
    private String arguments;

    @Override
    public EventType getEventType() {
        return EventType.tool_call_start;
    }

}
