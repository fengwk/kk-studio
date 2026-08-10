package fun.fengwk.kkstudio.harness.tool.remote;

/** 远程目标在 INVOKE 发送前不可用（离线、未 READY 或能力不匹配）。调用肯定未开始。 */
public final class RemoteToolUnavailableException extends RuntimeException {

  public RemoteToolUnavailableException(String message) {
    super(message);
  }

  public RemoteToolUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
