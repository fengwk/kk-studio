package fun.fengwk.kkstudio.harness.runtime.thread.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;

import java.util.ArrayList;
import java.util.List;

/** Typed command payload role, field and defensive-copy invariants. */
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
            ThreadCommandType.SET_MODEL,
            ThreadCommandType.SET_THINKING_LEVEL,
            ThreadCommandType.SET_ACTIVE_TOOLS,
            ThreadCommandType.SET_YOLO,
            ThreadCommandType.SET_ENVIRONMENT),
        List.of(
                new UserMessageCommandPayload(user("hello")),
                new CustomMessageCommandPayload(system("system")),
                new SetAgentCommandPayload("coding"),
                new SetModelCommandPayload(MODEL),
                new SetThinkingLevelCommandPayload("high"),
                new SetActiveToolsCommandPayload(List.of("read")),
                new SetYoloCommandPayload(true),
                new SetEnvironmentCommandPayload("workspace-A"))
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

  @Test
  void settingPayloadsEnforceCanonicalValuesAndAtomicModel() {
    assertEquals(MODEL, new SetModelCommandPayload(MODEL).model());
    assertThrows(IllegalArgumentException.class, () -> new SetAgentCommandPayload(" coding"));
    assertThrows(IllegalArgumentException.class, () -> new SetThinkingLevelCommandPayload(" "));
    assertThrows(NullPointerException.class, () -> new SetModelCommandPayload(null));
    assertNull(new SetEnvironmentCommandPayload(null).environmentName());
  }

  @Test
  void classifiesMessageCommandTypes() {
    assertTrue(ThreadCommandType.USER_MESSAGE.isMessage());
    assertTrue(ThreadCommandType.CUSTOM_MESSAGE.isMessage());
    assertFalse(ThreadCommandType.SET_AGENT.isMessage());
  }

  @Test
  void activeToolsAreOrderedDeduplicatedAndDefensivelyCopied() {
    List<String> source = new ArrayList<>(List.of("read", "grep", "read"));
    SetActiveToolsCommandPayload payload = new SetActiveToolsCommandPayload(source);
    source.add("bash");

    assertEquals(List.of("read", "grep"), payload.activeTools());
    assertThrows(UnsupportedOperationException.class, () -> payload.activeTools().add("bash"));
    assertThrows(
        NullPointerException.class, () -> new SetActiveToolsCommandPayload(List.of("read", null)));
    assertThrows(
        IllegalArgumentException.class, () -> new SetActiveToolsCommandPayload(List.of(" read")));
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
