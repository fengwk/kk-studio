package fun.fengwk.kkstudio.harness.infra.postgresql;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** PostgreSQL realtime notification envelope 的 strict canonical codec 契约。 */
class RealtimeNotificationCodecTest {

  private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");
  private static final UUID ORIGIN = id(900L);

  private final RealtimeEventJsonCodec eventCodec = new RealtimeEventJsonCodec();
  private final RealtimeNotificationCodec codec = new RealtimeNotificationCodec(eventCodec);

  /** EVENT 必须嵌入完整 canonical RealtimeEvent 与 origin，重复编码稳定且 decode 不损失事件字段。 */
  @Test
  void eventEnvelopeIsCanonicalDeterministicAndRoundTripsFullEvent() {
    RealtimeEvent.ModelDelta event = modelDelta("你好");
    String expected =
        "{\"kind\":\"EVENT\",\"originNodeId\":\""
            + ORIGIN
            + "\",\"event\":"
            + eventCodec.encode(event)
            + "}";

    String encoded = codec.encodeEvent(ORIGIN, event);

    assertEquals(expected, encoded);
    assertEquals(encoded, codec.encodeEvent(ORIGIN, event));
    RealtimeNotificationCodec.Envelope.Event decoded =
        assertInstanceOf(RealtimeNotificationCodec.Envelope.Event.class, codec.decode(encoded));
    assertEquals(ORIGIN, decoded.originNodeId());
    assertEquals(event, decoded.event());
  }

  /** TOOL_PARTIAL 复用同一 EVENT envelope：携带 eventId 的 canonical 事件稳定往返。 */
  @Test
  void toolPartialEnvelopeRoundTripsEventIdentity() {
    RealtimeEvent.ToolPartial event = toolPartial(id(99L));
    String expected =
        "{\"kind\":\"EVENT\",\"originNodeId\":\""
            + ORIGIN
            + "\",\"event\":"
            + eventCodec.encode(event)
            + "}";

    String encoded = codec.encodeEvent(ORIGIN, event);

    assertEquals(expected, encoded);
    RealtimeNotificationCodec.Envelope.Event decoded =
        assertInstanceOf(RealtimeNotificationCodec.Envelope.Event.class, codec.decode(encoded));
    assertEquals(event, decoded.event());
    assertEquals(id(99L), ((RealtimeEvent.ToolPartial) decoded.event()).eventId());
  }

  /** RESYNC 携带 origin、canonical threadId 与非空 reason，保持固定字段顺序。 */
  @Test
  void resyncEnvelopeIsCanonicalAndRoundTrips() {
    String encoded = codec.encodeResync(ORIGIN, id(7L), "EVENT_TOO_LARGE");

    assertEquals(
        "{\"kind\":\"RESYNC\",\"originNodeId\":\""
            + ORIGIN
            + "\",\"threadId\":\"00000000-0000-0000-0000-000000000007\","
            + "\"reason\":\"EVENT_TOO_LARGE\"}",
        encoded);
    RealtimeNotificationCodec.Envelope.Resync decoded =
        assertInstanceOf(RealtimeNotificationCodec.Envelope.Resync.class, codec.decode(encoded));
    assertEquals(ORIGIN, decoded.originNodeId());
    assertEquals(id(7L), decoded.threadId());
    assertEquals("EVENT_TOO_LARGE", decoded.reason());
  }

  /** REFERENCE 只携带 origin/Thread/payloadId，固定顺序且不包含事件内容或 URL。 */
  @Test
  void referenceEnvelopeIsCanonicalAndRoundTrips() {
    UUID payloadId = id(77L);
    String encoded = codec.encodeReference(ORIGIN, id(7L), payloadId);

    assertEquals(
        "{\"kind\":\"REFERENCE\",\"originNodeId\":\""
            + ORIGIN
            + "\",\"threadId\":\"00000000-0000-0000-0000-000000000007\","
            + "\"payloadId\":\""
            + payloadId
            + "\"}",
        encoded);
    RealtimeNotificationCodec.Envelope.Reference decoded =
        assertInstanceOf(RealtimeNotificationCodec.Envelope.Reference.class, codec.decode(encoded));
    assertEquals(ORIGIN, decoded.originNodeId());
    assertEquals(id(7L), decoded.threadId());
    assertEquals(payloadId, decoded.payloadId());
  }

