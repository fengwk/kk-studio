package fun.fengwk.kkstudio.harness.daemon.mcp;

/** MCP 工具摘要：name/description + 完整输入 schema JSON（LangChain {@code ToolSpecification} 序列化结果）。 */
public record McpToolSpec(String name, String description, String schemaJson) {

  public McpToolSpec {
    name = requireNonBlank(name, "name");
    description = requireNonBlank(description, "description");
    schemaJson = requireNonBlank(schemaJson, "schemaJson");
  }

  private static String requireNonBlank(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }
}
