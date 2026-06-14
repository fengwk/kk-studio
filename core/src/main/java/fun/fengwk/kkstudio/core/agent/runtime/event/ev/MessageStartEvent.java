package fun.fengwk.kkstudio.core.agent.runtime.event.ev;

import lombok.Data;

import java.util.List;

/**
 * @author fengwk
 */
@Data
public class MessageStartEvent extends Event {

    /** 本轮 turn 消费的 taskId 列表，用于排查重复消费和多节点竞态。 */
    private List<String> taskIdList;

    /** 本轮新增的用户消息文本。 */
    private List<String> userMessageList;

    @Override
    public EventType getEventType() {
        return EventType.message_start;
    }

}
