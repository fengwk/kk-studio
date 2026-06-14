package fun.fengwk.kkstudio.core.agent.runtime.event.ev;

import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class ToolCallEndEvent extends Event {

    /** tool call id。 */
    private String id;

    /** 工具完整执行结果，会被转成 ToolExecutionResultMessage 回灌给模型。 */
    private String result;

    /** 工具执行结果是否为错误。 */
    private boolean error;

    @Override
    public EventType getEventType() {
        return EventType.tool_call_end;
    }

}
