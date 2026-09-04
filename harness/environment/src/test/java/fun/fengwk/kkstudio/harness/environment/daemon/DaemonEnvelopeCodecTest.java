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
            7,
            "{\"request\":\"test\"}");

    String json = codec.encode(envelope);
    DaemonEnvelope decoded = codec.decode(json);

    assertEquals(envelope, decoded);
    assertEquals(ID, decoded.environmentId());
    assertTrue(json.contains("\"environmentId\":\"" + ID_TEXT + "\""));
  }

  /** 未知版本、未知类型、负序号、非对象 payload 和未知字段必须分别在 wire 边界拒绝。 */
  @Test
  void rejectsUnsupportedOrMalformedWireEnvelope() {
    assertProtocolError(
        "{\"protocolVersion\":3,\"messageType\":\"READY\",\"environmentId\":\""
            + ID_TEXT
            + "\",\"sequence\":0,\"payload\":{}}");
    assertProtocolError(
        "{\"protocolVersion\":4,\"messageType\":\"READY\",\"environmentId\":\""
            + ID_TEXT
            + "\",\"sequence\":0,\"payload\":{}}");
    assertProtocolError(
        "{\"protocolVersion\":7,\"messageType\":\"READY\",\"environmentId\":\""
            + ID_TEXT
            + "\",\"sequence\":0,\"payload\":{}}");
    assertProtocolError(
        current("\"messageType\":\"FUTURE\",\"environmentId\":\"" + ID_TEXT + "\",")
            + "\"sequence\":0,\"payload\":{}}");
    assertProtocolError(
        current("\"messageType\":\"READY\",\"environmentId\":\"" + ID_TEXT + "\",")
            + "\"sequence\":-1,\"payload\":{}}");
    assertProtocolError(
        current("\"messageType\":\"READY\",\"environmentId\":\"" + ID_TEXT + "\",")
            + "\"sequence\":0,\"payload\":[]}");
    assertProtocolError(
        current("\"messageType\":\"READY\",\"environmentId\":\"" + ID_TEXT + "\",")
            + "\"sequence\":0,\"payload\":{},\"unexpected\":true}");
  }

  /** READY 等 connection 消息必须携带 canonical UUID scope。 */
  @Test
  void rejectsMissingOrNonCanonicalEnvironmentId() {
    assertProtocolError(current("\"messageType\":\"READY\",\"sequence\":0,\"payload\":{}}"));
    assertProtocolError(
        current("\"messageType\":\"READY\",\"environmentId\":\"not-a-uuid\",")
            + "\"sequence\":0,\"payload\":{}}");
    assertProtocolError(
        current("\"messageType\":\"READY\",\"environmentId\":\"" + ID_TEXT.toUpperCase() + "\",")
            + "\"sequence\":0,\"payload\":{}}");
    assertProtocolError(
        current("\"messageType\":\"READY\",\"environmentId\":7,")
            + "\"sequence\":0,\"payload\":{}}");
  }

  /** HELLO envelope 不得声明 scope；HELLO 上的 environmentId 字段在边界拒绝。 */
  @Test
  void rejectsScopeOnHello() {
    assertProtocolError(
        current("\"messageType\":\"HELLO\",\"environmentId\":\"" + ID_TEXT + "\",")
            + "\"sequence\":0,\"payload\":{}}");
    DaemonEnvelope hello =
        codec.decode(current("\"messageType\":\"HELLO\",\"sequence\":0,\"payload\":{}}"));
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
            + "\",\"sequence\":0,\"payload\":{}}");
    assertProtocolError(
        current("\"messageType\":\"READY\",\"environmentId\":\"" + ID_TEXT + "\",")
            + "\"sequence\":0,\"payload\":{}} trailing");
  }

  /** connection-level（无 invocationId）envelope 可往返；payload helper 与嵌套 JSON 读写可用。 */
  @Test
  void encodesConnectionLevelEnvelopeAndExposesPayloadHelpers() {
    DaemonEnvelope envelope =
        new DaemonEnvelope(
            DaemonProtocol.VERSION, DaemonMessageType.READY, ID, null, 0, "{\"k\":1}");

    String json = codec.encode(envelope);
    assertEquals(envelope, codec.decode(json));
    assertTrue(json.contains("\"payload\":{\"k\":1}"));

    assertEquals(0, codec.createPayload().size());
    assertEquals(1, codec.readPayload(codec.decode(json)).get("k").asInt());
    assertEquals(1, codec.readJson("{\"k\":1}").get("k").asInt());
    assertThrows(DaemonProtocolException.class, () -> codec.readJson("not json"));
  }

  /** 根节点非对象、protocolVersion/sequence 类型错误必须在 wire 边界拒绝。 */
  @Test
  void rejectsNonObjectRootAndWrongTypedNumericFields() {
    assertProtocolError("[]");
    assertProtocolError(
        "{\"protocolVersion\":\""
            + DaemonProtocol.VERSION
            + "\",\"messageType\":\"READY\",\"environmentId\":\""
            + ID_TEXT
            + "\",\"sequence\":0,\"payload\":{}}");
    assertProtocolError(
        current("\"messageType\":\"READY\",\"environmentId\":\"" + ID_TEXT + "\",")
            + "\"sequence\":1.5,\"payload\":{}}");
  }

  private static String current(String fields) {
    return "{\"protocolVersion\":" + DaemonProtocol.VERSION + "," + fields;
  }

  private void assertProtocolError(String json) {
    assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
  }
}
