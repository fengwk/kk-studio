package fun.fengwk.kkstudio.platform.canvas.function.opencli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import fun.fengwk.kkstudio.platform.canvas.function.opencli.OpenCliHubClient.ExecutionResource;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
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

/** 使用真实本机 HTTP 响应验证 headers 与 body 的超时边界，而非模拟 InputStream。 */
@Timeout(10)
class OpenCliResponseDeadlineTest {

  @Test
  void stalledJsonAndErrorBodiesFailAndCloseRealConnection() throws Exception {
    // 不完整 2xx JSON 和非 2xx 错误都必须受整体预算约束，并通知服务端连接关闭。
    for (int status : new int[] {200, 500}) {
      try (Endpoint endpoint = new Endpoint(status, "100", "application/json", "{", true)) {
        OpenCliHubException failure =
            assertThrows(
                OpenCliHubException.class, () -> endpoint.client().getExecution("exec-1", 0));
        assertTrue(hasTimeout(failure));
        assertFalse(failure.getMessage().contains(endpoint.origin()));
        assertEquals(-1, endpoint.peerRead.get(1, TimeUnit.SECONDS));
        assertEquals(0, DeadlineResponseBody.pendingWatchdogTasks());
      }
    }
  }

  @Test
  void stalledResourceReadIsInterruptedAndCloseIsIdempotent() throws Exception {
    // 流交给调用方后仍有原始预算；read 正在网络等待时，关闭 body 必须打断它。
    try (Endpoint endpoint = new Endpoint(200, "100", "image/png", "x", true);
        var resource = endpoint.client().openResource(resource(100))) {
      assertEquals('x', resource.content().read());
      assertThrows(HttpTimeoutException.class, () -> resource.content().read());
      resource.close();
      resource.close();
      assertThrows(HttpTimeoutException.class, () -> resource.content().read());
      assertEquals(-1, endpoint.peerRead.get(1, TimeUnit.SECONDS));
      assertEquals(0, DeadlineResponseBody.pendingWatchdogTasks());
    }
  }

  @Test
  void stalledResourceErrorBodyAlsoExpiresAndClosesConnection() throws Exception {
    // openResource 的非 2xx 分支同样读取错误体，不能绕过统一 deadline。
    try (Endpoint endpoint = new Endpoint(500, "100", "text/plain", "error", true)) {
      var error =
          assertThrows(
              OpenCliHubException.class, () -> endpoint.client().openResource(resource(100)));
      assertTrue(hasTimeout(error));
      assertEquals(-1, endpoint.peerRead.get(1, TimeUnit.SECONDS));
      assertEquals(0, DeadlineResponseBody.pendingWatchdogTasks());
    }
  }

  @Test
  void unconsumedResourceExpiresWithoutReadAndDoesNotRestartBudget() throws Exception {
    // 即便调用方没有开始读取，看门狗也会到期；之后读不能把超时产生的 EOF 当成功。
    try (Endpoint endpoint = new Endpoint(200, "100", "image/png", "x", true);
        var resource = endpoint.client().openResource(resource(100))) {
      assertEquals(-1, endpoint.peerRead.get(2, TimeUnit.SECONDS));
      assertThrows(HttpTimeoutException.class, () -> resource.content().readAllBytes());
      assertEquals(0, DeadlineResponseBody.pendingWatchdogTasks());
    }
  }

  @Test
  void transportRejectsInvalidAndOverflowingContentLengthWithoutEchoingHeader() throws Exception {
    // JDK 21 在返回响应前拒绝非法长度，无 body 可供客户端关闭；此路径只验证安全错误。
    // 客户端已获得 body 后的非法 header 关闭由 ContractTest 单独验证。
    for (String length : new String[] {"credential-secret", "999999999999999999999999"}) {
      try (Endpoint endpoint = new Endpoint(200, length, "image/png", "x", true)) {
        var error =
            assertThrows(
                OpenCliHubException.class, () -> endpoint.client().openResource(resource(0)));
        assertFalse(error.getMessage().contains(length));
        assertEquals(null, error.getCause());
        assertEquals(0, DeadlineResponseBody.pendingWatchdogTasks());
      }
    }
  }

