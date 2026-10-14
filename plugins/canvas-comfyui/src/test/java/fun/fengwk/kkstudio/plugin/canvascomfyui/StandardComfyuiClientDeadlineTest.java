package fun.fengwk.kkstudio.plugin.canvascomfyui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 生产客户端 + 本机 HTTP 服务验证响应体 deadline 与安全失败，而非模拟 InputStream。
 *
 * <p>共享 helper 的看门狗队列生命周期由 harness/common 的包内测试断言；此处只观察客户端边界：连接是否被真实关闭、 返回流的 read/close
 * 失败是否收敛为固定信息、超时类型是否保留且不带原始 cause。
 */
@Timeout(15)
class StandardComfyuiClientDeadlineTest {

  @Test
  void stalledJsonBodyExpiresUnderRequestTimeoutAndClosesRealConnection() throws Exception {
    // request.timeout 只覆盖 headers；慢 body 必须被冻结预算打断，并把连接真正关闭。
    try (Endpoint endpoint = new Endpoint(200, "100", "application/json", "{", true)) {
      StandardComfyuiClient client = endpoint.client(Duration.ofMillis(1000));

      UncheckedIOException failure =
          assertThrows(UncheckedIOException.class, () -> client.history("p1"));

      // 超时类型由固定消息的新 HttpTimeoutException 保留，且不串原始 cause。
      assertTrue(hasTimeout(failure));
      assertEquals(
          "ComfyUI response read exceeded the request deadline", failure.getCause().getMessage());
      assertNull(failure.getCause().getCause());
      assertFalse(failure.getMessage().contains(endpoint.origin()));
      assertEquals(-1, endpoint.peerRead.get(2, TimeUnit.SECONDS));
    }
  }

  @Test
  void stalledViewStreamIsInterruptedAndCloseIsIdempotent() throws Exception {
    // 流交给调用方后仍共享原始预算；read 阻塞时关闭 body 必须打断它，close 幂等且不重试底层关闭。
    try (Endpoint endpoint = new Endpoint(200, "100", "video/mp4", "x", true)) {
      StandardComfyuiClient client = endpoint.client(Duration.ofMillis(1000));
      H3ComfyDownload download = client.download(new H3OutputDescriptor("a.mp4", "out", "output"));
      try {
        assertEquals('x', download.content().read());
        HttpTimeoutException timeout =
            assertThrows(HttpTimeoutException.class, () -> download.content().read());
        assertNull(timeout.getCause());
        download.close();
        download.close();
        assertThrows(HttpTimeoutException.class, () -> download.content().read());
        assertEquals(-1, endpoint.peerRead.get(2, TimeUnit.SECONDS));
      } finally {
        download.close();
      }
    }
  }

  @Test
  void waitingForHeadersConsumesTheReturnedStreamBudget() throws Exception {
    // 控制 headers 放行时机：send 已消耗的时间必须从返回流预算中扣除，而非返回时重新计时。
    CountDownLatch headers = new CountDownLatch(1);
    try (Endpoint endpoint = new Endpoint(200, "100", "video/mp4", "x", true, headers);
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      StandardComfyuiClient client = endpoint.client(Duration.ofMillis(2000));
      var opening =
          workers.submit(() -> client.download(new H3OutputDescriptor("a.mp4", "out", "output")));
      try {
        assertTrue(endpoint.requestSeen.await(1, TimeUnit.SECONDS));
        // headers 未放行前 open 必须返回不了；预算从 send 前冻结，不能重置。
        assertThrows(TimeoutException.class, () -> opening.get(1200, TimeUnit.MILLISECONDS));
      } finally {
        headers.countDown();
      }
      try (H3ComfyDownload download = opening.get(2, TimeUnit.SECONDS)) {
        assertThrows(HttpTimeoutException.class, () -> download.content().readAllBytes());
        assertEquals(-1, endpoint.peerRead.get(2, TimeUnit.SECONDS));
      }
    } finally {
      headers.countDown();
    }
  }

  @Test
  void truncatedViewBodyFailsWithoutReturningPartialMedia() throws Exception {
    // 声明 100 字节却只发 3 字节：只有实际长度不符才判定长度失败，不返回部分媒体。
    try (Endpoint endpoint = new Endpoint(200, "100", "video/mp4", "abc", false)) {
      StandardComfyuiClient client = endpoint.client(Duration.ofSeconds(2));
      H3ComfyDownload download = client.download(new H3OutputDescriptor("a.mp4", "out", "output"));
      try {
        IOException failure =
            assertThrows(IOException.class, () -> download.content().readAllBytes());
        assertEquals("response body ended after 3 of 100 bytes", failure.getMessage());
        assertNull(failure.getCause());
        assertFalse(failure instanceof HttpTimeoutException);
        // 失败后流已关闭，不复用同一 body。
        assertThrows(IOException.class, () -> download.content().read());
      } finally {
        download.close();
      }
    }
  }

