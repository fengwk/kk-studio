package fun.fengwk.kkstudio.core.agent.runtime.event.ev;

import lombok.Builder;
import lombok.Data;

/**
 * @author fengwk
 */
@Builder
@Data
public class AgentStartEvent extends Event {

    @Override
    public EventType getEventType() {
        return EventType.agent_start;
    }

}
