package fun.fengwk.kkstudio.core.agent.runtime.event.ev;

import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class ErrorEvent extends Event {

    /** 失败摘要。详细堆栈通过日志记录，事件中只保存上层可展示/排查的简要信息。 */
    private String errorMessage;

    @Override
    public EventType getEventType() {
        return EventType.error;
    }

}
