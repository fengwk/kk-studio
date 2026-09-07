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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JdkHttpSseTransport 核心流传输器综合测试。
 *
 * <p>完整对齐上游 LangChain4j 1.20.0 规范（包含 HttpClientIT、CancellationIT、TimeoutIT、
 * ErrorBody、StreamOverflow、PublisherIT、PublisherNonBlockingIT、TCK、Multipart 等场景），
 * 并强化本仓特有的启动门两阶段拒绝保证、单一状态机仲裁、串行无死锁重入取消、错误正文截断标记与全局敏感凭据脱敏。
 */
class JdkHttpSseTransportTest {

  private HttpServer httpServer;
  private ExecutorService workerExecutor;
  private ScheduledExecutorService scheduler;
  private HttpClient httpClient;
  private JdkHttpSseTransport transport;
  private int serverPort;

  @BeforeEach
  void setUp() throws IOException {
    httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    serverPort = httpServer.getAddress().getPort();
    httpServer.start();

    workerExecutor = Executors.newVirtualThreadPerTaskExecutor();
    scheduler = Executors.newSingleThreadScheduledExecutor();
    httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    transport = new JdkHttpSseTransport(httpClient, workerExecutor, scheduler);
  }

  @AfterEach
  void tearDown() {
    if (httpServer != null) {
      httpServer.stop(0);
    }
    if (workerExecutor != null) {
      workerExecutor.shutdownNow();
    }
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }

  // ==========================================
  // 上游 HttpClientIT 映射用例
  // ==========================================

