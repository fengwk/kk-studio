package fun.fengwk.kkstudio.ide.mux;

/** Wire 格式、顺序或 credit 违反协议；连接必须 fail-stop。 */
public class MuxProtocolException extends RuntimeException {

  public MuxProtocolException(String message) {
    super(message);
  }

  public MuxProtocolException(String message, Throwable cause) {
    super(message, cause);
  }
}
