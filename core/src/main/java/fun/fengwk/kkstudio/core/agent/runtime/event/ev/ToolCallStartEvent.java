package fun.fengwk.kkstudio.core.agent.runtime.event.ev;

import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class ToolCallStartEvent extends Event {

    /** 模型生成的 tool call id。 */
    private String id;

    /** 工具名称。 */
    private String name;

    /** 工具调用参数 JSON。 */
    private String arguments;

    @Override
    public EventType getEventType() {
        return EventType.tool_call_start;
    }

}
