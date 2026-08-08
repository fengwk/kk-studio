package fun.fengwk.kkstudio.harness.daemon.mcp.langchain;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.mcp.client.transport.websocket.WebSocketMcpTransport;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.daemon.mcp.McpServerConfig;
import fun.fengwk.kkstudio.harness.daemon.mcp.McpTransportType;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** transport 工厂映射（纯构造，不发起网络/进程连接）。 */
class LangChainMcpTransportFactoryTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  @Test
  void mapsStdioConfigToStdioTransport() {
    McpServerConfig config =
        new McpServerConfig(
            "fs",
            McpTransportType.STDIO,
            null,
            List.of("npx", "server"),
            Map.of("LANG", "C"),
            null,
            null);
    McpTransport transport = LangChainMcpTransportFactory.create(config, TIMEOUT);
    assertInstanceOf(StdioMcpTransport.class, transport);
  }

  @Test
  void mapsStreamableHttpConfigToStreamableHttpTransport() {
    McpServerConfig config =
        new McpServerConfig(
            "remote",
            McpTransportType.STREAMABLE_HTTP,
            null,
            null,
            null,
            "https://mcp.example.com/mcp",
            Map.of("Authorization", "Bearer x"));
    McpTransport transport = LangChainMcpTransportFactory.create(config, TIMEOUT);
    assertInstanceOf(StreamableHttpMcpTransport.class, transport);
  }

  @Test
  void mapsWebsocketConfigToWebSocketTransport() {
    McpServerConfig config =
        new McpServerConfig(
            "ws-server",
            McpTransportType.WEBSOCKET,
            null,
            null,
            null,
            "wss://mcp.example.com/mcp",
            null);
    McpTransport transport = LangChainMcpTransportFactory.create(config, TIMEOUT);
    assertInstanceOf(WebSocketMcpTransport.class, transport);
  }
}
