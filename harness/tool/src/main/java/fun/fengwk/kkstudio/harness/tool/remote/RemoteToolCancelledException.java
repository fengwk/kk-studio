package fun.fengwk.kkstudio.harness.tool.remote;

/** 远程 daemon 以 CANCELLED 终态回报；ToolGateway 映射为 durable CANCELLED。 */
public final class RemoteToolCancelledException extends RuntimeException {

  public RemoteToolCancelledException(String message) {
    super(message == null || message.isBlank() ? "Tool execution cancelled." : message);
  }
}
