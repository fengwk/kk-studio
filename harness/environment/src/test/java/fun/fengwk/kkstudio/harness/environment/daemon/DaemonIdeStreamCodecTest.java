package fun.fengwk.kkstudio.harness.environment.daemon;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 意图：反向数据面 wire 必须严格且无歧义 —— 文本控制帧只接受三种固定形态，二进制帧按 byte1..5 判别；未知类型/字段/判别字节、 越界载荷、缺少 FIN 字节一律
 * fail-closed，避免半包/越界被当作合法流。
 */
class DaemonIdeStreamCodecTest {

  private final DaemonIdeStreamCodec codec = new DaemonIdeStreamCodec();

  @Test
  void roundTripsHttpResponseControlFrame() {
    IdeStreamControlFrame.HttpResponse response =
        new IdeStreamControlFrame.HttpResponse(
            200,
            List.of(
                new IdeHeader("Content-Type", "text/html"), new IdeHeader("Set-Cookie", "a=1")));
    IdeStreamControlFrame decoded = codec.decodeControl(codec.encodeHttpResponse(response));
    assertEquals(response, decoded);
  }

  @Test
  void roundTripsWsReadyAndError() {
    assertEquals(new IdeStreamControlFrame.WsReady(), codec.decodeControl(codec.encodeWsReady()));
    IdeStreamControlFrame decoded = codec.decodeControl(codec.encodeError("upstream unavailable"));
    assertEquals(new IdeStreamControlFrame.Error("upstream unavailable"), decoded);
  }

  @Test
  void rejectsUnknownControlTypeAndFields() {
    assertThrows(
        DaemonProtocolException.class, () -> codec.decodeControl("{\"type\":\"MYSTERY\"}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decodeControl("{\"type\":\"WS_READY\",\"extra\":1}"));
  }

  @Test
  void roundTripsHttpBodyAndEnd() {
    byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
    assertArrayEquals(
        body,
        ((IdeStreamBinaryFrame.HttpBody) codec.decodeBinary(codec.encodeHttpBody(body))).bytes());
    assertInstanceOf(IdeStreamBinaryFrame.HttpEnd.class, codec.decodeBinary(codec.encodeHttpEnd()));
  }

  @Test
  void roundTripsAckAsUnsignedBigEndian() {
    byte[] frame = codec.encodeAck(0x01020304L);
    assertArrayEquals(new byte[] {3, 1, 2, 3, 4}, frame);
    assertEquals(0x01020304L, ((IdeStreamBinaryFrame.Ack) codec.decodeBinary(frame)).byteCount());
    assertEquals(
        0xFFFFFFFFL,
        ((IdeStreamBinaryFrame.Ack) codec.decodeBinary(codec.encodeAck(0xFFFFFFFFL))).byteCount());
  }

  @Test
  void roundTripsWsSegmentsWithFinByte() {
    byte[] text = "é".getBytes(StandardCharsets.UTF_8);
    IdeStreamBinaryFrame.WsSegment textFrame =
        (IdeStreamBinaryFrame.WsSegment)
            codec.decodeBinary(codec.encodeWsSegment(true, false, text));
    assertEquals(true, textFrame.text());
    assertEquals(false, textFrame.last());
    assertArrayEquals(text, textFrame.bytes());

    IdeStreamBinaryFrame.WsSegment binaryFrame =
        (IdeStreamBinaryFrame.WsSegment)
            codec.decodeBinary(codec.encodeWsSegment(false, true, new byte[] {1, 2}));
    assertEquals(false, binaryFrame.text());
    assertEquals(true, binaryFrame.last());
  }

  @Test
  void rejectsMalformedBinaryFrames() {
    assertThrows(DaemonProtocolException.class, () -> codec.decodeBinary(new byte[0]));
    assertThrows(DaemonProtocolException.class, () -> codec.decodeBinary(new byte[] {9}));
    assertThrows(DaemonProtocolException.class, () -> codec.decodeBinary(new byte[] {2, 1}));
    assertThrows(DaemonProtocolException.class, () -> codec.decodeBinary(new byte[] {3, 1}));
    assertThrows(DaemonProtocolException.class, () -> codec.decodeBinary(new byte[] {4}));
    assertThrows(DaemonProtocolException.class, () -> codec.decodeBinary(new byte[] {4, 2, 1}));
  }

  @Test
  void rejectsOversizedPayloadAndAck() {
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.encodeHttpBody(new byte[DaemonIdeStreamCodec.MAX_PAYLOAD_BYTES + 1]));
    assertThrows(IllegalArgumentException.class, () -> codec.encodeAck(0x1_0000_0000L));
    assertThrows(IllegalArgumentException.class, () -> codec.encodeAck(-1L));
  }

  @Test
  void decodeControlRejectsMalformedJson() {
    assertThrows(DaemonProtocolException.class, () -> codec.decodeControl("not json"));
    assertThrows(DaemonProtocolException.class, () -> codec.decodeControl("[1,2]"));
  }
}
