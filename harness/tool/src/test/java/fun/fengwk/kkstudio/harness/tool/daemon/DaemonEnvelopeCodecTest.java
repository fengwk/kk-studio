package fun.fengwk.kkstudio.harness.tool.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.EnvironmentName;

import java.util.List;

/** Daemon envelope codec 的协议边界测试。 */
class DaemonEnvelopeCodecTest {

  private final DaemonEnvelopeCodec codec = new DaemonEnvelopeCodec();

  /** 每个受支持版本都必须采用相同 envelope 形状往返；environmentName 编码为 canonical 路由名称文本。 */
  @Test
  void encodesAndDecodesSupportedEnvelopes() {
    for (int version : List.of(DaemonProtocol.VERSION_3, DaemonProtocol.VERSION_4)) {
      DaemonEnvelope envelope =
          new DaemonEnvelope(
              version,
              DaemonMessageType.INVOKE,
              new EnvironmentName("environment"),
              "invocation",
              7,
              "{\"request\":\"test\"}");

      String json = codec.encode(envelope);
      DaemonEnvelope decoded = codec.decode(json);

      assertEquals(envelope, decoded);
      assertEquals("environment", decoded.environmentName().value());
      assertTrue(json.contains("\"environmentName\":\"environment\""));
    }
  }

  /** 未知版本、未知类型、负序号、非对象 payload 和未知字段必须分别在 wire 边界拒绝。 */
  @Test
  void rejectsUnsupportedOrMalformedWireEnvelope() {
    assertProtocolError(
        "{\"protocolVersion\":1,\"messageType\":\"READY\",\"environmentName\":\"e\","
            + "\"sequence\":0,\"payload\":{}}");
    assertProtocolError(
        v4("\"messageType\":\"FUTURE\",\"environmentName\":\"e\",")
            + "\"sequence\":0,\"payload\":{}}");
    assertProtocolError(
        v4("\"messageType\":\"READY\",\"environmentName\":\"e\",")
            + "\"sequence\":-1,\"payload\":{}}");
    assertProtocolError(
        v4("\"messageType\":\"READY\",\"environmentName\":\"e\",")
            + "\"sequence\":0,\"payload\":[]}");
    assertProtocolError(
        v4("\"messageType\":\"READY\",\"environmentName\":\"e\",")
            + "\"sequence\":0,\"payload\":{},\"unexpected\":true}");
  }

  /** environmentName 必须存在且为 canonical 有界小写路由名称；缺失、非法文本与非协议字段都拒绝。 */
  @Test
  void rejectsMissingOrNonCanonicalEnvironmentName() {
    assertProtocolError(v4("\"messageType\":\"READY\",\"sequence\":0,\"payload\":{}}"));
    assertProtocolError(
        v4("\"messageType\":\"READY\",\"environmentName\":\"Not-Canonical\",")
            + "\"sequence\":0,\"payload\":{}}");
    assertProtocolError(
        v4("\"messageType\":\"READY\",\"environmentName\":\"e/v\",")
            + "\"sequence\":0,\"payload\":{}}");
    assertProtocolError(
        v4("\"messageType\":\"READY\",\"environmentName\":7,") + "\"sequence\":0,\"payload\":{}}");
    // environmentId 不是任何受支持 envelope 的字段。
    assertProtocolError(
        v4("\"messageType\":\"READY\",\"environmentId\":\"")
            + "123e4567-e89b-12d3-a456-426614174000"
            + "\",\"environmentName\":\"e\",\"sequence\":0,\"payload\":{}}");
  }

  /** duplicate field 与 trailing token 由共享 ObjectMapper 在 wire 边界拒绝。 */
  @Test
  void rejectsDuplicateFieldsAndTrailingTokens() {
    assertProtocolError(
        "{\"protocolVersion\":"
            + DaemonProtocol.VERSION_4
            + ",\"protocolVersion\":"
            + DaemonProtocol.VERSION_4
            + ",\"messageType\":\"READY\","
            + "\"environmentName\":\"e\",\"sequence\":0,\"payload\":{}}");
    assertProtocolError(
        v4("\"messageType\":\"READY\",\"environmentName\":\"e\",")
            + "\"sequence\":0,\"payload\":{}} trailing");
  }

  /** connection-level（无 invocationId）envelope 可往返；payload helper 与嵌套 JSON 读写可用。 */
  @Test
  void encodesConnectionLevelEnvelopeAndExposesPayloadHelpers() {
    DaemonEnvelope envelope =
        new DaemonEnvelope(
            DaemonProtocol.VERSION_3,
            DaemonMessageType.READY,
            new EnvironmentName("environment"),
            null,
            0,
            "{\"k\":1}");

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
            + DaemonProtocol.VERSION_4
            + "\",\"messageType\":\"READY\",\"environmentName\":\"e\","
            + "\"sequence\":0,\"payload\":{}}");
    assertProtocolError(
        v4("\"messageType\":\"READY\",\"environmentName\":\"e\",")
            + "\"sequence\":1.5,\"payload\":{}}");
  }

  /** 非 canonical environmentName（control 字符、周边空白、大写）在解码边界拒绝。 */
  @Test
  void rejectsUnsafeEnvironmentNameOnDecode() {
    assertProtocolError(
        v4("\"messageType\":\"READY\",\"environmentName\":\"e\\u0000v\",")
            + "\"sequence\":0,\"payload\":{}}");
    assertProtocolError(
        v4("\"messageType\":\"READY\",\"environmentName\":\" e\",")
            + "\"sequence\":0,\"payload\":{}}");
  }

  private static String v4(String fields) {
    return "{\"protocolVersion\":" + DaemonProtocol.VERSION_4 + "," + fields;
  }

  private void assertProtocolError(String json) {
    assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
  }
}
