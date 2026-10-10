package fun.fengwk.kkstudio.harness.environment.server.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.terminal.ErrorCode;
import fun.fengwk.kkstudio.harness.environment.terminal.ErrorDisposition;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalCommand;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalControlCodec;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalEvent;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalIdentity;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalRequest;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalResponse;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalRoute;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * {@link ShellTopics} 固定 topic 的严格 codec 契约测试。
 *
 * <p>只验证新增绑定本身：topic 名称/hint、根字段严格性、canonical UUID、嵌套复用 TerminalControlCodec、以及 {@link
 * TerminalDispatch}/{@link TerminalDelivery} 的 {@code toString} 不回显 secret/payload。
 */
class ShellTopicsCodecTest {

  private static final TerminalControlCodec CONTROL_CODEC = new TerminalControlCodec();

  private static final UUID LEASE = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
  private static final UUID APP_NODE = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
  private static final UUID DAEMON = UUID.fromString("cccccccc-0000-0000-0000-000000000003");
  private static final UUID TERMINAL = UUID.fromString("dddddddd-0000-0000-0000-000000000004");
  private static final UUID ENVIRONMENT = UUID.fromString("eeeeeeee-0000-0000-0000-000000000005");
  private static final UUID VIEWER = UUID.fromString("ffffffff-0000-0000-0000-000000000006");
  private static final UUID REQUEST = UUID.fromString("11111111-0000-0000-0000-000000000007");
  private static final UUID STREAM = UUID.fromString("22222222-0000-0000-0000-000000000008");

  private static final TerminalIdentity IDENTITY = new TerminalIdentity(DAEMON, TERMINAL);

  private static TerminalRequest request() {
    return new TerminalRequest(
        new TerminalRoute(APP_NODE, "conn-1"),
        new TerminalCommand(
            REQUEST, ENVIRONMENT, VIEWER, new TerminalCommand.Detach(IDENTITY, STREAM)));
  }

  private static TerminalResponse response() {
    return new TerminalResponse(
        new TerminalRoute(APP_NODE, "conn-1"),
        new TerminalEvent(
            REQUEST,
            ENVIRONMENT,
            VIEWER,
            IDENTITY,
            new TerminalEvent.ErrorPayload(
                ErrorCode.DAEMON_MISMATCH, ErrorDisposition.NOT_EXECUTED)));
  }

  private static String dispatchJson(String leaseToken, String requestJson) {
    return "{\"leaseToken\":\"" + leaseToken + "\",\"request\":" + requestJson + "}";
  }

  private static String deliveryJson(
      String ownerNodeId, String leaseToken, String daemonInstanceId, String responseJson) {
    return "{\"ownerNodeId\":\""
        + ownerNodeId
        + "\",\"leaseToken\":\""
        + leaseToken
        + "\",\"daemonInstanceId\":\""
        + daemonInstanceId
        + "\",\"response\":"
        + responseJson
        + "}";
  }

  private static String canonicalDispatchJson() {
    return dispatchJson(LEASE.toString(), CONTROL_CODEC.encodeRequest(request()));
  }

  private static String canonicalDeliveryJson() {
    return deliveryJson(
        APP_NODE.toString(),
        LEASE.toString(),
        DAEMON.toString(),
        CONTROL_CODEC.encodeResponse(response()));
  }

  private static String deliveryWithNullDaemonJson(String responseJson) {
    return "{\"ownerNodeId\":\""
        + APP_NODE
        + "\",\"leaseToken\":\""
        + LEASE
        + "\",\"daemonInstanceId\":null,\"response\":"
        + responseJson
        + "}";
  }

  private static TerminalResponse unauthenticatedNotExecutedResponse() {
    return new TerminalResponse(
        new TerminalRoute(APP_NODE, "conn-1"),
        new TerminalEvent(
            REQUEST,
            ENVIRONMENT,
            VIEWER,
            null,
            new TerminalEvent.ErrorPayload(
                ErrorCode.ROUTE_UNAVAILABLE, ErrorDisposition.NOT_EXECUTED)));
  }

  @Test
  void commandTopicIsFixedAndNotHint() {
    assertEquals("shell.command", ShellTopics.COMMAND.name());
    assertFalse(ShellTopics.COMMAND.hint());
  }

  @Test
  void eventTopicIsFixedAndNotHint() {
    assertEquals("shell.event", ShellTopics.EVENT.name());
    assertFalse(ShellTopics.EVENT.hint());
  }

