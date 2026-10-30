package fun.fengwk.kkstudio.platform.catalog.mcp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.common.result.TextResultContent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 远端 Streamable HTTP MCP 客户端的契约测试。 */
class RemoteMcpClientTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private HttpServer server;
  private String serverUrl;
  private final AtomicReference<String> receivedAuthHeader = new AtomicReference<>();

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    serverUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    server.createContext(
        "/mcp",
        exchange -> {
          String auth = exchange.getRequestHeaders().getFirst("Authorization");
          if (auth != null) {
            receivedAuthHeader.set(auth);
          }
          if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            InputStream in = exchange.getRequestBody();
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            JsonNode req = MAPPER.readTree(body);
            String method = req.path("method").asText();
            JsonNode idNode = req.get("id");

            if ("initialize".equals(method)) {
              sendJsonRpc(
                  exchange,
                  idNode,
                  "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{\"tools\":{}},"
                      + "\"serverInfo\":{\"name\":\"test-remote\",\"version\":\"1.0\"}}");
            } else if ("notifications/initialized".equals(method)) {
              exchange.sendResponseHeaders(204, -1);
            } else if ("tools/list".equals(method)) {
              sendJsonRpc(
                  exchange,
                  idNode,
                  "{\"tools\":[{\"name\":\"remote_tool\",\"description\":\"remote\","
                      + "\"inputSchema\":{\"type\":\"object\"}}]}");
            } else if ("tools/call".equals(method)) {
              String toolName = req.path("params").path("name").asText();
              if ("slow_tool".equals(toolName)) {
                try {
                  Thread.sleep(5000);
                } catch (InterruptedException ignored) {
                }
              }
              if ("error_tool".equals(toolName)) {
                sendJsonRpc(
                    exchange,
                    idNode,
                    "{\"content\":[{\"type\":\"text\",\"text\":\"tool_error\"}],"
                        + "\"isError\":true}");
              } else if ("structured_tool".equals(toolName)) {
                sendJsonRpc(
                    exchange, idNode, "{\"content\":[],\"structuredContent\":{\"answer\":42}}");
              } else if ("large_tool".equals(toolName)) {
                sendJsonRpc(
                    exchange,
                    idNode,
                    "{\"content\":[{\"type\":\"text\",\"text\":\""
                        + "x".repeat(21 * 1024 * 1024)
                        + "\"}]}");
              } else {
                sendJsonRpc(
                    exchange,
                    idNode,
                    "{\"content\":[{\"type\":\"text\",\"text\":\"remote_success\"}]}");
              }
            } else {
              exchange.sendResponseHeaders(404, -1);
            }
          } else {
            exchange.sendResponseHeaders(405, -1);
          }
        });
    server.start();
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  /** 验证 Remote client 握手、工具发现、工具调用与自定义 header 传递。 */
  @Test
  void createsRemoteClientAndExecutesTool() {
    RemoteMcpConfig config =
        new RemoteMcpConfig(serverUrl, Map.of("Authorization", "Bearer test_token_123"));
    try (McpClient client = McpClientFactory.createRemote(config, Duration.ofSeconds(5))) {
      List<McpToolDefinition> tools =
          client.listTools(McpDeadline.of(Duration.ofSeconds(5)), McpCancellationToken.none());
      assertThat(tools).hasSize(1);
      assertThat(tools.getFirst().name()).isEqualTo("remote_tool");

      McpToolCallResult result =
          client.callTool(
              "remote_tool",
              "{}",
              McpDeadline.of(Duration.ofSeconds(5)),
              McpCancellationToken.none());
      assertThat(result.error()).isFalse();
      assertThat(result.contents()).hasSize(1);
      assertThat(((TextResultContent) result.contents().getFirst()).text())
          .isEqualTo("remote_success");

      assertThat(receivedAuthHeader.get()).isEqualTo("Bearer test_token_123");
    }
  }

  /** 真实 HTTP 链路同样按 SDK 结果映射：content 为空时使用结构化结果，isError 结果映射为可读错误文本。 */
  @Test
  void mapsStructuredAndErrorResultsOverRealTransport() {
    RemoteMcpConfig config = new RemoteMcpConfig(serverUrl, Map.of());
    try (McpClient client = McpClientFactory.createRemote(config, Duration.ofSeconds(5))) {
      McpToolCallResult structured =
          client.callTool(
              "structured_tool",
              "{}",
              McpDeadline.of(Duration.ofSeconds(5)),
              McpCancellationToken.none());
      assertThat(structured.error()).isFalse();
      assertThat(structured.contents()).containsExactly(new TextResultContent("{\"answer\":42}"));

      McpToolCallResult error =
          client.callTool(
              "error_tool",
              "{}",
              McpDeadline.of(Duration.ofSeconds(5)),
              McpCancellationToken.none());
      assertThat(error.error()).isTrue();
      assertThat(error.contents()).containsExactly(new TextResultContent("tool_error"));
    }
  }

  /** 验证远端客户端在传输无本地协议中止器时，令牌取消能够直接终止本地等待，且不需要外部中断调用线程。 */
  @Test
  @Timeout(15)
  void tokenCancellationDirectlyTerminatesLocalWaitOnRemoteClient() {
    RemoteMcpConfig config = new RemoteMcpConfig(serverUrl, Map.of());
    McpClient client = McpClientFactory.createRemote(config, Duration.ofSeconds(10));
    try {
      McpCancellationToken token = new McpCancellationToken();
      long startNanos = System.nanoTime();

      Thread canceller =
          new Thread(
              () -> {
                try {
                  Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
                token.cancel();
              });
      canceller.start();

      assertThatThrownBy(
              () ->
                  client.callTool("slow_tool", "{}", McpDeadline.of(Duration.ofSeconds(10)), token))
          .isInstanceOf(McpCancelledException.class)
          .hasMessageContaining("cancelled");

      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
      assertThat(elapsedMillis).as("必须迅速终止本地等待").isLessThan(3_000L);
      assertThat(Thread.currentThread().isInterrupted()).as("调用者线程不应被外部中断").isFalse();
    } finally {
      client.close();
    }
  }

  /** SDK 16 MiB 报文与 Jackson 默认字符串限制不得拒绝仍在既有 64 MiB 内容预算内的响应。 */
  @Test
  void preservesContentBudgetBeyondSdkAndJacksonDefaults() {
    try (McpClient client =
        McpClientFactory.createRemote(
            new RemoteMcpConfig(serverUrl, Map.of()), Duration.ofSeconds(10))) {
      McpToolCallResult result =
          client.callTool(
              "large_tool",
              "{}",
              McpDeadline.of(Duration.ofSeconds(10)),
              McpCancellationToken.none());
      assertThat(result.error()).isFalse();
      assertThat(((TextResultContent) result.contents().getFirst()).text())
          .hasSize(21 * 1024 * 1024);
    }
  }

  private static void sendJsonRpc(HttpExchange exchange, JsonNode id, String resultJson)
      throws IOException {
    ObjectNode response = MAPPER.createObjectNode();
    response.put("jsonrpc", "2.0");
    if (id != null) {
      response.set("id", id);
    }
    response.set("result", MAPPER.readTree(resultJson));
    byte[] bytes = MAPPER.writeValueAsBytes(response);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
