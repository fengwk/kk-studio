package fun.fengwk.kkstudio.harness.environment.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.share.notification.NotificationCarrier;
import fun.fengwk.kkstudio.share.notification.NotificationPacket;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** Daemon envelope codec 的协议边界测试。 */
class DaemonEnvelopeCodecTest {

  private static final String ID_TEXT = "123e4567-e89b-12d3-a456-426614174000";
  private static final EnvironmentId ID = EnvironmentId.parse(ID_TEXT);

  private final DaemonEnvelopeCodec codec = new DaemonEnvelopeCodec();

  /** 当前协议版本采用固定 envelope 形状往返；environmentId 编码为 canonical UUID 文本。 */
  @Test
  void encodesAndDecodesSupportedEnvelopes() {
    DaemonEnvelope envelope =
        new DaemonEnvelope(
            DaemonProtocol.VERSION,
            DaemonMessageType.INVOKE,
            ID,
            "invocation",
            "{\"request\":\"test\"}");

    String json = codec.encode(envelope);
    DaemonEnvelope decoded = codec.decode(json);

    assertEquals(envelope, decoded);
    assertEquals(ID, decoded.environmentId());
    assertTrue(json.contains("\"environmentId\":\"" + ID_TEXT + "\""));
    assertTrue(json.contains("\"invocationId\":\"invocation\""));
  }

  /** 未知版本、未知类型、非对象 payload 和未知字段必须分别在 wire 边界拒绝。 */
  @Test
  void rejectsUnsupportedOrMalformedWireEnvelope() {
    // 相邻版本与非当前版本都不可接受：不做版本协商或双解码。
    for (int unsupported : new int[] {DaemonProtocol.VERSION - 1, DaemonProtocol.VERSION + 1}) {
      assertProtocolError(
          "{\"protocolVersion\":"
              + unsupported
              + ",\"messageType\":\"READY\",\"environmentId\":\""
              + ID_TEXT
              + "\",\"payload\":{}}");
    }
    assertProtocolError(
        current("\"messageType\":\"FUTURE\",\"environmentId\":\"" + ID_TEXT + "\",")
            + "\"payload\":{}}");
    assertProtocolError(
        current("\"messageType\":\"READY\",\"environmentId\":\"" + ID_TEXT + "\",")
            + "\"payload\":[]}");
    assertProtocolError(
        current("\"messageType\":\"READY\",\"environmentId\":\"" + ID_TEXT + "\",")
            + "\"payload\":{},\"unexpected\":true}");
    assertProtocolError(current("\"environmentId\":\"" + ID_TEXT + "\",") + "\"payload\":{}}");
    assertProtocolError(
        current("\"messageType\":123,\"environmentId\":\"" + ID_TEXT + "\",") + "\"payload\":{}}");
    assertProtocolError(
        current("\"messageType\":\"   \",\"environmentId\":\"" + ID_TEXT + "\",")
            + "\"payload\":{}}");
    assertProtocolError(
        current(
                "\"messageType\":\"INVOKE\",\"environmentId\":\""
                    + ID_TEXT
                    + "\",\"invocationId\":\"   \",")
            + "\"payload\":{}}");
  }

  /** 已删除的 wire 字段（sequence/ACK）必须按未知字段拒绝，证明不存在兼容解析。 */
  @Test
  void rejectsRemovedEnvelopeFields() {
    assertProtocolError(
        current("\"messageType\":\"READY\",\"environmentId\":\"" + ID_TEXT + "\",")
            + "\"sequence\":0,\"payload\":{}}");
    assertProtocolError(
        "{\"protocolVersion\":"
            + DaemonProtocol.VERSION
            + ",\"messageType\":\"ACK\",\"environmentId\":\""
            + ID_TEXT
            + "\",\"payload\":{}}");
  }

  /** READY 等 connection 消息必须携带 canonical UUID scope。 */
  @Test
  void rejectsMissingOrNonCanonicalEnvironmentId() {
    assertProtocolError(current("\"messageType\":\"READY\",\"payload\":{}}"));
    assertProtocolError(
        current("\"messageType\":\"READY\",\"environmentId\":\"not-a-uuid\",") + "\"payload\":{}}");
    assertProtocolError(
        current("\"messageType\":\"READY\",\"environmentId\":\"" + ID_TEXT.toUpperCase() + "\",")
            + "\"payload\":{}}");
    assertProtocolError(
        current("\"messageType\":\"READY\",\"environmentId\":7,") + "\"payload\":{}}");
  }

