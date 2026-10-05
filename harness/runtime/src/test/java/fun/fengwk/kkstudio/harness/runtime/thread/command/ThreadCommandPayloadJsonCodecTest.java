package fun.fengwk.kkstudio.harness.runtime.thread.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
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

  private static final String LEGACY_WORKSPACE_PATH = "123e4567-e89b-12d3-a456-426614174000";
  private static final ModelSelection MODEL =
      new ModelSelection("anthropic", "claude-sonnet", "default");
  private final ThreadCommandPayloadJsonCodec codec = new ThreadCommandPayloadJsonCodec();

  @Test
  void roundTripsAllFiveCommandTypes() {
    List<ThreadCommandPayload> payloads =
        List.of(
            new UserMessageCommandPayload(user("hello")),
            new CustomMessageCommandPayload(user("custom")),
            new SetAgentCommandPayload("coding"),
            new SetModelCommandPayload(MODEL),
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
        "{\"model\":{\"providerName\":\"anthropic\",\"modelName\":\"claude-sonnet\","
            + "\"variant\":\"default\"}}",
        codec.encode(new SetModelCommandPayload(MODEL)));
    assertEquals("{\"agentName\":\"coding\"}", codec.encode(new SetAgentCommandPayload("coding")));
    // SET_ENVIRONMENT 的 environmentName 必须显式存在：null 表示解除环境选择，不能省略。
    assertEquals(
        "{\"environmentName\":\"local\"}", codec.encode(new SetEnvironmentCommandPayload("local")));
    assertEquals(
        "{\"environmentName\":null}", codec.encode(new SetEnvironmentCommandPayload(null)));
  }

  /** 测试意图：SET_ENVIRONMENT 只接受 text 或 null；缺失字段、未知字段与错误类型都必须严格拒绝。 */
  @Test
  void setEnvironmentAcceptsOnlyTextOrNullAndRejectsLegacyShapes() {
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
            "{\"workspacePath\":\"../outside\"}",
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
                ThreadCommandType.SET_MODEL,
                "{\"model\":{\"providerName\":\"p\",\"modelName\":\"m\",\"variant\":\"v\",\"workspacePath\":null}}"));
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

  /** workspacePath 形状已整体删除，对任何 command type 都必须保持拒绝。 */
  @Test
  void rejectsLegacyWorkspacePathPayloadAcrossAllTypes() {
    for (ThreadCommandType type : ThreadCommandType.values()) {
      assertThrows(
          IllegalArgumentException.class,
          () -> codec.decode(type, "{\"workspacePath\":\"" + LEGACY_WORKSPACE_PATH + "\"}"),
          "workspacePath 形状必须对 " + type + " 保持拒绝");
      assertThrows(
          IllegalArgumentException.class,
          () -> codec.decode(type, "{\"workspacePath\":null}"),
          "workspacePath 形状必须对 " + type + " 保持拒绝");
    }
    assertEquals(8, ThreadCommandType.values().length);
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

  /** 测试意图：SET_CONTRIBUTOR_STATE 的 state 四字段严格往返，嵌套 data 不丢字段。 */
  @Test
  void roundTripsSetContributorStateCommandWithoutDroppingDataFields() {
    SetContributorStateCommandPayload payload =
        new SetContributorStateCommandPayload(
            new CustomEntryPayload(
                "com.example.issue",
                "issue-scope",
                2,
                "{\"issueId\":\"i-1\",\"labels\":[\"a\",\"b\"],\"nested\":{\"k\":1}}"));
    String json = codec.encode(payload);
    assertEquals(
        "{\"state\":{\"contributorId\":\"com.example.issue\",\"customType\":\"issue-scope\","
            + "\"schemaVersion\":2,\"data\":{\"issueId\":\"i-1\",\"labels\":[\"a\",\"b\"],"
            + "\"nested\":{\"k\":1}}}}",
        json);
    assertEquals(ThreadCommandType.SET_CONTRIBUTOR_STATE, payload.type());
    SetContributorStateCommandPayload decoded =
        (SetContributorStateCommandPayload)
            codec.decode(ThreadCommandType.SET_CONTRIBUTOR_STATE, json);
    assertEquals(payload, decoded);
    // data 逐字保留（含数组与嵌套对象），重编码与原始 JSON 一致。
    assertEquals(json, codec.encode(decoded));
  }

  /**
   * 测试意图：SET_CONTRIBUTOR_STATE 只接受 {@code
   * {state:{contributorId,customType,schemaVersion,data}}}；缺失、未知、 类型错误、非法标识符与非正 schemaVersion 一律拒绝。
   */
  @Test
  void rejectsSetContributorStateMissingUnknownOrWrongTypedFields() {
    for (String json :
        List.of(
            "{}",
            "{\"state\":5}",
            "{\"state\":[]}",
            "{\"state\":{\"contributorId\":\"c\",\"customType\":\"t\",\"schemaVersion\":1,",
            "{\"state\":{\"customType\":\"t\",\"schemaVersion\":1,\"data\":{}}}",
            "{\"state\":{\"contributorId\":\"c\",\"schemaVersion\":1,\"data\":{}}}",
            "{\"state\":{\"contributorId\":\"c\",\"customType\":\"t\",\"data\":{}}}",
            "{\"state\":{\"contributorId\":\"c\",\"customType\":\"t\",\"schemaVersion\":1}}",
            "{\"state\":{\"contributorId\":\"c\",\"customType\":\"t\",\"schemaVersion\":0,\"data\":{}}}",
            "{\"state\":{\"contributorId\":\"c\",\"customType\":\"t\",\"schemaVersion\":-1,\"data\":{}}}",
            "{\"state\":{\"contributorId\":\"C\",\"customType\":\"t\",\"schemaVersion\":1,\"data\":{}}}",
            "{\"state\":{\"contributorId\":\"c\",\"customType\":\"t\",\"schemaVersion\":1,\"data\":[]}}",
            "{\"state\":{\"contributorId\":\"c\",\"customType\":\"t\",\"schemaVersion\":1,\"data\":{},\"extra\":1}}",
            "{\"state\":{\"contributorId\":\"c\",\"customType\":\"t\",\"schemaVersion\":1,\"data\":{}},\"extra\":1}")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> codec.decode(ThreadCommandType.SET_CONTRIBUTOR_STATE, json),
          "SET_CONTRIBUTOR_STATE must reject: " + json);
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

  /** schemaVersion 必须是整数 token，不能把小数截断为另一个 schema 版本。 */
  @Test
  void rejectsNonIntegralContributorSchemaVersions() {
    for (String version : List.of("1.5", "1.0", "1e0", "\"1\"", "null", "2147483648")) {
      String json =
          "{\"state\":{\"contributorId\":\"project\",\"customType\":\"run\",\"schemaVersion\":"
              + version
              + ",\"data\":{}}}";
      assertThrows(
          IllegalArgumentException.class,
          () -> codec.decode(ThreadCommandType.SET_CONTRIBUTOR_STATE, json));
    }
  }

  private static AgentMessage user(String text) {
    return new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)));
  }
}
