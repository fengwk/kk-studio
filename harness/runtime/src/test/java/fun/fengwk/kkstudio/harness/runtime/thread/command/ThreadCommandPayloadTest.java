package fun.fengwk.kkstudio.harness.runtime.thread.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;

import java.util.List;

/** 类型化 command payload 的 role、字段与 canonical 值不变量。 */
class ThreadCommandPayloadTest {

  private static final ModelSelection MODEL =
      new ModelSelection("anthropic", "claude-sonnet", "default");

  @Test
  void everyPayloadReportsItsCommandType() {
    assertEquals(
        List.of(
            ThreadCommandType.USER_MESSAGE,
            ThreadCommandType.CUSTOM_MESSAGE,
            ThreadCommandType.SET_AGENT,
            ThreadCommandType.SET_MODEL),
        List.of(
                new UserMessageCommandPayload(user("hello")),
                new CustomMessageCommandPayload(system("system")),
                new SetAgentCommandPayload("coding"),
                new SetModelCommandPayload(MODEL))
            .stream()
            .map(ThreadCommandPayload::type)
            .toList());
  }

  @Test
  void messagePayloadsEnforceRoles() {
    assertEquals(
        AgentMessageRole.USER, new UserMessageCommandPayload(user("hello")).message().role());
    assertEquals(
        AgentMessageRole.SYSTEM,
        new CustomMessageCommandPayload(system("system")).message().role());
    assertEquals(
        AgentMessageRole.USER,
        new CustomMessageCommandPayload(user("custom-user")).message().role());
    assertThrows(
        IllegalArgumentException.class, () -> new UserMessageCommandPayload(assistant("answer")));
    assertThrows(
        IllegalArgumentException.class, () -> new CustomMessageCommandPayload(assistant("answer")));
    assertThrows(NullPointerException.class, () -> new UserMessageCommandPayload(null));
  }

  /** SET_* payload 只接受 canonical 取值；model selection 作为整体原子替换。 */
  @Test
  void settingPayloadsEnforceCanonicalValuesAndAtomicModel() {
    assertEquals(MODEL, new SetModelCommandPayload(MODEL).model());
    assertEquals("coding", new SetAgentCommandPayload("coding").agentName());
    assertThrows(IllegalArgumentException.class, () -> new SetAgentCommandPayload(" coding"));
    assertThrows(IllegalArgumentException.class, () -> new SetAgentCommandPayload(" "));
    assertThrows(NullPointerException.class, () -> new SetModelCommandPayload(null));
  }

  @Test
  void classifiesMessageCommandTypes() {
    assertTrue(ThreadCommandType.USER_MESSAGE.isMessage());
    assertTrue(ThreadCommandType.CUSTOM_MESSAGE.isMessage());
    assertFalse(ThreadCommandType.SET_AGENT.isMessage());
    assertFalse(ThreadCommandType.SET_MODEL.isMessage());
  }

  private static AgentMessage user(String text) {
    return message(AgentMessageRole.USER, text);
  }

  private static AgentMessage system(String text) {
    return message(AgentMessageRole.SYSTEM, text);
  }

  private static AgentMessage assistant(String text) {
    return message(AgentMessageRole.ASSISTANT, text);
  }

  private static AgentMessage message(AgentMessageRole role, String text) {
    return new AgentMessage(role, List.of(new TextMessageContent(text)));
  }
}
