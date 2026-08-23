package fun.fengwk.kkstudio.harness.runtime.spring.postgresql;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;

import java.time.Instant;

/** PostgreSQL realtime notification envelope 的 strict canonical codec 契约。 */
class RealtimeNotificationCodecTest {

  private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");

  private final RealtimeEventJsonCodec eventCodec = new RealtimeEventJsonCodec();
  private final RealtimeNotificationCodec codec = new RealtimeNotificationCodec(eventCodec);

  /** EVENT 必须嵌入完整 canonical RealtimeEvent，重复编码稳定且 decode 不损失事件字段。 */
  @Test
  void eventEnvelopeIsCanonicalDeterministicAndRoundTripsFullEvent() {
    RealtimeEvent.ModelDelta event = modelDelta("你好");
    String expected = "{\"kind\":\"EVENT\",\"event\":" + eventCodec.encode(event) + "}";

    String encoded = codec.encodeEvent(event);

    assertEquals(expected, encoded);
    assertEquals(encoded, codec.encodeEvent(event));
    RealtimeNotificationCodec.Envelope.Event decoded =
        assertInstanceOf(RealtimeNotificationCodec.Envelope.Event.class, codec.decode(encoded));
    assertEquals(event, decoded.event());
  }

  /** RESYNC 只携带 canonical threadId 与非空 reason，保持固定字段顺序。 */
  @Test
  void resyncEnvelopeIsCanonicalAndRoundTrips() {
    String encoded = codec.encodeResync(id(7L), "EVENT_TOO_LARGE");

    assertEquals(
        "{\"kind\":\"RESYNC\",\"threadId\":\"00000000-0000-0000-0000-000000000007\","
            + "\"reason\":\"EVENT_TOO_LARGE\"}",
        encoded);
    RealtimeNotificationCodec.Envelope.Resync decoded =
        assertInstanceOf(RealtimeNotificationCodec.Envelope.Resync.class, codec.decode(encoded));
    assertEquals(id(7L), decoded.threadId());
    assertEquals("EVENT_TOO_LARGE", decoded.reason());
  }

  /** malformed、unknown、重复字段、额外字段与非 canonical 空白都必须拒绝，避免同一语义出现多种 wire 表示。 */
  @Test
  void rejectsMalformedUnknownAndNonCanonicalPayloads() {
    String event = codec.encodeEvent(modelDelta("x"));

    assertThrows(IllegalArgumentException.class, () -> codec.decode("{not-json"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"kind\":\"UNKNOWN\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode("{\"kind\":\"EVENT\",\"event\":\"not-an-object\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode("{\"kind\":\"RESYNC\",\"kind\":\"RESYNC\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"kind\":\"RESYNC\",\"threadId\":\""
                    + id(1L)
                    + "\",\"reason\":\"x\",\"extra\":1}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode("{\"kind\":\"RESYNC\",\"threadId\":\"not-a-uuid\",\"reason\":\"x\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"kind\":\"RESYNC\",\"threadId\":"
                    + "\"ABCDEFAB-CDEF-ABCD-EFAB-CDEFABCDEFAB\",\"reason\":\"x\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode("{\"kind\":\"RESYNC\",\"threadId\":\"" + id(1L) + "\",\"reason\":1}"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(" " + event));
  }

  /** RESYNC reason 必须有实际诊断值，避免生成无法解释的恢复指令。 */
  @Test
  void rejectsBlankResyncReason() {
    assertThrows(IllegalArgumentException.class, () -> codec.encodeResync(id(1L), " "));
  }

  private static RealtimeEvent.ModelDelta modelDelta(String text) {
    return new RealtimeEvent.ModelDelta(
        id(1L), id(42L), 2, 3L, new ProviderStreamEvent.TextDelta(text), NOW);
  }
}
