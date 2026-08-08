package fun.fengwk.kkstudio.harness.daemon.mcp.langchain;

import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.mcp.client.transport.websocket.WebSocketMcpTransport;

import fun.fengwk.kkstudio.harness.daemon.mcp.McpServerConfig;

import java.time.Duration;
import java.util.Map;

/** 按配置把 {@link McpServerConfig} 映射为 LangChain4j transport；不发起任何网络/进程连接。 */
final class LangChainMcpTransportFactory {

  private LangChainMcpTransportFactory() {}

  static McpTransport create(McpServerConfig config, Duration timeout) {
    switch (config.transport()) {
      case STDIO:
        StdioMcpTransport.Builder stdio = StdioMcpTransport.builder().command(config.command());
        if (config.environment() != null) {
          stdio.environment(config.environment());
        }
        return stdio.build();
      case STREAMABLE_HTTP:
        StreamableHttpMcpTransport.Builder http =
            StreamableHttpMcpTransport.builder().url(config.url()).timeout(timeout);
        if (config.headers() != null) {
          http.customHeaders(Map.copyOf(config.headers()));
        }
        return http.build();
      case WEBSOCKET:
        WebSocketMcpTransport.Builder websocket =
            WebSocketMcpTransport.builder().url(config.url()).timeout(timeout);
        if (config.headers() != null) {
          websocket.headersSupplier(() -> Map.copyOf(config.headers()));
        }
        return websocket.build();
      default:
        throw new IllegalArgumentException("unsupported transport: " + config.transport());
    }
  }
}
