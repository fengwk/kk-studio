package fun.fengwk.kkstudio.harness.infra.realtime;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.infra.realtime.RealtimeNotificationCodec.Envelope;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
    Envelope.Event envelope = new Envelope.Event(event);

    byte[] encoded = codec.encode(envelope);

    assertEquals(expected, new String(encoded, StandardCharsets.UTF_8));
    assertArrayEquals(encoded, codec.encode(envelope));
    Envelope.Event decoded = assertInstanceOf(Envelope.Event.class, codec.decode(encoded));
    assertEquals(event, decoded.event());
  }

  /** TOOL_PARTIAL 复用同一 EVENT envelope：携带 eventId 的 canonical 事件稳定往返。 */
  @Test
  void toolPartialEnvelopeRoundTripsEventIdentity() {
    RealtimeEvent.ToolPartial event = toolPartial(id(99L));
    String expected = "{\"kind\":\"EVENT\",\"event\":" + eventCodec.encode(event) + "}";
    Envelope.Event envelope = new Envelope.Event(event);

    byte[] encoded = codec.encode(envelope);

    assertEquals(expected, new String(encoded, StandardCharsets.UTF_8));
    Envelope.Event decoded = assertInstanceOf(Envelope.Event.class, codec.decode(encoded));
    assertEquals(event, decoded.event());
    assertEquals(id(99L), ((RealtimeEvent.ToolPartial) decoded.event()).eventId());
  }

  /** RESYNC 只携带 canonical threadId 与非空 reason，保持固定字段顺序。 */
  @Test
  void resyncEnvelopeIsCanonicalAndRoundTrips() {
    Envelope.Resync envelope = new Envelope.Resync(id(7L), "EVENT_TOO_LARGE");

    byte[] encoded = codec.encode(envelope);

    assertEquals(
        "{\"kind\":\"RESYNC\",\"threadId\":\"00000000-0000-0000-0000-000000000007\","
            + "\"reason\":\"EVENT_TOO_LARGE\"}",
        new String(encoded, StandardCharsets.UTF_8));
    Envelope.Resync decoded = assertInstanceOf(Envelope.Resync.class, codec.decode(encoded));
    assertEquals(id(7L), decoded.threadId());
    assertEquals("EVENT_TOO_LARGE", decoded.reason());
  }

  /** malformed、unknown、重复字段、额外字段与非 canonical 空白都必须拒绝，避免同一语义出现多种 wire 表示。 */
  @Test
  void rejectsMalformedUnknownAndNonCanonicalPayloads() {
    byte[] event = codec.encode(new Envelope.Event(modelDelta("x")));

    assertThrows(IllegalArgumentException.class, () -> decode("{not-json"));
    assertThrows(IllegalArgumentException.class, () -> decode("[]"));
    assertThrows(IllegalArgumentException.class, () -> decode("{\"kind\":\"UNKNOWN\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> decode("{\"kind\":\"EVENT\",\"event\":\"not-an-object\"}"));
    assertThrows(
        IllegalArgumentException.class, () -> decode("{\"kind\":\"RESYNC\",\"kind\":\"RESYNC\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            decode(
                "{\"kind\":\"RESYNC\",\"threadId\":\""
                    + id(1L)
                    + "\",\"reason\":\"x\",\"extra\":1}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> decode("{\"kind\":\"RESYNC\",\"threadId\":\"not-a-uuid\",\"reason\":\"x\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            decode(
                "{\"kind\":\"RESYNC\",\"threadId\":"
                    + "\"ABCDEFAB-CDEF-ABCD-EFAB-CDEFABCDEFAB\",\"reason\":\"x\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> decode("{\"kind\":\"RESYNC\",\"threadId\":\"" + id(1L) + "\",\"reason\":1}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                (" " + new String(event, StandardCharsets.UTF_8))
                    .getBytes(StandardCharsets.UTF_8)));
  }

  /** 坏字节必须被拒绝：绝不静默替换成 replacement character 后靠重编码比对通过。 */
  @Test
  void rejectsMalformedUtf8BytesInsteadOfSilentReplacement() {
    byte[] prefix =
        ("{\"kind\":\"RESYNC\",\"threadId\":\"" + id(1L) + "\",\"reason\":\"")
            .getBytes(StandardCharsets.UTF_8);
    byte[] suffix = "\"}".getBytes(StandardCharsets.UTF_8);

    // 单字节非法序列：宽松解码会变成 U+FFFD 并仍然 canonical，严格解码必须拒绝。
    byte[] invalidByte = new byte[prefix.length + 1 + suffix.length];
    System.arraycopy(prefix, 0, invalidByte, 0, prefix.length);
    invalidByte[prefix.length] = (byte) 0xFF;
    System.arraycopy(suffix, 0, invalidByte, prefix.length + 1, suffix.length);

    // 截断的多字节序列：同样必须拒绝。
    byte[] truncated = new byte[prefix.length + 2 + suffix.length];
    System.arraycopy(prefix, 0, truncated, 0, prefix.length);
    truncated[prefix.length] = (byte) 0xE5;
    truncated[prefix.length + 1] = (byte) 0xA5;
    System.arraycopy(suffix, 0, truncated, prefix.length + 2, suffix.length);

    assertThrows(IllegalArgumentException.class, () -> codec.decode(invalidByte));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(truncated));
    // 同一 shape 携带真正的 U+FFFD 是合法 payload：拒绝的确实是坏字节而不是这个字段。
    Envelope.Resync replacement =
        assertInstanceOf(Envelope.Resync.class, codec.decode(validReason("\uFFFD")));
    assertEquals("\uFFFD", replacement.reason());
  }

  /** 规范编码绝不产出孤立 surrogate 字节：本地编码与远端解码必须对同一内容达成一致。 */
  @Test
  void encodeRejectsLoneSurrogateContent() {
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.encode(new Envelope.Event(modelDelta("\uD800"))));
  }

  /** RESYNC reason 必须有实际诊断值，避免生成无法解释的恢复指令。 */
  @Test
  void rejectsBlankResyncReason() {
    assertThrows(IllegalArgumentException.class, () -> new Envelope.Resync(id(1L), " "));
  }

  private byte[] validReason(String reason) {
    return ("{\"kind\":\"RESYNC\",\"threadId\":\"" + id(1L) + "\",\"reason\":\"" + reason + "\"}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private Envelope decode(String text) {
    return codec.decode(text.getBytes(StandardCharsets.UTF_8));
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