  @Test
  void dispatchRoundTripsCanonicalJson() {
    TerminalDispatch dispatch = new TerminalDispatch(LEASE, request());
    byte[] encoded = ShellTopics.COMMAND.codec().encode(dispatch);
    assertEquals(canonicalDispatchJson(), new String(encoded, StandardCharsets.UTF_8));

    TerminalDispatch decoded = ShellTopics.COMMAND.codec().decode(encoded);
    assertEquals(dispatch, decoded);
  }

  @Test
  void deliveryRoundTripsCanonicalJson() {
    TerminalDelivery delivery = new TerminalDelivery(APP_NODE, LEASE, DAEMON, response());
    byte[] encoded = ShellTopics.EVENT.codec().encode(delivery);
    assertEquals(canonicalDeliveryJson(), new String(encoded, StandardCharsets.UTF_8));

    TerminalDelivery decoded = ShellTopics.EVENT.codec().decode(encoded);
    assertEquals(delivery, decoded);
  }

  @Test
  void deliveryAllowsNullDaemonInstanceOnlyForUnauthenticatedNotExecuted() {
    TerminalResponse unauthenticated = unauthenticatedNotExecutedResponse();
    TerminalDelivery delivery = new TerminalDelivery(APP_NODE, LEASE, null, unauthenticated);
    assertTrue(delivery.unauthenticatedNotExecuted());

    // daemonInstanceId 字段必须显式写出且为 JSON null，绝不省略。
    byte[] encoded = ShellTopics.EVENT.codec().encode(delivery);
    String json = new String(encoded, StandardCharsets.UTF_8);
    assertEquals(deliveryWithNullDaemonJson(CONTROL_CODEC.encodeResponse(unauthenticated)), json);
    assertEquals(delivery, ShellTopics.EVENT.codec().decode(encoded));
  }

  @Test
  void deliveryRejectsNullDaemonInstanceOutsideTheSingleAllowedCase() {
    TerminalResponse attached = response();
    TerminalResponse errorUnknown =
        new TerminalResponse(
            new TerminalRoute(APP_NODE, "conn-1"),
            new TerminalEvent(
                REQUEST,
                ENVIRONMENT,
                VIEWER,
                null,
                new TerminalEvent.ErrorPayload(
                    ErrorCode.RUNTIME_FAILED, ErrorDisposition.OUTCOME_UNKNOWN)));
    TerminalResponse notExecutedWithIdentity =
        new TerminalResponse(
            new TerminalRoute(APP_NODE, "conn-1"),
            new TerminalEvent(
                REQUEST,
                ENVIRONMENT,
                VIEWER,
                IDENTITY,
                new TerminalEvent.ErrorPayload(
                    ErrorCode.ROUTE_UNAVAILABLE, ErrorDisposition.NOT_EXECUTED)));

    // 模型层：非「NOT_EXECUTED 且无 identity 的 ERROR」一律拒绝。
    for (TerminalResponse response :
        new TerminalResponse[] {attached, errorUnknown, notExecutedWithIdentity}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new TerminalDelivery(APP_NODE, LEASE, null, response));
    }

