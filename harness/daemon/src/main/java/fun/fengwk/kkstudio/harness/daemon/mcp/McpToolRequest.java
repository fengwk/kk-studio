package fun.fengwk.kkstudio.harness.daemon.mcp;

import fun.fengwk.kkstudio.harness.tool.schema.ToolArgumentsValidator;

/** 一次 MCP 工具调用的请求：精确工具名 + 真实 JSON 对象参数（不是 JSON 字符串）。 */
public record McpToolRequest(String toolName, String argumentsJson) {

  public McpToolRequest {
    toolName = requireNonBlank(toolName, "toolName");
    argumentsJson = ToolArgumentsValidator.requireJsonObject(argumentsJson);
  }

  private static String requireNonBlank(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }
}
