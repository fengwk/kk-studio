package fun.fengwk.kkstudio.platform.daemon.protocol;

/** 控制协议在成帧、解析或应用层校验边界上的确定性拒绝。 */
public final class ControlProtocolException extends RuntimeException {

  public ControlProtocolException(String message) {
    super(message);
  }

  public ControlProtocolException(String message, Throwable cause) {
    super(message, cause);
  }
}
