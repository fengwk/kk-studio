package fun.fengwk.kkstudio.core.agent.runtime.event.ev;

import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class MessageDeltaEvent extends Event {

    private MessageDeltaType type;

    /* thinking and text */
    private String partialText;

    /* tool call */
    private Integer index;
    private String id;
    private String name;
    private String partialArguments;

    @Override
    public EventType getEventType() {
        return EventType.message_delta;
    }

}
