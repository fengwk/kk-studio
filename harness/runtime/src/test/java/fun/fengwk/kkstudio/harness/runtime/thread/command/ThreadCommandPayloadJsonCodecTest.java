package fun.fengwk.kkstudio.harness.runtime.thread.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationKind;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ImageMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.VideoMessageContent;

import java.util.List;
import java.util.UUID;

/** Thread command payload codec：严格 canonical 形态、无类型 JSON 与未知字段拒绝。 */
class ThreadCommandPayloadJsonCodecTest {

  private static final ExecutionSelection EXECUTION =
      new ExecutionSelection(
          "builtin", "{\"modelName\":\"claude-sonnet\",\"variant\":\"default\"}");
  private final ThreadCommandPayloadJsonCodec codec = new ThreadCommandPayloadJsonCodec();

  @Test
  void roundTripsAllFiveCommandTypes() {
    List<ThreadCommandPayload> payloads =
        List.of(
            new UserMessageCommandPayload(user("hello")),
            new CustomMessageCommandPayload(user("custom")),
            new SetAgentCommandPayload("coding"),
            new SetExecutionCommandPayload(EXECUTION),
            new SetEnvironmentCommandPayload("local"),
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
        "{\"execution\":{\"driver\":\"builtin\",\"settings\":{\"modelName\":\"claude-sonnet\",\"variant\":\"default\"}}}",
        codec.encode(new SetExecutionCommandPayload(EXECUTION)));
    assertEquals("{\"agentName\":\"coding\"}", codec.encode(new SetAgentCommandPayload("coding")));
    // SET_ENVIRONMENT 的 environmentName 必须显式存在：null 表示解除环境选择，不能省略。
    assertEquals(
        "{\"environmentName\":\"local\"}", codec.encode(new SetEnvironmentCommandPayload("local")));
    assertEquals(
        "{\"environmentName\":null}", codec.encode(new SetEnvironmentCommandPayload(null)));
  }

