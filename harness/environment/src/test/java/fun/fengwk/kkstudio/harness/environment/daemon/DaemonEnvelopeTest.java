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
        new DaemonEnvelope(DaemonProtocol.VERSION, DaemonMessageType.INVOKE, ID, "invocation", "{}")
            .environmentId());
    assertThrows(
        NullPointerException.class,
        () ->
            new DaemonEnvelope(
                DaemonProtocol.VERSION, DaemonMessageType.INVOKE, null, "invocation", "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonEnvelope(DaemonProtocol.VERSION, DaemonMessageType.INVOKE, ID, " ", "{}"));
  }

  /** HELLO scope 必须为 null；READY/HEARTBEAT 等 connection 消息必须非空。 */
  @Test
  void connectionMessagesFollowScopeContract() {
    assertNull(
        new DaemonEnvelope(DaemonProtocol.VERSION, DaemonMessageType.HELLO, null, null, "{}")
            .environmentId());
    assertEquals(
        ID,
        new DaemonEnvelope(DaemonProtocol.VERSION, DaemonMessageType.READY, ID, null, "{}")
            .environmentId());
    assertThrows(
        NullPointerException.class,
        () ->
            new DaemonEnvelope(DaemonProtocol.VERSION, DaemonMessageType.READY, null, null, "{}"));
    assertThrows(
        NullPointerException.class,
        () ->
            new DaemonEnvelope(
                DaemonProtocol.VERSION, DaemonMessageType.HEARTBEAT, null, null, "{}"));
    // 握手前的 ERROR 允许空 scope。
    assertNull(
        new DaemonEnvelope(DaemonProtocol.VERSION, DaemonMessageType.ERROR, null, null, "{}")
            .environmentId());
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonEnvelope(DaemonProtocol.VERSION, DaemonMessageType.HELLO, ID, null, "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonEnvelope(DaemonProtocol.VERSION, DaemonMessageType.READY, ID, "   ", "{}"));
  }

  /** 只有当前协议版本可以构造；其他版本一律拒绝，不做版本兼容。 */
  @Test
  void rejectsAnyProtocolVersionOtherThanCurrent() {
    assertEquals(
        1,
        new DaemonEnvelope(DaemonProtocol.VERSION, DaemonMessageType.READY, ID, null, "{}")
            .protocolVersion());
    for (int version : new int[] {0, 2, 3}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new DaemonEnvelope(version, DaemonMessageType.READY, ID, null, "{}"));
    }
  }
}
