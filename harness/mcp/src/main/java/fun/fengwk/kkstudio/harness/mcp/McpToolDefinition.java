package fun.fengwk.kkstudio.harness.mcp;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;

/**
 * 跨 Platform/Daemon 复用的 MCP 工具规格定义。
 *
 * <p>{@code inputSchemaJson} 必须是合法 JSON object；不接受非法 schema 降级为字符串。
 *
 * @param name 工具名称（非空）
 * @param description 工具描述
 * @param inputSchemaJson JSON object 形式的输入参数 JSON Schema
 */
public record McpToolDefinition(String name, String description, String inputSchemaJson) {

  public McpToolDefinition {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    description = description == null ? "" : description;
    inputSchemaJson = JsonValues.requireJsonObject(inputSchemaJson, "inputSchemaJson");
  }
}
