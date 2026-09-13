package fun.fengwk.kkstudio.harness.daemon.mcp;

/**
 * Daemon 本地 MCP 参数或配置校验失败异常。
 *
 * <p>只承载固定不透明文本，且不提供 cause 构造：Jackson 等底层异常的消息会回显原始 JSON 片段与路径，可能夹带 secrets， 因此校验边界只抛出不含底层细节的固定文本。
 */
public class DaemonMcpValidationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public DaemonMcpValidationException(String message) {
    super(message);
  }
}
