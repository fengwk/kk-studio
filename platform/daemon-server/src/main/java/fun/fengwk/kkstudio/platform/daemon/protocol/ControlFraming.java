package fun.fengwk.kkstudio.platform.daemon.protocol;

/**
 * 单条控制消息的四字节 big-endian 长度前缀。
 *
 * <p>前缀只描述紧随其后的 protobuf 消息长度，不含自身四个字节。长度范围是 1..16 MiB，成帧不绑定任何 HTTP/2 或 WebSocket 引擎；调用方负责连续读取或写入。
 */
public final class ControlFraming {

  /** 单条控制消息的最大字节数：16 MiB。 */
  public static final int MAX_MESSAGE_BYTES = 16 * 1024 * 1024;

  private static final int PREFIX_BYTES = Integer.BYTES;

  private ControlFraming() {}

  /** 把一条非空控制消息包装为单个长度前缀帧。 */
  public static byte[] frame(byte[] message) {
    if (message == null) {
      throw new ControlProtocolException("control message must not be null");
    }
    if (message.length == 0 || message.length > MAX_MESSAGE_BYTES) {
      throw new ControlProtocolException(
          "control message length must be in 1.." + MAX_MESSAGE_BYTES + " bytes");
    }
    byte[] frame = new byte[PREFIX_BYTES + message.length];
    frame[0] = (byte) (message.length >>> 24);
    frame[1] = (byte) (message.length >>> 16);
    frame[2] = (byte) (message.length >>> 8);
    frame[3] = (byte) message.length;
    System.arraycopy(message, 0, frame, PREFIX_BYTES, message.length);
    return frame;
  }

  /** 读取恰好一条帧并返回其消息体；截断、超限或尾随字节都拒绝。 */
  public static byte[] unframe(byte[] frame) {
    if (frame == null || frame.length < PREFIX_BYTES) {
      throw new ControlProtocolException("control frame must contain a 4-byte length prefix");
    }
    int length =
        ((frame[0] & 0xff) << 24)
            | ((frame[1] & 0xff) << 16)
            | ((frame[2] & 0xff) << 8)
            | (frame[3] & 0xff);
    if (length < 1 || length > MAX_MESSAGE_BYTES) {
      throw new ControlProtocolException(
          "control message length must be in 1.." + MAX_MESSAGE_BYTES + " bytes");
    }
    if (frame.length != PREFIX_BYTES + length) {
      throw new ControlProtocolException("control frame length does not match its prefix");
    }
    byte[] message = new byte[length];
    System.arraycopy(frame, PREFIX_BYTES, message, 0, length);
    return message;
  }
}
