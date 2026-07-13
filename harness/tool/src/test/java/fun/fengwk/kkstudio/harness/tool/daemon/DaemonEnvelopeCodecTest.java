package fun.fengwk.kkstudio.harness.tool.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Daemon v1 codec 的协议边界测试。 */
class DaemonEnvelopeCodecTest {

  private final DaemonEnvelopeCodec codec = new DaemonEnvelopeCodec();

  /** 正常消息必须采用协议约定的 payload 字段并可往返。 */
  @Test
  void encodesAndDecodesVersionOneEnvelope() {
    DaemonEnvelope envelope =
        new DaemonEnvelope(
            DaemonProtocol.VERSION_1,
            DaemonMessageType.INVOKE,
            "workspace",
            "environment",
            "invocation",
            7,
            "{\"toolName\":\"test\"}");

    DaemonEnvelope decoded = codec.decode(codec.encode(envelope));

    assertEquals(envelope, decoded);
  }

  /** 未知版本、类型、负序号或非对象 payload 必须在 wire 边界明确拒绝。 */
  @Test
  void rejectsUnsupportedOrMalformedWireEnvelope() {
    assertProtocolError(
        "{\"protocolVersion\":2,\"messageType\":\"READY\",\"workspaceId\":\"w\","
            + "\"environmentId\":\"e\",\"sequence\":0,\"payload\":{}}");
    assertProtocolError(
        "{\"protocolVersion\":1,\"messageType\":\"FUTURE\",\"workspaceId\":\"w\","
            + "\"environmentId\":\"e\",\"sequence\":0,\"payload\":{}}");
    assertProtocolError(
        "{\"protocolVersion\":1,\"messageType\":\"READY\",\"workspaceId\":\"w\","
            + "\"environmentId\":\"e\",\"sequence\":-1,\"payload\":{}}");
    assertProtocolError(
        "{\"protocolVersion\":1,\"messageType\":\"READY\",\"workspaceId\":\"w\","
            + "\"environmentId\":\"e\",\"sequence\":0,\"payload\":[]}");
  }

  private void assertProtocolError(String json) {
    assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
  }
}
