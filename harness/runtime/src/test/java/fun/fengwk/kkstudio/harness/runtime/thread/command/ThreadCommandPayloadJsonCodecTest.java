package fun.fengwk.kkstudio.harness.runtime.thread.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.tool.EnvironmentId;

import java.util.List;

/**
 * Thread command payload codec: strict canonical shapes, type-free JSON and old-field rejection.
 */
class ThreadCommandPayloadJsonCodecTest {

  private static final String ENV = "123e4567-e89b-12d3-a456-426614174000";
  private static final ModelSelection MODEL =
      new ModelSelection("anthropic", "claude-sonnet", "default");
  private final ThreadCommandPayloadJsonCodec codec = new ThreadCommandPayloadJsonCodec();

  @Test
  void roundTripsAllEightCommandTypes() {
    List<ThreadCommandPayload> payloads =
        List.of(
            new UserMessageCommandPayload(user("hello")),
            new CustomMessageCommandPayload(system("system")),
            new SetAgentCommandPayload("coding"),
            new SetModelCommandPayload(MODEL),
            new SetThinkingLevelCommandPayload("high"),
            new SetActiveToolsCommandPayload(List.of("read", "grep")),
            new SetYoloCommandPayload(true),
            new SetEnvironmentCommandPayload(new EnvironmentId(ENV)),
            new SetEnvironmentCommandPayload(null));

    for (ThreadCommandPayload payload : payloads) {
      assertEquals(payload, codec.decode(payload.type(), codec.encode(payload)));
    }
  }

  @Test
  void encodesCanonicalFieldOrderWithoutTypeDiscriminator() {
    String userJson = codec.encode(new UserMessageCommandPayload(user("hello")));
    assertEquals(
        "{\"message\":{\"role\":\"USER\",\"contents\":[{\"type\":\"text\",\"text\":\"hello\"}]}}",
        userJson);
    assertFalse(userJson.contains("USER_MESSAGE"));

    assertEquals(
        "{\"model\":{\"providerName\":\"anthropic\",\"modelName\":\"claude-sonnet\","
            + "\"variant\":\"default\"}}",
        codec.encode(new SetModelCommandPayload(MODEL)));
    assertEquals("{\"agentName\":\"coding\"}", codec.encode(new SetAgentCommandPayload("coding")));
    assertEquals(
        "{\"thinkingLevel\":\"high\"}", codec.encode(new SetThinkingLevelCommandPayload("high")));
    assertEquals(
        "{\"activeTools\":[\"read\",\"grep\"]}",
        codec.encode(new SetActiveToolsCommandPayload(List.of("read", "grep"))));
    assertEquals("{\"yoloEnabled\":true}", codec.encode(new SetYoloCommandPayload(true)));
    assertEquals(
        "{\"environmentId\":\"" + ENV + "\"}",
        codec.encode(new SetEnvironmentCommandPayload(new EnvironmentId(ENV))));
    assertEquals("{\"environmentId\":null}", codec.encode(new SetEnvironmentCommandPayload(null)));
  }

  @Test
  void roundTripsMultiContentMessages() {
    UserMessageCommandPayload payload =
        new UserMessageCommandPayload(
            new AgentMessage(
                AgentMessageRole.USER,
                List.of(new TextMessageContent("a"), new TextMessageContent("b"))));
    assertEquals(payload, codec.decode(ThreadCommandType.USER_MESSAGE, codec.encode(payload)));
  }

  @Test
  void rejectsDuplicateFieldsAndTrailingTokens() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(ThreadCommandType.SET_AGENT, "{\"agentName\":\"a\",\"agentName\":\"b\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(ThreadCommandType.SET_AGENT, "{\"agentName\":\"a\"} {}"));
  }

  @Test
  void rejectsUnknownMissingAndWrongTypedFields() {
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(ThreadCommandType.SET_AGENT, "{\"agentName\":\"a\",\"extra\":1}"));
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(ThreadCommandType.SET_AGENT, "{}"));
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(ThreadCommandType.SET_AGENT, "[]"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(ThreadCommandType.SET_AGENT, "{\"agentName\":5}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(ThreadCommandType.SET_AGENT, "{\"agentName\":\" a\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(ThreadCommandType.SET_THINKING_LEVEL, "{\"thinkingLevel\":\" \"}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(ThreadCommandType.SET_YOLO, "{\"yoloEnabled\":\"true\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(ThreadCommandType.SET_ACTIVE_TOOLS, "{\"activeTools\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(ThreadCommandType.SET_ACTIVE_TOOLS, "{\"activeTools\":[5]}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(ThreadCommandType.SET_ACTIVE_TOOLS, "{\"activeTools\":[\" read\"]}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(ThreadCommandType.SET_ENVIRONMENT, "{\"environmentId\":5}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(ThreadCommandType.SET_ENVIRONMENT, "{\"environmentId\":\"not-a-uuid\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(ThreadCommandType.USER_MESSAGE, "{\"message\":[]}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(ThreadCommandType.SET_MODEL, "{\"model\":{\"providerName\":\"p\"}}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(ThreadCommandType.SET_MODEL, "{\"model\":[\"p\"]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                ThreadCommandType.SET_MODEL,
                "{\"model\":{\"providerName\":\" p\",\"modelName\":\"m\",\"variant\":\"v\"}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                ThreadCommandType.SET_MODEL,
                "{\"model\":{\"providerName\":\"p\",\"modelName\":\"m\",\"variant\":\"v\",\"extra\":1}}"));
  }

  @Test
  void rejectsOldEnvironmentNameAndWrongTypeDispatch() {
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(ThreadCommandType.SET_ENVIRONMENT, "{\"environmentName\":\"env-1\"}"));
    String userJson = codec.encode(new UserMessageCommandPayload(user("hello")));
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(ThreadCommandType.SET_AGENT, userJson));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(ThreadCommandType.USER_MESSAGE, "{\"agentName\":\"a\"}"));
  }

  @Test
  void rejectsMalformedOrNullJsonAndNullArguments() {
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(ThreadCommandType.SET_AGENT, "{"));
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(ThreadCommandType.SET_AGENT, "null"));
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(ThreadCommandType.SET_AGENT, ""));
    assertThrows(NullPointerException.class, () -> codec.encode(null));
    assertThrows(NullPointerException.class, () -> codec.decode(null, "{}"));
    assertThrows(NullPointerException.class, () -> codec.decode(ThreadCommandType.SET_AGENT, null));
  }

  @Test
  void preservesEnvironmentClearSemantics() {
    SetEnvironmentCommandPayload cleared =
        (SetEnvironmentCommandPayload)
            codec.decode(ThreadCommandType.SET_ENVIRONMENT, "{\"environmentId\":null}");
    assertNull(cleared.environmentId());
    SetEnvironmentCommandPayload bound =
        (SetEnvironmentCommandPayload)
            codec.decode(ThreadCommandType.SET_ENVIRONMENT, "{\"environmentId\":\"" + ENV + "\"}");
    assertEquals(new EnvironmentId(ENV), bound.environmentId());
  }

  private static AgentMessage user(String text) {
    return new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)));
  }

  private static AgentMessage system(String text) {
    return new AgentMessage(AgentMessageRole.SYSTEM, List.of(new TextMessageContent(text)));
  }
}
