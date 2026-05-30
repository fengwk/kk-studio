package fun.fengwk.kkstudio.core.agent.runtime.event.ev;

import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class TurnStartEvent extends Event {

    @Override
    public EventType getEventType() {
        return EventType.turn_start;
    }

}