  /** 回拉的 canonical event 必须严格 canonical；非 canonical 文本与额外空白都拒绝。 */
  @Test
  void fetchedEventPayloadMustBeCanonical() {
    RealtimeEvent event = toolPartial(id(99L));
    String canonical = eventCodec.encode(event);

    assertEquals(event, codec.decodeEvent(canonical));
    assertThrows(IllegalArgumentException.class, () -> codec.decodeEvent(" " + canonical));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decodeEvent(canonical.replace("\"attempt\":2", "\"attempt\":2.0")));
  }

  /** malformed、unknown、重复字段、额外字段、缺失 origin 与非 canonical 空白都必须拒绝。 */
  @Test
  void rejectsMalformedUnknownAndNonCanonicalPayloads() {
    String event = codec.encodeEvent(ORIGIN, modelDelta("x"));

    assertThrows(IllegalArgumentException.class, () -> codec.decode("{not-json"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"kind\":\"UNKNOWN\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode("{\"kind\":\"EVENT\",\"event\":\"not-an-object\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"kind\":\"EVENT\",\"originNodeId\":\"" + ORIGIN + "\"," + "\"event\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode("{\"kind\":\"RESYNC\",\"threadId\":\"" + id(1L) + "\",\"reason\":\"x\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"kind\":\"RESYNC\",\"originNodeId\":\""
                    + ORIGIN
                    + "\","
                    + "\"threadId\":\""
                    + id(1L)
                    + "\",\"reason\":\"x\",\"extra\":1}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"kind\":\"RESYNC\",\"originNodeId\":\""
                    + ORIGIN
                    + "\","
                    + "\"threadId\":\"not-a-uuid\",\"reason\":\"x\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"kind\":\"RESYNC\",\"originNodeId\":\""
                    + ORIGIN
                    + "\","
                    + "\"threadId\":"
                    + "\"ABCDEFAB-CDEF-ABCD-EFAB-CDEFABCDEFAB\",\"reason\":\"x\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"kind\":\"RESYNC\",\"originNodeId\":\""
                    + ORIGIN
                    + "\","
                    + "\"threadId\":\""
                    + id(1L)
                    + "\",\"reason\":1}"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(" " + event));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"kind\":\"REFERENCE\",\"originNodeId\":\""
                    + ORIGIN
                    + "\",\"threadId\":\""
                    + id(1L)
                    + "\",\"payloadId\":\"not-a-uuid\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"kind\":\"REFERENCE\",\"originNodeId\":\""
                    + ORIGIN
                    + "\",\"threadId\":\""
                    + id(1L)
                    + "\",\"payloadId\":\""
                    + id(2L)
                    + "\",\"url\":\"http://evil\"}"));
  }

  /** origin 是所有 envelope 的必需字段，缺失或非 canonical UUID 一律拒绝。 */
  @Test
  void rejectsMissingOrNonCanonicalOrigin() {
    String event = eventCodec.encode(modelDelta("x"));

    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode("{\"kind\":\"EVENT\",\"event\":" + event + "}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"kind\":\"EVENT\",\"originNodeId\":\"not-a-uuid\",\"event\":" + event + "}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"kind\":\"RESYNC\",\"originNodeId\":\""
                    + ORIGIN
                    + "\","
                    + "\"threadId\":\""
                    + id(1L)
                    + "\",\"reason\":\" \"}"));
  }

  /** RESYNC reason 必须有实际诊断值，避免生成无法解释的恢复指令。 */
  @Test
  void rejectsBlankResyncReason() {
    assertThrows(IllegalArgumentException.class, () -> codec.encodeResync(ORIGIN, id(1L), " "));
  }

  private static RealtimeEvent.ModelDelta modelDelta(String text) {
    return new RealtimeEvent.ModelDelta(
        id(1L), id(42L), 2, 3L, new ProviderStreamEvent.TextDelta(text), NOW);
  }

  private static RealtimeEvent.ToolPartial toolPartial(UUID eventId) {
    return new RealtimeEvent.ToolPartial(
        id(1L),
        id(42L),
        2,
        eventId,
        new ToolResult("call-1", List.of(new TextResultContent("partial")), false, "{}"),
        NOW);
  }
}