  /** HELLO envelope 不得声明 scope；HELLO 上的 environmentId 字段在边界拒绝。 */
  @Test
  void rejectsScopeOnHello() {
    assertProtocolError(
        current("\"messageType\":\"HELLO\",\"environmentId\":\"" + ID_TEXT + "\",")
            + "\"payload\":{}}");
    DaemonEnvelope hello = codec.decode(current("\"messageType\":\"HELLO\",\"payload\":{}}"));
    assertNull(hello.environmentId());
  }

  /** duplicate field 与 trailing token 由共享 ObjectMapper 在 wire 边界拒绝。 */
  @Test
  void rejectsDuplicateFieldsAndTrailingTokens() {
    assertProtocolError(
        "{\"protocolVersion\":"
            + DaemonProtocol.VERSION
            + ",\"protocolVersion\":"
            + DaemonProtocol.VERSION
            + ",\"messageType\":\"READY\","
            + "\"environmentId\":\""
            + ID_TEXT
            + "\",\"payload\":{}}");
    assertProtocolError(
        current("\"messageType\":\"READY\",\"environmentId\":\"" + ID_TEXT + "\",")
            + "\"payload\":{}} trailing");
  }

  /** connection-level（无 invocationId）envelope 可往返；payload helper 与嵌套 JSON 读写可用。 */
  @Test
  void encodesConnectionLevelEnvelopeAndExposesPayloadHelpers() {
    DaemonEnvelope envelope =
        new DaemonEnvelope(DaemonProtocol.VERSION, DaemonMessageType.READY, ID, null, "{\"k\":1}");

    String json = codec.encode(envelope);
    assertEquals(envelope, codec.decode(json));
    assertTrue(json.contains("\"payload\":{\"k\":1}"));
    assertNull(codec.decode(json).invocationId());

    assertEquals(0, codec.createPayload().size());
    assertEquals(1, codec.readPayload(codec.decode(json)).get("k").asInt());
    assertEquals(1, codec.readJson("{\"k\":1}").get("k").asInt());
    assertThrows(DaemonProtocolException.class, () -> codec.readJson("not json"));
  }

  /** 根节点非对象、protocolVersion 类型错误必须在 wire 边界拒绝。 */
  @Test
  void rejectsNonObjectRootAndWrongTypedVersionField() {
    assertProtocolError("[]");
    assertProtocolError(
        "{\"protocolVersion\":\""
            + DaemonProtocol.VERSION
            + "\",\"messageType\":\"READY\",\"environmentId\":\""
            + ID_TEXT
            + "\",\"payload\":{}}");
    assertProtocolError(
        "{\"protocolVersion\":1.5,\"messageType\":\"READY\",\"environmentId\":\""
            + ID_TEXT
            + "\",\"payload\":{}}");
  }

