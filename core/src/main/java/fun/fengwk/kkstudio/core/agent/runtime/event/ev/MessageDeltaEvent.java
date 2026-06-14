package fun.fengwk.kkstudio.core.agent.runtime.event.ev;

import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class MessageDeltaEvent extends Event {

    /** delta 类型：thinking/text/tool_call。 */
    private MessageDeltaType type;

    /** 是否为纠偏快照。为 true 时，重建侧应先清空同类型累计内容再写入 partialText。 */
    private boolean replace;

    /* thinking and text */
    /** thinking 或 text 的增量文本。 */
    private String partialText;

    /* tool call */
    /** 工具调用在本次 assistant message 中的下标。 */
    private Integer index;

    /** 模型生成的 tool call id。 */
    private String id;

    /** 模型生成的 tool name，可能只在部分 delta 中出现。 */
    private String name;

    /** 模型生成的工具参数增量 JSON 片段。 */
    private String partialArguments;

    @Override
    public EventType getEventType() {
        return EventType.message_delta;
    }

}
