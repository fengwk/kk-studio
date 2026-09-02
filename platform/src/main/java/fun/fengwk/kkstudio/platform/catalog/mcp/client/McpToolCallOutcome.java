package fun.fengwk.kkstudio.platform.catalog.mcp.client;

import fun.fengwk.kkstudio.harness.tool.ToolResult;

/**
 * 一次 MCP 工具调用的结果：成功时返回模型可见 {@link ToolResult}；连接/协议失败时返回 {@link #failure}，文本是稳定的通用错误文案，绝不携带
 * URL、token 或 header。
 */
public record McpToolCallOutcome(ToolResult result, boolean failure) {

  private static final String CALL_FAILED_MESSAGE = "MCP tool call failed.";

  /** 成功/上游应用错误的结果（isError 语义保留在 ToolResult 内）。 */
  public static McpToolCallOutcome of(ToolResult result) {
    return new McpToolCallOutcome(result, false);
  }

  /** 传输/协议层失败：模型侧只接收稳定通用文本。 */
  public static McpToolCallOutcome failure(String toolCallId) {
    return new McpToolCallOutcome(ToolResult.error(toolCallId, CALL_FAILED_MESSAGE), true);
  }

  /** 稳定的通用失败文案（供测试断言）。 */
  public static String failureMessage() {
    return CALL_FAILED_MESSAGE;
  }
}
