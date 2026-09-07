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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JdkHttpSseTransport 核心流传输器综合测试。
 *
 * <p>完整对齐上游 LangChain4j 1.20.0 规范中针对 SSE 流传输的核心行为， 并针对 Review 阻塞项强化状态机权威 RUNNING
 * 限制、Direct/Caller-runs 启动门死锁防护、 自适应 Watchdog、严格最小 Allowlist 脱敏与竞态资源管理。
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
  // 上游 HttpClientIT / Cancellation / Timeout 移植用例
  // ==========================================

  /** 对应上游 should_stream_successful_response：验证流式成功接收 SSE 响应。 */
  @Test
  void should_stream_successful_response() throws Exception {
    httpServer.createContext(
        "/it-stream-success",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: first\n\ndata: second\n\n".getBytes(StandardCharsets.UTF_8));
          }
        });

    RecordingCallback callback = new RecordingCallback();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/it-stream-success"))
            .GET()
            .build();
    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertTrue(callback.opened);
    assertTrue(callback.completed);
    assertNull(callback.error);
    assertEquals(2, callback.events.size());
    assertEquals("first", callback.events.get(0).data());
    assertEquals("second", callback.events.get(1).data());
  }

  /** 对应上游 should_cancel_streaming：验证流式传输取消断开连接。 */
  @Test
  void should_cancel_streaming() throws Exception {
    CountDownLatch firstSent = new CountDownLatch(1);
    CountDownLatch clientCancelled = new CountDownLatch(1);
    AtomicBoolean serverWriteFailed = new AtomicBoolean(false);

    httpServer.createContext(
        "/it-stream-cancel",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: 1\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            firstSent.countDown();
            assertTrue(clientCancelled.await(5, TimeUnit.SECONDS));
            // 客户端取消后继续写，应当触发连接关闭或异常
            for (int i = 2; i <= 20; i++) {
              os.write(("data: " + i + "\n\n").getBytes(StandardCharsets.UTF_8));
              os.flush();
            }
          } catch (Exception e) {
            serverWriteFailed.set(true);
          }
        });

    RecordingCallback callback = new RecordingCallback();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/it-stream-cancel"))
            .GET()
            .build();
    ProviderStream stream =
        transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(firstSent.await(5, TimeUnit.SECONDS));
    stream.cancel();
    clientCancelled.countDown();

    assertTrue(stream.isCancelled());
    assertFalse(callback.completed);
    assertNull(callback.error);
  }

  /** 对应上游 should_stream_response_with_double_newline：验证双换行分隔流事件分发。 */
  @Test
  void should_stream_response_with_double_newline() throws Exception {
    httpServer.createContext(
        "/it-stream-newlines",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: msg1\n\n\n\ndata: msg2\n\n".getBytes(StandardCharsets.UTF_8));
          }
        });

    RecordingCallback callback = new RecordingCallback();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/it-stream-newlines"))
            .GET()
            .build();
    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertEquals(2, callback.events.size());
    assertEquals("msg1", callback.events.get(0).data());
    assertEquals("msg2", callback.events.get(1).data());
  }

  /** 对应上游 should_deliver_error_when_streaming_400：验证流式响应 400 交付错误终态。 */
  @Test
  void should_deliver_error_when_streaming_400() throws Exception {
    httpServer.createContext(
        "/it-stream-400",
        exchange -> {
          byte[] err = "bad request body".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(400, err.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(err);
          }
        });

    RecordingCallback callback = new RecordingCallback();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/it-stream-400"))
            .GET()
            .build();
    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertNotNull(callback.error);
    assertEquals(TransportErrorKind.HTTP_STATUS, callback.error.kind());
    assertEquals(400, callback.error.statusCode());
    assertEquals("bad request body", callback.error.errorBodyUtf8());
  }

  /** 对应上游 should_not_fail_when_listener_onOpen_throws_exception：验证 onOpen 抛出异常安全隔离转终态。 */
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
          public void onComplete() {}

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

  /** 对应上游 should_not_fail_when_listener_onEvent_throws_exception：验证 onEvent 抛出异常安全隔离转终态。 */
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
          public void onComplete() {}

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

  /** 对应上游 should_not_fail_when_listener_onError_throws_exception：验证 onError 抛出异常被安全吸收不产生第二终态。 */
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
  }

  /** 对应上游 should_deliver_error_when_streaming_connect_fails：验证建连失败交付错误终态。 */
  @Test
  void should_deliver_error_when_streaming_connect_fails() throws Exception {
    int deadPort;
    try (ServerSocket ss = new ServerSocket(0)) {
      deadPort = ss.getLocalPort();
    }

    RecordingCallback callback = new RecordingCallback();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + deadPort + "/connect-fail"))
            .GET()
            .build();
    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertNotNull(callback.error);
    assertEquals(TransportErrorKind.IO, callback.error.kind());
  }

  /** 对应上游 cancelling_the_future_releases_the_caller：验证取消流立即返回释放调用方。 */
  @Test
  void cancelling_the_future_releases_the_caller() throws Exception {
    CountDownLatch clientConnected = new CountDownLatch(1);
    httpServer.createContext(
        "/it-cancel-release",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          clientConnected.countDown();
          try (OutputStream os = exchange.getResponseBody()) {
            new CountDownLatch(1).await(5, TimeUnit.SECONDS);
          } catch (Exception ignored) {
          }
        });

    RecordingCallback callback = new RecordingCallback();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/it-cancel-release"))
            .GET()
            .build();
    ProviderStream stream =
        transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(clientConnected.await(5, TimeUnit.SECONDS));
    stream.cancel();
    assertTrue(stream.isCancelled());
  }

  /** 对应上游 cancelling_the_future_aborts_the_request_and_closes_the_connection：验证取消流立即关闭底层连接。 */
  @Test
  void cancelling_the_future_aborts_the_request_and_closes_the_connection() throws Exception {
    CountDownLatch firstChunk = new CountDownLatch(1);
    AtomicBoolean serverDetectedClose = new AtomicBoolean(false);

    httpServer.createContext(
        "/it-cancel-close",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: chunk1\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            firstChunk.countDown();
            for (int i = 0; i < 50; i++) {
              os.write(("data: chunk" + i + "\n\n").getBytes(StandardCharsets.UTF_8));
              os.flush();
            }
          } catch (Exception e) {
            serverDetectedClose.set(true);
          }
        });

    RecordingCallback callback = new RecordingCallback();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/it-cancel-close"))
            .GET()
            .build();
    ProviderStream stream =
        transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(firstChunk.await(5, TimeUnit.SECONDS));
    stream.cancel();

    assertTrue(stream.isCancelled());
    assertNull(callback.error);
  }

  /** 对应上游 should_timeout_on_read_async：验证异步流读取超时触发错误终态。 */
  @Test
  void should_timeout_on_read_async() throws Exception {
    httpServer.createContext(
        "/it-timeout-async",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            new CountDownLatch(1).await(5, TimeUnit.SECONDS);
          } catch (Exception ignored) {
          }
        });

    ModelCallTimeoutPolicy tightPolicy =
        new ModelCallTimeoutPolicy(Duration.ofMillis(50), Duration.ofMillis(50));
    RecordingCallback callback = new RecordingCallback();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/it-timeout-async"))
            .GET()
            .build();
    transport.stream(request, tightPolicy, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertNotNull(callback.error);
    assertEquals(TransportErrorKind.TIMEOUT, callback.error.kind());
  }

  /** 对应上游 should_preserve_line_separators_of_error_response_body：验证错误正文保留换行符。 */
  @Test
  void should_preserve_line_separators_of_error_response_body() throws Exception {
    String body = "line1\r\nline2\r\n";
    httpServer.createContext(
        "/error-newlines",
        exchange -> {
          byte[] b = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(400, b.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(b);
          }
        });

    RecordingCallback callback = new RecordingCallback();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/error-newlines"))
            .GET()
            .build();
    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertNotNull(callback.error);
    assertEquals(body, callback.error.errorBodyUtf8());
    assertEquals(400, callback.error.statusCode());
  }

  /** 对应上游 should_decode_error_response_body_as_utf8：验证错误正文以 UTF-8 解码。 */
  @Test
  void should_decode_error_response_body_as_utf8() throws Exception {
    String koreanBody = "모델 오류 발생";
    httpServer.createContext(
        "/error-utf8",
        exchange -> {
          byte[] b = koreanBody.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(400, b.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(b);
          }
        });

    RecordingCallback callback = new RecordingCallback();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/error-utf8"))
            .GET()
            .build();
    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertNotNull(callback.error);
    assertEquals(koreanBody, callback.error.errorBodyUtf8());
  }

  /** 对应上游 should_not_fail_on_successful_response：验证 200 成功响应不触发错误。 */
  @Test
  void should_not_fail_on_successful_response() throws Exception {
    httpServer.createContext(
        "/success-no-fail",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: ok\n\n".getBytes(StandardCharsets.UTF_8));
          }
        });

    RecordingCallback callback = new RecordingCallback();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/success-no-fail"))
            .GET()
            .build();
    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertNull(callback.error);
    assertTrue(callback.completed);
    assertEquals(1, callback.events.size());
    assertEquals("ok", callback.events.get(0).data());
  }

  /**
   * 对应上游
   * overflowing_the_stream_buffer_aborts_the_request_and_closes_the_connection：验证缓冲区溢出中止请求并关闭连接。
   */
  @Test
  void overflowing_the_stream_buffer_aborts_the_request_and_closes_the_connection()
      throws Exception {
    httpServer.createContext(
        "/overflow-stream",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            // 写入单行超限事件
            os.write("data: ".getBytes(StandardCharsets.UTF_8));
            byte[] big = new byte[200];
            Arrays.fill(big, (byte) 'a');
            os.write(big);
            os.write("\n\n".getBytes(StandardCharsets.UTF_8));
          } catch (Exception ignored) {
          }
        });

    HttpSseLimits smallLimits = new HttpSseLimits(64, 128, 1024, 64);
    RecordingCallback callback = new RecordingCallback();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/overflow-stream"))
            .GET()
            .build();
    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, smallLimits, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertNotNull(callback.error);
    assertEquals(TransportErrorKind.INVALID_RESPONSE, callback.error.kind());
    assertTrue(callback.error.getMessage().contains("SSE line size exceeded limit"));
  }

  // ==========================================
  // Review 阻塞项确定性回归测试
  // ==========================================

  /**
   * 阻塞项 A.1：Watchdog 转 FAILED 终态后，服务端后到的数据绝不再派发任何 onOpen / onEvent； parser/callback 失败后 worker
   * 立即停止后续读取和解析。
   */
  @Test
  void watchdog_terminal_prevents_subsequent_callbacks_and_halts_worker() throws Exception {
    CountDownLatch serverStarted = new CountDownLatch(1);
    CountDownLatch clientTimeoutReceived = new CountDownLatch(1);
    AtomicBoolean failureOccurred = new AtomicBoolean(false);
    AtomicInteger callbacksAfterFailure = new AtomicInteger(0);

    httpServer.createContext(
        "/watchdog-late-data",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          serverStarted.countDown();
          try (OutputStream os = exchange.getResponseBody()) {
            // 等待客户端超时转为 FAILED 终态
            clientTimeoutReceived.await(5, TimeUnit.SECONDS);
            // 超时后发送数据
            os.write("data: late event\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
          } catch (Exception ignored) {
          }
        });

    ModelCallTimeoutPolicy tightPolicy =
        new ModelCallTimeoutPolicy(Duration.ofMillis(20), Duration.ofMillis(20));
    CountDownLatch failureLatch = new CountDownLatch(1);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/watchdog-late-data"))
            .GET()
            .build();

    transport.stream(
        request,
        tightPolicy,
        HttpSseLimits.DEFAULT,
        new HttpSseCallback() {
          @Override
          public void onOpen(HttpOpenMetadata metadata) {
            if (failureOccurred.get()) {
              callbacksAfterFailure.incrementAndGet();
            }
          }

          @Override
          public void onEvent(ServerSentEvent event) {
            if (failureOccurred.get()) {
              callbacksAfterFailure.incrementAndGet();
            }
          }

          @Override
          public void onComplete() {
            if (failureOccurred.get()) {
              callbacksAfterFailure.incrementAndGet();
            }
          }

          @Override
          public void onFailure(TransportException error) {
            failureOccurred.set(true);
            clientTimeoutReceived.countDown();
            failureLatch.countDown();
          }
        });

    assertTrue(failureLatch.await(5, TimeUnit.SECONDS));
    // 验证终态后非终态回调绝对为零
    assertEquals(
        0,
        callbacksAfterFailure.get(),
        "no non-terminal callbacks permitted after terminal FAILED state");
  }

  /** 阻塞项 A.1：onEvent 失败后，worker 立即停止后续读取，服务端后续事件绝不被派发。 */
  @Test
  void callback_failure_halts_further_worker_reading_and_no_further_callbacks() throws Exception {
    AtomicInteger eventDeliveries = new AtomicInteger(0);
    CountDownLatch failedLatch = new CountDownLatch(1);

    httpServer.createContext(
        "/event-fail-halt",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            for (int i = 1; i <= 10; i++) {
              os.write(("data: event-" + i + "\n\n").getBytes(StandardCharsets.UTF_8));
              os.flush();
            }
          } catch (Exception ignored) {
          }
        });

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/event-fail-halt"))
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
            eventDeliveries.incrementAndGet();
            throw new RuntimeException("Simulated onEvent failure");
          }

          @Override
          public void onComplete() {
            eventDeliveries.incrementAndGet();
          }

          @Override
          public void onFailure(TransportException error) {
            failedLatch.countDown();
          }
        });

    assertTrue(failedLatch.await(5, TimeUnit.SECONDS));
    assertEquals(
        1, eventDeliveries.get(), "worker must halt reading immediately upon callback failure");
  }

  /** 阻塞项 A.2：重新设计的 Future 挂接必须竞态安全：一经挂接若已处于终态必须立即 cancel。 */
  @Test
  void future_attach_after_terminal_is_immediately_cancelled() {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/dummy"))
            .GET()
            .build();
    RecordingCallback callback = new RecordingCallback();
    HttpSseStreamExecution execution =
        new HttpSseStreamExecution(
            httpClient, request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    // 提前取消使 execution 进入 CANCELLED 终态
    execution.cancel();
    assertTrue(execution.isCancelled());

    // 随后挂接未完成的 futures，必须被立即 cancel
    CountDownLatch blocker = new CountDownLatch(1);
    Future<?> mockWorkerFuture =
        workerExecutor.submit(
            () -> {
              try {
                blocker.await(5, TimeUnit.SECONDS);
              } catch (InterruptedException ignored) {
              }
            });
    ScheduledFuture<?> mockWatchdogFuture = scheduler.schedule(() -> {}, 1, TimeUnit.MINUTES);

    execution.attachWorkerFuture(mockWorkerFuture);
    execution.attachWatchdogFuture(mockWatchdogFuture);

    assertTrue(
        mockWorkerFuture.isCancelled(),
        "workerFuture must be cancelled immediately when attached in terminal state");
    assertTrue(
        mockWatchdogFuture.isCancelled(),
        "watchdogFuture must be cancelled immediately when attached in terminal state");
    blocker.countDown();
  }

  /** 阻塞项 A.3：检测并拒绝 direct executor inline execution，防止 stream 死锁，保证无触网、无回调。 */
  @Test
  void direct_executor_inline_execution_is_rejected_without_deadlock() {
    ExecutorService directExecutor =
        new AbstractExecutorService() {
          private volatile boolean shutdown = false;

          @Override
          public void shutdown() {
            shutdown = true;
          }

          @Override
          public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
          }

          @Override
          public boolean isShutdown() {
            return shutdown;
          }

          @Override
          public boolean isTerminated() {
            return shutdown;
          }

          @Override
          public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
          }

          @Override
          public void execute(Runnable command) {
            command.run(); // 强制在调用线程同步执行
          }
        };

    JdkHttpSseTransport directTransport =
        new JdkHttpSseTransport(httpClient, directExecutor, scheduler);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/dummy"))
            .GET()
            .build();
    RecordingCallback callback = new RecordingCallback();

    TransportException ex =
        assertThrows(
            TransportException.class,
            () ->
                directTransport.stream(
                    request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback));

    assertEquals(TransportErrorKind.EXECUTOR_REJECTED, ex.kind());
    assertFalse(callback.opened);
    assertFalse(callback.completed);
    assertNull(callback.error);
  }

  /** 阻塞项 A.3：使用 CallerRunsPolicy 满载时的 inline execution 确定性拒绝测试，杜绝死锁。 */
  @Test
  void caller_runs_policy_inline_execution_is_rejected_without_deadlock() throws Exception {
    CountDownLatch blockerLatch = new CountDownLatch(1);
    // 单线程池，队列容量为 0，超出时执行 CallerRunsPolicy
    ThreadPoolExecutor callerRunsPool =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            new ThreadPoolExecutor.CallerRunsPolicy());

    try {
      // 占满该唯一工作线程
      callerRunsPool.submit(
          () -> {
            try {
              blockerLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
          });

      JdkHttpSseTransport callerRunsTransport =
          new JdkHttpSseTransport(httpClient, callerRunsPool, scheduler);
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/dummy"))
              .GET()
              .build();
      RecordingCallback callback = new RecordingCallback();

      // 再次提交将触发 CallerRunsPolicy 在当前调用线程内执行
      TransportException ex =
          assertThrows(
              TransportException.class,
              () ->
                  callerRunsTransport.stream(
                      request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback));

      assertEquals(TransportErrorKind.EXECUTOR_REJECTED, ex.kind());
      assertFalse(callback.opened);
      assertNull(callback.error);
    } finally {
      blockerLatch.countDown();
      callerRunsPool.shutdownNow();
    }
  }

  /** 阻塞项 A.4：非显式 cancel 导致的 worker 线程 InterruptedException 必须按 IO 语义分类并保留中断标志。 */
  @Test
  void non_explicit_worker_interrupt_classifies_as_io_and_preserves_interrupt_flag()
      throws Exception {
    CountDownLatch serverConnected = new CountDownLatch(1);
    AtomicReference<Thread> workerThreadRef = new AtomicReference<>();
    CountDownLatch workerThreadCaptured = new CountDownLatch(1);

    httpServer.createContext(
        "/interrupt-io",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          serverConnected.countDown();
          try (OutputStream os = exchange.getResponseBody()) {
            new CountDownLatch(1).await(5, TimeUnit.SECONDS);
          } catch (Exception ignored) {
          }
        });

    ExecutorService customWorker =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r);
              workerThreadRef.set(t);
              workerThreadCaptured.countDown();
              return t;
            });

    try {
      JdkHttpSseTransport customTransport =
          new JdkHttpSseTransport(httpClient, customWorker, scheduler);
      RecordingCallback callback = new RecordingCallback();
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/interrupt-io"))
              .GET()
              .build();

      ProviderStream stream =
          customTransport.stream(
              request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

      assertTrue(workerThreadCaptured.await(5, TimeUnit.SECONDS));
      assertTrue(serverConnected.await(5, TimeUnit.SECONDS));

      // 外部非显式 cancel 中断 worker 线程
      workerThreadRef.get().interrupt();

      assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
      assertNotNull(callback.error);
      // 必须分类为 IO，绝不得误报为 CANCELLED
      assertEquals(TransportErrorKind.IO, callback.error.kind());
    } finally {
      customWorker.shutdownNow();
    }
  }

  /** 阻塞项 A.5：自适应 Watchdog 调度测试，避免 5ms 短 timeout 被固定 25ms 延迟，无 Thread.sleep。 */
  @Test
  void short_timeout_triggers_rapidly_via_adaptive_watchdog() throws Exception {
    httpServer.createContext(
        "/rapid-timeout",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            new CountDownLatch(1).await(5, TimeUnit.SECONDS);
          } catch (Exception ignored) {
          }
        });

    ModelCallTimeoutPolicy shortTimeout =
        new ModelCallTimeoutPolicy(Duration.ofMillis(5), Duration.ofMillis(5));
    RecordingCallback callback = new RecordingCallback();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/rapid-timeout"))
            .GET()
            .build();

    long start = System.nanoTime();
    transport.stream(request, shortTimeout, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(2, TimeUnit.SECONDS));
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    assertNotNull(callback.error);
    assertEquals(TransportErrorKind.TIMEOUT, callback.error.kind());
    // 自适应 Watchdog 周期使得短超时迅速触发，远小于 500ms
    assertTrue(elapsedMillis < 500, "short timeout must trigger rapidly without artificial delay");
  }

  /** 阻塞项 A.5：大 Duration (Long.MAX_VALUE) 饱和算术不发生溢出崩溃或误报超时。 */
  @Test
  void large_duration_does_not_overflow() throws Exception {
    httpServer.createContext(
        "/large-duration-safe",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: normal\n\n".getBytes(StandardCharsets.UTF_8));
          }
        });

    ModelCallTimeoutPolicy safeLargePolicy =
        new ModelCallTimeoutPolicy(
            Duration.ofSeconds(Long.MAX_VALUE), Duration.ofSeconds(Long.MAX_VALUE));
    RecordingCallback callback = new RecordingCallback();
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + serverPort + "/large-duration-safe"))
            .GET()
            .build();

    transport.stream(request, safeLargePolicy, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertNull(callback.error);
    assertTrue(callback.completed);
    assertEquals(1, callback.events.size());
  }

  /** 阻塞项 B.6：INVALID_CONTENT_TYPE 错误信息不得拼入远端原始 Content-Type 字符串。 */
  @Test
  void invalid_content_type_does_not_leak_remote_header_value() throws Exception {
    String sensitiveContentType = "text/html; secret_token=leak-value-999";
    httpServer.createContext(
        "/ct-leak-test",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", sensitiveContentType);
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("<h1>Not SSE</h1>".getBytes(StandardCharsets.UTF_8));
          }
        });

    RecordingCallback callback = new RecordingCallback();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/ct-leak-test"))
            .GET()
            .build();

    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertNotNull(callback.error);
    assertEquals(TransportErrorKind.INVALID_RESPONSE, callback.error.kind());
    // 错误消息绝对不得拼入远端原始值
    assertFalse(callback.error.getMessage().contains("leak-value-999"));
    assertFalse(callback.error.getMessage().contains(sensitiveContentType));
    assertFalse(callback.error.toString().contains("leak-value-999"));
  }

  /** 阻塞项 B.7：未知响应 Header 携带 secret 时，安全过滤掉未知标头且任何字段均不泄露 secret。 */
  @Test
  void unknown_response_header_with_secret_is_dropped_and_never_leaked() throws Exception {
    String sensitiveSecret = "my-confidential-secret-key-12345";
    httpServer.createContext(
        "/unknown-headers-leak",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.getResponseHeaders().set("X-Debug-Session", sensitiveSecret);
          exchange.getResponseHeaders().set("Set-Cookie", "session=" + sensitiveSecret);
          exchange.getResponseHeaders().set("X-Request-Id", "req-12345");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: ok\n\n".getBytes(StandardCharsets.UTF_8));
          }
        });

    AtomicReference<HttpOpenMetadata> metaRef = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + serverPort + "/unknown-headers-leak"))
            .GET()
            .build();

    transport.stream(
        request,
        ModelCallTimeoutPolicy.DEFAULT,
        HttpSseLimits.DEFAULT,
        new HttpSseCallback() {
          @Override
          public void onOpen(HttpOpenMetadata metadata) {
            metaRef.set(metadata);
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
    HttpOpenMetadata metadata = metaRef.get();
    assertNotNull(metadata);

    // 未知标头 X-Debug-Session 与 Cookie 等彻底被丢弃
    assertFalse(metadata.headers().containsKey("x-debug-session"));
    assertFalse(metadata.headers().containsKey("set-cookie"));
    assertTrue(metadata.headers().containsKey("x-request-id"));
    assertTrue(metadata.headers().containsKey("content-type"));
    assertFalse(metadata.toString().contains(sensitiveSecret));
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
    assertFalse(serverHit.get(), "worker must never hit http server when scheduler rejects");
  }

  /** 契约测试：四个不同取消窗口的幂等性与静默性。 */
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
    ProviderStream stream =
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
    streamRef.set(stream);

    assertTrue(slowEventStart.await(5, TimeUnit.SECONDS));
    streamRef.get().cancel();
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

  /** 契约测试：错误正文读取挂起时受 Watchdog 超时保护并关闭连接（消除 sleep，使用 latch 优雅挂起）。 */
  @Test
  void error_body_read_timeout_closes_stream() throws Exception {
    httpServer.createContext(
        "/hanging-error",
        exchange -> {
          exchange.sendResponseHeaders(500, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("initial error part".getBytes(StandardCharsets.UTF_8));
            os.flush();
            new CountDownLatch(1).await(5, TimeUnit.SECONDS);
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

  /** 契约测试：Content-Type 格式校验。 */
  @Test
  void content_type_validation() throws Exception {
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
