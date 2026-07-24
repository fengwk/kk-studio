package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.kernel.thread.ThreadInputType;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.CustomMessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** final Input payload codec 必须按 durable type 严格还原最终 payload。 */
class ThreadInputPayloadJsonCodecTest {

  private final ThreadInputPayloadJsonCodec codec = new ThreadInputPayloadJsonCodec();

  @Test
  void roundTripsConfigAndFinalUserEntryPayload() throws Exception {
    RuntimeConfigSnapshot config = new RuntimeConfigJsonCodec().decode(canonicalConfigJson());
    RuntimeConfigInputPayload configPayload =
        new RuntimeConfigInputPayload(ThreadInputType.SET_AGENT, config);
    assertEquals(
        configPayload, codec.decode(ThreadInputType.SET_AGENT, codec.encode(configPayload)));

    RuntimeEntryInputPayload messagePayload =
        new RuntimeEntryInputPayload(
            ThreadInputType.USER_MESSAGE,
            new MessageEntryPayload(
                new AgentMessage(
                    AgentMessageRole.USER,
                    List.<AgentMessageContent>of(new TextMessageContent("hello")))));
    RuntimeEntryInputPayload decoded =
        assertInstanceOf(
            RuntimeEntryInputPayload.class,
            codec.decode(ThreadInputType.USER_MESSAGE, codec.encode(messagePayload)));
    assertEquals(messagePayload, decoded);

    RuntimeEntryInputPayload customPayload =
        new RuntimeEntryInputPayload(
            ThreadInputType.CUSTOM_MESSAGE,
            new CustomMessageEntryPayload(
                new AgentMessage(
                    AgentMessageRole.SYSTEM,
                    List.<AgentMessageContent>of(new TextMessageContent("instruction")))));
    assertEquals(
        customPayload, codec.decode(ThreadInputType.CUSTOM_MESSAGE, codec.encode(customPayload)));
  }

  @Test
  void rejectsDuplicateTrailingAndWrongPayloadType() throws Exception {
    String config = canonicalConfigJson();
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(ThreadInputType.SET_MODEL, config + " {}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                ThreadInputType.SET_YOLO,
                config.replaceFirst("\\\"agent\\\":", "\\\"agent\\\":{},\\\"agent\\\":")));
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(ThreadInputType.USER_MESSAGE, "{}"));
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(ThreadInputType.SET_AGENT, "[]"));
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(ThreadInputType.SET_AGENT, "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(ThreadInputType.CUSTOM_MESSAGE, "{\"message\":1}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RuntimeConfigInputPayload(
                ThreadInputType.USER_MESSAGE, new RuntimeConfigJsonCodec().decode(config)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RuntimeEntryInputPayload(
                ThreadInputType.SET_MODEL,
                new MessageEntryPayload(
                    new AgentMessage(
                        AgentMessageRole.USER,
                        List.<AgentMessageContent>of(new TextMessageContent("bad"))))));
  }

  private static String canonicalConfigJson() throws IOException {
    try (InputStream stream =
        ThreadInputPayloadJsonCodecTest.class.getResourceAsStream(
            "/fun/fengwk/kkstudio/harness/runtime/configuration/runtime-config.json")) {
      if (stream == null) {
        throw new IOException("runtime config fixture is missing");
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
