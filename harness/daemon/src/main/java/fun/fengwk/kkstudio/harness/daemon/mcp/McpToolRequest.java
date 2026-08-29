package fun.fengwk.kkstudio.harness.daemon.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 一次 MCP 工具调用的请求：精确工具名 + 真实 JSON 对象参数（不是 JSON 字符串）。 */
public record McpToolRequest(String toolName, String argumentsJson) {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public McpToolRequest {
    toolName = requireNonBlank(toolName, "toolName");
    argumentsJson = requireJsonObject(argumentsJson);
  }

  private static String requireJsonObject(String json) {
    if (json == null || json.isBlank()) {
      throw new IllegalArgumentException("argumentsJson must not be blank");
    }
    try {
      JsonNode node = MAPPER.readTree(json);
      if (node == null || !node.isObject()) {
        throw new IllegalArgumentException("argumentsJson must be a JSON object");
      }
      return json;
    } catch (Exception error) {
      throw new IllegalArgumentException(
          "argumentsJson must be valid JSON: " + error.getMessage(), error);
    }
  }

  private static String requireNonBlank(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }
}
