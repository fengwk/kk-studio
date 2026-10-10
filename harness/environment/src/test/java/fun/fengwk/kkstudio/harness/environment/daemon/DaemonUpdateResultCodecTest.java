package fun.fengwk.kkstudio.harness.environment.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** 意图：受管更新阶段回执 payload 必须严格 —— 固定字段、拒绝未知字段与非规范阶段，message 缺省/null 等价于无说明， 失败的说明有界且不含控制字符。 */
class DaemonUpdateResultCodecTest {

  private static final String OP = "11111111-1111-1111-1111-111111111111";

  private final DaemonUpdateResultCodec codec = new DaemonUpdateResultCodec();

  @Test
  void roundTripsAcceptedAndPreparedWithoutMessage() {
    DaemonUpdateResult accepted = codec.decode(codec.encode(DaemonUpdateResult.accepted(OP)));
    assertEquals(DaemonUpdatePhase.ACCEPTED, accepted.phase());
    assertNull(accepted.message());

    DaemonUpdateResult prepared = codec.decode(codec.encode(DaemonUpdateResult.prepared(OP)));
    assertEquals(DaemonUpdatePhase.PREPARED, prepared.phase());
    assertNull(prepared.message());
  }

  @Test
  void roundTripsFailedWithMessage() {
    DaemonUpdateResult failed =
        codec.decode(codec.encode(DaemonUpdateResult.failed(OP, "checksum mismatch")));
    assertEquals(DaemonUpdatePhase.FAILED, failed.phase());
    assertEquals("checksum mismatch", failed.message());
  }

  @Test
  void rejectsUnknownField() {
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"operationId\":\"" + OP + "\",\"phase\":\"ACCEPTED\",\"extra\":1}"));
  }

  @Test
  void rejectsUnknownPhase() {
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"operationId\":\"" + OP + "\",\"phase\":\"DONE\"}"));
  }

  @Test
  void rejectsBlankOperationId() {
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"operationId\":\"\",\"phase\":\"ACCEPTED\"}"));
  }

  @Test
  void rejectsControlCharactersInMessage() {
    assertThrows(
        IllegalArgumentException.class, () -> DaemonUpdateResult.failed(OP, "bad\u0000message"));
  }

  @Test
  void rejectsOversizedMessage() {
    assertThrows(
        IllegalArgumentException.class,
        () -> DaemonUpdateResult.failed(OP, "x".repeat(DaemonUpdateResult.MAX_MESSAGE_CHARS + 1)));
  }
}
