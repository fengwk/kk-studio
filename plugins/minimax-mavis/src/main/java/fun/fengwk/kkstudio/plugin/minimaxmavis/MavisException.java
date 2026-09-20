package fun.fengwk.kkstudio.plugin.minimaxmavis;

/** MiniMax Mavis 协议客户端错误基类。 */
public class MavisException extends RuntimeException {

  public MavisException(String message) {
    super(message);
  }

  public MavisException(String message, Throwable cause) {
    super(message, cause);
  }
}