  @Test
  void waitingForHeadersConsumesTheReturnedResourceBudget() throws Exception {
    // 控制 headers 放行时机：send 已消耗的时间必须从返回流预算中扣除，而非返回时重新计时。
    CountDownLatch headers = new CountDownLatch(1);
    try (Endpoint endpoint = new Endpoint(200, "100", "image/png", "x", true, headers);
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var opening = workers.submit(() -> endpoint.client(2000).openResource(resource(100)));
      try {
        assertTrue(endpoint.requestSeen.await(1, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> opening.get(1200, TimeUnit.MILLISECONDS));
      } finally {
        headers.countDown();
      }
      try (var resource = opening.get(1, TimeUnit.SECONDS)) {
        assertEquals(-1, endpoint.peerRead.get(1200, TimeUnit.MILLISECONDS));
        assertThrows(HttpTimeoutException.class, () -> resource.content().read());
      }
    } finally {
      headers.countDown();
    }
  }

  @Test
  void metadataMismatchClosesBodyAndTruncatedResourceFails() throws Exception {
    // 有效但不符 metadata 的超大声明应立即关闭，不读 body；短响应不能被当作完整媒体。
    try (Endpoint endpoint =
        new Endpoint(200, Long.toString(Long.MAX_VALUE), "image/png", "x", true)) {
      assertThrows(OpenCliHubException.class, () -> endpoint.client().openResource(resource(100)));
      assertEquals(-1, endpoint.peerRead.get(1, TimeUnit.SECONDS));
    }
    try (Endpoint endpoint = new Endpoint(200, "100", "image/png", "x", false);
        var resource = endpoint.client().openResource(resource(100))) {
      assertThrows(IOException.class, () -> resource.content().readAllBytes());
      assertEquals(0, DeadlineResponseBody.pendingWatchdogTasks());
    }
  }

  @Test
  void normalJsonErrorAndResourcePreserveContracts() throws Exception {
    // 正常响应、错误详情和媒体 EOF 均须保留原契约，同时及时移除看门狗。
    String json =
        """
        {"status":200,"code":"OK","data":{"id":"exec-1","status":"SUCCEEDED",
        "stdout":"","stderr":"","stdoutTruncated":false,"stderrTruncated":false}}
        """;
    try (Endpoint endpoint =
        new Endpoint(200, Integer.toString(json.length()), "application/json", json, false)) {
      assertEquals(
          OpenCliHubClient.ExecutionStatus.SUCCEEDED,
          endpoint.client().getExecution("exec-1", 0).status());
    }
    try (Endpoint endpoint = new Endpoint(503, "4", "text/plain", "busy", false)) {
      var error =
          assertThrows(
              OpenCliHubException.class, () -> endpoint.client().getExecution("exec-1", 0));
      assertEquals("OpenCLI Hub HTTP 503: busy", error.getMessage());
    }
    try (Endpoint endpoint = new Endpoint(200, "3", "image/png", "abc", false);
        var resource = endpoint.client().openResource(resource(3))) {
      assertEquals("abc", new String(resource.content().readAllBytes(), StandardCharsets.UTF_8));
      assertEquals(-1, resource.content().read());
      assertEquals(3, resource.size());
      assertEquals("image/png", resource.mediaType());
      assertEquals(0, DeadlineResponseBody.pendingWatchdogTasks());
    }
  }

  @Test
  void requestTimeoutDoesNotBoundBodyReadAndJdkCloseUnblocksIt() throws Exception {
    // headers 和部分 JSON 已到达；request.timeout 失效后，只有主动 close 才能解除阻塞。
    CountDownLatch release = new CountDownLatch(1);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try (var workers = Executors.newVirtualThreadPerTaskExecutor();
        var http = HttpClient.newHttpClient()) {
      server.setExecutor(workers);
      server.createContext(
          "/stalled",
          exchange -> {
            try {
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, 100);
              exchange.getResponseBody().write('{');
              exchange.getResponseBody().flush();
              release.await();
            } catch (InterruptedException exception) {
              Thread.currentThread().interrupt();
            } finally {
              exchange.close();
            }
          });
      server.start();
      var request =
          HttpRequest.newBuilder(
                  URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/stalled"))
              .timeout(Duration.ofMillis(500))
              .build();
      try (InputStream body =
          http.send(request, HttpResponse.BodyHandlers.ofInputStream()).body()) {
        assertEquals('{', body.read());
        var reading =
            workers.submit(
                () -> {
                  try {
                    return body.read();
                  } catch (Exception exception) {
                    return -2;
                  }
                });
        assertThrows(TimeoutException.class, () -> reading.get(800, TimeUnit.MILLISECONDS));
        var closing =
            workers.submit(
                () -> {
                  body.close();
                  return null;
                });
        closing.get(1, TimeUnit.SECONDS);
        assertTrue(reading.get(1, TimeUnit.SECONDS) < 0);
      } finally {
        release.countDown();
      }
    } finally {
      release.countDown();
      server.stop(0);
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

  private static ExecutionResource resource(long size) {
    return new ExecutionResource("a.png", "image/png", size, null, "/api/resources/a.png");
  }

  /** 原始本机 HTTP 服务可保留非法 headers，并直接观察客户端是否关闭 TCP 连接。 */
  private static final class Endpoint implements AutoCloseable {
    private final ServerSocket server = new ServerSocket();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient http = HttpClient.newHttpClient();
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
                  socket.setSoTimeout(4000);
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

    OpenCliHubClient client() {
      return client(1000);
    }

    OpenCliHubClient client(long requestTimeoutMillis) {
      var hub =
          new SystemSettings.OpenCliHub(
              true, origin(), 1000, requestTimeoutMillis, 121_000, 1024, 512 * 1024, 4096, 65_535);
      var settings =
          new SystemSettings(
              SystemSettings.Tool.DEFAULT,
              SystemSettings.AiRuntime.DEFAULT,
              SystemSettings.Environment.DEFAULT,
              new SystemSettings.Integrations(
                  SystemSettings.Comfyui.DEFAULT,
                  hub,
                  SystemSettings.Seedance.DEFAULT,
                  SystemSettings.GptImage2.DEFAULT,
                  SystemSettings.MiniMaxH3.DEFAULT),
              SystemSettings.StorageMedia.DEFAULT,
              SystemSettings.Advanced.DEFAULT);
      return new OpenCliHubClient(
          new OpenCliHubProperties(),
          new SystemSettingsSnapshot(settings),
          new ObjectMapper(),
          http);
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
