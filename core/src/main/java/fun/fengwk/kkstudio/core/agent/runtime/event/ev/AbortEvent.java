package fun.fengwk.kkstudio.core.agent.runtime.event.ev;

import lombok.Data;

/**
 * 用户或上层显式中止当前 turn。
 *
 * @author fengwk
 */
@Data
public class AbortEvent extends Event {

    /** 中止原因。 */
    private String reason;

    @Override
    public EventType getEventType() {
        return EventType.abort;
    }

}
