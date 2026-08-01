package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.CustomMessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;

import java.util.List;

/** Thread input payloads persist only final message entries and their turn reference. */
class ThreadInputPayloadJsonCodecTest {

  private static final TurnSettings SETTINGS = new TurnSettings("agent", "env", true);
  private final ThreadInputPayloadJsonCodec codec = new ThreadInputPayloadJsonCodec();

  @Test
  void roundTripsUserAndCustomMessagesWithTurnSettings() {
    RuntimeEntryInputPayload user =
        new RuntimeEntryInputPayload(
            ThreadInputType.USER_MESSAGE, new MessageEntryPayload(userMessage(), SETTINGS, null));
    RuntimeEntryInputPayload custom =
        new RuntimeEntryInputPayload(
            ThreadInputType.CUSTOM_MESSAGE,
            new CustomMessageEntryPayload(systemMessage(), SETTINGS));

    assertEquals(user, codec.decode(ThreadInputType.USER_MESSAGE, codec.encode(user)));
    assertEquals(custom, codec.decode(ThreadInputType.CUSTOM_MESSAGE, codec.encode(custom)));
  }

  @Test
  void rejectsWrongPayloadTypeAndMalformedJson() {
    CustomMessageEntryPayload custom = new CustomMessageEntryPayload(systemMessage(), SETTINGS);
    assertThrows(
        IllegalArgumentException.class,
        () -> new RuntimeEntryInputPayload(ThreadInputType.USER_MESSAGE, custom));
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(ThreadInputType.USER_MESSAGE, "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(ThreadInputType.CUSTOM_MESSAGE, "{\"message\":{}}"));
  }

  private static AgentMessage userMessage() {
    return message(AgentMessageRole.USER, "hello");
  }

  private static AgentMessage systemMessage() {
    return message(AgentMessageRole.SYSTEM, "system");
  }

  private static AgentMessage message(AgentMessageRole role, String text) {
    return new AgentMessage(role, List.of(new TextMessageContent(text)));
  }
}
