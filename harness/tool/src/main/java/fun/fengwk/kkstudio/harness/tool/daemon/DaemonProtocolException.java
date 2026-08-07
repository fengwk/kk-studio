package fun.fengwk.kkstudio.harness.tool.daemon;

/** 不符合 Daemon wire 协议的消息。 */
public class DaemonProtocolException extends IllegalArgumentException {

  public DaemonProtocolException(String message) {
    super(message);
  }

  public DaemonProtocolException(String message, Throwable cause) {
    super(message, cause);
  }
}
