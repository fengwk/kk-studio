package fun.fengwk.kkstudio.harness.mcp;

/**
 * MCP 操作异常基类。
 *
 * <p>只承载固定不透明文本，且<strong>不提供 cause 构造</strong>：底层 SDK、Jackson 或子进程异常的消息可能包含工具入参、响应正文或本地路径，
 * 一旦被上层记录即造成泄漏。诊断所需细节在抛出点内部消化，绝不跨越模块边界。
 */
public class McpException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public McpException(String message) {
    super(message);
  }
}
