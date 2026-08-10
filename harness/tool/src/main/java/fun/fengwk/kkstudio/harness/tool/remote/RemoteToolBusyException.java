package fun.fengwk.kkstudio.harness.tool.remote;

/** 远程目标在 INVOKE 发送前发生瞬时容量冲突。调用肯定未开始，调用方应稍后重新执行 admission。 */
public final class RemoteToolBusyException extends RuntimeException {

  public RemoteToolBusyException(String message) {
    super(message);
  }

  public RemoteToolBusyException(String message, Throwable cause) {
    super(message, cause);
  }
}
