package fun.fengwk.kkstudio.harness.mcp;

/** MCP 操作被调用方取消或以取消结束；调用方据此区分「主动取消」与「连接/进程失败」。 */
public class McpCancelledException extends McpException {

  private static final long serialVersionUID = 1L;

  public McpCancelledException(String message) {
    super(message);
  }
}
