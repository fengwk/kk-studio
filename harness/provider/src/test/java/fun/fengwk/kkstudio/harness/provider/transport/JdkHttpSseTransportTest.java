package fun.fengwk.kkstudio.harness.provider.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JdkHttpSseTransport 核心流传输器综合测试。
 *
 * <p>覆盖与上游 LangChain4j 对齐的错误正文换行/编码、流溢出断连、异步取消关闭连接、超时， 以及本仓的各取消窗口、Terminal-Once 保证、Callback
 * 异常隔离、Executor 拒绝、 Content-Type 校验、3xx 重定向拒绝、错误正文截断与 Secret-Safe 敏感数据脱敏。
 */
class JdkHttpSseTransportTest {

  private HttpServer httpServer;
  private ExecutorService executor;
  private HttpClient httpClient;
  private JdkHttpSseTransport transport;
  private int serverPort;

  @BeforeEach
  void setUp() throws IOException {
    httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    serverPort = httpServer.getAddress().getPort();
    httpServer.start();

    executor = Executors.newVirtualThreadPerTaskExecutor();
    httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    transport = new JdkHttpSseTransport(httpClient, executor);
  }

  @AfterEach
  void tearDown() {
    if (httpServer != null) {
      httpServer.stop(0);
    }
    if (executor != null) {
      executor.shutdownNow();
    }
  }

  /** 对应上游 should_preserve_line_separators_of_error_response_body：验证非 2xx 响应的错误正文字节完整保留原始换行符。 */
  @Test
  void should_preserve_line_separators_of_error_response_body() throws Exception {
    String errorBody = "line1\r\nline2\r\n";
    httpServer.createContext(
        "/error-crlf",
        exchange -> {
          byte[] bytes = errorBody.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(400, bytes.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
          }
        });

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/error-crlf"))
            .GET()
            .build();
    RecordingCallback callback = new RecordingCallback();
    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertNotNull(callback.error);
    assertEquals(TransportErrorKind.HTTP_STATUS, callback.error.kind());
    assertEquals(400, callback.error.statusCode());
    assertEquals(errorBody, new String(callback.error.errorBodyBytes(), StandardCharsets.UTF_8));
  }

  /** 对应上游 should_decode_error_response_body_as_utf8：验证非 2xx 响应能够读取多字节 UTF-8 错误正文字节。 */
  @Test
  void should_decode_error_response_body_as_utf8() throws Exception {
    String errorBody = "모델 오류 / 错误详情 / Error detail";
    httpServer.createContext(
        "/error-utf8",
        exchange -> {
          byte[] bytes = errorBody.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(429, bytes.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
          }
        });

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/error-utf8"))
            .GET()
            .build();
    RecordingCallback callback = new RecordingCallback();
    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertNotNull(callback.error);
    assertEquals(TransportErrorKind.HTTP_STATUS, callback.error.kind());
    assertEquals(429, callback.error.statusCode());
    assertEquals(errorBody, new String(callback.error.errorBodyBytes(), StandardCharsets.UTF_8));
  }