  /** 测试意图：SET_ENVIRONMENT 只接受 text 或 null；缺失字段、未知字段与错误类型都必须严格拒绝。 */
  @Test
  void setEnvironmentAcceptsOnlyTextOrNullAndRejectsMalformedPayloads() {
    assertEquals(
        new SetEnvironmentCommandPayload("local"),
        codec.decode(ThreadCommandType.SET_ENVIRONMENT, "{\"environmentName\":\"local\"}"));
    assertEquals(
        new SetEnvironmentCommandPayload(null),
        codec.decode(ThreadCommandType.SET_ENVIRONMENT, "{\"environmentName\":null}"));
    for (String json :
        List.of(
            "{}",
            "{\"environmentName\":5}",
            "{\"environmentName\":[\"a\"]}",
            "{\"environmentName\":{\"name\":\"a\"}}",
            "{\"environmentName\":\" \"}",
            "{\"environmentName\":\" a\"}",
            "{\"environmentName\":\"a/b\"}",
            "{\"environmentName\":\"" + "e".repeat(65) + "\"}",
            "{\"environmentName\":null,\"extra\":1}",
            "{\"environmentName\":null}{\"environmentName\":null}")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> codec.decode(ThreadCommandType.SET_ENVIRONMENT, json),
          "SET_ENVIRONMENT must reject: " + json);
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                ThreadCommandType.SET_ENVIRONMENT,
                "{\"environmentName\":\"a\",\"environmentName\":\"b\"}"));
  }

  /** 测试意图：environmentName 进入 canonical request hash，因此同 id 的语义变更不会被误判为重放。 */
  @Test
  void requestHashIncludesEnvironmentName() {
    String selected =
        ThreadCommandPayloadJsonCodec.requestHash(new SetEnvironmentCommandPayload("a"));
    String other = ThreadCommandPayloadJsonCodec.requestHash(new SetEnvironmentCommandPayload("b"));
    String cleared =
        ThreadCommandPayloadJsonCodec.requestHash(new SetEnvironmentCommandPayload(null));
    assertFalse(selected.equals(other));
    assertFalse(selected.equals(cleared));
    assertEquals(
        selected, ThreadCommandPayloadJsonCodec.requestHash(new SetEnvironmentCommandPayload("a")));
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
        () ->
            codec.decode(
                ThreadCommandType.SET_EXECUTION,
                "{\"execution\":{\"driver\":\"builtin\",\"settings\":{},\"workspacePath\":null}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                ThreadCommandType.SET_AGENT, "{\"agentName\":\"a\",\"workspacePath\":null}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(ThreadCommandType.USER_MESSAGE, "{\"message\":[]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                ThreadCommandType.SET_EXECUTION, "{\"execution\":{\"driver\":\"builtin\"}}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(ThreadCommandType.SET_EXECUTION, "{\"execution\":[\"p\"]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                ThreadCommandType.SET_EXECUTION,
                "{\"execution\":{\"driver\":\" builtin\",\"settings\":{}}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                ThreadCommandType.SET_EXECUTION,
                "{\"execution\":{\"driver\":\"builtin\",\"settings\":{},\"extra\":1}}"));
  }

  @Test
  void rejectsWrongTypeDispatch() {
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

  /** 测试意图：NOTIFICATION command 四字段严格往返，canonical 字段序与历史 payload 同形，仅允许 USER 角色。 */
  @Test
  void roundTripsNotificationCommandWithStrictFieldOrder() {
    NotificationCommandPayload payload =
        new NotificationCommandPayload(
            new UUID(0L, 21L),
            NotificationKind.SUBAGENT_RESULT,
            new UUID(0L, 7L),
            user("child finished"));
    String json = codec.encode(payload);
    assertEquals(
        "{\"notificationId\":\"00000000-0000-0000-0000-000000000015\","
            + "\"kind\":\"SUBAGENT_RESULT\","
            + "\"sourceThreadId\":\"00000000-0000-0000-0000-000000000007\","
            + "\"message\":{\"role\":\"USER\",\"contents\":[{\"type\":\"text\","
            + "\"text\":\"child finished\"}]}}",
        json);
    assertFalse(json.contains("\"type\":\"NOTIFICATION\""));
    assertEquals(ThreadCommandType.NOTIFICATION, payload.type());
    assertEquals(payload, codec.decode(ThreadCommandType.NOTIFICATION, json));
    // request 形态与 durable 形态一致：可信内部入口不经 HTTP 请求编码。
    assertEquals(
        payload, codec.decode(ThreadCommandType.NOTIFICATION, codec.encodeRequest(payload)));
    assertEquals(
        ThreadCommandPayloadJsonCodec.requestHash(payload),
        ThreadCommandPayloadJsonCodec.requestHash(payload));
    assertFalse(
        ThreadCommandPayloadJsonCodec.requestHash(payload)
            .equals(
                ThreadCommandPayloadJsonCodec.requestHash(
                    new NotificationCommandPayload(
                        new UUID(0L, 21L),
                        NotificationKind.TASK_BUDGET,
                        new UUID(0L, 7L),
                        user("child finished")))));
  }

  /** 测试意图：NOTIFICATION 的 message 只允许 USER 角色；非 USER 必须被拒绝。 */
  @Test
  void rejectsNotificationCommandWithNonUserMessageRole() {
    String assistantMessage =
        "{\"role\":\"ASSISTANT\",\"contents\":[{\"type\":\"text\",\"text\":\"x\"}]}";
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                ThreadCommandType.NOTIFICATION, notificationCommandJson(assistantMessage)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new NotificationCommandPayload(
                new UUID(0L, 21L),
                NotificationKind.SUBAGENT_RESULT,
                new UUID(0L, 7L),
                new AgentMessage(
                    AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("x")))));
  }

  /** 测试意图：NOTIFICATION 四字段全必填；缺失、未知、kind 越界与类型错误都必须严格拒绝。 */
  @Test
  void rejectsNotificationCommandMissingUnknownOrWrongTypedFields() {
    String message = "{\"role\":\"USER\",\"contents\":[{\"type\":\"text\",\"text\":\"n\"}]}";
    String id21 = "\"notificationId\":\"00000000-0000-0000-0000-000000000015\"";
    String kind = "\"kind\":\"SUBAGENT_RESULT\"";
    String id7 = "\"sourceThreadId\":\"00000000-0000-0000-0000-000000000007\"";
    String msg = "\"message\":" + message;

    for (String json :
        List.of(
            "{" + kind + "," + id7 + "," + msg + "}",
            "{" + id21 + "," + id7 + "," + msg + "}",
            "{" + id21 + "," + kind + "," + msg + "}",
            "{" + id21 + "," + kind + "," + id7 + "}",
            "{" + id21 + "," + kind + "," + id7 + "," + msg + ",\"extra\":1}",
            "{" + id21 + ",\"kind\":\"BOGUS\"," + id7 + "," + msg + "}",
            "{\"notificationId\":\"not-a-uuid\"," + kind + "," + id7 + "," + msg + "}",
            "{" + id21 + "," + kind + ",\"sourceThreadId\":\"not-a-uuid\"," + msg + "}",
            "{" + id21 + "," + kind + "," + id7 + ",\"message\":5}",
            "{\"notificationId\":5," + kind + "," + id7 + "," + msg + "}",
            "{" + id21 + ",\"kind\":5," + id7 + "," + msg + "}")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> codec.decode(ThreadCommandType.NOTIFICATION, json),
          "NOTIFICATION must reject: " + json);
    }
  }

  private static String notificationCommandJson(String messageJson) {
    return "{\"notificationId\":\"00000000-0000-0000-0000-000000000015\","
        + "\"kind\":\"SUBAGENT_RESULT\","
        + "\"sourceThreadId\":\"00000000-0000-0000-0000-000000000007\","
        + "\"message\":"
        + messageJson
        + "}";
  }

  private static AgentMessage user(String text) {
    return new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)));
  }

  /**
   * 测试意图：SET_EXECUTION 的 settings 是自由 JSON；encode 与 decode 都必须按 BigDecimal 搬运，1e309/1e-324
   * 与高精度小数不得退化为 Infinity / 0 或被改写成字符串，否则 canonical dataJson 与 command request hash 都会漂移。
   */
  @Test
  void preservesExecutionSettingsNumberPrecision() {
    String dataJson = "{\"big\":1E+309,\"exact\":0.1234567890123456789012345678,\"tiny\":1E-324}";
    SetExecutionCommandPayload payload =
        new SetExecutionCommandPayload(new ExecutionSelection("builtin", dataJson));

    String json = codec.encode(payload);

    assertTrue(json.contains("\"big\":1E+309"), json);
    assertTrue(json.contains("\"tiny\":1E-324"), json);
    assertFalse(json.contains("Infinity"), json);
    assertEquals("{\"execution\":{\"driver\":\"builtin\",\"settings\":" + dataJson + "}}", json);

    SetExecutionCommandPayload decoded =
        (SetExecutionCommandPayload) codec.decode(ThreadCommandType.SET_EXECUTION, json);
    assertEquals(payload, decoded);
    assertEquals(json, codec.encode(decoded));
    // request hash 只基于 canonical 请求形态：精度丢失会改变 hash。
    assertEquals(
        ThreadCommandPayloadJsonCodec.requestHash(payload),
        ThreadCommandPayloadJsonCodec.requestHash(decoded));
  }
}
