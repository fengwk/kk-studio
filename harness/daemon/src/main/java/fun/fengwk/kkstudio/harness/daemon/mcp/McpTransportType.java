package fun.fengwk.kkstudio.harness.daemon.mcp;

/** MCP server 的传输类型；wire 名称为规范的 kebab-case。 */
public enum McpTransportType {
  STDIO("stdio"),
  STREAMABLE_HTTP("streamable-http"),
  WEBSOCKET("websocket");

  private final String wireName;

  McpTransportType(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }

  /** 严格映射 wire 名称；未知值抛 {@link IllegalArgumentException}。 */
  public static McpTransportType fromWire(String value) {
    for (McpTransportType type : values()) {
      if (type.wireName.equals(value)) {
        return type;
      }
    }
    throw new IllegalArgumentException("unknown transport: " + value);
  }
}
