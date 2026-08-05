package fun.fengwk.kkstudio.harness.tool.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.EnvironmentId;

/** Daemon v2 codec 的协议边界测试。 */
class DaemonEnvelopeCodecTest {

  private static final String ENVIRONMENT_ID = "123e4567-e89b-12d3-a456-426614174000";

  private final DaemonEnvelopeCodec codec = new DaemonEnvelopeCodec();

  /** 正常消息必须采用协议约定的 payload 字段并可往返；environmentId 编码为小写 UUID 文本。 */
  @Test
  void encodesAndDecodesVersionTwoEnvelope() {
    DaemonEnvelope envelope =
        new DaemonEnvelope(
            DaemonProtocol.VERSION_2,
            DaemonMessageType.INVOKE,
            new EnvironmentId(ENVIRONMENT_ID),
            "environment",
            "invocation",
            7,
            "{\"toolName\":\"test\"}");

    String json = codec.encode(envelope);
    DaemonEnvelope decoded = codec.decode(json);

    assertEquals(envelope, decoded);
    assertEquals(ENVIRONMENT_ID, decoded.environmentId().value());
    assertTrue(json.contains("\"environmentId\":\"" + ENVIRONMENT_ID + "\""));
    assertTrue(json.indexOf("environmentId") < json.indexOf("environmentName"));
  }

  /** 未知版本（含 v1）、类型、负序号或非对象 payload 必须在 wire 边界明确拒绝。 */
  @Test
  void rejectsUnsupportedOrMalformedWireEnvelope() {
    assertProtocolError(
        "{\"protocolVersion\":1,\"messageType\":\"READY\",\"environmentId\":\""
            + ENVIRONMENT_ID
            + "\",\"environmentName\":\"e\",\"sequence\":0,\"payload\":{}}");
    assertProtocolError(
        "{\"protocolVersion\":3,\"messageType\":\"READY\",\"environmentId\":\""
            + ENVIRONMENT_ID
            + "\",\"environmentName\":\"e\",\"sequence\":0,\"payload\":{}}");
    assertProtocolError(
        "{\"protocolVersion\":2,\"messageType\":\"FUTURE\",\"environmentId\":\""
            + ENVIRONMENT_ID
            + "\",\"environmentName\":\"e\",\"sequence\":0,\"payload\":{}}");
    assertProtocolError(
        "{\"protocolVersion\":2,\"messageType\":\"READY\",\"environmentId\":\""
            + ENVIRONMENT_ID
            + "\",\"environmentName\":\"e\",\"sequence\":-1,\"payload\":{}}");
    assertProtocolError(
        "{\"protocolVersion\":2,\"messageType\":\"READY\",\"environmentId\":\""
            + ENVIRONMENT_ID
            + "\",\"environmentName\":\"e\",\"sequence\":0,\"payload\":[]}");
    assertProtocolError(
        "{\"protocolVersion\":2,\"messageType\":\"READY\",\"environmentId\":\""
            + ENVIRONMENT_ID
            + "\",\"environmentName\":\"e\",\"sequence\":0,\"payload\":{},\"unexpected\":true}");
  }

  /** environmentId 必须存在且为 canonical 小写 UUID；缺失字段与非法文本都拒绝。 */
  @Test
  void rejectsMissingOrNonCanonicalEnvironmentId() {
    assertProtocolError(
        "{\"protocolVersion\":2,\"messageType\":\"READY\",\"environmentName\":\"e\","
            + "\"sequence\":0,\"payload\":{}}");
    assertProtocolError(
        "{\"protocolVersion\":2,\"messageType\":\"READY\",\"environmentId\":\"not-a-uuid\","
            + "\"environmentName\":\"e\",\"sequence\":0,\"payload\":{}}");
    assertProtocolError(
        "{\"protocolVersion\":2,\"messageType\":\"READY\",\"environmentId\":\""
            + ENVIRONMENT_ID.toUpperCase()
            + "\",\"environmentName\":\"e\",\"sequence\":0,\"payload\":{}}");
    assertProtocolError(
        "{\"protocolVersion\":2,\"messageType\":\"READY\",\"environmentId\":7,"
            + "\"environmentName\":\"e\",\"sequence\":0,\"payload\":{}}");
  }

  /** duplicate field 与 trailing token 由共享 ObjectMapper 在 wire 边界拒绝。 */
  @Test
  void rejectsDuplicateFieldsAndTrailingTokens() {
    assertProtocolError(
        "{\"protocolVersion\":2,\"protocolVersion\":2,\"messageType\":\"READY\","
            + "\"environmentId\":\""
            + ENVIRONMENT_ID
            + "\",\"environmentName\":\"e\",\"sequence\":0,\"payload\":{}}");
    assertProtocolError(
        "{\"protocolVersion\":2,\"messageType\":\"READY\",\"environmentId\":\""
            + ENVIRONMENT_ID
            + "\",\"environmentName\":\"e\",\"sequence\":0,\"payload\":{}} trailing");
  }

  /** connection-level（无 invocationId）envelope 可往返；payload helper 与嵌套 JSON 读写可用。 */
  @Test
  void encodesConnectionLevelEnvelopeAndExposesPayloadHelpers() {
    DaemonEnvelope envelope =
        new DaemonEnvelope(
            DaemonProtocol.VERSION_2,
            DaemonMessageType.READY,
            new EnvironmentId(ENVIRONMENT_ID),
            "environment",
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
        "{\"protocolVersion\":\"2\",\"messageType\":\"READY\",\"environmentId\":\""
            + ENVIRONMENT_ID
            + "\",\"environmentName\":\"e\",\"sequence\":0,\"payload\":{}}");
    assertProtocolError(
        "{\"protocolVersion\":2,\"messageType\":\"READY\",\"environmentId\":\""
            + ENVIRONMENT_ID
            + "\",\"environmentName\":\"e\",\"sequence\":1.5,\"payload\":{}}");
  }

  /** environmentName 必须通过 envelope 的 wire 安全校验：control 字符与周边空白在解码边界拒绝。 */
  @Test
  void rejectsUnsafeEnvironmentNameOnDecode() {
    assertProtocolError(
        "{\"protocolVersion\":2,\"messageType\":\"READY\",\"environmentId\":\""
            + ENVIRONMENT_ID
            + "\",\"environmentName\":\"e\\u0000v\",\"sequence\":0,\"payload\":{}}");
    assertProtocolError(
        "{\"protocolVersion\":2,\"messageType\":\"READY\",\"environmentId\":\""
            + ENVIRONMENT_ID
            + "\",\"environmentName\":\" e\",\"sequence\":0,\"payload\":{}}");
  }

  private void assertProtocolError(String json) {
    assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
  }
}
