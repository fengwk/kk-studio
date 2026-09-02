package fun.fengwk.kkstudio.platform.catalog.mcp.client;

/** 远端 MCP 工具规格。 */
public record McpRemoteToolSpec(String name, String description, String inputSchemaJson) {

  public McpRemoteToolSpec {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    description = description == null ? "" : description;
    if (inputSchemaJson == null || inputSchemaJson.isBlank()) {
      inputSchemaJson = "{}";
    }
  }
}
