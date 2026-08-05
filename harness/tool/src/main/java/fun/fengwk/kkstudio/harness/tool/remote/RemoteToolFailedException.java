package fun.fengwk.kkstudio.harness.tool.remote;

/** 远程 daemon 以 FAILED 终态回报；ToolGateway 映射为已确认的非可重试已知失败（durable FAILED）。 */
public final class RemoteToolFailedException extends RuntimeException {

  public RemoteToolFailedException(String message) {
    super(message == null || message.isBlank() ? "Tool execution failed." : message);
  }
}
