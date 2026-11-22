package fun.fengwk.kkstudio.platform.daemon.protocol;

/**
 * 单条控制消息的四字节 big-endian 长度前缀。
 *
 * <p>前缀只描述紧随其后的 protobuf 消息长度，不含自身四个字节。长度范围是 1..16 MiB，成帧不绑定任何 HTTP/2 或 WebSocket
 * 引擎；调用方负责连续写入或增量读取长度与消息体。
 */
public final class ControlFraming {

  /** 单条控制消息的最大字节数：16 MiB。 */
  public static final int MAX_MESSAGE_BYTES = 16 * 1024 * 1024;

  /** 长度前缀的字节数；成帧后的总长是消息长度加该前缀。 */
  public static final int PREFIX_BYTES = Integer.BYTES;

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
}