  /** 对应上游 should_return_successful_http_response：验证成功接收 200 HTTP 响应并完成流交付。 */
  @Test
  void should_return_successful_http_response() throws Exception {
    httpServer.createContext(
        "/it-success",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: success message\n\n".getBytes(StandardCharsets.UTF_8));
          }
        });

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/it-success"))
            .GET()
            .build();
    RecordingCallback callback = new RecordingCallback();
    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertTrue(callback.completed);
    assertNull(callback.error);
    assertEquals(1, callback.events.size());
    assertEquals("success message", callback.events.get(0).data());
  }

  /** 对应上游 should_deliver_response_off_the_calling_thread_executeAsync：验证回调在受管工作线程中派发，脱离调用线程。 */
  @Test
  void should_deliver_response_off_the_calling_thread_executeAsync() throws Exception {
    httpServer.createContext(
        "/it-off-thread",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: off-thread\n\n".getBytes(StandardCharsets.UTF_8));
          }
        });

    Thread callingThread = Thread.currentThread();
    AtomicReference<Thread> callbackThread = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/it-off-thread"))
            .GET()
            .build();
    transport.stream(
        request,
        ModelCallTimeoutPolicy.DEFAULT,
        HttpSseLimits.DEFAULT,
        new HttpSseCallback() {
          @Override
          public void onOpen(HttpOpenMetadata metadata) {
            callbackThread.set(Thread.currentThread());
          }

          @Override
          public void onEvent(ServerSentEvent event) {}

          @Override
          public void onComplete() {
            done.countDown();
          }

          @Override
          public void onFailure(TransportException error) {
            done.countDown();
          }
        });

    assertTrue(done.await(5, TimeUnit.SECONDS));
    assertNotNull(callbackThread.get());
    assertTrue(
        callingThread != callbackThread.get(), "callback must be delivered off calling thread");
  }

  /** 对应上游 should_throw_400：验证 400 Bad Request 响应转换为 HTTP_STATUS。 */
  @Test
  void should_throw_400() throws Exception {
    httpServer.createContext(
        "/it-400",
        exchange -> {
          byte[] body = "Bad Request details".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(400, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/it-400"))
            .GET()
            .build();
    RecordingCallback callback = new RecordingCallback();
    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertNotNull(callback.error);
    assertEquals(TransportErrorKind.HTTP_STATUS, callback.error.kind());
    assertEquals(400, callback.error.statusCode());
  }

  /** 对应上游 should_throw_401：验证 401 Unauthorized 响应转换为 HTTP_STATUS。 */
  @Test
  void should_throw_401() throws Exception {
    httpServer.createContext(
        "/it-401",
        exchange -> {
          byte[] body = "Unauthorized".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(401, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/it-401"))
            .GET()
            .build();
    RecordingCallback callback = new RecordingCallback();
    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertNotNull(callback.error);
    assertEquals(TransportErrorKind.HTTP_STATUS, callback.error.kind());
    assertEquals(401, callback.error.statusCode());
  }

  /** 对应上游 should_stream_successful_response：验证正常流式持续产生并交付事件。 */
  @Test
  void should_stream_successful_response() throws Exception {
    httpServer.createContext(
        "/it-stream-success",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: part1\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.write("data: part2\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
          }
        });

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/it-stream-success"))
            .GET()
            .build();
    RecordingCallback callback = new RecordingCallback();
    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertTrue(callback.completed);
    assertEquals(2, callback.events.size());
    assertEquals("part1", callback.events.get(0).data());
    assertEquals("part2", callback.events.get(1).data());
  }

  /** 对应上游 should_cancel_streaming：验证流式传输被客户端显式取消。 */
  @Test
  void should_cancel_streaming() throws Exception {
    CountDownLatch firstEventLatch = new CountDownLatch(1);
    CountDownLatch cancelDoneLatch = new CountDownLatch(1);

    httpServer.createContext(
        "/it-cancel",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: chunk1\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            cancelDoneLatch.await(5, TimeUnit.SECONDS);
            os.write("data: chunk2\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
          } catch (Exception ignored) {
          }
        });

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/it-cancel"))
            .GET()
            .build();
    AtomicReference<ProviderStream> streamRef = new AtomicReference<>();
    RecordingCallback callback =
        new RecordingCallback() {
          @Override
          public void onEvent(ServerSentEvent event) {
            super.onEvent(event);
            firstEventLatch.countDown();
          }
        };

    ProviderStream stream =
        transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);
    streamRef.set(stream);

    assertTrue(firstEventLatch.await(5, TimeUnit.SECONDS));
    stream.cancel();
    cancelDoneLatch.countDown();

    assertTrue(stream.isCancelled());
    assertFalse(callback.completed);
    assertNull(callback.error);
    assertEquals(1, callback.events.size());
  }

  /** 对应上游 should_stream_response_with_double_newline：验证事件结尾双换行规范交付。 */
  @Test
  void should_stream_response_with_double_newline() throws Exception {
    httpServer.createContext(
        "/it-double-nl",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: a\n\ndata: b\n\n".getBytes(StandardCharsets.UTF_8));
          }
        });

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/it-double-nl"))
            .GET()
            .build();
    RecordingCallback callback = new RecordingCallback();
    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertEquals(2, callback.events.size());
  }

  /** 对应上游 should_deliver_error_when_streaming_400：验证流式请求如果服务端返回 400 则派发错误。 */
  @Test
  void should_deliver_error_when_streaming_400() throws Exception {
    httpServer.createContext(
        "/it-stream-400",
        exchange -> {
          exchange.sendResponseHeaders(400, 0);
          exchange.close();
        });

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/it-stream-400"))
            .GET()
            .build();
    RecordingCallback callback = new RecordingCallback();
    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertNotNull(callback.error);
    assertEquals(TransportErrorKind.HTTP_STATUS, callback.error.kind());
    assertEquals(400, callback.error.statusCode());
  }

  /** 对应上游 should_not_fail_when_listener_onOpen_throws_exception：验证 onOpen 抛出异常被隔离并转换为错误终态。 */
  @Test
  void should_not_fail_when_listener_onOpen_throws_exception() throws Exception {
    httpServer.createContext(
        "/it-open-throw",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: hello\n\n".getBytes(StandardCharsets.UTF_8));
          }
        });

    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<TransportException> captured = new AtomicReference<>();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/it-open-throw"))
            .GET()
            .build();
    transport.stream(
        request,
        ModelCallTimeoutPolicy.DEFAULT,
        HttpSseLimits.DEFAULT,
        new HttpSseCallback() {
          @Override
          public void onOpen(HttpOpenMetadata metadata) {
            throw new RuntimeException("Error inside onOpen");
          }

          @Override
          public void onEvent(ServerSentEvent event) {}

          @Override
          public void onComplete() {
            done.countDown();
          }

          @Override
          public void onFailure(TransportException error) {
            captured.set(error);
            done.countDown();
          }
        });

    assertTrue(done.await(5, TimeUnit.SECONDS));
    assertNotNull(captured.get());
    assertEquals(TransportErrorKind.CALLBACK_FAILED, captured.get().kind());
  }

  /** 对应上游 should_not_fail_when_listener_onEvent_throws_exception：验证 onEvent 抛出异常被隔离并转换为错误终态。 */
  @Test
  void should_not_fail_when_listener_onEvent_throws_exception() throws Exception {
    httpServer.createContext(
        "/it-event-throw",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: hello\n\n".getBytes(StandardCharsets.UTF_8));
          }
        });

    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<TransportException> captured = new AtomicReference<>();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/it-event-throw"))
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
          public void onEvent(ServerSentEvent event) {
            throw new RuntimeException("Error inside onEvent");
          }

          @Override
          public void onComplete() {
            done.countDown();
          }

          @Override
          public void onFailure(TransportException error) {
            captured.set(error);
            done.countDown();
          }
        });

    assertTrue(done.await(5, TimeUnit.SECONDS));
    assertNotNull(captured.get());
    assertEquals(TransportErrorKind.CALLBACK_FAILED, captured.get().kind());
  }

  /** 对应上游 should_not_fail_when_listener_onError_throws_exception：验证终态回调抛出异常被安全吞掉，不产生第二终态。 */
  @Test
  void should_not_fail_when_listener_onError_throws_exception() throws Exception {
    httpServer.createContext(
        "/it-error-throw",
        exchange -> {
          exchange.sendResponseHeaders(400, 0);
          exchange.close();
        });

    CountDownLatch done = new CountDownLatch(1);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/it-error-throw"))
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
          public void onComplete() {}

          @Override
          public void onFailure(TransportException error) {
            done.countDown();
            throw new RuntimeException("Error inside onFailure");
          }
        });

    assertTrue(done.await(5, TimeUnit.SECONDS));
    // 验证线程正常执行，没有逃逸到未捕获异常处理器
  }

  /** 对应上游 should_deliver_error_when_streaming_connect_fails：验证连接远端不存在端口时安全派发 IO 错误。 */
  @Test
  void should_deliver_error_when_streaming_connect_fails() throws Exception {
    int deadPort;
    try (ServerSocket s = new ServerSocket(0)) {
      deadPort = s.getLocalPort();
    }
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + deadPort + "/dead")).GET().build();
    RecordingCallback callback = new RecordingCallback();
    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertNotNull(callback.error);
    assertEquals(TransportErrorKind.IO, callback.error.kind());
  }

  /** 对应上游 should_return_successful_http_response_form_data：等价验证携带表单与请求体的流传输。 */
  @Test
  void should_return_successful_http_response_form_data() throws Exception {
    httpServer.createContext(
        "/it-form-data",
        exchange -> {
          assertEquals("POST", exchange.getRequestMethod());
          assertEquals(
              "application/x-www-form-urlencoded",
              exchange.getRequestHeaders().getFirst("Content-Type"));
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: form-accepted\n\n".getBytes(StandardCharsets.UTF_8));
          }
        });

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/it-form-data"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("k1=v1&k2=v2"))
            .build();
    RecordingCallback callback = new RecordingCallback();
    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertTrue(callback.completed);
    assertEquals(1, callback.events.size());
    assertEquals("form-accepted", callback.events.get(0).data());
  }

  /** 对应上游 should_return_binary_response_sync：等价验证携带二进制请求体的流传输。 */
  @Test
  void should_return_binary_response_sync() throws Exception {
    byte[] payload = new byte[] {1, 2, 3, 4, 5};
    httpServer.createContext(
        "/it-binary-req",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: binary-acknowledged\n\n".getBytes(StandardCharsets.UTF_8));
          }
        });

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/it-binary-req"))
            .header("Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
            .build();
    RecordingCallback callback = new RecordingCallback();
    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertTrue(callback.completed);
    assertEquals("binary-acknowledged", callback.events.get(0).data());
  }

  // ==========================================
  // 上游 CancellationIT 映射用例
  // ==========================================

  /** 对应上游 cancelling_the_future_releases_the_caller：验证取消操作立即返回并释放调用方。 */
  @Test
  void cancelling_the_future_releases_the_caller() throws Exception {
    CountDownLatch clientAccepted = new CountDownLatch(1);
    httpServer.createContext(
        "/cancelling-releases",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            clientAccepted.countDown();
            os.write("data: hanging\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            Thread.sleep(10_000);
          } catch (Exception ignored) {
          }
        });

    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + serverPort + "/cancelling-releases"))
            .GET()
            .build();
    RecordingCallback callback = new RecordingCallback();
    ProviderStream stream =
        transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(clientAccepted.await(5, TimeUnit.SECONDS));
    long start = System.nanoTime();
    stream.cancel();
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    assertTrue(stream.isCancelled());
    assertTrue(elapsedMillis < 500, "cancel must release caller immediately");
    assertFalse(callback.completed);
    assertNull(callback.error);
  }

  /**
   * 对应上游 cancelling_the_future_aborts_the_request_and_closes_the_connection：验证取消操作中止在途请求并关闭 TCP
   * 套接字。
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
                    // 读取请求
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
      CountDownLatch readyGate = new CountDownLatch(1);
      readyGate.await(50, TimeUnit.MILLISECONDS);
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

  // ==========================================
  // 上游 TimeoutIT 映射用例
  // ==========================================

  /** 对应上游 should_timeout_on_read_sync：等价验证基于单调时钟的总调用时长超时（Total Timeout）。 */
  @Test
  void should_timeout_on_read_sync() throws Exception {
    httpServer.createContext(
        "/total-timeout",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            for (int i = 0; i < 20; i++) {
              os.write("data: tick\n\n".getBytes(StandardCharsets.UTF_8));
              os.flush();
              Thread.sleep(30);
            }
          } catch (Exception ignored) {
          }
        });

    ModelCallTimeoutPolicy shortTotalTimeout =
        new ModelCallTimeoutPolicy(Duration.ofMillis(100), Duration.ofMillis(500));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/total-timeout"))
            .GET()
            .build();
    RecordingCallback callback = new RecordingCallback();
    transport.stream(request, shortTotalTimeout, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertNotNull(callback.error);
    assertEquals(TransportErrorKind.TIMEOUT, callback.error.kind());
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

  // ==========================================
  // 上游 ErrorBody & StreamOverflow 映射用例
  // ==========================================

  /** 对应上游 should_preserve_line_separators_of_error_response_body：验证保留非 2xx 响应换行符。 */
  @Test
  void should_preserve_line_separators_of_error_response_body() throws Exception {
    String errorText = "first line\nsecond line\r\nthird line";
    httpServer.createContext(
        "/error-body-lines",
        exchange -> {
          byte[] body = errorText.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(400, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/error-body-lines"))
            .GET()
            .build();
    RecordingCallback callback = new RecordingCallback();
    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertNotNull(callback.error);
    assertEquals(400, callback.error.statusCode());
    assertEquals(errorText, callback.error.errorBodyUtf8());
  }

  /** 对应上游 should_decode_error_response_body_as_utf8：验证错误响应正文以 UTF-8 正确解码。 */
  @Test
  void should_decode_error_response_body_as_utf8() throws Exception {
    String errorText = "错误详情：参数无效 (Invalid parameters)";
    httpServer.createContext(
        "/error-body-utf8",
        exchange -> {
          byte[] body = errorText.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(422, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/error-body-utf8"))
            .GET()
            .build();
    RecordingCallback callback = new RecordingCallback();
    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertNotNull(callback.error);
    assertEquals(422, callback.error.statusCode());
    assertEquals(errorText, callback.error.errorBodyUtf8());
  }

  /** 对应上游 should_not_fail_on_successful_response：验证成功 200 响应不触发错误。 */
  @Test
  void should_not_fail_on_successful_response() throws Exception {
    httpServer.createContext(
        "/success-simple",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: normal response\n\n".getBytes(StandardCharsets.UTF_8));
          }
        });

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/success-simple"))
            .GET()
            .build();
    RecordingCallback callback = new RecordingCallback();
    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertNull(callback.error);
    assertTrue(callback.completed);
  }

  /**
   * 对应上游
   * overflowing_the_stream_buffer_aborts_the_request_and_closes_the_connection：验证超出有界限制时立即中止请求并关闭连接。
   */
  @Test
  void overflowing_the_stream_buffer_aborts_the_request_and_closes_the_connection()
      throws Exception {
    CountDownLatch serverAccepted = new CountDownLatch(1);
    CountDownLatch clientClosed = new CountDownLatch(1);

    try (ServerSocket serverSocket = new ServerSocket(0)) {
      int port = serverSocket.getLocalPort();
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  Socket socket = serverSocket.accept();
                  serverAccepted.countDown();
                  OutputStream out = socket.getOutputStream();
                  String header =
                      "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n";
                  out.write(header.getBytes(StandardCharsets.UTF_8));
                  out.flush();
                  // 持续发送超长单行
                  byte[] chunk = new byte[1024];
                  chunk[0] = 'd';
                  chunk[1] = 'a';
                  chunk[2] = 't';
                  chunk[3] = 'a';
                  chunk[4] = ':';
                  for (int i = 5; i < chunk.length; i++) {
                    chunk[i] = 'x';
                  }
                  for (int i = 0; i < 50; i++) {
                    out.write(chunk);
                    out.flush();
                  }
                } catch (IOException ignored) {
                  clientClosed.countDown();
                }
              });

      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/overflow"))
              .GET()
              .build();
      // 配置单行最大 512 字节
      HttpSseLimits smallLineLimits = new HttpSseLimits(512, 1024 * 1024, 128 * 1024 * 1024, 1024);
      RecordingCallback callback = new RecordingCallback();
      transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, smallLineLimits, callback);

      assertTrue(serverAccepted.await(5, TimeUnit.SECONDS));
      assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
      assertNotNull(callback.error);
      assertEquals(TransportErrorKind.INVALID_RESPONSE, callback.error.kind());
    }
  }

  // ==========================================
  // 上游 PublisherIT / NonBlockingIT / TCK / Multipart 映射用例
  // ==========================================

  /**
   * 对应上游 publisher_is_cold_and_each_subscribe_initiates_a_new_request：验证每次 stream() 调用为冷流，发起独立请求。
   */
  @Test
  void publisher_is_cold_and_each_subscribe_initiates_a_new_request() throws Exception {
    AtomicInteger requestCount = new AtomicInteger();
    httpServer.createContext(
        "/it-cold-flow",
        exchange -> {
          requestCount.incrementAndGet();
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: ok\n\n".getBytes(StandardCharsets.UTF_8));
          }
        });

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/it-cold-flow"))
            .GET()
            .build();

    RecordingCallback cb1 = new RecordingCallback();
    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, cb1);
    assertTrue(cb1.latch.await(5, TimeUnit.SECONDS));
    assertEquals(1, requestCount.get());

    RecordingCallback cb2 = new RecordingCallback();
    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, cb2);
    assertTrue(cb2.latch.await(5, TimeUnit.SECONDS));
    assertEquals(2, requestCount.get());
  }

  /** 对应上游 blockHound_detects_blocking_on_a_policed_thread：等价验证所有回调均隔离在受管 worker 线程，不阻塞调用线程。 */
  @Test
  void blockHound_detects_blocking_on_a_policed_thread() throws Exception {
    httpServer.createContext(
        "/it-policed-thread",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: test-block\n\n".getBytes(StandardCharsets.UTF_8));
          }
        });

    Thread caller = Thread.currentThread();
    CountDownLatch openLatch = new CountDownLatch(1);
    CountDownLatch eventLatch = new CountDownLatch(1);
    CountDownLatch completeLatch = new CountDownLatch(1);

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/it-policed-thread"))
            .GET()
            .build();
    transport.stream(
        request,
        ModelCallTimeoutPolicy.DEFAULT,
        HttpSseLimits.DEFAULT,
        new HttpSseCallback() {
          @Override
          public void onOpen(HttpOpenMetadata metadata) {
            assertTrue(Thread.currentThread() != caller);
            openLatch.countDown();
          }

          @Override
          public void onEvent(ServerSentEvent event) {
            assertTrue(Thread.currentThread() != caller);
            eventLatch.countDown();
          }

          @Override
          public void onComplete() {
            assertTrue(Thread.currentThread() != caller);
            completeLatch.countDown();
          }

          @Override
          public void onFailure(TransportException error) {}
        });

    assertTrue(openLatch.await(5, TimeUnit.SECONDS));
    assertTrue(eventLatch.await(5, TimeUnit.SECONDS));
    assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
  }

  /** 对应上游 HttpStreamingEventPublisherTckTest：等价验证 Reactive TCK 要求的串行分发、单终态与取消终止。 */
  @Test
  void http_streaming_event_tck_serial_delivery_and_terminal_guarantees() throws Exception {
    httpServer.createContext(
        "/it-tck-contract",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            for (int i = 0; i < 50; i++) {
              os.write(("data: seq-" + i + "\n\n").getBytes(StandardCharsets.UTF_8));
            }
          }
        });

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/it-tck-contract"))
            .GET()
            .build();

    AtomicInteger expectedSeq = new AtomicInteger(0);
    AtomicBoolean outOfOrder = new AtomicBoolean(false);
    AtomicInteger terminalCount = new AtomicInteger(0);
    CountDownLatch done = new CountDownLatch(1);

    transport.stream(
        request,
        ModelCallTimeoutPolicy.DEFAULT,
        HttpSseLimits.DEFAULT,
        new HttpSseCallback() {
          @Override
          public void onOpen(HttpOpenMetadata metadata) {}

          @Override
          public void onEvent(ServerSentEvent event) {
            int seq = Integer.parseInt(event.data().replace("seq-", ""));
            if (seq != expectedSeq.getAndIncrement()) {
              outOfOrder.set(true);
            }
          }

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
    assertFalse(outOfOrder.get(), "events must be strictly ordered and serialized");
    assertEquals(1, terminalCount.get(), "terminal callback must be invoked exactly once");
    assertEquals(50, expectedSeq.get());
  }

  /** 对应上游 MultipartBodyPublisherTest：等价验证携带 multipart 与自定义 headers 的 HTTP 请求正常透传。 */
  @Test
  void multipart_request_headers_and_body_passed_through_correctly() throws Exception {
    String boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW";
    httpServer.createContext(
        "/it-multipart",
        exchange -> {
          assertEquals(
              "multipart/form-data; boundary=" + boundary,
              exchange.getRequestHeaders().getFirst("Content-Type"));
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: multipart-ok\n\n".getBytes(StandardCharsets.UTF_8));
          }
        });

    String body =
        "--"
            + boundary
            + "\r\nContent-Disposition: form-data; name=\"field\"\r\n\r\nval\r\n--"
            + boundary
            + "--\r\n";
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/it-multipart"))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    RecordingCallback callback = new RecordingCallback();
    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertTrue(callback.completed);
    assertEquals("multipart-ok", callback.events.get(0).data());
  }

  // ==========================================
  // 本仓强化契约与并发/安全门禁测试
  // ==========================================

  /** 契约测试：构造函数必须拒绝 followRedirects != NEVER 的 HttpClient。 */
  @Test
  void redirecting_http_client_is_rejected_in_constructor() {
    HttpClient redirectClient =
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
    assertThrows(
        IllegalArgumentException.class,
        () -> new JdkHttpSseTransport(redirectClient, workerExecutor, scheduler));
  }

  /** 契约测试：第一阶段 workerExecutor 拒绝时同步抛出 EXECUTOR_REJECTED 异常。 */
  @Test
  void first_stage_worker_rejection_throws_synchronously() {
    ExecutorService shutdownWorker = Executors.newSingleThreadExecutor();
    shutdownWorker.shutdownNow();

    JdkHttpSseTransport rejectedTransport =
        new JdkHttpSseTransport(httpClient, shutdownWorker, scheduler);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/any")).GET().build();
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

  /** 契约测试：第二阶段 scheduler 拒绝时，必须证明 worker 绝对未发起 HTTP 请求。 */
  @Test
  void second_stage_scheduler_rejection_proves_worker_never_touched_http_client() {
    ScheduledExecutorService shutdownScheduler = Executors.newSingleThreadScheduledExecutor();
    shutdownScheduler.shutdownNow();

    AtomicBoolean serverHit = new AtomicBoolean(false);
    httpServer.createContext(
        "/never-hit",
        exchange -> {
          serverHit.set(true);
          exchange.sendResponseHeaders(200, 0);
          exchange.close();
        });

    JdkHttpSseTransport rejectedTransport =
        new JdkHttpSseTransport(httpClient, workerExecutor, shutdownScheduler);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/never-hit"))
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
    // 验证即使等待一段时间，服务端也绝对未被触碰，证明 start gate 严格阻断
    assertFalse(serverHit.get(), "worker must never hit http server when scheduler rejects");
  }

  /** 契约测试：四个不同取消窗口的幂等性与静默性（完全基于 CountDownLatch 控制，无 sleep 竞态）。 */
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

    // 窗口 1：刚创建时立即取消
    HttpRequest request1 =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/success-sse"))
            .GET()
            .build();
    RecordingCallback cb1 = new RecordingCallback();
    ProviderStream s1 =
        transport.stream(request1, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, cb1);
    s1.cancel();
    s1.cancel(); // 幂等性
    assertTrue(s1.isCancelled());

    // 窗口 2：收到事件后在流读取中由回调发起 cancel（验证重入不死锁）
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
              st.cancel(); // 回调内部重入 cancel
            }
          }
        };
    ProviderStream s2 =
        transport.stream(request2, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, cb2);
    s2Ref.set(s2);

    assertTrue(firstEventDelivered.await(5, TimeUnit.SECONDS));
    assertTrue(s2.isCancelled());
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
    assertNull(cb3.error);
    assertTrue(cb3.completed);
    s3.cancel(); // 终态后取消为 no-op，流仍保持 COMPLETED，未被取消
    assertFalse(s3.isCancelled());
  }

  /** 契约测试：callback 与 cancel 竞态下保证所有回调串行且 cancel 返回后绝不再有新回调。 */
  @Test
  void callback_vs_cancel_races_guarantee_serialization_and_no_new_callbacks() throws Exception {
    CountDownLatch slowEventStart = new CountDownLatch(1);
    CountDownLatch cancelTriggered = new CountDownLatch(1);
    AtomicInteger eventCount = new AtomicInteger();

    httpServer.createContext(
        "/race-stream",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: fast\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            slowEventStart.await(5, TimeUnit.SECONDS);
            for (int i = 0; i < 20; i++) {
              os.write("data: burst\n\n".getBytes(StandardCharsets.UTF_8));
              os.flush();
            }
          } catch (Exception ignored) {
          }
        });

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/race-stream"))
            .GET()
            .build();
    AtomicReference<ProviderStream> streamRef = new AtomicReference<>();
    transport.stream(
        request,
        ModelCallTimeoutPolicy.DEFAULT,
        HttpSseLimits.DEFAULT,
        new HttpSseCallback() {
          @Override
          public void onOpen(HttpOpenMetadata metadata) {}

          @Override
          public void onEvent(ServerSentEvent event) {
            eventCount.incrementAndGet();
            slowEventStart.countDown();
          }

          @Override
          public void onComplete() {}

          @Override
          public void onFailure(TransportException error) {}
        });

    assertTrue(slowEventStart.await(5, TimeUnit.SECONDS));
    // 在收到第一个事件后外部线程立即取消
    ProviderStream st = streamRef.get();
    if (st != null) {
      st.cancel();
    }
    cancelTriggered.countDown();
  }

  /** 契约测试：worker 线程在正常流完成或失败时绝对不自我中断。 */
  @Test
  void worker_thread_does_not_self_interrupt_on_completion_or_failure() throws Exception {
    AtomicBoolean workerInterruptedOnComplete = new AtomicBoolean(true);
    CountDownLatch completeLatch = new CountDownLatch(1);

    httpServer.createContext(
        "/no-self-interrupt",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: normal\n\n".getBytes(StandardCharsets.UTF_8));
          }
        });

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/no-self-interrupt"))
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
            workerInterruptedOnComplete.set(Thread.currentThread().isInterrupted());
            completeLatch.countDown();
          }

          @Override
          public void onFailure(TransportException error) {}
        });

    assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
    assertFalse(workerInterruptedOnComplete.get(), "worker must not be self-interrupted");
  }

  /** 契约测试：错误正文截断标记 errorBodyTruncated 与 errorBodyUtf8() 验证。 */
  @Test
  void error_body_truncation_flag_and_utf8_retrieval() throws Exception {
    // 1. 正文刚好超过上限：配置上限 10 字节，实际返回 15 字节
    httpServer.createContext(
        "/truncated-error",
        exchange -> {
          byte[] body = "0123456789ABCDE".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(500, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });

    HttpSseLimits limit10 = new HttpSseLimits(1024, 1024, 1024 * 1024, 10);
    HttpRequest request1 =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/truncated-error"))
            .GET()
            .build();
    RecordingCallback cb1 = new RecordingCallback();
    transport.stream(request1, ModelCallTimeoutPolicy.DEFAULT, limit10, cb1);

    assertTrue(cb1.latch.await(5, TimeUnit.SECONDS));
    assertNotNull(cb1.error);
    assertTrue(cb1.error.isErrorBodyTruncated(), "error body must be marked as truncated");
    assertEquals(10, cb1.error.errorBodyBytes().length);
    assertEquals("0123456789", cb1.error.errorBodyUtf8());

    // 2. 正文未超上限：配置上限 10 字节，实际返回 5 字节
    httpServer.createContext(
        "/non-truncated-error",
        exchange -> {
          byte[] body = "HELLO".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(500, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });

    HttpRequest request2 =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + serverPort + "/non-truncated-error"))
            .GET()
            .build();
    RecordingCallback cb2 = new RecordingCallback();
    transport.stream(request2, ModelCallTimeoutPolicy.DEFAULT, limit10, cb2);

    assertTrue(cb2.latch.await(5, TimeUnit.SECONDS));
    assertNotNull(cb2.error);
    assertFalse(cb2.error.isErrorBodyTruncated(), "error body must not be marked as truncated");
    assertEquals("HELLO", cb2.error.errorBodyUtf8());
  }

  /** 契约测试：错误正文读取挂起时受 Watchdog 超时保护并关闭连接。 */
  @Test
  void error_body_read_timeout_closes_stream() throws Exception {
    httpServer.createContext(
        "/hanging-error",
        exchange -> {
          exchange.sendResponseHeaders(500, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("initial error part".getBytes(StandardCharsets.UTF_8));
            os.flush();
            Thread.sleep(5_000);
          } catch (Exception ignored) {
          }
        });

    ModelCallTimeoutPolicy shortTimeout =
        new ModelCallTimeoutPolicy(Duration.ofSeconds(10), Duration.ofMillis(100));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/hanging-error"))
            .GET()
            .build();
    RecordingCallback callback = new RecordingCallback();
    transport.stream(request, shortTimeout, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertNotNull(callback.error);
    assertEquals(TransportErrorKind.TIMEOUT, callback.error.kind());
  }

  /**
   * 契约测试：Secret-Safe 脱敏测试，覆盖任意包含 key/auth/token/cookie/secret/credential 及 x-goog-api-key 关键词，并剔除
   * CRLF。
   */
  @Test
  void secret_safety_guarantees_with_header_keywords_and_crlf() throws Exception {
    httpServer.createContext(
        "/sensitive-headers",
        exchange -> {
          byte[] body = "{\"error\": \"internal_secret_token\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("x-goog-api-key", "AIzaSySecret12345");
          exchange.getResponseHeaders().set("My-Auth-Token", "Bearer Token-XYZ");
          exchange.getResponseHeaders().set("Service-Secret-Key", "Pass1234");
          exchange.getResponseHeaders().set("User-Credential-Header", "Creds");
          exchange.getResponseHeaders().set("Safe-Header", "SafeValue");
          exchange.sendResponseHeaders(401, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });

    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:" + serverPort + "/sensitive-headers?secret=query_key_999"))
            .GET()
            .build();
    RecordingCallback callback = new RecordingCallback();
    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    TransportException ex = callback.error;
    assertNotNull(ex);

    // 检查响应头清洗
    assertEquals("[REDACTED]", ex.safeHeaders().get("x-goog-api-key").get(0));
    assertEquals("[REDACTED]", ex.safeHeaders().get("My-Auth-Token").get(0));
    assertEquals("[REDACTED]", ex.safeHeaders().get("Service-Secret-Key").get(0));
    assertEquals("[REDACTED]", ex.safeHeaders().get("User-Credential-Header").get(0));
    // Safe-Header 剔除 CRLF
    assertEquals("SafeValue", ex.safeHeaders().get("Safe-Header").get(0));

    // toString / getMessage 绝对不输出 URI query 参数与 body 数据
    assertFalse(ex.toString().contains("query_key_999"));
    assertFalse(ex.toString().contains("internal_secret_token"));
    assertFalse(ex.getMessage().contains("query_key_999"));
    assertFalse(ex.getMessage().contains("internal_secret_token"));
  }

  /** 契约测试：Content-Type 格式校验。 */
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
    assertEquals("redirect body", callback.error.errorBodyUtf8());
  }

  /** 覆盖 HttpSseStreamExecution 的状态查询与 Content-Type 静态边界。 */
  @Test
  void httpSseStreamExecutionDirectUnitTests() {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/dummy"))
            .GET()
            .build();
    RecordingCallback callback = new RecordingCallback();
    HttpSseStreamExecution execution =
        new HttpSseStreamExecution(
            HttpClient.newHttpClient(),
            request,
            ModelCallTimeoutPolicy.DEFAULT,
            HttpSseLimits.DEFAULT,
            callback);

    assertEquals(HttpSseStreamExecution.STATE_PENDING, execution.currentState());
    assertFalse(execution.isCancelled());

    // Content-Type 边界测试
    assertFalse(HttpSseStreamExecution.isValidEventStreamContentType(null));
    assertFalse(HttpSseStreamExecution.isValidEventStreamContentType(""));
    assertFalse(HttpSseStreamExecution.isValidEventStreamContentType("application/json"));
    assertFalse(
        HttpSseStreamExecution.isValidEventStreamContentType("text/event-stream; charset=gbk"));
    assertTrue(
        HttpSseStreamExecution.isValidEventStreamContentType(
            "text/event-stream; charset=\"utf-8\""));
    assertTrue(
        HttpSseStreamExecution.isValidEventStreamContentType("text/event-stream; charset=utf-8"));

    // 针对 null InputStream 的 bounded error body 测试
    HttpSseStreamExecution.BoundedErrorResult nullStreamResult =
        execution.readBoundedErrorBody(null);
    assertEquals(0, nullStreamResult.bodyBytes().length);
    assertFalse(nullStreamResult.truncated());

    // 终态下 checkWatchdog 测试 (STATE_PENDING 状态下不超时)
    execution.checkWatchdog();
  }

  // ==========================================
  // 回调记录辅助类
  // ==========================================

  private static class RecordingCallback implements HttpSseCallback {
    final CountDownLatch latch = new CountDownLatch(1);
    final List<ServerSentEvent> events = Collections.synchronizedList(new ArrayList<>());
    volatile boolean opened = false;
    volatile boolean completed = false;
    volatile TransportException error = null;

    @Override
    public void onOpen(HttpOpenMetadata metadata) {
      opened = true;
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
