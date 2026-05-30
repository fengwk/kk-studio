package fun.fengwk.kkstudio.core.agent.runtime.event.ev;

import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class ErrorEvent extends Event {

    private String errorMessage;

    @Override
    public EventType getEventType() {
        return EventType.error;
    }

}
