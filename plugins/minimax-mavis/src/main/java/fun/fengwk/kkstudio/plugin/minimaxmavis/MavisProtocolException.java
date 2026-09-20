package fun.fengwk.kkstudio.plugin.minimaxmavis;

/** 服务端返回了与协议不符的结构或值。 */
public class MavisProtocolException extends MavisException {

  public MavisProtocolException(String message) {
    super(message);
  }

  public MavisProtocolException(String message, Throwable cause) {
    super(message, cause);
  }
}
