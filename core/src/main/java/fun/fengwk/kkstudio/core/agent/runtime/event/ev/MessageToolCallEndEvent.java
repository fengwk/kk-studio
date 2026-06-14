package fun.fengwk.kkstudio.core.agent.runtime.event.ev;

import lombok.Data;

/**
 * Assistant message 中模型生成的完整 tool call。
 * <p>
 * 该事件不同于 {@link ToolCallEndEvent}：前者表示模型要求调用工具，后者表示 runtime 已完成工具执行。
 *
 * @author fengwk
 */
@Data
public class MessageToolCallEndEvent extends Event {

    /** 工具调用在本次 assistant message 中的下标。 */
    private Integer index;

    /** 模型生成的 tool call id。 */
    private String id;

    /** 工具名称。 */
    private String name;

    /** 完整工具参数 JSON。 */
    private String arguments;

    @Override
    public EventType getEventType() {
        return EventType.message_tool_call_end;
    }

}
