package fun.fengwk.kkstudio.core.agent.runtime.event.ev;

import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class ConfigConfigEvent extends Event {

    @Override
    public EventType getEventType() {
        return EventType.config_change;
    }

}
