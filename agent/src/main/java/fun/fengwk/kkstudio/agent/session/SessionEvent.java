package fun.fengwk.kkstudio.agent.session;

import fun.fengwk.kkstudio.agent.session.payload.Payload;
import fun.fengwk.kkstudio.agent.util.IdGenerator;
import java.time.LocalDateTime;
import lombok.Data;

import fun.fengwk.kkstudio.agent.session.payload.Payload;
import fun.fengwk.kkstudio.agent.util.IdGenerator;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * SessionEvent 是 session tree 中的持久化事件。
 *
 * <p>语义说明： - event 表示一条可恢复、可重放的会话事实。 - event 流可投影为 message 上下文。 - event 通过 parentEventId 形成
 * append-only 的 session tree。
 *
 * @author fengwk
 */
@Data
public class SessionEvent {

  public static final String ROOT_EVENT_ID = "root";

  /** 所属 session 的唯一标识。 */
  private String sessionId;

  /** 当前 event 的唯一标识。 */
  private String eventId;

  /** 当前 event 的语义类型。 */
  private SessionEventType eventType;

  /**
   * 父 event 的唯一标识。
   *
   * <p>根事件的 parentEventId 固定为 {@link #ROOT_EVENT_ID}。
   */
  private String parentEventId;

  /** 当前 event 承载的业务负载。 */
  private Payload payload;

  /** 当前 event 的创建时间。 */
  private LocalDateTime createTime;

  public static SessionEvent newEvent(
      String sessionId, SessionEventType eventType, String parentEventId, Payload payload) {
    SessionEventValidator.validateNewEventInput(sessionId, eventType, parentEventId, payload);
    SessionEvent event = new SessionEvent();
    event.setSessionId(sessionId);
    event.setEventId(IdGenerator.newEventId());
    event.setEventType(eventType);
    event.setParentEventId(parentEventId);
    event.setPayload(payload);
    event.setCreateTime(LocalDateTime.now());
    SessionEventValidator.validateCompleteEvent(event);
    return event;
  }
}
