package fun.fengwk.kkstudio.harness.mcp;

/** MCP 操作超出预算而结束；调用方据此区分「超时」与「连接/进程失败」。 */
public class McpTimeoutException extends McpException {

  private static final long serialVersionUID = 1L;

  public McpTimeoutException(String message) {
    super(message);
  }
}
