package fun.fengwk.kkstudio.harness.environment.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

/** DaemonEnvelope environmentId scope 与协议字段契约测试。 */
class DaemonEnvelopeTest {

  private static final EnvironmentId ID =
      EnvironmentId.parse("123e4567-e89b-12d3-a456-426614174000");

  /** 调用消息必须携带非空 scope 与非空 invocationId。 */
  @Test
  void invocationMessagesRequireScopeAndInvocationId() {
    assertEquals(
        ID,
        new DaemonEnvelope(
                DaemonProtocol.VERSION, DaemonMessageType.INVOKE, ID, "invocation", 0, "{}")
            .environmentId());
    assertThrows(
        NullPointerException.class,
        () ->
            new DaemonEnvelope(
                DaemonProtocol.VERSION, DaemonMessageType.INVOKE, null, "invocation", 0, "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonEnvelope(DaemonProtocol.VERSION, DaemonMessageType.INVOKE, ID, " ", 0, "{}"));
  }

  /** HELLO scope 必须为 null；READY/HEARTBEAT 等 connection 消息必须非空。 */
  @Test
  void connectionMessagesFollowScopeContract() {
    assertNull(
        new DaemonEnvelope(DaemonProtocol.VERSION, DaemonMessageType.HELLO, null, null, 0, "{}")
            .environmentId());
    assertEquals(
        ID,
        new DaemonEnvelope(DaemonProtocol.VERSION, DaemonMessageType.READY, ID, null, 0, "{}")
            .environmentId());
    assertThrows(
        NullPointerException.class,
        () ->
            new DaemonEnvelope(
                DaemonProtocol.VERSION, DaemonMessageType.READY, null, null, 0, "{}"));
    assertThrows(
        NullPointerException.class,
        () ->
            new DaemonEnvelope(
                DaemonProtocol.VERSION, DaemonMessageType.HEARTBEAT, null, null, 0, "{}"));
    // 握手前的 ERROR 允许空 scope。
    assertNull(
        new DaemonEnvelope(DaemonProtocol.VERSION, DaemonMessageType.ERROR, null, null, 0, "{}")
            .environmentId());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonEnvelope(DaemonProtocol.VERSION, DaemonMessageType.HELLO, ID, null, 0, "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonEnvelope(
                DaemonProtocol.VERSION, DaemonMessageType.READY, ID, "   ", 0, "{}"));
  }

  /** 其余 envelope 字段的构造期契约：版本、序号规则。 */
  @Test
  void rejectsInvalidProtocolVersionAndSequence() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonEnvelope(0, DaemonMessageType.READY, ID, null, 0, "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonEnvelope(
                DaemonProtocol.VERSION, DaemonMessageType.READY, ID, null, -1, "{}"));
  }
}