    // codec 层：JSON null 经解码后落到同一个构造判据，同样拒绝。
    for (TerminalResponse response :
        new TerminalResponse[] {attached, errorUnknown, notExecutedWithIdentity}) {
      String json = deliveryWithNullDaemonJson(CONTROL_CODEC.encodeResponse(response));
      assertThrows(
          IllegalArgumentException.class, () -> ShellTopics.EVENT.codec().decode(utf8(json)), json);
    }
  }

  @Test
  void dispatchRejectsInvalidJson() {
    String request = CONTROL_CODEC.encodeRequest(request());
    for (String invalid :
        new String[] {
          dispatchJson(LEASE.toString(), request) + "{}", // 尾随 token
          dispatchJson(LEASE.toString().toUpperCase(), request), // 非 canonical UUID
          "{\"leaseToken\":\"" + LEASE + "\",\"request\":" + request + ",\"extra\":1}", // 未知字段
          "{\"request\":" + request + "}", // 缺 leaseToken
          "{\"leaseToken\":\"" + LEASE + "\"}", // 缺 request
          "{\"leaseToken\":7,\"request\":" + request + "}", // 类型错误
          dispatchJson(LEASE.toString(), "{\"type\":\"DETACH\"}"), // 嵌套 request 非法
          "{\"leaseToken\":\""
              + LEASE
              + "\",\"leaseToken\":\""
              + LEASE
              + "\",\"request\":"
              + request
              + "}",
          "[]",
          dispatchJson(LEASE.toString(), "null"),
          dispatchJson("not-a-uuid", request),
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () -> ShellTopics.COMMAND.codec().decode(utf8(invalid)),
          invalid);
    }
  }

  @Test
  void deliveryRejectsInvalidJson() {
    String response = CONTROL_CODEC.encodeResponse(response());
    for (String invalid :
        new String[] {
          canonicalDeliveryJson() + "[]", // 尾随 token
          deliveryJson(
              APP_NODE.toString().toUpperCase(), LEASE.toString(), DAEMON.toString(), response),
          deliveryJson(APP_NODE.toString(), LEASE.toString(), DAEMON.toString(), response)
              .replace(",\"response\"", ",\"unknown\":1,\"response\""), // 未知字段
          "{\"leaseToken\":\""
              + LEASE
              + "\",\"daemonInstanceId\":\""
              + DAEMON
              + "\",\"response\":"
              + response
              + "}",
          deliveryJson(
              APP_NODE.toString(), LEASE.toString(), DAEMON.toString(), "{}"), // 嵌套 response 非法
          canonicalDeliveryJson()
              .replace("\"ownerNodeId\":", "\"ownerNodeId\":null,\"ownerNodeId\":"),
          canonicalDeliveryJson().replace("\"response\":", "\"response\":null,\"response\":"),
          "[]",
          deliveryJson(APP_NODE.toString(), LEASE.toString(), DAEMON.toString(), "null"),
          deliveryJson("not-a-uuid", LEASE.toString(), DAEMON.toString(), response),
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () -> ShellTopics.EVENT.codec().decode(utf8(invalid)),
          invalid);
    }
  }

  @Test
  void acceptsWhitespaceAndReorderedFields() {
    String command =
        "{ \"request\": "
            + CONTROL_CODEC.encodeRequest(request())
            + ", \"leaseToken\": \""
            + LEASE
            + "\" }\n";
    assertEquals(
        new TerminalDispatch(LEASE, request()), ShellTopics.COMMAND.codec().decode(utf8(command)));
    String event =
        "{ \"response\": "
            + CONTROL_CODEC.encodeResponse(response())
            + ", \"daemonInstanceId\": \""
            + DAEMON
            + "\", \"leaseToken\": \""
            + LEASE
            + "\", \"ownerNodeId\": \""
            + APP_NODE
            + "\" }\n";
    assertEquals(
        new TerminalDelivery(APP_NODE, LEASE, DAEMON, response()),
        ShellTopics.EVENT.codec().decode(utf8(event)));
  }

  @Test
  void decodingFailuresNeverRetainUntrustedPayload() {
    String marker = "private-terminal-input-marker";
    for (String invalid :
        new String[] {
          "{\"leaseToken\":" + marker + "}",
          dispatchJson(marker, CONTROL_CODEC.encodeRequest(request()))
        }) {
      Throwable error =
          assertThrows(
              IllegalArgumentException.class,
              () -> ShellTopics.COMMAND.codec().decode(utf8(invalid)));
      assertSanitized(error, marker);
    }
    for (String invalid :
        new String[] {
          "{\"ownerNodeId\":" + marker + "}",
          deliveryJson(
              marker, LEASE.toString(), DAEMON.toString(), CONTROL_CODEC.encodeResponse(response()))
        }) {
      Throwable error =
          assertThrows(
              IllegalArgumentException.class,
              () -> ShellTopics.EVENT.codec().decode(utf8(invalid)));
      assertSanitized(error, marker);
    }
  }

  private static void assertSanitized(Throwable error, String marker) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      assertFalse(String.valueOf(cause.getMessage()).contains(marker));
    }
  }

  @Test
  void nullCodecArgumentsAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> ShellTopics.COMMAND.codec().encode(null));
    assertThrows(IllegalArgumentException.class, () -> ShellTopics.COMMAND.codec().decode(null));
    assertThrows(IllegalArgumentException.class, () -> ShellTopics.EVENT.codec().encode(null));
    assertThrows(IllegalArgumentException.class, () -> ShellTopics.EVENT.codec().decode(null));
  }

  @Test
  void toStringDoesNotEchoSecretsOrPayload() {
    String dispatchText = new TerminalDispatch(LEASE, request()).toString();
    assertTrue(dispatchText.contains("<redacted>"));
    assertFalse(dispatchText.contains(LEASE.toString()));
    assertFalse(dispatchText.contains("DETACH"));

    String deliveryText = new TerminalDelivery(APP_NODE, LEASE, DAEMON, response()).toString();
    assertTrue(deliveryText.contains("<redacted>"));
    assertFalse(deliveryText.contains(LEASE.toString()));
    assertFalse(deliveryText.contains("DAEMON_MISMATCH"));
  }

  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