  /** 精确 8 MiB 整包可 encode 并被共享 carrier 接受；+1 byte 同时被 encode 与 carrier 拒绝。 */
  @Test
  void enforcesExactSharedCarrierLimit() {
    String invocationId = "limit";
    int budget = codec.payloadBudget(DaemonMessageType.PROGRESS, invocationId);
    ObjectNode payload = codec.createPayload();
    payload.put("x", "a".repeat(budget - 8));

    String json =
        codec.encode(
            new DaemonEnvelope(
                DaemonProtocol.VERSION,
                DaemonMessageType.PROGRESS,
                ID,
                invocationId,
                payload.toString()));
    assertEquals(DaemonEnvelopeCodec.MAX_ENVELOPE_UTF8_BYTES, ResourceRef.utf8Length(json, "json"));

    UUID publisher = UUID.randomUUID();
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    NotificationPacket packet =
        new NotificationPacket(publisher, null, "daemon.wire", UUID.randomUUID(), bytes);
    assertEquals(DaemonEnvelopeCodec.MAX_ENVELOPE_UTF8_BYTES, packet.byteLength());
    assertEquals(
        NotificationCarrier.count(DaemonEnvelopeCodec.MAX_ENVELOPE_UTF8_BYTES),
        NotificationCarrier.chunk(packet, 0).count());

    byte[] plusOne = Arrays.copyOf(bytes, bytes.length + 1);
    assertThrows(
        IllegalArgumentException.class,
        () -> new NotificationPacket(publisher, null, "daemon.wire", UUID.randomUUID(), plusOne));

    ObjectNode tooBig = codec.createPayload();
    tooBig.put("x", "a".repeat(budget - 7));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.encode(
                new DaemonEnvelope(
                    DaemonProtocol.VERSION,
                    DaemonMessageType.PROGRESS,
                    ID,
                    invocationId,
                    tooBig.toString())));
  }

  /** 多字节/引号/反斜杠/控制字符 invocationId 的预算按实际 encode 精确一致。 */
  @Test
  void budgetAccountsDynamicInvocationIdEscaping() {
    for (String invocationId : new String[] {"中文-\"q\"-\\-😀", "a\"\\\n\tb", "   spaced   "}) {
      int budget = codec.payloadBudget(DaemonMessageType.COMPLETED, invocationId);
      ObjectNode atBudget = codec.createPayload();
      atBudget.put("x", "a".repeat(budget - 8));
      assertEquals(
          DaemonEnvelopeCodec.MAX_ENVELOPE_UTF8_BYTES,
          ResourceRef.utf8Length(
              codec.encode(
                  new DaemonEnvelope(
                      DaemonProtocol.VERSION,
                      DaemonMessageType.COMPLETED,
                      ID,
                      invocationId,
                      atBudget.toString())),
              "json"));

      ObjectNode overBudget = codec.createPayload();
      overBudget.put("x", "a".repeat(budget - 7));
      assertThrows(
          DaemonProtocolException.class,
          () ->
              codec.encode(
                  new DaemonEnvelope(
                      DaemonProtocol.VERSION,
                      DaemonMessageType.COMPLETED,
                      ID,
                      invocationId,
                      overBudget.toString())));
    }
  }

  /** payloadFits 与 payloadBudget 一致，供运行时按真实 invocationId 判断注入。 */
  @Test
  void payloadFitsMatchesBudget() {
    String invocationId = "fits";
    int budget = codec.payloadBudget(DaemonMessageType.PROGRESS, invocationId);
    ObjectNode ok = codec.createPayload();
    ok.put("x", "a".repeat(budget - 8));
    assertTrue(codec.payloadFits(DaemonMessageType.PROGRESS, invocationId, ok));
    ObjectNode tooBig = codec.createPayload();
    tooBig.put("x", "a".repeat(budget - 7));
    assertFalse(codec.payloadFits(DaemonMessageType.PROGRESS, invocationId, tooBig));
  }

  /** 固定 UUID 外壳计数只适用于 scoped 结果，不能用于认证前 HELLO 或无调用心跳。 */
  @Test
  void rejectsBudgetRequestsForNonResultTypes() {
    assertThrows(
        IllegalArgumentException.class, () -> codec.payloadBudget(DaemonMessageType.HELLO, "id"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.payloadFits(DaemonMessageType.HEARTBEAT, "id", codec.createPayload()));
  }

  /** decode 超限、非法 JSON、未知 messageType/字段都固定去敏：不 echo body sentinel，也不留 cause。 */
  @Test
  void decodeRejectsOversizeAndInvalidInputWithoutEchoingBody() {
    String sentinel = "SENTINEL-SECRET-VALUE";
    String oversize =
        "{\"protocolVersion\":1,\"messageType\":\""
            + sentinel
            + "\",\"x\":\""
            + "a".repeat(DaemonEnvelopeCodec.MAX_ENVELOPE_UTF8_BYTES)
            + "\"}";
    assertNoThrowableMessageContains(
        assertThrows(DaemonProtocolException.class, () -> codec.decode(oversize)), sentinel);

    String invalidJson = "{\"protocolVersion\":" + sentinel + "}";
    assertNoThrowableMessageContains(
        assertThrows(DaemonProtocolException.class, () -> codec.decode(invalidJson)), sentinel);

    String unknownType =
        current("\"messageType\":\"" + sentinel + "\",\"environmentId\":\"" + ID_TEXT + "\",")
            + "\"payload\":{}}";
    assertNoThrowableMessageContains(
        assertThrows(DaemonProtocolException.class, () -> codec.decode(unknownType)), sentinel);

    String unknownField =
        current(
                "\"messageType\":\"READY\",\"environmentId\":\""
                    + ID_TEXT
                    + "\",\""
                    + sentinel
                    + "\":true,")
            + "\"payload\":{}}";
    assertNoThrowableMessageContains(
        assertThrows(DaemonProtocolException.class, () -> codec.decode(unknownField)), sentinel);

    // 未配对代理项在解析前被拒绝，不进入 Jackson。
    assertThrows(DaemonProtocolException.class, () -> codec.decode("{\"p\":\"\uD800\"}"));
  }

  private static void assertNoThrowableMessageContains(Throwable error, String sensitive) {
    Throwable current = error;
    while (current != null) {
      String message = current.getMessage();
      assertFalse(message != null && message.contains(sensitive));
      current = current.getCause();
    }
  }

  private static String current(String fields) {
    return "{\"protocolVersion\":" + DaemonProtocol.VERSION + "," + fields;
  }

  private void assertProtocolError(String json) {
    assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
  }
}
