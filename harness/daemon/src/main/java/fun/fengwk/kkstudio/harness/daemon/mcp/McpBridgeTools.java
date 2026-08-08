package fun.fengwk.kkstudio.harness.daemon.mcp;

import fun.fengwk.kkstudio.harness.daemon.DaemonToolRegistry;

import java.util.Objects;

/** 按稳定的能力顺序注册固定 MCP 桥接工具；无论是否配置 MCP server 都注册，保证 daemon registry 与固定 catalog 完全一致。 */
public final class McpBridgeTools {

  private McpBridgeTools() {}

  /** 注册 {@code mcp_list_tools} 与 {@code mcp_call_tool}。 */
  public static void registerAll(DaemonToolRegistry registry, McpServerRegistry mcpRegistry) {
    Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(mcpRegistry, "mcpRegistry");
    registry.register(new McpListToolsTool(mcpRegistry));
    registry.register(new McpCallToolTool(mcpRegistry));
  }
}
