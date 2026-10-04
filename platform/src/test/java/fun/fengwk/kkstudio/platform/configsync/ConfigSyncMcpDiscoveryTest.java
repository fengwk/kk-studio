package fun.fengwk.kkstudio.platform.configsync;

import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.mcpServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.mcp.McpCancellationToken;
import fun.fengwk.kkstudio.harness.mcp.McpClient;
import fun.fengwk.kkstudio.harness.mcp.McpDeadline;
import fun.fengwk.kkstudio.harness.mcp.McpToolCallResult;
import fun.fengwk.kkstudio.harness.mcp.McpToolDefinition;
import fun.fengwk.kkstudio.platform.catalog.mcp.discovery.McpToolDiscovery;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 测试意图：锁定配置同步 MCP 发现的失败语义——成功返回候选工具行；任何发现失败（网络/校验）只返回 {@code null}，绝不向上抛出 携带 URL/header 的错误；底层
 * client 无论成功失败都由 try-with-resources 关闭，不泄漏连接。
 */
class ConfigSyncMcpDiscoveryTest {

  private McpServer server() {
    McpServer server = mcpServer("mcp", true);
    server.setHeaders(Map.of());
    return server;
  }

  private static McpClient client(
      List<McpToolDefinition> tools, RuntimeException failure, AtomicBoolean closed) {
    return new McpClient() {
      @Override
      public List<McpToolDefinition> listTools(McpDeadline deadline, McpCancellationToken token) {
        if (failure != null) {
          throw failure;
        }
        return tools;
      }

      @Override
      public McpToolCallResult callTool(
          String name, String argumentsJson, McpDeadline deadline, McpCancellationToken token) {
        throw new UnsupportedOperationException("not used");
      }

      @Override
      public void close() {
        closed.set(true);
      }
    };
  }

  @Test
  void noArgConstructorCreatesProductionDiscovery() {
    // 生产装配路径必须可用（构造不发起任何网络 I/O）。
    assertNotNull(new ConfigSyncMcpDiscovery());
  }

  @Test
  void successfulDiscoveryMapsToolsAndClosesClient() {
    AtomicBoolean closed = new AtomicBoolean();
    McpToolDiscovery delegate =
        new McpToolDiscovery(
            (config, deadline) ->
                client(
                    List.of(new McpToolDefinition("echo", "Echo", "{\"type\":\"object\"}")),
                    null,
                    closed),
            name -> null);
    ConfigSyncMcpDiscovery discovery = new ConfigSyncMcpDiscovery(delegate);

    List<McpTool> tools = discovery.discoverOrNull(server());

    assertEquals(1, tools.size());
    assertEquals("mcp_mcp_echo", tools.get(0).getName());
    assertTrue(closed.get());
  }

  @Test
  void discoveryFailureReturnsNullClosesClientAndLeaksNothing() {
    AtomicBoolean closed = new AtomicBoolean();
    McpToolDiscovery delegate =
        new McpToolDiscovery(
            (config, deadline) -> client(null, new RuntimeException("boom"), closed), name -> null);
    ConfigSyncMcpDiscovery discovery = new ConfigSyncMcpDiscovery(delegate);

    // 返回 null 表示只保存配置，不伪造发现结果；错误文本不进入返回值或异常。
    assertNull(discovery.discoverOrNull(server()));
    assertTrue(closed.get());
  }
}
