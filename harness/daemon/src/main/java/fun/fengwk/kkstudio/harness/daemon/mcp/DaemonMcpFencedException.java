package fun.fengwk.kkstudio.harness.daemon.mcp;

/** 当请求的目标 MCP server 版本已被新版本淘汰并停止新准入时抛出。 */
public class DaemonMcpFencedException extends RuntimeException {

  public DaemonMcpFencedException(String message) {
    super(message);
  }
}