  @Test
  void oversizedJsonResponseIsRejectedAtTheBoundedLimit() throws Exception {
    // 超 1MiB 的 2xx JSON 必须在有界读取处失败，而不是完整物化到内存。
    byte[] body =
        ("{\"padding\":\"" + "a".repeat(StandardComfyuiClient.MAX_JSON_BYTES + 16) + "\"}")
            .getBytes(StandardCharsets.UTF_8);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/history/big",
        exchange -> {
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      StandardComfyuiClient client =
          new StandardComfyuiClient(
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "secret",
              Duration.ofSeconds(1),
              Duration.ofSeconds(5),
              new ObjectMapper());

      IllegalArgumentException failure =
          assertThrows(IllegalArgumentException.class, () -> client.history("big"));

      assertTrue(failure.getMessage().contains("exceeded"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void httpErrorsAndTransportFailuresNeverEchoRemoteOrRequestSecrets() throws Exception {
    // 远端正文与 send 自身 IOException 都可能含路径/URL/密钥；公开错误只能给固定事实。
    String secret = "synthetic-secret path/to/agent.conf https://evil.example/leak";
    try (Endpoint endpoint =
        new Endpoint(500, Integer.toString(secret.length()), "text/plain", secret, false)) {
      StandardComfyuiClient client = endpoint.client(Duration.ofSeconds(2));

      IllegalStateException historyFailure =
          assertThrows(IllegalStateException.class, () -> client.history("p1"));

      assertEquals("ComfyUI request failed with HTTP 500", historyFailure.getMessage());
      assertNull(historyFailure.getCause());
      assertFalse(historyFailure.getMessage().contains("synthetic-secret"));
      assertEquals(-1, endpoint.peerRead.get(2, TimeUnit.SECONDS));
    }

    try (Endpoint endpoint =
        new Endpoint(500, Integer.toString(secret.length()), "text/plain", secret, false)) {
      StandardComfyuiClient client = endpoint.client(Duration.ofSeconds(2));

      IllegalStateException viewFailure =
          assertThrows(
              IllegalStateException.class,
              () -> client.download(new H3OutputDescriptor("a.mp4", "out", "output")));

      assertEquals("ComfyUI view failed with HTTP 500", viewFailure.getMessage());
      assertNull(viewFailure.getCause());
      assertFalse(viewFailure.getMessage().contains("synthetic-secret"));
      assertEquals(-1, endpoint.peerRead.get(2, TimeUnit.SECONDS));
    }

    HttpClient failing = mock(HttpClient.class);
    doThrow(new IOException(secret))
        .when(failing)
        .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    StandardComfyuiClient client =
        new StandardComfyuiClient(
            "http://127.0.0.1:1", "secret", Duration.ofSeconds(1), new ObjectMapper(), failing);

    UncheckedIOException transport =
        assertThrows(UncheckedIOException.class, () -> client.history("p1"));

    assertFalse(transport.getMessage().contains("synthetic-secret"));
    assertFalse(transport.getCause().getMessage().contains("synthetic-secret"));
    assertNull(transport.getCause().getCause());
  }

  @Test
  void healthyResponseConsumesExactBytesAndClosesIdempotently() throws Exception {
    // 正常响应仍保留原契约：精确字节、EOF、close 幂等。
    try (Endpoint endpoint = new Endpoint(200, "2", "video/mp4", "ok", false)) {
      StandardComfyuiClient client = endpoint.client(Duration.ofSeconds(2));
      H3ComfyDownload download = client.download(new H3OutputDescriptor("a.mp4", "out", "output"));
      try {
        assertEquals("ok", new String(download.content().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(2L, download.length());
        assertEquals(-1, download.content().read());
        download.close();
        download.close();
      } finally {
        download.close();
      }
      assertEquals(-1, endpoint.peerRead.get(2, TimeUnit.SECONDS));
    }
  }

  private static boolean hasTimeout(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof HttpTimeoutException) {
        return true;
      }
    }
    return false;
  }

  /** 原始本机 HTTP 服务可保留声明长度与实际字节的差异，并直接观察客户端是否关闭 TCP 连接。 */
  private static final class Endpoint implements AutoCloseable {

    private final ServerSocket server = new ServerSocket();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient http = HttpClient.newBuilder().proxy(ProxySelector.of(null)).build();
    private final Future<Integer> peerRead;
    private final CountDownLatch requestSeen = new CountDownLatch(1);
    private volatile Socket accepted;

    Endpoint(int status, String length, String type, String prefix, boolean stall)
        throws IOException {
      this(status, length, type, prefix, stall, new CountDownLatch(0));
    }

    Endpoint(
        int status,
        String length,
        String type,
        String prefix,
        boolean stall,
        CountDownLatch headers)
        throws IOException {
      server.bind(new InetSocketAddress("127.0.0.1", 0));
      peerRead =
          workers.submit(
              () -> {
                try (Socket socket = server.accept()) {
                  accepted = socket;
                  socket.setSoTimeout(5000);
                  var reader =
                      new BufferedReader(
                          new InputStreamReader(
                              socket.getInputStream(), StandardCharsets.US_ASCII));
                  while (!reader.readLine().isEmpty()) {
                    // 消费请求行及 headers；测试请求没有 body。
                  }
                  requestSeen.countDown();
                  headers.await();
                  String response =
                      "HTTP/1.1 "
                          + status
                          + " Test\r\nContent-Length: "
                          + length
                          + "\r\nContent-Type: "
                          + type
                          + "\r\nConnection: close\r\n\r\n"
                          + prefix;
                  socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                  socket.getOutputStream().flush();
                  return stall ? socket.getInputStream().read() : -1;
                }
              });
    }

    String origin() {
      return "http://127.0.0.1:" + server.getLocalPort();
    }

    StandardComfyuiClient client(Duration requestTimeout) {
      return new StandardComfyuiClient(
          origin(), "secret", requestTimeout, new ObjectMapper(), http);
    }

    @Override
    public void close() throws IOException {
      server.close();
      if (accepted != null) {
        accepted.close();
      }
      http.close();
      workers.close();
    }
  }
}
