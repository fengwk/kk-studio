package fun.fengwk.kkstudio.agent.session;

import fun.fengwk.kkstudio.agent.session.payload.AbortPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantDeltaPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantEndPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantErrorPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantStartPayload;
import fun.fengwk.kkstudio.agent.session.payload.Payload;
import fun.fengwk.kkstudio.agent.session.payload.SetAgentInfoPayload;
import fun.fengwk.kkstudio.agent.session.payload.SetModelInfoPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolDeltaPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolEndPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolErrorPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolStartPayload;

/**
 * 统一校验可持久化 SessionEvent 的结构完整性。
 *
 * @author fengwk
 */
public final class SessionEventValidator {

  private SessionEventValidator() {}

  /** 校验创建事件所需的输入字段。 */
  public static void validateNewEventInput(
      String sessionId, SessionEventType eventType, String parentEventId, Payload payload) {
    requireNonBlank(sessionId, "sessionId");
    requireEventType(eventType);
    requireNonBlank(parentEventId, "parentEventId");
    requirePayload(eventType, payload);
  }

  /** 校验即将写入的完整事件。 */
  public static void validateCompleteEvent(SessionEvent event) {
    if (event == null) {
      throw new IllegalArgumentException("event must not be null");
    }
    requireNonBlank(event.getSessionId(), "event.sessionId");
    requireNonBlank(event.getEventId(), "event.eventId");
    if (SessionEvent.ROOT_EVENT_ID.equals(event.getEventId())) {
      throw new IllegalArgumentException("event.eventId must not be root");
    }
    requireEventType(event.getEventType());
    requireNonBlank(event.getParentEventId(), "event.parentEventId");
    if (event.getCreateTime() == null) {
      throw new IllegalArgumentException("event.createTime must not be null");
    }
    requirePayload(event.getEventType(), event.getPayload());
  }

  private static void requireEventType(SessionEventType eventType) {
    if (eventType == null) {
      throw new IllegalArgumentException("eventType must not be null");
    }
  }

  private static void requirePayload(SessionEventType eventType, Payload payload) {
    if (payload == null) {
      throw new IllegalArgumentException("payload must not be null");
    }
    Class<? extends Payload> expectedPayloadType = expectedPayloadType(eventType);
    if (!expectedPayloadType.isInstance(payload)) {
      throw new IllegalArgumentException("payload type does not match eventType: " + eventType);
    }
  }

  private static Class<? extends Payload> expectedPayloadType(SessionEventType eventType) {
    return switch (eventType) {
      case set_agent_info -> SetAgentInfoPayload.class;
      case set_model_info -> SetModelInfoPayload.class;
      case assistant_start -> AssistantStartPayload.class;
      case assistant_delta -> AssistantDeltaPayload.class;
      case assistant_end -> AssistantEndPayload.class;
      case assistant_error -> AssistantErrorPayload.class;
      case tool_start -> ToolStartPayload.class;
      case tool_delta -> ToolDeltaPayload.class;
      case tool_end -> ToolEndPayload.class;
      case tool_error -> ToolErrorPayload.class;
      case abort -> AbortPayload.class;
    };
  }

  private static void requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
