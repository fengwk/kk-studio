package fun.fengwk.kkstudio.core.agent.runtime.event.ev;

import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class ToolCallDeltaEvent extends Event {

    /** tool call id。 */
    private String id;

    /** 工具执行结果增量。 */
    private String partialResult;

    @Override
    public EventType getEventType() {
        return EventType.tool_call_delta;
    }

}
