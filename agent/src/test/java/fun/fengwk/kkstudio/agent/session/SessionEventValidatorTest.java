package fun.fengwk.kkstudio.agent.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import java.time.LocalDateTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
import java.time.LocalDateTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 校验 Session、Branch 与 SessionEvent 写入前不变量。
 *
 * @author fengwk
 */
public class SessionEventValidatorTest {

  /** 校验新建 Session 的默认 head 与 currentBranch 语义。 */
  @Test
  public void testNewSessionCreatesRootBranch() {
    Session session = Session.newSession();

    assertNotNull(session.getSessionId());
    assertEquals(SessionEvent.ROOT_EVENT_ID, session.getCurrentHeadEventId());
    assertEquals(session.getSessionId(), session.currentBranch().sessionId());
    assertEquals(SessionEvent.ROOT_EVENT_ID, session.currentBranch().headEventId());
  }

  /** 校验 Branch 拒绝空白输入，且 next 只推进 head。 */
  @Test
  public void testBranchValidatesInputAndAdvancesHead() {
    assertThrows(IllegalArgumentException.class, () -> Branch.newBranch(null, "ev_1"));
    assertThrows(IllegalArgumentException.class, () -> Branch.newBranch(" ", "ev_1"));
    assertThrows(IllegalArgumentException.class, () -> Branch.newBranch("se_1", null));
    assertThrows(IllegalArgumentException.class, () -> Branch.newBranch("se_1", " "));

    Branch branch = Branch.newBranch("se_1", SessionEvent.ROOT_EVENT_ID);
    Branch next = branch.next("ev_1");

    assertEquals("se_1", next.sessionId());
    assertEquals("ev_1", next.headEventId());
  }

  /** 校验 newEvent 要求显式 parent 与非空 payload。 */
  @Test
  public void testNewEventRequiresExplicitParentAndPayload() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SessionEvent.newEvent(
                "se_1", SessionEventType.assistant_start, null, new AssistantStartPayload()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SessionEvent.newEvent(
                "se_1", SessionEventType.assistant_start, " ", new AssistantStartPayload()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SessionEvent.newEvent(
                "se_1", SessionEventType.assistant_start, SessionEvent.ROOT_EVENT_ID, null));
  }

  /** 校验所有事件类型都接受各自的 payload 类型。 */
  @ParameterizedTest
  @MethodSource("eventPayloads")
  public void testNewEventAcceptsMatchingPayload(SessionEventType eventType, Payload payload) {
    SessionEvent event =
        SessionEvent.newEvent("se_1", eventType, SessionEvent.ROOT_EVENT_ID, payload);

    assertEquals("se_1", event.getSessionId());
    assertNotNull(event.getEventId());
    assertEquals(eventType, event.getEventType());
    assertEquals(SessionEvent.ROOT_EVENT_ID, event.getParentEventId());
    assertEquals(payload, event.getPayload());
    assertNotNull(event.getCreateTime());
  }

  /** 校验 eventType 与 payload 类型不匹配时会拒绝构造。 */
  @Test
  public void testNewEventRejectsPayloadTypeMismatch() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SessionEvent.newEvent(
                "se_1",
                SessionEventType.assistant_start,
                SessionEvent.ROOT_EVENT_ID,
                new ToolEndPayload()));
  }

  /** 校验完整事件写入校验会拒绝缺失核心字段的对象。 */
  @Test
  public void testValidateCompleteEventRejectsIncompleteEvent() {
    assertThrows(
        IllegalArgumentException.class, () -> SessionEventValidator.validateCompleteEvent(null));
    assertRejectsIncompleteEvent(
        event(
            null,
            "ev_1",
            SessionEventType.assistant_start,
            SessionEvent.ROOT_EVENT_ID,
            new AssistantStartPayload(),
            LocalDateTime.now()));
    assertRejectsIncompleteEvent(
        event(
            "se_1",
            " ",
            SessionEventType.assistant_start,
            SessionEvent.ROOT_EVENT_ID,
            new AssistantStartPayload(),
            LocalDateTime.now()));
    assertRejectsIncompleteEvent(
        event(
            "se_1",
            SessionEvent.ROOT_EVENT_ID,
            SessionEventType.assistant_start,
            SessionEvent.ROOT_EVENT_ID,
            new AssistantStartPayload(),
            LocalDateTime.now()));
    assertRejectsIncompleteEvent(
        event(
            "se_1",
            "ev_1",
            null,
            SessionEvent.ROOT_EVENT_ID,
            new AssistantStartPayload(),
            LocalDateTime.now()));
    assertRejectsIncompleteEvent(
        event(
            "se_1",
            "ev_1",
            SessionEventType.assistant_start,
            null,
            new AssistantStartPayload(),
            LocalDateTime.now()));
    assertRejectsIncompleteEvent(
        event(
            "se_1",
            "ev_1",
            SessionEventType.assistant_start,
            SessionEvent.ROOT_EVENT_ID,
            new AssistantStartPayload(),
            null));
    assertRejectsIncompleteEvent(
        event(
            "se_1",
            "ev_1",
            SessionEventType.assistant_start,
            SessionEvent.ROOT_EVENT_ID,
            null,
            LocalDateTime.now()));
    assertRejectsIncompleteEvent(
        event(
            "se_1",
            "ev_1",
            SessionEventType.assistant_start,
            SessionEvent.ROOT_EVENT_ID,
            new ToolEndPayload(),
            LocalDateTime.now()));
  }

  private static Stream<Arguments> eventPayloads() {
    return Stream.of(
        Arguments.of(SessionEventType.set_agent_info, new SetAgentInfoPayload()),
        Arguments.of(SessionEventType.set_model_info, new SetModelInfoPayload()),
        Arguments.of(SessionEventType.assistant_start, new AssistantStartPayload()),
        Arguments.of(SessionEventType.assistant_delta, new AssistantDeltaPayload()),
        Arguments.of(SessionEventType.assistant_end, new AssistantEndPayload()),
        Arguments.of(SessionEventType.assistant_error, new AssistantErrorPayload()),
        Arguments.of(SessionEventType.tool_start, new ToolStartPayload()),
        Arguments.of(SessionEventType.tool_delta, new ToolDeltaPayload()),
        Arguments.of(SessionEventType.tool_end, new ToolEndPayload()),
        Arguments.of(SessionEventType.tool_error, new ToolErrorPayload()),
        Arguments.of(SessionEventType.abort, new AbortPayload()));
  }

  private void assertRejectsIncompleteEvent(SessionEvent event) {
    assertThrows(
        IllegalArgumentException.class, () -> SessionEventValidator.validateCompleteEvent(event));
  }

  private SessionEvent event(
      String sessionId,
      String eventId,
      SessionEventType eventType,
      String parentEventId,
      Payload payload,
      LocalDateTime createTime) {
    SessionEvent event = new SessionEvent();
    event.setSessionId(sessionId);
    event.setEventId(eventId);
    event.setEventType(eventType);
    event.setParentEventId(parentEventId);
    event.setPayload(payload);
    event.setCreateTime(createTime);
    return event;
  }
}
