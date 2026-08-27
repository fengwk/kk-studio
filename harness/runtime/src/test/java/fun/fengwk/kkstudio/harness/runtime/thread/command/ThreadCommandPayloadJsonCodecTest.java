package fun.fengwk.kkstudio.harness.runtime.thread.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.EnvironmentBindings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ImageMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.VideoMessageContent;

import java.util.List;

/** Thread command payload codec：严格 canonical 形态、无类型 JSON 与旧字段拒绝。 */
class ThreadCommandPayloadJsonCodecTest {

  private static final String ENV = "123e4567-e89b-12d3-a456-426614174000";
  private static final ModelSelection MODEL =
      new ModelSelection("anthropic", "claude-sonnet", "default");
  private final ThreadCommandPayloadJsonCodec codec = new ThreadCommandPayloadJsonCodec();

  @Test
  void roundTripsAllSixCommandTypes() {
    List<ThreadCommandPayload> payloads =
        List.of(
            new UserMessageCommandPayload(user("hello")),
            new CustomMessageCommandPayload(system("system")),
            new SetAgentCommandPayload("coding"),
            new SetModelCommandPayload(MODEL),
            new SetEnvironmentCommandPayload(EnvironmentBindings.binding(ENV)),
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
        "{\"environment\":{\"name\":\"" + ENV + "\",\"workspacePath\":\".\"}}",
        codec.encode(new SetEnvironmentCommandPayload(EnvironmentBindings.binding(ENV))));
    assertEquals("{\"environment\":null}", codec.encode(new SetEnvironmentCommandPayload(null)));
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
    // 旧 wire 的 name-only 字段（environmentName）不再是合法输入，必须拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(ThreadCommandType.SET_ENVIRONMENT, "{\"environmentName\":5}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                ThreadCommandType.SET_ENVIRONMENT, "{\"environmentName\":\"Not-A-Name\"}"));
    // 新 wire：binding 对象必须恰好 {name, workspacePath} 且两字段都严格校验。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                ThreadCommandType.SET_ENVIRONMENT, "{\"environment\":{\"name\":\"" + ENV + "\"}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                ThreadCommandType.SET_ENVIRONMENT,
                "{\"environment\":{\"name\":\"Not-A-Name\",\"workspacePath\":\".\"}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                ThreadCommandType.SET_ENVIRONMENT,
                "{\"environment\":{\"name\":\"" + ENV + "\",\"workspacePath\":\"/abs\"}}"));
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
  void rejectsOldEnvironmentIdFieldAndWrongTypeDispatch() {
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(ThreadCommandType.SET_ENVIRONMENT, "{\"environmentId\":\"env-1\"}"));
    String userJson = codec.encode(new UserMessageCommandPayload(user("hello")));
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(ThreadCommandType.SET_AGENT, userJson));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(ThreadCommandType.USER_MESSAGE, "{\"agentName\":\"a\"}"));
  }

  @Test
  void requestHashUsesCanonicalTypeEnvelopeAndRejectsTransientMedia() {
    // 相同 message shape：USER_MESSAGE 与 CUSTOM_MESSAGE 的类型信封不同 → hash 必须不同。
    AgentMessage hello = user("hello");
    String userHash =
        ThreadCommandPayloadJsonCodec.requestHash(new UserMessageCommandPayload(hello));
    String customHash =
        ThreadCommandPayloadJsonCodec.requestHash(
            new CustomMessageCommandPayload(
                new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hello")))));
    assertFalse(userHash.equals(customHash));
    // 完全相同的 payload → 确定性 hash 相等。
    assertEquals(
        userHash, ThreadCommandPayloadJsonCodec.requestHash(new UserMessageCommandPayload(hello)));
    // CUSTOM_MESSAGE request 走 durable codec：transient media 形式一律拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadCommandPayloadJsonCodec.requestHash(
                new CustomMessageCommandPayload(
                    new AgentMessage(
                        AgentMessageRole.USER,
                        List.of(new VideoMessageContent("video/mp4", "https://example.test/v"))))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.encode(
                new CustomMessageCommandPayload(
                    new AgentMessage(
                        AgentMessageRole.USER,
                        List.of(
                            new ImageMessageContent("image/png", "data:image/png;base64,AA=="))))));
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
            codec.decode(ThreadCommandType.SET_ENVIRONMENT, "{\"environment\":null}");
    assertNull(cleared.environment());
    SetEnvironmentCommandPayload bound =
        (SetEnvironmentCommandPayload)
            codec.decode(
                ThreadCommandType.SET_ENVIRONMENT,
                "{\"environment\":{\"name\":\"" + ENV + "\",\"workspacePath\":\".\"}}");
    assertEquals(EnvironmentBindings.binding(ENV), bound.environment());
  }

  private static AgentMessage user(String text) {
    return new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)));
  }

  private static AgentMessage system(String text) {
    return new AgentMessage(AgentMessageRole.SYSTEM, List.of(new TextMessageContent(text)));
  }
}
