package fun.fengwk.kkstudio.harness.tool.remote;

/** 远程目标在 INVOKE 发送前不可用（离线/未 READY）。调用方必须释放 claim 回到 prior QUEUED/RETRY_WAIT，不得记为执行失败。 */
public final class RemoteToolUnavailableException extends RuntimeException {

  public RemoteToolUnavailableException(String message) {
    super(message);
  }

  public RemoteToolUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