  /** 对应上游 should_not_fail_on_successful_response：验证正常 200 text/event-stream 响应成功解析并完成流。 */
  @Test
  void should_not_fail_on_successful_response() throws Exception {
    httpServer.createContext(
        "/success-sse",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: hello\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
          }
        });

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/success-sse"))
            .GET()
            .build();
    RecordingCallback callback = new RecordingCallback();
    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertNull(callback.error);
    assertTrue(callback.completed);
    assertEquals(1, callback.events.size());
    assertEquals("hello", callback.events.get(0).data());
  }

  /**
   * 对应上游 overflowing_the_stream_buffer_aborts_the_request_and_closes_the_connection：超限时立即关闭连接与套接字。
   */
  @Test
  void overflowing_the_stream_buffer_aborts_the_request_and_closes_the_connection()
      throws Exception {
    CountDownLatch serverAccepted = new CountDownLatch(1);
    CountDownLatch clientDisconnected = new CountDownLatch(1);

    try (ServerSocket serverSocket = new ServerSocket(0)) {
      int port = serverSocket.getLocalPort();
      Thread serverThread =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try (Socket socket = serverSocket.accept()) {
                      serverAccepted.countDown();
                      InputStream in = socket.getInputStream();
                      OutputStream out = socket.getOutputStream();

                      // 读取请求头直到双换行
                      StringBuilder sb = new StringBuilder();
                      int b;
                      while ((b = in.read()) != -1) {
                        sb.append((char) b);
                        if (sb.length() >= 4
                            && "\r\n\r\n".contentEquals(sb.substring(sb.length() - 4))) {
                          break;
                        }
                      }

                      out.write(
                          "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\n"
                              .getBytes(StandardCharsets.UTF_8));
                      out.flush();

                      // 持续发送超长事件直到客户端因为超限关闭连接
                      byte[] event =
                          "data: token-data-payload-chunk\n".getBytes(StandardCharsets.UTF_8);
                      while (true) {
                        out.write(event);
                        out.flush();
                      }
                    } catch (IOException expected) {
                      // 客户端关闭了连接，写操作报错，符合预期
                      clientDisconnected.countDown();
                    }
                  });

      // 设置非常严格的 maxEventBytes (100字节)
      HttpSseLimits limits = new HttpSseLimits(1024, 100, 1024 * 1024, 1024);
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/stream")).GET().build();
      RecordingCallback callback = new RecordingCallback();
      transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, limits, callback);

      assertTrue(serverAccepted.await(5, TimeUnit.SECONDS), "server must accept connection");
      assertTrue(
          clientDisconnected.await(5, TimeUnit.SECONDS),
          "overflowing buffer must close socket and disconnect");
      assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
      assertNotNull(callback.error);
      assertEquals(TransportErrorKind.INVALID_RESPONSE, callback.error.kind());
    }
  }

  /** 对应上游 cancelling_the_future_releases_the_caller：取消操作立即返回，不阻塞调用方。 */
  @Test
  void cancelling_the_future_releases_the_caller() throws Exception {
    CountDownLatch serverHangLatch = new CountDownLatch(1);
    httpServer.createContext(
        "/hang",
        exchange -> {
          serverHangLatch.countDown();
          try {
            Thread.sleep(10_000);
          } catch (InterruptedException ignored) {
          }
        });

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/hang"))
            .GET()
            .build();
    RecordingCallback callback = new RecordingCallback();
    ProviderStream stream =
        transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(serverHangLatch.await(5, TimeUnit.SECONDS));
    long start = System.nanoTime();
    stream.cancel();
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    assertTrue(stream.isCancelled());
    assertTrue(elapsedMillis < 500, "cancel must release caller immediately");
    // 静默取消，不触发任何回调
    assertFalse(callback.completed);
    assertNull(callback.error);
  }

  /**
   * 对应上游 cancelling_the_future_aborts_the_request_and_closes_the_connection：取消操作中断在途请求并关闭 TCP 连接。
   */
  @Test
  void cancelling_the_future_aborts_the_request_and_closes_the_connection() throws Exception {
    CountDownLatch serverAccepted = new CountDownLatch(1);
    CountDownLatch clientClosedSocket = new CountDownLatch(1);
    AtomicReference<Socket> socketRef = new AtomicReference<>();

    try (ServerSocket serverSocket = new ServerSocket(0)) {
      int port = serverSocket.getLocalPort();
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  Socket socket = serverSocket.accept();
                  socketRef.set(socket);
                  serverAccepted.countDown();
                  InputStream in = socket.getInputStream();
                  int b;
                  while ((b = in.read()) != -1) {
                    // 消费数据
                  }
                  clientClosedSocket.countDown();
                } catch (IOException ignored) {
                  clientClosedSocket.countDown();
                }
              });

      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/hang")).GET().build();
      RecordingCallback callback = new RecordingCallback();
      ProviderStream stream =
          transport.stream(
              request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

      assertTrue(serverAccepted.await(5, TimeUnit.SECONDS));
      Thread.sleep(50); // 确保请求字节已到达并被服务端接收
      stream.cancel();
      boolean closed = clientClosedSocket.await(5, TimeUnit.SECONDS);
      Socket s = socketRef.get();
      if (s != null) {
        try {
          s.close();
        } catch (IOException ignored) {
        }
      }
      assertTrue(closed, "cancelling must close socket connection from client");
    }
  }

  /** 对应上游 should_timeout_on_read_async：验证无活动闲置超时能够被 Watchdog 准确触发。 */
  @Test
  void should_timeout_on_read_async() throws Exception {
    httpServer.createContext(
        "/slow-stream",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: first\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            // 之后挂起不发送数据，触发 idle timeout
            Thread.sleep(5_000);
          } catch (InterruptedException ignored) {
          }
        });

    ModelCallTimeoutPolicy shortTimeout =
        new ModelCallTimeoutPolicy(Duration.ofSeconds(10), Duration.ofMillis(100));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/slow-stream"))
            .GET()
            .build();
    RecordingCallback callback = new RecordingCallback();
    transport.stream(request, shortTimeout, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertNotNull(callback.error);
    assertEquals(TransportErrorKind.TIMEOUT, callback.error.kind());
    assertEquals(1, callback.events.size());
    assertEquals("first", callback.events.get(0).data());
  }

  /** 契约测试：验证总调用时长（Total Timeout）超时判定。 */
  @Test
  void total_timeout_triggers_failure() throws Exception {
    httpServer.createContext(
        "/continuous-stream",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            for (int i = 0; i < 20; i++) {
              os.write("data: tick\n\n".getBytes(StandardCharsets.UTF_8));
              os.flush();
              Thread.sleep(40);
            }
          } catch (Exception ignored) {
          }
        });

    // 总超时 100ms，闲置超时 500ms：验证即使持续有心跳事件，总时长超期仍会触发超时终态
    ModelCallTimeoutPolicy shortTotalTimeout =
        new ModelCallTimeoutPolicy(Duration.ofMillis(100), Duration.ofMillis(500));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/continuous-stream"))
            .GET()
            .build();
    RecordingCallback callback = new RecordingCallback();
    transport.stream(request, shortTotalTimeout, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertNotNull(callback.error);
    assertEquals(TransportErrorKind.TIMEOUT, callback.error.kind());
  }

  /** 契约测试：验证四个不同取消窗口的幂等性与静默性。 */
  @Test
  void cancellation_windows_are_safe_and_idempotent() throws Exception {
    httpServer.createContext(
        "/success-sse",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: ok\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
          }
        });

    // 窗口 1：在请求还未真正启动或刚创建时取消
    HttpRequest request1 =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/success-sse"))
            .GET()
            .build();
    RecordingCallback cb1 = new RecordingCallback();
    ProviderStream s1 =
        transport.stream(request1, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, cb1);
    s1.cancel();
    s1.cancel(); // 幂等调用
    assertTrue(s1.isCancelled());

    // 窗口 2：收到事件后在流读取中取消
    CountDownLatch firstEventDelivered = new CountDownLatch(1);
    AtomicReference<ProviderStream> s2Ref = new AtomicReference<>();
    httpServer.createContext(
        "/stream-mid-cancel",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: 1\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            firstEventDelivered.await(5, TimeUnit.SECONDS);
            for (int i = 2; i <= 10; i++) {
              os.write(("data: " + i + "\n\n").getBytes(StandardCharsets.UTF_8));
              os.flush();
              Thread.sleep(50);
            }
          } catch (Exception ignored) {
          }
        });

    HttpRequest request2 =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/stream-mid-cancel"))
            .GET()
            .build();
    RecordingCallback cb2 =
        new RecordingCallback() {
          @Override
          public void onEvent(ServerSentEvent event) {
            super.onEvent(event);
            firstEventDelivered.countDown();
            ProviderStream st = s2Ref.get();
            if (st != null) {
              st.cancel();
            }
          }
        };
    ProviderStream s2 =
        transport.stream(request2, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, cb2);
    s2Ref.set(s2);

    assertTrue(firstEventDelivered.await(5, TimeUnit.SECONDS));
    Thread.sleep(150);
    // 取消后不能触发 completed，也不能触发 error
    assertFalse(cb2.completed);
    assertNull(cb2.error);
    assertEquals(1, cb2.events.size());

    // 窗口 3：流正常完成之后再次 cancel()
    HttpRequest request3 =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/success-sse"))
            .GET()
            .build();
    RecordingCallback cb3 = new RecordingCallback();
    ProviderStream s3 =
        transport.stream(request3, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, cb3);
    assertTrue(cb3.latch.await(5, TimeUnit.SECONDS));
    if (cb3.error != null) {
      System.err.println("CB3 ERROR: " + cb3.error + " cause=" + cb3.error.getCause());
    }
    assertNull(cb3.error);
    assertTrue(cb3.completed);
    s3.cancel(); // 终态后取消是安全的 no-op
    assertTrue(s3.isCancelled());
  }

  /** 契约测试：Terminal-Once 保证，即便并发竞争终态，onComplete / onFailure 也绝对至多执行一次。 */
  @Test
  void terminal_callback_is_invoked_at_most_once() throws Exception {
    AtomicInteger terminalCount = new AtomicInteger();
    CountDownLatch done = new CountDownLatch(1);

    httpServer.createContext(
        "/terminal-once",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: done\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
          }
        });

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/terminal-once"))
            .GET()
            .build();
    transport.stream(
        request,
        ModelCallTimeoutPolicy.DEFAULT,
        HttpSseLimits.DEFAULT,
        new HttpSseCallback() {
          @Override
          public void onOpen(HttpOpenMetadata metadata) {}

          @Override
          public void onEvent(ServerSentEvent event) {}

          @Override
          public void onComplete() {
            terminalCount.incrementAndGet();
            done.countDown();
          }

          @Override
          public void onFailure(TransportException error) {
            terminalCount.incrementAndGet();
            done.countDown();
          }
        });

    assertTrue(done.await(5, TimeUnit.SECONDS));
    Thread.sleep(100);
    assertEquals(1, terminalCount.get());
  }

  /** 契约测试：Callback 内部抛出异常不会引发第二终态或资源泄漏。 */
  @Test
  void callback_exception_is_isolated_and_causes_clean_failure() throws Exception {
    httpServer.createContext(
        "/callback-throw",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: bad\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            Thread.sleep(500);
          } catch (Exception ignored) {
          }
        });

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/callback-throw"))
            .GET()
            .build();
    CountDownLatch failureLatch = new CountDownLatch(1);
    AtomicReference<TransportException> captured = new AtomicReference<>();
    AtomicInteger failureCount = new AtomicInteger();

    transport.stream(
        request,
        ModelCallTimeoutPolicy.DEFAULT,
        HttpSseLimits.DEFAULT,
        new HttpSseCallback() {
          @Override
          public void onOpen(HttpOpenMetadata metadata) {}

          @Override
          public void onEvent(ServerSentEvent event) {
            throw new IllegalStateException("Simulated callback handler bug");
          }

          @Override
          public void onComplete() {}

          @Override
          public void onFailure(TransportException error) {
            failureCount.incrementAndGet();
            captured.set(error);
            failureLatch.countDown();
          }
        });

    assertTrue(failureLatch.await(5, TimeUnit.SECONDS));
    Thread.sleep(100);
    assertEquals(1, failureCount.get());
    assertNotNull(captured.get());
    assertEquals(TransportErrorKind.CALLBACK_FAILED, captured.get().kind());
  }

  /** 契约测试：Executor 拒绝提交任务时同步抛出安全的 EXECUTOR_REJECTED 异常，可证明任务未启动。 */
  @Test
  void executor_rejection_throws_synchronously() {
    ExecutorService shutdownExecutor = Executors.newSingleThreadExecutor();
    shutdownExecutor.shutdownNow();

    JdkHttpSseTransport rejectedTransport = new JdkHttpSseTransport(httpClient, shutdownExecutor);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/success-sse"))
            .GET()
            .build();
    RecordingCallback callback = new RecordingCallback();

    TransportException ex =
        assertThrows(
            TransportException.class,
            () ->
                rejectedTransport.stream(
                    request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback));

    assertEquals(TransportErrorKind.EXECUTOR_REJECTED, ex.kind());
    assertFalse(callback.completed);
    assertNull(callback.error);
  }

  /** 契约测试：Content-Type 格式校验（包括参数与大小写合法性）。 */
  @Test
  void content_type_validation() throws Exception {
    // 1. 合法大写 TEXT/EVENT-STREAM; CHARSET=UTF-8
    httpServer.createContext(
        "/ct-uppercase",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "TEXT/EVENT-STREAM; CHARSET=UTF-8");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: ok\n\n".getBytes(StandardCharsets.UTF_8));
          }
        });
    RecordingCallback cb1 = new RecordingCallback();
    transport.stream(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/ct-uppercase"))
            .GET()
            .build(),
        ModelCallTimeoutPolicy.DEFAULT,
        HttpSseLimits.DEFAULT,
        cb1);
    assertTrue(cb1.latch.await(5, TimeUnit.SECONDS));
    assertTrue(cb1.completed);

    // 2. 非法 application/json
    httpServer.createContext(
        "/ct-json",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("{\"error\": true}".getBytes(StandardCharsets.UTF_8));
          }
        });
    RecordingCallback cb2 = new RecordingCallback();
    transport.stream(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/ct-json"))
            .GET()
            .build(),
        ModelCallTimeoutPolicy.DEFAULT,
        HttpSseLimits.DEFAULT,
        cb2);
    assertTrue(cb2.latch.await(5, TimeUnit.SECONDS));
    assertNotNull(cb2.error);
    assertEquals(TransportErrorKind.INVALID_RESPONSE, cb2.error.kind());

    // 3. 非法 charset (gbk)
    httpServer.createContext(
        "/ct-gbk",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=gbk");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: gbk\n\n".getBytes(StandardCharsets.UTF_8));
          }
        });
    RecordingCallback cb3 = new RecordingCallback();
    transport.stream(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/ct-gbk"))
            .GET()
            .build(),
        ModelCallTimeoutPolicy.DEFAULT,
        HttpSseLimits.DEFAULT,
        cb3);
    assertTrue(cb3.latch.await(5, TimeUnit.SECONDS));
    assertNotNull(cb3.error);
    assertEquals(TransportErrorKind.INVALID_RESPONSE, cb3.error.kind());
  }

  /** 契约测试：3xx 重定向严格拒绝，作为 HTTP_STATUS 错误处理且不跟随。 */
  @Test
  void redirects_are_not_followed_and_fail_with_http_status() throws Exception {
    httpServer.createContext(
        "/redirect",
        exchange -> {
          exchange.getResponseHeaders().set("Location", "/destination");
          byte[] body = "redirect body".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(307, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/redirect"))
            .GET()
            .build();
    RecordingCallback callback = new RecordingCallback();
    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertNotNull(callback.error);
    assertEquals(TransportErrorKind.HTTP_STATUS, callback.error.kind());
    assertEquals(307, callback.error.statusCode());
    assertEquals(
        "redirect body", new String(callback.error.errorBodyBytes(), StandardCharsets.UTF_8));
  }

  /** 契约测试：非 2xx 响应的错误正文超过上限时被严格截断读取并关闭输入流。 */
  @Test
  void error_body_is_truncated_to_configured_limit() throws Exception {
    byte[] hugeError = new byte[10_000];
    for (int i = 0; i < hugeError.length; i++) {
      hugeError[i] = (byte) ('a' + (i % 26));
    }
    httpServer.createContext(
        "/huge-error",
        exchange -> {
          exchange.sendResponseHeaders(500, hugeError.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(hugeError);
          }
        });

    HttpSseLimits limit500 = new HttpSseLimits(1024, 1024, 1024 * 1024, 500);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/huge-error"))
            .GET()
            .build();
    RecordingCallback callback = new RecordingCallback();
    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, limit500, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertNotNull(callback.error);
    assertEquals(500, callback.error.statusCode());
    assertEquals(500, callback.error.errorBodyBytes().length);
  }

  /** 契约测试：Secret-Safe 严格验证，保证 toString/message/cause 链绝对不泄漏敏感 URI、Token、Header 或错误体。 */
  @Test
  void secret_safety_guarantees() throws Exception {
    String sensitiveUriSecret = "SUPER_SECRET_TOKEN_IN_URI_12345";
    String sensitiveHeaderSecret = "BEARER_AUTH_TOKEN_99999";
    String sensitiveBodyLeak = "INTERNAL_CREDENTIAL_KEY_LEAK";

    httpServer.createContext(
        "/secure-endpoint",
        exchange -> {
          byte[] body = sensitiveBodyLeak.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("X-Api-Key", "SECRET_KEY_HEADER");
          exchange.getResponseHeaders().set("Authorization", sensitiveHeaderSecret);
          exchange.getResponseHeaders().set("Safe-Header", "SafeValue");
          exchange.sendResponseHeaders(401, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });

    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + serverPort
                        + "/secure-endpoint?token="
                        + sensitiveUriSecret))
            .header("Authorization", "Bearer " + sensitiveHeaderSecret)
            .header("X-Api-Key", "top-secret-key")
            .GET()
            .build();

    RecordingCallback callback = new RecordingCallback();
    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    TransportException ex = callback.error;
    assertNotNull(ex);

    String toString = ex.toString();
    String message = ex.getMessage();

    // 1. toString 与 message 绝对不含敏感信息
    assertFalse(toString.contains(sensitiveUriSecret), "toString must not leak URI secret");
    assertFalse(toString.contains(sensitiveHeaderSecret), "toString must not leak header secret");
    assertFalse(toString.contains(sensitiveBodyLeak), "toString must not leak body secret");

    assertFalse(message.contains(sensitiveUriSecret), "message must not leak URI secret");
    assertFalse(message.contains(sensitiveHeaderSecret), "message must not leak header secret");
    assertFalse(message.contains(sensitiveBodyLeak), "message must not leak body secret");

    // 2. 整个异常原因链不包含敏感词
    Throwable current = ex;
    while (current != null) {
      String causeMsg = String.valueOf(current.getMessage());
      assertFalse(causeMsg.contains(sensitiveUriSecret));
      assertFalse(causeMsg.contains(sensitiveHeaderSecret));
      assertFalse(causeMsg.contains(sensitiveBodyLeak));
      current = current.getCause();
    }

    // 3. safeHeaders 清洗验证
    assertFalse(ex.safeHeaders().containsKey("Authorization"));
    assertFalse(ex.safeHeaders().containsKey("authorization"));
    assertFalse(ex.safeHeaders().containsKey("X-Api-Key"));
    assertFalse(ex.safeHeaders().containsKey("x-api-key"));
    assertTrue(ex.safeHeaders().containsKey("Safe-Header"));

    // 4. errorBodyBytes 防御拷贝与协议层读取
    byte[] errorBytes = ex.errorBodyBytes();
    assertEquals(sensitiveBodyLeak, new String(errorBytes, StandardCharsets.UTF_8));
    // 修改返回的数组不污染内部状态
    errorBytes[0] = 'X';
    assertEquals(sensitiveBodyLeak, new String(ex.errorBodyBytes(), StandardCharsets.UTF_8));
  }

  private static class RecordingCallback implements HttpSseCallback {
    final CountDownLatch latch = new CountDownLatch(1);
    final List<ServerSentEvent> events = new ArrayList<>();
    volatile HttpOpenMetadata metadata;
    volatile boolean completed;
    volatile TransportException error;

    @Override
    public void onOpen(HttpOpenMetadata metadata) {
      this.metadata = metadata;
    }

    @Override
    public void onEvent(ServerSentEvent event) {
      events.add(event);
    }

    @Override
    public void onComplete() {
      completed = true;
      latch.countDown();
    }

    @Override
    public void onFailure(TransportException error) {
      this.error = error;
      latch.countDown();
    }
  }
}
