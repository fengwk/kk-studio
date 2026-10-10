package fun.fengwk.kkstudio.platform.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import fun.fengwk.kkstudio.harness.mcp.McpCancellationToken;
import fun.fengwk.kkstudio.harness.mcp.McpClient;
import fun.fengwk.kkstudio.harness.mcp.McpClientFactory;
import fun.fengwk.kkstudio.harness.mcp.McpDeadline;
import fun.fengwk.kkstudio.harness.mcp.McpToolDefinition;
import fun.fengwk.kkstudio.harness.mcp.RemoteMcpConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 真实 MCP（Streamable HTTP）动态代理路由回归：{@link McpClientFactory} 在装配完成后按当前默认 selector 建客户端， 同一
 * client/transport 在快照切换到 proxyB 后，新请求现读快照改走 proxyB，无需重建 client。
 *
 * <p>两个专用回环服务各自模拟最小 MCP 握手（initialize / notifications/initialized）与 tools/list 协议并计数；目标 URL
 * 不可解析，因此计数只可能来自代理，能区分请求实际命中的是 A 还是 B。LangChain4j 传输层构建 JDK {@code HttpClient} 时不覆盖 proxy，捕获的是全局默认
 * selector（SDK 无 proxy 入口），所以这一回归直接覆盖 Platform MCP 发现/执行路径的真实选路。
 */
@ResourceLock("jvm-proxy-selector")
class SystemNetworkConfigurationMcpProxyTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final URI TARGET = URI.create("http://unresolvable.invalid/mcp");

  @Test
  void routesNewMcpRequestsThroughTheCurrentProxySnapshot() throws Exception {
    AtomicInteger proxyARequests = new AtomicInteger();
    AtomicInteger proxyBRequests = new AtomicInteger();
    HttpServer proxyA = mcpProxy(proxyARequests);
    HttpServer proxyB = mcpProxy(proxyBRequests);
    ProxySelector previous = ProxySelector.getDefault();
    SystemSettingsSnapshot snapshot = snapshot(proxyUrl(proxyA));
    try {
      runner(snapshot)
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                ProxySelector selector =
                    context.getBean("systemProxySelector", ProxySelector.class);
                assertSame(selector, ProxySelector.getDefault());

                try (McpClient client =
                    McpClientFactory.createRemote(
                        new RemoteMcpConfig(TARGET.toString(), Map.of()), Duration.ofSeconds(15))) {
                  // 装配完成后建 client：MCP 握手只能经 proxyA，且此时尚未触碰 proxyB。
                  int handshakeThroughA = proxyARequests.get();
                  assertThat(handshakeThroughA).isPositive();
                  assertEquals(0, proxyBRequests.get());

                  snapshot.replace(settings(proxyUrl(proxyB)));

                  // 同一个 client/transport 的新请求必须现读快照改走 proxyB，proxyA 不再新增。
                  List<McpToolDefinition> tools =
                      client.listTools(
                          McpDeadline.of(Duration.ofSeconds(15)), McpCancellationToken.none());
                  assertThat(tools)
                      .extracting(McpToolDefinition::name)
                      .containsExactly("remote_tool");
                  assertEquals(handshakeThroughA, proxyARequests.get());
                  assertEquals(1, proxyBRequests.get());
                }
              });
      // 上下文关闭后恢复进入前的默认 selector，不残留全局副作用。
      assertSame(previous, ProxySelector.getDefault());
    } finally {
      ProxySelector.setDefault(previous);
      proxyA.stop(0);
      proxyB.stop(0);
    }
  }

  private static ApplicationContextRunner runner(SystemSettingsSnapshot snapshot) {
    return new ApplicationContextRunner()
        .withUserConfiguration(SystemNetworkConfiguration.class)
        .withBean(SystemSettingsSnapshot.class, () -> snapshot);
  }

  private static String proxyUrl(HttpServer server) {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private static SystemSettingsSnapshot snapshot(String proxyUrl) {
    return new SystemSettingsSnapshot(settings(proxyUrl));
  }

  private static SystemSettings settings(String proxyUrl) {
    SystemSettings defaults = SystemSettings.DEFAULT;
    return new SystemSettings(
        defaults.tool(),
        defaults.aiRuntime(),
        defaults.environment(),
        new SystemSettings.Network(proxyUrl, ""),
        defaults.integrations(),
        defaults.storageMedia(),
        defaults.advanced());
  }

  /** 最小 MCP 回环服务：作为代理忽略目标 host，按 JSON-RPC 方法应答并统计收到的请求数。 */
  private static HttpServer mcpProxy(AtomicInteger requests) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
          }
          JsonNode request = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
          String method = request.path("method").asText();
          JsonNode id = request.get("id");
          if ("initialize".equals(method)) {
            json(
                exchange,
                200,
                "{\"jsonrpc\":\"2.0\",\"id\":"
                    + id
                    + ",\"result\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"proxy-mcp\",\"version\":\"1.0\"}}}");
          } else if ("notifications/initialized".equals(method)) {
            exchange.sendResponseHeaders(204, -1);
          } else if ("tools/list".equals(method)) {
            json(
                exchange,
                200,
                "{\"jsonrpc\":\"2.0\",\"id\":"
                    + id
                    + ",\"result\":{\"tools\":[{\"name\":\"remote_tool\",\"description\":\"remote\",\"inputSchema\":{\"type\":\"object\"}}]}}");
          } else {
            exchange.sendResponseHeaders(404, -1);
          }
        });
    server.start();
    return server;
  }

  private static void json(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }
}
