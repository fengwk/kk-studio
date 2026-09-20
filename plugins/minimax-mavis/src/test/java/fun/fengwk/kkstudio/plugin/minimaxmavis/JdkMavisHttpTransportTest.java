package fun.fengwk.kkstudio.plugin.minimaxmavis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 生产传输实现的行为验证。
 *
 * <p>只使用本机 loopback {@link HttpServer}，不访问真实网络。这里锁定三件必须成立的事：请求按 HTTP/1.1 发送并带上 header/body、302
 * 不会被自动跟随、传输失败一律映射为不含凭据的 {@link MavisTransportException}。
 */
class JdkMavisHttpTransportTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private final List<RecordedExchange> recorded = new ArrayList<>();
  private HttpServer server;
  private String origin;

  /** 每次测试都在随机 loopback 端口上启动一个只服务固定路径的服务器。 */
  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/echo", exchange -> finish(exchange, 200, "{\"ok\":true}"));
    server.createContext(
        "/redirect",
        exchange -> {
          exchange.getResponseHeaders().set("Location", "/echo");
          finish(exchange, 302, "");
        });
    server.createContext(
        "/slow",
        exchange -> {
          sleepQuietly(Duration.ofMillis(1_500));
          finish(exchange, 200, "{}");
        });
    server.start();
    origin = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  /** 请求按 HTTP/1.1 发送，header 与 body 原样到达服务端。 */
  @Test
  void sendsHttp11RequestWithHeadersAndBody() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Authorization", "Bearer token-value");
    headers.put("Content-Type", "application/json");
    MavisHttpResponse response =
        new JdkMavisHttpTransport()
            .send(
                new MavisHttpRequest(
                    "POST", origin + "/echo", headers, "{\"query\":\"mavis\"}", TIMEOUT));

    assertEquals(200, response.statusCode());
    assertEquals("{\"ok\":true}", response.body());
    RecordedExchange exchange = recorded.get(0);
    assertEquals("POST", exchange.method());
    assertEquals("HTTP/1.1", exchange.protocol());
    assertEquals("{\"query\":\"mavis\"}", exchange.body());
    assertEquals("Bearer token-value", exchange.headers().get("authorization"));
  }

  /** 302 原样返回，不被自动跟随，也不会产生第二个请求。 */
  @Test
  void doesNotFollowRedirects() {
    MavisHttpResponse response =
        new JdkMavisHttpTransport()
            .send(new MavisHttpRequest("GET", origin + "/redirect", Map.of(), null, TIMEOUT));

    assertEquals(302, response.statusCode());
    assertEquals(1, recorded.size());
    assertEquals("/redirect", recorded.get(0).path());
  }

  /** 超时映射为传输错误，消息只包含 JDK 异常类型名。 */
  @Test
  void mapsRequestTimeoutToTransportException() {
    MavisTransportException error =
        assertThrows(
            MavisTransportException.class,
            () ->
                new JdkMavisHttpTransport()
                    .send(
                        new MavisHttpRequest(
                            "GET", origin + "/slow", Map.of(), null, Duration.ofMillis(200))));

    assertTrue(error.getMessage().contains("Timeout"), error.getMessage());
  }

  /** 连接失败映射为传输错误。 */
  @Test
  void mapsConnectionFailureToTransportException() throws IOException {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      closedPort = socket.getLocalPort();
    }

    MavisTransportException error =
        assertThrows(
            MavisTransportException.class,
            () ->
                new JdkMavisHttpTransport()
                    .send(
                        new MavisHttpRequest(
                            "GET",
                            "http://127.0.0.1:" + closedPort + "/closed",
                            Map.of(),
                            null,
                            TIMEOUT)));

    assertEquals("ConnectException", error.getMessage());
  }

  /** 非法 URL 在构造请求阶段就映射为传输错误，不产生任何网络调用。 */
  @Test
  void mapsInvalidUrlToTransportException() {
    MavisTransportException error =
        assertThrows(
            MavisTransportException.class,
            () ->
                new JdkMavisHttpTransport()
                    .send(
                        new MavisHttpRequest(
                            "GET", "http://127.0.0.1/bad path", Map.of(), null, TIMEOUT)));

    assertEquals("IllegalArgumentException", error.getMessage());
    assertTrue(recorded.isEmpty());
  }

  private void finish(HttpExchange exchange, int status, String body) {
    try {
      recorded.add(
          new RecordedExchange(
              exchange.getRequestMethod(),
              exchange.getProtocol(),
              exchange.getRequestURI().getPath(),
              requestHeaders(exchange),
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
      byte[] payload = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, payload.length);
      exchange.getResponseBody().write(payload);
    } catch (IOException ignored) {
      // 客户端可能已超时并关闭连接，这里没有可恢复动作。
    } finally {
      exchange.close();
    }
  }

  private static Map<String, String> requestHeaders(HttpExchange exchange) {
    Map<String, String> headers = new LinkedHashMap<>();
    exchange
        .getRequestHeaders()
        .forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), values.get(0)));
    return headers;
  }

  private static void sleepQuietly(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    }
  }

  private record RecordedExchange(
      String method, String protocol, String path, Map<String, String> headers, String body) {}
}
