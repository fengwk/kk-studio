package fun.fengwk.kkstudio.core.agent.runtime.event.ev;

import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class ToolCallDeltaEvent extends Event {

    private String id;
    private String partialResult;

    @Override
    public EventType getEventType() {
        return EventType.tool_call_delta;
    }

}
