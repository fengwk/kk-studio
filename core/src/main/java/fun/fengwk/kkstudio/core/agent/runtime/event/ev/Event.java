package fun.fengwk.kkstudio.core.agent.runtime.event.ev;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * event 是树状的事件流，{@link fun.fengwk.kkstudio.core.agent.runtime.event.EventSession} 记录一条流的会话分支
 *
 * @author fengwk
 */
@Data
public abstract class Event {

    private String eventId;
    private String parentEventId;
//    private String eventTreeId;
    private LocalDateTime createTime;

    public abstract EventType getEventType();

}
