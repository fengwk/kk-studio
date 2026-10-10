package fun.fengwk.kkstudio.harness.environment.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonIdeStreamCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocolException;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeStreamBinaryFrame;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeStreamControlFrame;

import java.util.ArrayList;
import java.util.List;

/**
 * 意图：反向数据面 relay 必须真正按 credit 双向传输任意大小负载（远大于 16MiB）、保持 HTTP 半关闭与 WS 分段/FIN 语义，并在 超窗、非法 ACK、关闭后
 * fail-closed，杜绝无界缓冲与「先 ACK 后缓冲」。
 */
class IdeStreamRelayTest {

  private static final DaemonIdeStreamCodec CODEC = new DaemonIdeStreamCodec();

  private static final class RecordingSink implements IdeStreamRelay.Sink {

    final List<String> texts = new ArrayList<>();
    final List<byte[]> binaries = new ArrayList<>();

    @Override
    public void sendText(String frame) {
      texts.add(frame);
    }

    @Override
    public void sendBinary(byte[] frame) {
      binaries.add(frame);
    }

    long bodyBytes() {
      long total = 0;
      for (byte[] frame : binaries) {
        if (frame.length >= 1 && frame[0] == 1) {
          total += frame.length - 1;
        }
      }
      return total;
    }
  }

  @Test
  void streamsMoreThan16MiBThroughCreditWindow() {
    RecordingSink sink = new RecordingSink();
    IdeStreamRelay relay = new IdeStreamRelay(sink);
    int total = 20 * 1024 * 1024;
    int chunk = 32 * 1024;
    byte[] expected = new byte[total];
    int sent = 0;
    while (sent < total) {
      int length = Math.min(chunk, total - sent);
      byte[] payload = new byte[length];
      for (int i = 0; i < length; i++) {
        payload[i] = (byte) (sent + i);
      }
      System.arraycopy(payload, 0, expected, sent, length);
      relay.sendHttpBody(payload);
      // 模拟 Daemon 在线消费后回 ACK，释放 credit。
      relay.receiveBinary(CODEC.encodeAck(length));
      sent += length;
    }

    assertEquals(total, sink.bodyBytes());
    int index = 0;
    for (byte[] frame : sink.binaries) {
      if (frame.length >= 1 && frame[0] == 1) {
        assertTrue(frame.length - 1 <= DaemonIdeStreamCodec.MAX_PAYLOAD_BYTES);
        for (int i = 1; i < frame.length; i++) {
          assertEquals(expected[index++], frame[i]);
        }
      }
    }
    assertEquals(total, index);
  }

  @Test
  void failsClosedWhenSendCreditExhaustedWithoutAck() {
    IdeStreamRelay relay = new IdeStreamRelay(new RecordingSink());
    byte[] chunk = new byte[65536];
    for (int i = 0; i < 4; i++) {
      relay.sendHttpBody(chunk);
    }
    assertThrows(DaemonProtocolException.class, () -> relay.sendHttpBody(chunk));
  }

  @Test
  void failsClosedWhenDaemonExceedsReceiveCredit() {
    IdeStreamRelay relay = new IdeStreamRelay(new RecordingSink());
    byte[] frame = CODEC.encodeHttpBody(new byte[65536]);
    for (int i = 0; i < 4; i++) {
      relay.receiveBinary(frame);
    }
    assertThrows(DaemonProtocolException.class, () -> relay.receiveBinary(frame));

    relay.ackFromDaemon(4 * 65536);
    relay.receiveBinary(frame);
  }

  @Test
  void ackReleasesDaemonSendWindowAndEmitsAckFrame() {
    RecordingSink sink = new RecordingSink();
    IdeStreamRelay relay = new IdeStreamRelay(sink);
    relay.receiveBinary(CODEC.encodeHttpBody(new byte[1000]));
    relay.ackFromDaemon(1000);
    byte[] last = sink.binaries.get(sink.binaries.size() - 1);
    assertEquals(3, last[0]);
    assertEquals(1000, ((IdeStreamBinaryFrame.Ack) CODEC.decodeBinary(last)).byteCount());
  }

