package fun.fengwk.kkstudio.platform.daemon.transport;

import org.eclipse.jetty.util.BufferUtil;

import fun.fengwk.kkstudio.platform.daemon.protocol.ControlFraming;
import fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolCodec;
import fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolException;
import fun.fengwk.kkstudio.platform.daemon.protocol.Envelope;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 控制流的增量入站解帧。
 *
 * <p>HTTP/2 DATA 帧的边界与协议帧边界无关：一次回调可能只带来长度前缀的一部分，也可能一次带来多个完整帧。这里按 4 字节长度前缀累积，凑满一个 body
 * 就立即解码，并保留跨回调的中间状态。
 *
 * <p>解码失败与长度越界都会停止后续解码并给出固定说明；调用方据此发出 ERROR 信封再关闭通道。
 *
 * <p>本类不做同步：它持有跨回调的中间状态，调用方必须与自己的连接锁一起使用，保证数据回调线程之间的可见性。
 */
final class ControlFrameDecoder {

  private final ByteBuffer lengthBuffer = ByteBuffer.allocate(Integer.BYTES);
  private byte[] bodyBuffer;
  private int bodyFilled;

  /**
   * 消费一段入站字节。
   *
   * <p>返回本次凑满并可解码的信封；一旦发现协议违规就停止并把说明写入 {@code error}，已解出的信封仍然返回，交由调用方先行交付。
   */
  List<Envelope> accept(ByteBuffer buffer, ProtocolViolation error) {
    List<Envelope> decoded = new ArrayList<>();
    while (buffer.hasRemaining() && error.message == null) {
      if (bodyBuffer == null) {
        readLength(buffer, error);
      } else {
        readBody(buffer, decoded, error);
      }
    }
    return decoded;
  }

  private void readLength(ByteBuffer buffer, ProtocolViolation error) {
    BufferUtil.put(buffer, lengthBuffer);
    if (lengthBuffer.hasRemaining()) {
      return;
    }
    lengthBuffer.flip();
    int length = lengthBuffer.getInt();
    lengthBuffer.clear();
    if (length < 1 || length > ControlFraming.MAX_MESSAGE_BYTES) {
      error.message = "invalid inbound frame length: " + length;
      return;
    }
    bodyBuffer = new byte[length];
    bodyFilled = 0;
  }

  private void readBody(ByteBuffer buffer, List<Envelope> decoded, ProtocolViolation error) {
    int toTake = Math.min(bodyBuffer.length - bodyFilled, buffer.remaining());
    buffer.get(bodyBuffer, bodyFilled, toTake);
    bodyFilled += toTake;
    if (bodyFilled < bodyBuffer.length) {
      return;
    }
    try {
      decoded.add(ControlProtocolCodec.decodeBinary(bodyBuffer));
    } catch (ControlProtocolException ex) {
      error.message = "failed to decode control envelope: " + ex.getMessage();
    } finally {
      bodyBuffer = null;
      bodyFilled = 0;
      lengthBuffer.clear();
    }
  }

  /** 一次解帧过程中记录的首个协议违规；{@code message} 为 null 表示尚无违规。 */
  static final class ProtocolViolation {

    private String message;

    /** 首个违规说明；没有违规时为 null。 */
    String message() {
      return message;
    }
  }
}
