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

    /** 事件 ID，全局唯一。 */
    private String eventId;

    /** 父事件 ID，形成可 fork/backtrace 的事件树。首个事件允许为空。 */
    private String parentEventId;
//    private String eventTreeId;

    /** 事件所属 session，便于查询、订阅与日志排查。 */
    private String sessionId;

    /** 事件所属 turn。一次模型调用到模型结果返回，如有 tool call 则包含工具执行完成。 */
    private String turnId;

    /** 事件创建时间。 */
    private LocalDateTime createTime;

    public abstract EventType getEventType();

}
