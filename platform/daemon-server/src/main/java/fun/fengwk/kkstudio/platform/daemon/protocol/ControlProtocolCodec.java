package fun.fengwk.kkstudio.platform.daemon.protocol;

import com.google.protobuf.InvalidProtocolBufferException;

/**
 * 控制协议的 protobuf binary codec 与领域映射。
 *
 * <p>负责四字节长度前缀成帧、protobuf 解析/序列化以及 proto 与领域对象的映射。{@link ControlProtocolValidator} 在 {@link
 * #decodeBinary} / {@link #encodeBinary} 的 wire 边界施加一次应用层语义校验；映射方法只转换已校验的 proto，不重复校验。
 */
public final class ControlProtocolCodec {

  /** 控制协议版本；用于握手与 426 升级载荷。 */
  public static final int PROTOCOL_VERSION = 4;

  private ControlProtocolCodec() {}

  /** 解码一条裸 protobuf 消息并施加全部应用校验。 */
  public static Envelope decodeBinary(byte[] bytes) {
    requireMessageLength(bytes);
    try {
      return ControlProtocolValidator.validate(Envelope.parseFrom(bytes));
    } catch (InvalidProtocolBufferException error) {
      throw new ControlProtocolException("control message is not valid protobuf", error);
    }
  }

  /** 校验后编码一条裸 protobuf 消息。 */
  public static byte[] encodeBinary(Envelope envelope) {
    ControlProtocolValidator.validate(envelope);
    int size = envelope.getSerializedSize();
    if (size == 0 || size > ControlFraming.MAX_MESSAGE_BYTES) {
      throw new ControlProtocolException(
          "control message length must be in 1.." + ControlFraming.MAX_MESSAGE_BYTES + " bytes");
    }
    return envelope.toByteArray();
  }

  /** 校验后编码为一条带四字节长度前缀的帧。 */
  public static byte[] encodeFrame(Envelope envelope) {
    return ControlFraming.frame(encodeBinary(envelope));
  }

  private static void requireMessageLength(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > ControlFraming.MAX_MESSAGE_BYTES) {
      throw new ControlProtocolException(
          "control message length must be in 1.." + ControlFraming.MAX_MESSAGE_BYTES + " bytes");
    }
  }
}
