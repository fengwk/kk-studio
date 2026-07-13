package fun.fengwk.kkstudio.harness.tool.execution;

/** 工具未在约定超时内终止时使用的标准失败。 */
public final class ToolTimeoutException extends RuntimeException {

  public ToolTimeoutException(String toolName) {
    super("tool execution timed out: " + toolName);
  }
}