  @Test
  void sendsWsSegmentsAndFailsClosedWhenSendCreditExhausted() {
    RecordingSink sink = new RecordingSink();
    IdeStreamRelay relay = new IdeStreamRelay(sink);
    byte[] chunk = new byte[65536];
    for (int i = 0; i < 4; i++) {
      relay.sendWsSegment(true, false, chunk);
    }
    // 第 5 段超窗：必须 fail-closed 且不产生「部分已发」的额外帧。
    int framesBefore = sink.binaries.size();
    assertThrows(DaemonProtocolException.class, () -> relay.sendWsSegment(true, true, chunk));
    assertEquals(framesBefore, sink.binaries.size());

    relay.receiveBinary(CODEC.encodeAck(4 * 65536));
    relay.sendWsSegment(false, true, chunk);
    IdeStreamBinaryFrame.WsSegment last =
        (IdeStreamBinaryFrame.WsSegment)
            CODEC.decodeBinary(sink.binaries.get(sink.binaries.size() - 1));
    assertFalse(last.text());
    assertTrue(last.last());
    assertEquals(65536, last.bytes().length);
  }

  @Test
  void streamsLargeWsMessageSegmentBySegmentUnderBackpressure() {
    RecordingSink sink = new RecordingSink();
    IdeStreamRelay relay = new IdeStreamRelay(sink);
    int total = 20 * 1024 * 1024;
    int segment = 65536;
    byte[] expected = new byte[total];
    int sent = 0;
    while (sent < total) {
      int length = Math.min(segment, total - sent);
      byte[] chunk = new byte[length];
      for (int i = 0; i < length; i++) {
        chunk[i] = (byte) (sent + i);
      }
      System.arraycopy(chunk, 0, expected, sent, length);
      // 适配层先等待 credit，再逐段发送；这里用即时 ACK 模拟 Daemon 的消费回执。
      relay.sendWsSegment(true, sent + length == total, chunk);
      relay.receiveBinary(CODEC.encodeAck(length));
      sent += length;
    }

    int index = 0;
    int segments = 0;
    for (byte[] frame : sink.binaries) {
      IdeStreamBinaryFrame.WsSegment decoded =
          (IdeStreamBinaryFrame.WsSegment) CODEC.decodeBinary(frame);
      segments++;
      for (byte value : decoded.bytes()) {
        assertEquals(expected[index++], value);
      }
    }
    assertEquals(total, index);
    assertEquals(total / segment, segments);
  }

  @Test
  void rejectsOversizedWsSegment() {
    IdeStreamRelay relay = new IdeStreamRelay(new RecordingSink());
    assertThrows(
        DaemonProtocolException.class,
        () ->
            relay.sendWsSegment(true, true, new byte[DaemonIdeStreamCodec.MAX_PAYLOAD_BYTES + 1]));
  }

  @Test
  void decodesDaemonControlFramesAndPreservesBodyBytes() {
    IdeStreamRelay relay = new IdeStreamRelay(new RecordingSink());
    IdeStreamControlFrame frame =
        relay.receiveText(
            CODEC.encodeHttpResponse(new IdeStreamControlFrame.HttpResponse(200, List.of())));
    assertEquals(200, ((IdeStreamControlFrame.HttpResponse) frame).status());

    byte[] payload = {(byte) 0x00, (byte) 0xFF, 1, 2, 3};
    IdeStreamBinaryFrame decoded = relay.receiveBinary(CODEC.encodeHttpBody(payload));
    assertArrayEquals(payload, ((IdeStreamBinaryFrame.HttpBody) decoded).bytes());
  }

  @Test
  void closeIsTerminalAndRejectsFurtherTraffic() {
    IdeStreamRelay relay = new IdeStreamRelay(new RecordingSink());
    relay.close();
    assertTrue(relay.isClosed());
    assertThrows(DaemonProtocolException.class, relay::sendHttpEnd);
    assertThrows(DaemonProtocolException.class, () -> relay.receiveText(CODEC.encodeWsReady()));
  }
}
