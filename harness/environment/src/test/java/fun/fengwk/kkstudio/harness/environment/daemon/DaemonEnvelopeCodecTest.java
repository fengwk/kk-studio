package fun.fengwk.kkstudio.harness.environment.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

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

  private static String current(String fields) {
    return "{\"protocolVersion\":" + DaemonProtocol.VERSION + "," + fields;
  }

  private void assertProtocolError(String json) {
    assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
  }
}
