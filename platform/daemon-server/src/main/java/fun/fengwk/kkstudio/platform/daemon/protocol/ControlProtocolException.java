package fun.fengwk.kkstudio.platform.daemon.protocol;

/**
 * 控制协议在成帧、解析或应用层校验边界上的确定性拒绝。
 *
 * <p>文案只由固定字段路径与固定规则短语构成，不包含输入值、远端内容或凭据，因此可以作为诊断原因写出；调用方不得把原始 throwable 或其它异常正文 拼进协议拒绝。
 */
public final class ControlProtocolException extends RuntimeException {

  public ControlProtocolException(String message) {
    super(message);
  }

  public ControlProtocolException(String message, Throwable cause) {
    super(message, cause);
  }
}
