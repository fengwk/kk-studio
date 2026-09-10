package fun.fengwk.kkstudio.harness.provider.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpClient.Version;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
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
import java.util.stream.Collectors;

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
    if (httpClient != null) {
      httpClient.shutdownNow();
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
    assertNotNull(callback.openMetadata);
    assertEquals(200, callback.openMetadata.statusCode());
    assertFalse(callback.openMetadata.headers().isEmpty());
    assertTrue(callback.completed);
    assertNull(callback.error);
    assertEquals(2, callback.events.size());
    assertEquals("first", callback.events.get(0).data());
    assertEquals("second", callback.events.get(1).data());
    assertEquals(
        1,
        callback.callbackThreads.size(),
        "all callbacks must be delivered on a single worker thread");
    assertEquals(
        List.of("OPEN", "EVENT:first", "EVENT:second", "COMPLETE"),
        callback.lifecycleEvents,
        "lifecycle sequence must strictly be OPEN -> EVENT+ -> COMPLETE");
    assertNotNull(callback.callbackThread);
    assertNotEquals(
        Thread.currentThread(),
        callback.callbackThread,
        "callbacks must be delivered off calling thread");
  }

  /**
   * 本仓契约测试：验证显式 cancel 静默且无任何终态回调（onClose/onComplete/onFailure），并关闭底层连接。 契约差异说明：上游期望在取消后派发
   * onClose；本模块显式取消对调用方保证静默无终态回调。
   */
  @Test
  void cancel_streaming_silently_aborts_without_terminal_callback() throws Exception {
    CountDownLatch firstSent = new CountDownLatch(1);
    CountDownLatch clientCancelled = new CountDownLatch(1);
    CountDownLatch serverDetectedClose = new CountDownLatch(1);

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
            while (true) {
              os.write("data: subsequent-data\n\n".getBytes(StandardCharsets.UTF_8));
              os.flush();
            }
          } catch (Exception e) {
            serverDetectedClose.countDown();
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

    assertTrue(
        serverDetectedClose.await(5, TimeUnit.SECONDS),
        "server must detect write failure/closed pipe after client cancel");
    assertTrue(stream.isCancelled());
    assertFalse(callback.completed, "no completed callback on explicit cancel");
    assertNull(callback.error, "no failure callback on explicit cancel");
  }

  /** 对应上游 should_stream_response_with_double_newline：验证事件 data 中保留字面 double newline 内容。 */
  @Test
  void should_stream_response_with_double_newline() throws Exception {
    httpServer.createContext(
        "/it-stream-newlines",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            // 发送跨行并带有空行的 data 内容，解析后应当保留字面 \n\n
            os.write("data: Berlin\ndata: \ndata: Paris\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
          }
        });

    RecordingCallback callback = new RecordingCallback();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/it-stream-newlines"))
            .GET()
            .build();
    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertTrue(callback.opened);
    assertTrue(callback.completed);
    assertNull(callback.error);
    assertFalse(callback.events.isEmpty());
    String combined =
        callback.events.stream().map(ServerSentEvent::data).collect(Collectors.joining(""));
    assertTrue(combined.contains("Berlin"));
    assertTrue(combined.contains("Paris"));
    assertTrue(combined.contains("\n\n"), "event data must contain literal double newline");
    assertNotNull(callback.callbackThread);
    assertNotEquals(Thread.currentThread(), callback.callbackThread, "callback off calling thread");
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
    assertFalse(callback.opened);
    assertFalse(callback.completed);
    assertTrue(callback.events.isEmpty());
    assertNotNull(callback.error);
    assertEquals(TransportErrorKind.HTTP_STATUS, callback.error.kind());
    assertEquals(400, callback.error.statusCode());
    assertEquals("bad request body", callback.error.errorBodyUtf8());
    assertFalse(
        callback.error.getMessage().contains("bad request body"),
        "error body must not leak into exception message");
    assertNotNull(callback.callbackThread);
    assertNotEquals(Thread.currentThread(), callback.callbackThread, "callback off calling thread");
  }

  /**
   * 本仓契约测试：验证 onOpen 抛出异常安全隔离转终态。 契约差异说明：上游忽略 listener onOpen 异常并继续流；本模块遵循“回调失败即流失败”原则，安全隔离并立即终结。
   */
  @Test
  void listener_onOpen_throwing_exception_transitions_to_callback_failed() throws Exception {
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

  /**
   * 本仓契约测试：验证 onEvent 抛出异常安全隔离转终态。 契约差异说明：上游忽略 listener onEvent 异常并继续流；本模块遵循“回调失败即流失败”原则，安全隔离并立即终结。
   */
  @Test
  void listener_onEvent_throwing_exception_transitions_to_callback_failed() throws Exception {
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

    AtomicInteger failureCount = new AtomicInteger(0);
    AtomicBoolean openCalled = new AtomicBoolean(false);
    AtomicBoolean eventCalled = new AtomicBoolean(false);
    AtomicBoolean completeCalled = new AtomicBoolean(false);
    AtomicReference<Thread> failureThread = new AtomicReference<>();
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
          public void onOpen(HttpOpenMetadata metadata) {
            openCalled.set(true);
          }

          @Override
          public void onEvent(ServerSentEvent event) {
            eventCalled.set(true);
          }

          @Override
          public void onComplete() {
            completeCalled.set(true);
          }

          @Override
          public void onFailure(TransportException error) {
            failureThread.set(Thread.currentThread());
            failureCount.incrementAndGet();
            done.countDown();
            throw new RuntimeException("Error inside onFailure");
          }
        });

    assertTrue(done.await(5, TimeUnit.SECONDS));
    assertEquals(1, failureCount.get(), "must trigger failure callback exactly once");
    assertFalse(openCalled.get(), "onOpen must not be called");
    assertFalse(eventCalled.get(), "onEvent must not be called");
    assertFalse(completeCalled.get(), "onComplete must not be called");
    assertNotNull(failureThread.get());
    assertNotEquals(
        Thread.currentThread(), failureThread.get(), "callback must be off calling thread");
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
    assertFalse(callback.opened);
    assertFalse(callback.completed);
    assertTrue(callback.events.isEmpty());
    assertNotNull(callback.error);
    assertEquals(TransportErrorKind.IO, callback.error.kind());
    assertNotNull(callback.callbackThread);
    assertNotEquals(Thread.currentThread(), callback.callbackThread, "callback off calling thread");
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
    long start = System.nanoTime();
    stream.cancel();
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    assertTrue(elapsedMs < 500, "cancel must release calling thread rapidly (<500ms)");
    assertTrue(stream.isCancelled());
    assertFalse(callback.completed, "no onComplete callback on cancel");
    assertNull(callback.error, "no onFailure callback on cancel");
  }

  /** 对应上游 cancelling_the_future_aborts_the_request_and_closes_the_connection：验证取消流立即关闭底层连接。 */
  @Test
  void cancelling_the_future_aborts_the_request_and_closes_the_connection() throws Exception {
    CountDownLatch firstChunk = new CountDownLatch(1);
    CountDownLatch clientCancelled = new CountDownLatch(1);
    CountDownLatch serverDetectedClose = new CountDownLatch(1);

    httpServer.createContext(
        "/it-cancel-close",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: chunk1\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            firstChunk.countDown();
            assertTrue(clientCancelled.await(5, TimeUnit.SECONDS));
            while (true) {
              os.write("data: subsequent-data\n\n".getBytes(StandardCharsets.UTF_8));
              os.flush();
            }
          } catch (Exception e) {
            serverDetectedClose.countDown();
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
    clientCancelled.countDown();

    assertTrue(
        serverDetectedClose.await(5, TimeUnit.SECONDS), "server must detect closed connection");
    assertTrue(stream.isCancelled());
    assertNull(callback.error);
  }

  /** 对应上游 should_timeout_on_read_async：验证在接收响应头前发生超时，生命周期仅触发一次 onError(TIMEOUT)。 */
  @Test
  void should_timeout_on_read_async() throws Exception {
    httpServer.createContext(
        "/it-timeout-async",
        exchange -> {
          // 在发送任何响应头之前阻塞，直到测试完成或超时触发
          try {
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
    assertFalse(callback.opened, "onOpen must not be called when timeout happens before headers");
    assertTrue(callback.events.isEmpty(), "onEvent must not be called");
    assertFalse(callback.completed, "onComplete must not be called");
    assertNotNull(callback.error, "onError must be called exactly once");
    assertEquals(TransportErrorKind.TIMEOUT, callback.error.kind());
    assertNotNull(callback.callbackThread);
    assertNotEquals(Thread.currentThread(), callback.callbackThread, "callback off calling thread");
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
    assertFalse(
        callback.error.getMessage().contains("line1"),
        "error body must not leak into exception message");
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
    assertFalse(
        callback.error.getMessage().contains("모델"),
        "error body must not leak into exception message");
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

  /** 本仓契约测试：验证 HttpSseLimits 单行缓冲区大小溢出时中止请求并关闭连接。 */
  @Test
  void line_limit_exceeded_aborts_request_with_protocol_error() throws Exception {
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

  /**
   * 契约测试（对应上游 shouldHandleIOException 的传输层映射）： 验证底层 HTTP 响应体流在读取过程中发生 I/O 故障时，精准且仅触发一次
   * onFailure(TransportErrorKind.IO)，且绝不触发 onComplete。
   */
  @Test
  void should_handle_io_exception_during_stream_read_and_fail_exactly_once() throws Exception {
    CountDownLatch firstEventReceived = new CountDownLatch(1);
    ServerSocket ss = new ServerSocket(0);
    int port = ss.getLocalPort();
    workerExecutor.submit(
        () -> {
          try (Socket client = ss.accept()) {
            OutputStream out = client.getOutputStream();
            String response =
                "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/event-stream\r\n"
                    + "Transfer-Encoding: chunked\r\n\r\n";
            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.flush();
            // 发送第一个有效事件 (chunk 长度必须精确为 19 即十六进制 13)
            byte[] eventBytes = "data: first-event\n\n".getBytes(StandardCharsets.UTF_8);
            String chunkHeader = Integer.toHexString(eventBytes.length) + "\r\n";
            out.write(chunkHeader.getBytes(StandardCharsets.UTF_8));
            out.write(eventBytes);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            // 等待客户端确认接收第一个事件后，通过 RST 强行重置 socket
            assertTrue(firstEventReceived.await(5, TimeUnit.SECONDS));
            client.setSoLinger(true, 0);
            client.close();
          } catch (Exception ignored) {
          } finally {
            try {
              ss.close();
            } catch (Exception ignored) {
            }
          }
        });

    RecordingCallback callback =
        new RecordingCallback() {
          @Override
          public void onEvent(ServerSentEvent event) {
            super.onEvent(event);
            firstEventReceived.countDown();
          }
        };

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/io-fail")).GET().build();
    transport.stream(request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
    assertTrue(callback.opened, "onOpen must have been invoked before IO error");
    assertEquals(1, callback.events.size(), "first event must be received");
    assertEquals("first-event", callback.events.get(0).data());
    assertFalse(callback.completed, "onComplete must not be invoked on IO failure");
    assertNotNull(callback.error, "onFailure must be invoked");
    assertEquals(TransportErrorKind.IO, callback.error.kind());
  }

  /**
   * 契约测试（对应上游 parse_stops_emitting_after_the_listener_cancels 的传输层映射）： 验证在首个事件回调中重入调用
   * ProviderStream.cancel()，即便服务端继续写入后续事件，客户端也立刻停止分发新事件且无终态回调。
   */
  @Test
  void parse_stops_emitting_after_the_listener_cancels() throws Exception {
    CountDownLatch firstEventReceived = new CountDownLatch(1);
    CountDownLatch serverFinished = new CountDownLatch(1);
    AtomicReference<ProviderStream> streamRef = new AtomicReference<>();
    AtomicInteger eventCount = new AtomicInteger(0);
    AtomicBoolean completedCalled = new AtomicBoolean(false);
    AtomicBoolean failedCalled = new AtomicBoolean(false);

    httpServer.createContext(
        "/cancel-reentrant-test",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: first\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            assertTrue(firstEventReceived.await(5, TimeUnit.SECONDS));
            // 客户端已在首个事件回调中 cancel，服务端继续写入后续多个事件
            for (int i = 0; i < 5; i++) {
              os.write(("data: subsequent-" + i + "\n\n").getBytes(StandardCharsets.UTF_8));
              os.flush();
            }
          } catch (Exception ignored) {
          } finally {
            serverFinished.countDown();
          }
        });

    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + serverPort + "/cancel-reentrant-test"))
            .GET()
            .build();

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
                // 收到首个事件立即重入取消
                streamRef.get().cancel();
                firstEventReceived.countDown();
              }

              @Override
              public void onComplete() {
                completedCalled.set(true);
              }

              @Override
              public void onFailure(TransportException error) {
                failedCalled.set(true);
              }
            });
    streamRef.set(stream);

    assertTrue(serverFinished.await(5, TimeUnit.SECONDS), "server writing must finish");

    // 严格断言仅首个事件被分发
    assertEquals(1, eventCount.get(), "only the first event should be emitted before cancel");
    assertTrue(stream.isCancelled());
    assertFalse(completedCalled.get(), "no completed callback on cancel");
    assertFalse(failedCalled.get(), "no failure callback on cancel");
  }

  // ==========================================
  // Review 阻塞项确定性回归测试
  // ==========================================

  /** 阻塞项 A.1：Watchdog 闲置超时转 FAILED 终态后，服务端后到的 late event 绝不再派发，worker 立即停止后续读取。 */
  @Test
  void watchdog_terminal_prevents_late_events_after_idle_timeout() throws Exception {
    CountDownLatch serverStarted = new CountDownLatch(1);
    CountDownLatch clientTimeoutReceived = new CountDownLatch(1);
    CountDownLatch serverLateWriteFinished = new CountDownLatch(1);
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
            assertTrue(clientTimeoutReceived.await(5, TimeUnit.SECONDS));
            // 超时后发送数据
            os.write("data: late event\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
          } catch (Exception ignored) {
          } finally {
            serverLateWriteFinished.countDown();
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
    assertTrue(
        serverLateWriteFinished.await(5, TimeUnit.SECONDS),
        "server must finish attempting late write");
    // 验证终态后非终态回调绝对为零
    assertEquals(
        0,
        callbacksAfterFailure.get(),
        "no non-terminal callbacks permitted after terminal FAILED state");
  }

  /** 阻塞项 A.1：Watchdog 总调用超时转 FAILED 终态后，服务端后到的 late headers/onOpen 绝不再派发。 */
  @Test
  void watchdog_terminal_prevents_late_headers_after_connect_timeout() throws Exception {
    CountDownLatch clientTimeoutReceived = new CountDownLatch(1);
    CountDownLatch serverLateResponseFinished = new CountDownLatch(1);
    AtomicBoolean failureOccurred = new AtomicBoolean(false);
    AtomicInteger onOpenCount = new AtomicInteger(0);
    AtomicInteger callbacksAfterFailure = new AtomicInteger(0);

    httpServer.createContext(
        "/watchdog-late-headers",
        exchange -> {
          try {
            // 等待客户端超时转为 FAILED 终态
            assertTrue(clientTimeoutReceived.await(5, TimeUnit.SECONDS));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
              os.write("data: late-event\n\n".getBytes(StandardCharsets.UTF_8));
              os.flush();
            }
          } catch (Exception ignored) {
          } finally {
            serverLateResponseFinished.countDown();
          }
        });

    ModelCallTimeoutPolicy tightPolicy =
        new ModelCallTimeoutPolicy(Duration.ofMillis(20), Duration.ofMillis(20));
    CountDownLatch failureLatch = new CountDownLatch(1);
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + serverPort + "/watchdog-late-headers"))
            .GET()
            .build();

    transport.stream(
        request,
        tightPolicy,
        HttpSseLimits.DEFAULT,
        new HttpSseCallback() {
          @Override
          public void onOpen(HttpOpenMetadata metadata) {
            onOpenCount.incrementAndGet();
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
    assertTrue(
        serverLateResponseFinished.await(5, TimeUnit.SECONDS),
        "server must finish attempting late response headers");
    assertEquals(0, onOpenCount.get(), "onOpen must never be called after connect timeout");
    assertEquals(0, callbacksAfterFailure.get(), "no callbacks permitted after terminal failure");
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
    AtomicBoolean workerInterruptedFlag = new AtomicBoolean(false);
    AtomicReference<TransportException> capturedError = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

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
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/interrupt-io"))
              .GET()
              .build();

      HttpSseCallback callback =
          new HttpSseCallback() {
            @Override
            public void onOpen(HttpOpenMetadata metadata) {}

            @Override
            public void onEvent(ServerSentEvent event) {}

            @Override
            public void onComplete() {}

            @Override
            public void onFailure(TransportException error) {
              workerInterruptedFlag.set(Thread.currentThread().isInterrupted());
              // 清理中断标志以便安全回收线程
              Thread.interrupted();
              capturedError.set(error);
              latch.countDown();
            }
          };

      ProviderStream stream =
          customTransport.stream(
              request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

      assertTrue(workerThreadCaptured.await(5, TimeUnit.SECONDS));
      assertTrue(serverConnected.await(5, TimeUnit.SECONDS));

      // 外部非显式 cancel 中断 worker 线程
      workerThreadRef.get().interrupt();

      assertTrue(latch.await(5, TimeUnit.SECONDS));
      assertNotNull(capturedError.get());
      // 必须分类为 IO，绝不得误报为 CANCELLED，且中断标志必须被保留
      assertEquals(TransportErrorKind.IO, capturedError.get().kind());
      assertTrue(workerInterruptedFlag.get(), "worker thread interrupt flag must be preserved");
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

    // 直接断言自适应单调时钟 Watchdog 间隔计算（5ms 时为 2.5ms = 2500000ns），杜绝固定 25ms
    HttpSseStreamExecution execution =
        new HttpSseStreamExecution(
            httpClient, request, shortTimeout, HttpSseLimits.DEFAULT, callback);
    assertEquals(
        2_500_000L,
        execution.watchdogIntervalNanos(),
        "5ms timeout must compute to 2.5ms watchdog interval (2500000ns)");

    long start = System.nanoTime();
    transport.stream(request, shortTimeout, HttpSseLimits.DEFAULT, callback);

    assertTrue(callback.latch.await(2, TimeUnit.SECONDS));
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    assertNotNull(callback.error);
    assertEquals(TransportErrorKind.TIMEOUT, callback.error.kind());
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
    try {
      assertThrows(
          IllegalArgumentException.class,
          () -> new JdkHttpSseTransport(redirectClient, workerExecutor, scheduler));
    } finally {
      redirectClient.shutdownNow();
    }
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
    CountDownLatch request2Started = new CountDownLatch(1);
    CountDownLatch request2Cancelled = new CountDownLatch(1);

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

    httpServer.createContext(
        "/hanging-sse",
        exchange -> {
          request2Started.countDown();
          try {
            assertTrue(request2Cancelled.await(5, TimeUnit.SECONDS));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
          } catch (Exception ignored) {
          }
        });

    // 窗口 1：PENDING 阶段刚创建时立即取消
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

    // 窗口 2：HTTP 请求发出且在等待响应阶段（send 挂起中）由外部线程 cancel
    HttpRequest request2 =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/hanging-sse"))
            .GET()
            .build();
    RecordingCallback cb2 = new RecordingCallback();
    ProviderStream s2 =
        transport.stream(request2, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, cb2);
    assertTrue(request2Started.await(5, TimeUnit.SECONDS));
    s2.cancel();
    request2Cancelled.countDown();
    assertTrue(s2.isCancelled());
    assertFalse(cb2.completed);
    assertNull(cb2.error);

    // 窗口 3：收到事件后在流读取中由回调发起 cancel（验证重入不死锁）
    //
    // 该窗口需要两个显式握手，否则断言与 cancel() 之间没有 happens-before，
    // 会在慢机器上偶发失败：
    //   1）streamHandlePublished：服务端发出首个事件前先等主线程发布句柄，
    //      保证回调里读到的引用一定非空（重入路径确定性被执行）；
    //   2）reentrantCancelReturned：回调内 cancel() 返回后再放行主线程断言，
    //      保证断言读取的是 cancel() 已生效后的状态。
    CountDownLatch streamHandlePublished = new CountDownLatch(1);
    CountDownLatch firstEventDelivered = new CountDownLatch(1);
    CountDownLatch reentrantCancelReturned = new CountDownLatch(1);
    AtomicReference<ProviderStream> s3Ref = new AtomicReference<>();
    httpServer.createContext(
        "/stream-mid-cancel",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            assertTrue(streamHandlePublished.await(5, TimeUnit.SECONDS));
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

    HttpRequest request3 =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/stream-mid-cancel"))
            .GET()
            .build();
    RecordingCallback cb3 =
        new RecordingCallback() {
          @Override
          public void onEvent(ServerSentEvent event) {
            super.onEvent(event);
            firstEventDelivered.countDown();
            ProviderStream st = s3Ref.get();
            if (st != null) {
              st.cancel(); // 回调内部重入 cancel
              reentrantCancelReturned.countDown();
            }
          }
        };
    ProviderStream s3 =
        transport.stream(request3, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, cb3);
    s3Ref.set(s3);
    streamHandlePublished.countDown();

    assertTrue(firstEventDelivered.await(5, TimeUnit.SECONDS));
    assertTrue(reentrantCancelReturned.await(5, TimeUnit.SECONDS));
    assertTrue(s3.isCancelled());
    assertFalse(cb3.completed);
    assertNull(cb3.error);
    assertEquals(1, cb3.events.size());

    // 窗口 4：流正常完成之后再次 cancel() 为 no-op
    HttpRequest request4 =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/success-sse"))
            .GET()
            .build();
    RecordingCallback cb4 = new RecordingCallback();
    ProviderStream s4 =
        transport.stream(request4, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, cb4);
    assertTrue(cb4.latch.await(5, TimeUnit.SECONDS));
    assertNull(cb4.error);
    assertTrue(cb4.completed);
    s4.cancel(); // 终态后取消为 no-op，流仍保持 COMPLETED，未被取消
    assertFalse(s4.isCancelled());
  }

  /** 契约测试：callback 与 cancel 竞态下保证所有回调串行且 cancel 返回后绝不再有新回调。 */
  @Test
  void callback_vs_cancel_races_guarantee_serialization_and_no_new_callbacks() throws Exception {
    CountDownLatch firstEventDelivered = new CountDownLatch(1);
    CountDownLatch slowCallbackHoldingLock = new CountDownLatch(1);
    CountDownLatch cancelInvocationFinished = new CountDownLatch(1);
    CountDownLatch cancelStarted = new CountDownLatch(1);
    CountDownLatch serverWritingFinished = new CountDownLatch(1);
    AtomicBoolean completedCalled = new AtomicBoolean(false);
    AtomicBoolean failedCalled = new AtomicBoolean(false);
    AtomicInteger eventCount = new AtomicInteger();

    httpServer.createContext(
        "/race-stream",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: first-slow\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            for (int i = 0; i < 20; i++) {
              os.write(("data: burst-" + i + "\n\n").getBytes(StandardCharsets.UTF_8));
              os.flush();
            }
          } catch (Exception ignored) {
          } finally {
            serverWritingFinished.countDown();
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
                firstEventDelivered.countDown();
                try {
                  slowCallbackHoldingLock.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
              }

              @Override
              public void onComplete() {
                completedCalled.set(true);
              }

              @Override
              public void onFailure(TransportException error) {
                failedCalled.set(true);
              }
            });
    streamRef.set(stream);

    assertTrue(firstEventDelivered.await(5, TimeUnit.SECONDS));

    // 启动独立任务调用 cancel
    AtomicBoolean cancelReturned = new AtomicBoolean(false);
    workerExecutor.submit(
        () -> {
          cancelStarted.countDown();
          streamRef.get().cancel();
          cancelReturned.set(true);
          cancelInvocationFinished.countDown();
        });

    assertTrue(cancelStarted.await(5, TimeUnit.SECONDS));
    // cancel() 由于 slowCallbackHoldingLock 还没释放，不得在 onEvent 结束前返回
    assertFalse(cancelReturned.get(), "cancel() must block waiting for active onEvent to finish");

    // 释放 onEvent
    slowCallbackHoldingLock.countDown();
    assertTrue(
        cancelInvocationFinished.await(5, TimeUnit.SECONDS),
        "cancel() must complete after onEvent releases lock");
    assertTrue(cancelReturned.get());

    // 确保服务端所有 burst 数据已发出
    assertTrue(serverWritingFinished.await(5, TimeUnit.SECONDS));

    // 确认在 cancel 返回后，已缓冲或新到达的数据不再分发，事件数稳定为 1
    assertEquals(1, eventCount.get(), "event count must stay at 1 after cancel returns");
    assertFalse(completedCalled.get(), "no onComplete callback after cancel");
    assertFalse(failedCalled.get(), "no onFailure callback after cancel");
    assertTrue(stream.isCancelled());
  }

  /** 契约测试：worker 线程在正常流完成或失败时绝对不自我中断。 */
  @Test
  void worker_thread_does_not_self_interrupt_on_completion_or_failure() throws Exception {
    // 1. 成功完成路径
    AtomicBoolean workerInterruptedOnComplete = new AtomicBoolean(true);
    CountDownLatch completeLatch = new CountDownLatch(1);

    httpServer.createContext(
        "/no-self-interrupt-ok",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("data: normal\n\n".getBytes(StandardCharsets.UTF_8));
          }
        });

    HttpRequest requestOk =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + serverPort + "/no-self-interrupt-ok"))
            .GET()
            .build();
    transport.stream(
        requestOk,
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
    assertFalse(
        workerInterruptedOnComplete.get(), "worker must not be self-interrupted on complete");

    // 2. 失败路径
    AtomicBoolean workerInterruptedOnFailure = new AtomicBoolean(true);
    CountDownLatch failLatch = new CountDownLatch(1);

    httpServer.createContext(
        "/no-self-interrupt-fail",
        exchange -> {
          exchange.sendResponseHeaders(500, 0);
          exchange.close();
        });

    HttpRequest requestFail =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + serverPort + "/no-self-interrupt-fail"))
            .GET()
            .build();
    transport.stream(
        requestFail,
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
            workerInterruptedOnFailure.set(Thread.currentThread().isInterrupted());
            failLatch.countDown();
          }
        });

    assertTrue(failLatch.await(5, TimeUnit.SECONDS));
    assertFalse(workerInterruptedOnFailure.get(), "worker must not be self-interrupted on failure");
  }

  /**
   * 契约测试：send 阻塞期间 execution 因超时或外部操作进入 FAILED 终态，当 send 随后返回 HttpResponse 时， 生产代码在设置
   * activeInputStream 后立即校验权威状态，直接关闭流，绝不读取响应体，且不触发 onOpen/onEvent/onComplete。
   */
  @Test
  void watchdog_terminal_prevents_late_headers_closes_stream_without_reading() throws Exception {
    AtomicInteger bodyReadCount = new AtomicInteger();
    AtomicInteger bodyCloseCount = new AtomicInteger();
    CountDownLatch bodyClosed = new CountDownLatch(1);

    InputStream trackingBody =
        new InputStream() {
          @Override
          public int read() {
            bodyReadCount.incrementAndGet();
            return -1;
          }

          @Override
          public int read(byte[] b, int off, int len) {
            bodyReadCount.incrementAndGet();
            return -1;
          }

          @Override
          public void close() {
            bodyCloseCount.incrementAndGet();
            bodyClosed.countDown();
          }
        };

    HttpHeaders headers =
        HttpHeaders.of(Map.of("Content-Type", List.of("text/event-stream")), (k, v) -> true);
    FakeHttpResponse<InputStream> fakeResponse = new FakeHttpResponse<>(200, headers, trackingBody);
    ControllableFakeHttpClient fakeClient = new ControllableFakeHttpClient(fakeResponse);

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/dummy"))
            .GET()
            .build();
    RecordingCallback callback = new RecordingCallback();
    HttpSseStreamExecution execution =
        new HttpSseStreamExecution(
            fakeClient, request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    Future<?> workerFuture = workerExecutor.submit(execution::runWorker);
    execution.attachWorkerFuture(workerFuture);
    execution.openStartGate();

    // 等待 worker 进入 send
    assertTrue(fakeClient.sendEntered.await(5, TimeUnit.SECONDS));

    // 模拟 watchdog 超时，使 execution 直接进入 FAILED 终态
    execution.triggerTimeout("Simulated call timeout while waiting for response headers");
    assertEquals(HttpSseStreamExecution.STATE_FAILED, execution.currentState());

    // 释放 send 返回
    fakeClient.allowSendReturn.countDown();

    // 等待 worker 退出 (终态时 workerFuture 会被 cancel，忽略 CancellationException)
    try {
      workerFuture.get(5, TimeUnit.SECONDS);
    } catch (Exception ignored) {
    }
    assertTrue(bodyClosed.await(5, TimeUnit.SECONDS), "late response body was not closed");

    // 核心断言：body 绝未读取，且流被且仅被 close 一次
    assertEquals(
        0, bodyReadCount.get(), "body must not be read after execution entered terminal state");
    assertEquals(1, bodyCloseCount.get(), "stream must be closed exactly once");
    assertFalse(callback.opened, "onOpen must not be called");
    assertTrue(callback.events.isEmpty(), "onEvent must not be called");
    assertFalse(callback.completed, "onComplete must not be called");
    assertNotNull(callback.error);
    assertEquals(TransportErrorKind.TIMEOUT, callback.error.kind());
  }

  /** 契约测试：readBoundedErrorBody 在终态下直接退出并关闭输入流。 */
  @Test
  void read_bounded_error_body_in_terminal_state_closes_stream_without_reading() {
    AtomicBoolean readAttempted = new AtomicBoolean(false);
    AtomicBoolean streamClosed = new AtomicBoolean(false);
    InputStream trackStream =
        new InputStream() {
          @Override
          public int read() {
            readAttempted.set(true);
            return -1;
          }

          @Override
          public int read(byte[] b, int off, int len) {
            readAttempted.set(true);
            return -1;
          }

          @Override
          public void close() {
            streamClosed.set(true);
          }
        };

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/dummy"))
            .GET()
            .build();
    RecordingCallback callback = new RecordingCallback();
    HttpSseStreamExecution execution =
        new HttpSseStreamExecution(
            httpClient, request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

    // 预先取消进入 CANCELLED 终态
    execution.cancel();
    assertTrue(execution.isCancelled());

    HttpSseStreamExecution.BoundedErrorResult result = execution.readBoundedErrorBody(trackStream);
    assertFalse(
        readAttempted.get(), "must not attempt reading body when stream is in terminal state");
    assertTrue(streamClosed.get(), "stream must be closed");
    assertEquals(0, result.bodyBytes().length);
    assertFalse(result.truncated());
  }

  /** 契约测试：readBoundedErrorBody 在 maxErrorBodyBytes 为 Integer.MAX_VALUE 时无整型溢出。 */
  @Test
  void read_bounded_error_body_handles_max_integer_limit_without_overflow() {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/dummy"))
            .GET()
            .build();
    RecordingCallback callback = new RecordingCallback();
    HttpSseLimits maxLimits = new HttpSseLimits(64, 128, 1024, Integer.MAX_VALUE);
    HttpSseStreamExecution execution =
        new HttpSseStreamExecution(
            httpClient, request, ModelCallTimeoutPolicy.DEFAULT, maxLimits, callback);

    byte[] testData = new byte[100];
    Arrays.fill(testData, (byte) 'x');
    ByteArrayInputStream in = new ByteArrayInputStream(testData);

    HttpSseStreamExecution.BoundedErrorResult result = execution.readBoundedErrorBody(in);
    assertEquals(100, result.bodyBytes().length);
    assertFalse(result.truncated());
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
            httpClient, request, ModelCallTimeoutPolicy.DEFAULT, HttpSseLimits.DEFAULT, callback);

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

  /** 测试 safeDurationToNanos 的各类边界条件（null、负数、零、纳秒累加溢出）。 */
  @Test
  void safe_duration_to_nanos_boundary_cases() {
    assertEquals(0L, HttpSseStreamExecution.safeDurationToNanos(null));
    assertEquals(0L, HttpSseStreamExecution.safeDurationToNanos(Duration.ofSeconds(-1)));
    assertEquals(0L, HttpSseStreamExecution.safeDurationToNanos(Duration.ZERO));
    assertEquals(
        Long.MAX_VALUE,
        HttpSseStreamExecution.safeDurationToNanos(
            Duration.ofSeconds(Long.MAX_VALUE / 1_000_000_000L + 1)));
    assertEquals(
        Long.MAX_VALUE,
        HttpSseStreamExecution.safeDurationToNanos(
            Duration.ofSeconds(Long.MAX_VALUE / 1_000_000_000L, 900_000_000)));
  }

  /** 测试超时配置很小时，watchdogIntervalNanos 安全回退到最小间隔 1ms。 */
  @Test
  void watchdog_interval_with_minimal_timeouts() {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/dummy"))
            .GET()
            .build();
    RecordingCallback callback = new RecordingCallback();
    HttpSseStreamExecution execution =
        new HttpSseStreamExecution(
            httpClient,
            request,
            new ModelCallTimeoutPolicy(Duration.ofNanos(1), Duration.ofNanos(1)),
            HttpSseLimits.DEFAULT,
            callback);
    assertEquals(TimeUnit.MILLISECONDS.toNanos(1), execution.watchdogIntervalNanos());
  }

  /** 契约测试：readBoundedErrorBody 正文超过上限截断分支。 */
  @Test
  void error_body_truncation_boundary_when_exceeding_max_limit() {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/dummy"))
            .GET()
            .build();
    RecordingCallback callback = new RecordingCallback();
    HttpSseLimits limits = new HttpSseLimits(1024, 1024, 1024 * 1024, 5);
    HttpSseStreamExecution execution =
        new HttpSseStreamExecution(
            httpClient, request, ModelCallTimeoutPolicy.DEFAULT, limits, callback);

    byte[] sourceData = "0123456789".getBytes(StandardCharsets.UTF_8);
    ByteArrayInputStream in = new ByteArrayInputStream(sourceData);
    HttpSseStreamExecution.BoundedErrorResult result = execution.readBoundedErrorBody(in);
    assertTrue(result.truncated());
    assertEquals(5, result.bodyBytes().length);
    assertEquals("01234", new String(result.bodyBytes(), StandardCharsets.UTF_8));
  }

  /** 契约测试：closeInputStream 遇到 IOException 静默关闭。 */
  @Test
  void close_input_stream_with_exception_handled_silently() {
    InputStream faultyStream =
        new InputStream() {
          @Override
          public int read() {
            return -1;
          }

          @Override
          public void close() throws IOException {
            throw new IOException("simulated close failure");
          }
        };
    HttpSseStreamExecution.closeInputStream(faultyStream);
  }

  /** 契约测试：application/problem+json 错误响应正常解析与状态分类。 */
  @Test
  void handle_non_2xx_with_problem_json_content_type() throws Exception {
    httpServer.createContext(
        "/problem-json",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "application/problem+json");
          byte[] body = "{\"title\": \"problem description\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(400, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });
    RecordingCallback cb = new RecordingCallback();
    transport.stream(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/problem-json"))
            .GET()
            .build(),
        ModelCallTimeoutPolicy.DEFAULT,
        HttpSseLimits.DEFAULT,
        cb);
    assertTrue(cb.latch.await(5, TimeUnit.SECONDS));
    assertNotNull(cb.error);
    assertEquals(TransportErrorKind.HTTP_STATUS, cb.error.kind());
    assertEquals(400, cb.error.statusCode());
    assertEquals("{\"title\": \"problem description\"}", cb.error.errorBodyUtf8());
  }

  // ==========================================
  // 回调记录辅助类
  // ==========================================

  private static class RecordingCallback implements HttpSseCallback {
    final CountDownLatch latch = new CountDownLatch(1);
    final List<ServerSentEvent> events = Collections.synchronizedList(new ArrayList<>());
    final List<String> lifecycleEvents = Collections.synchronizedList(new ArrayList<>());
    final Set<Thread> callbackThreads = Collections.synchronizedSet(new HashSet<>());
    volatile boolean opened = false;
    volatile boolean completed = false;
    volatile TransportException error = null;
    volatile HttpOpenMetadata openMetadata = null;
    volatile Thread callbackThread = null;

    @Override
    public void onOpen(HttpOpenMetadata metadata) {
      this.callbackThread = Thread.currentThread();
      this.callbackThreads.add(Thread.currentThread());
      this.lifecycleEvents.add("OPEN");
      this.openMetadata = metadata;
      opened = true;
    }

    @Override
    public void onEvent(ServerSentEvent event) {
      this.callbackThread = Thread.currentThread();
      this.callbackThreads.add(Thread.currentThread());
      this.lifecycleEvents.add("EVENT:" + event.data());
      events.add(event);
    }

    @Override
    public void onComplete() {
      this.callbackThread = Thread.currentThread();
      this.callbackThreads.add(Thread.currentThread());
      this.lifecycleEvents.add("COMPLETE");
      completed = true;
      latch.countDown();
    }

    @Override
    public void onFailure(TransportException error) {
      this.callbackThread = Thread.currentThread();
      this.callbackThreads.add(Thread.currentThread());
      this.lifecycleEvents.add("FAILURE:" + error.kind());
      this.error = error;
      latch.countDown();
    }
  }

  private static class ControllableFakeHttpClient extends HttpClient {
    final CountDownLatch sendEntered = new CountDownLatch(1);
    final CountDownLatch allowSendReturn = new CountDownLatch(1);
    final HttpResponse<InputStream> response;

    ControllableFakeHttpClient(HttpResponse<InputStream> response) {
      this.response = response;
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
      return null;
    }

    @Override
    public SSLParameters sslParameters() {
      return null;
    }

    @Override
    public Optional<Authenticator> authenticator() {
      return Optional.empty();
    }

    @Override
    public Version version() {
      return Version.HTTP_2;
    }

    @Override
    public Optional<Executor> executor() {
      return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> HttpResponse<T> send(
        HttpRequest request, HttpResponse.BodyHandler<T> responseHandler)
        throws IOException, InterruptedException {
      sendEntered.countDown();
      try {
        allowSendReturn.await();
      } catch (InterruptedException ignored) {
        // 忽略中断，模拟 send 在终态后仍返回 HttpResponse 的极端竞态场景
      }
      return (HttpResponse<T>) response;
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> responseHandler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> responseHandler,
        HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
      throw new UnsupportedOperationException();
    }
  }

  private static class FakeHttpResponse<T> implements HttpResponse<T> {
    private final int statusCode;
    private final HttpHeaders headers;
    private final T body;

    FakeHttpResponse(int statusCode, HttpHeaders headers, T body) {
      this.statusCode = statusCode;
      this.headers = headers;
      this.body = body;
    }

    @Override
    public int statusCode() {
      return statusCode;
    }

    @Override
    public HttpRequest request() {
      return null;
    }

    @Override
    public Optional<HttpResponse<T>> previousResponse() {
      return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
      return headers;
    }

    @Override
    public T body() {
      return body;
    }

    @Override
    public Optional<SSLSession> sslSession() {
      return Optional.empty();
    }

    @Override
    public URI uri() {
      return URI.create("http://127.0.0.1/fake");
    }

    @Override
    public Version version() {
      return Version.HTTP_1_1;
    }
  }
}
