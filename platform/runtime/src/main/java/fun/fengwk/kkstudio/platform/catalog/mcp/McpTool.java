package fun.fengwk.kkstudio.platform.catalog.mcp;

import lombok.Builder;

/**
 * MCP server 当前发现结果中的一个工具（{@code mcp_tool} 行）：{@code name} 是模型可见身份与主键（{@code
 * mcp_<server_name>_<normalized_source_tool_name>}），{@code sourceName} 是远端原始名，同一 server 内唯一。
 *
 * <p>远端描述与 JSON input schema 可能含敏感内容，{@link #toString()} 不输出它们。
 */
@Builder(toBuilder = true)
public record McpTool(
    String name, String serverName, String sourceName, String description, String inputSchemaJson) {

  @Override
  public String toString() {
    return "McpTool[name="
        + name
        + ", serverName="
        + serverName
        + ", sourceName="
        + sourceName
        + "]";
  }
}
