package fun.fengwk.kkstudio.harness.environment.daemon;

/**
 * Daemon READY 中暴露的 MCP 工具摘要。
 *
 * <p>仅包含可安全上报的 name/description；完整输入 schema 只通过固定的 {@code mcp_list_tools} 桥接工具返回。
 */
public record DaemonMcpToolDescriptor(String name, String description) {

  public DaemonMcpToolDescriptor {
    name = requireNonBlank(name, "name");
    description = requireNonBlank(description, "description");
  }

  private static String requireNonBlank(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }
}
