package fun.fengwk.kkstudio.harness.daemon.mcp;

import fun.fengwk.kkstudio.harness.daemon.DaemonCapabilityRegistry;

import java.util.Objects;
import java.util.concurrent.ExecutorService;

/** 按稳定的能力顺序注册固定 MCP bridge capability；无论是否配置 MCP server 都注册。 */
public final class McpBridgeCapabilities {

  private McpBridgeCapabilities() {}

  /** 注册 {@code mcp.list} 与 {@code mcp.call}。 */
  public static void registerAll(
      DaemonCapabilityRegistry registry, McpServerRegistry mcpRegistry, ExecutorService executor) {
    Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(mcpRegistry, "mcpRegistry");
    Objects.requireNonNull(executor, "executor");
    registry.register(new McpListCapability(mcpRegistry, executor));
    registry.register(new McpCallCapability(mcpRegistry, executor));
  }
}
