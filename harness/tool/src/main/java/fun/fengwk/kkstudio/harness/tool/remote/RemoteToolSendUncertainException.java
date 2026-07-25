package fun.fengwk.kkstudio.harness.tool.remote;

/** 远程 INVOKE 发送结果不确定（可能已投递）。调用方必须保留 RUNNING lease，不得重放副作用；最终由 lease 过期收敛为 UNKNOWN。 */
public final class RemoteToolSendUncertainException extends RuntimeException {

  public RemoteToolSendUncertainException(String message) {
    super(message);
  }

  public RemoteToolSendUncertainException(String message, Throwable cause) {
    super(message, cause);
  }
}
