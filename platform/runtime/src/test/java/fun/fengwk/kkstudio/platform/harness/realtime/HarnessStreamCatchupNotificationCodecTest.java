package fun.fengwk.kkstudio.platform.harness.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.UUID;

/** 锁定跨节点追帧 envelope 的 canonical 编码与严格解码边界。 */
class HarnessStreamCatchupNotificationCodecTest {

  private static final UUID ORIGIN = new UUID(0L, 900L);
  private static final UUID THREAD = new UUID(0L, 1L);
  private static final UUID INVOCATION = new UUID(0L, 2L);

  private final HarnessStreamCatchupNotificationCodec codec =
      new HarnessStreamCatchupNotificationCodec();

  @Test
  void shouldRoundTripCanonicalEnvelope() {
    HarnessStreamCatchupNotificationCodec.Envelope envelope =
        new HarnessStreamCatchupNotificationCodec.Envelope(ORIGIN, THREAD, INVOCATION, 3);

    String payload = codec.encode(envelope);

    assertEquals(
        "{\"originNodeId\":\"00000000-0000-0000-0000-000000000384\","
            + "\"threadId\":\"00000000-0000-0000-0000-000000000001\","
            + "\"invocationId\":\"00000000-0000-0000-0000-000000000002\",\"attempt\":3}",
        payload);
    assertEquals(envelope, codec.decode(payload));
  }

  @Test
  void shouldRejectUnknownMissingDuplicateAndTrailingFields() {
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(validJson().replace("\"attempt\":3", "\"attempt\":3,\"extra\":1")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(validJson().replace(",\"attempt\":3", "")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(validJson().replace("\"attempt\":3", "\"attempt\":3,\"attempt\":3")));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(validJson() + " trailing"));
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(validJson() + "{\"attempt\":3}"));
  }

  @Test
  void shouldRejectNonCanonicalUuidAndNonPositiveOrNonIntegerAttempt() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                validJson()
                    .replace(
                        "00000000-0000-0000-0000-000000000002",
                        "00000000-0000-0000-0000-00000000002")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(validJson().replace("\"attempt\":3", "\"attempt\":0")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(validJson().replace("\"attempt\":3", "\"attempt\":-1")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(validJson().replace("\"attempt\":3", "\"attempt\":\"3\"")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(validJson().replace("\"attempt\":3", "\"attempt\":3.5")));
  }

  private static String validJson() {
    return "{\"originNodeId\":\"00000000-0000-0000-0000-000000000384\","
        + "\"threadId\":\"00000000-0000-0000-0000-000000000001\","
        + "\"invocationId\":\"00000000-0000-0000-0000-000000000002\",\"attempt\":3}";
  }
}
