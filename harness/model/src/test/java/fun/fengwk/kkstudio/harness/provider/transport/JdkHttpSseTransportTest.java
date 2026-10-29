package fun.fengwk.kkstudio.harness.provider.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;

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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 阻塞式 SSE 传输器：在调用线程上完成请求与逐事件交付；非 2xx / 非法 Content-Type / 超限 / 超时 / 中断都以分类明确且不泄露远端细节的 {@link
 * TransportException} 结束，且任何路径都关闭响应体。
 */
class JdkHttpSseTransportTest {

  private static final ModelCallTimeoutPolicy GENEROUS =
      new ModelCallTimeoutPolicy(Duration.ofSeconds(10), Duration.ofSeconds(10));

  private HttpServer server;
  private ScheduledExecutorService watchdog;
  private HttpClient httpClient;
  private JdkHttpSseTransport transport;
  private final AtomicReference<Handler> handler = new AtomicReference<>();

  @FunctionalInterface
  interface Handler {
    void handle(HttpExchange exchange) throws Exception;
  }

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext(
        "/",
        exchange -> {
          try {
            handler.get().handle(exchange);
          } catch (Exception ignored) {
            // 客户端主动断开等场景：服务端写失败属于预期。
          } finally {
            exchange.close();
          }
        });
    server.start();
    watchdog = Executors.newSingleThreadScheduledExecutor();
    httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    transport = new JdkHttpSseTransport(httpClient, watchdog);
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
    watchdog.shutdownNow();
    httpClient.close();
  }

  /** 成功响应：按序交付全部事件（含事件名与多行 data），读完正常返回。 */
  @Test
  void deliversEventsInOrderAndReturnsAtEndOfStream() {
    handler.set(
        exchange ->
            sse(
                exchange,
                "event: first\ndata: one\n\ndata: two\ndata: lines\n\n\n\ndata: three\n\n"));

    List<ServerSentEvent> events = execute(GENEROUS, HttpSseLimits.DEFAULT);

    assertEquals(
        List.of(
            new ServerSentEvent("first", "one"),
            new ServerSentEvent(null, "two\nlines"),
            new ServerSentEvent(null, "three")),
        events);
  }

  /** 空 data 行保留为字面换行：多行 data 合并后含 double newline。 */
  @Test
  void blankDataLinesKeepLiteralDoubleNewline() {
    handler.set(exchange -> sse(exchange, "data: Berlin\ndata: \ndata: Paris\n\n"));

    assertEquals(
        List.of(new ServerSentEvent(null, "Berlin\n\nParis")),
        execute(GENEROUS, HttpSseLimits.DEFAULT));
  }

  /** 回调返回 false：立即停止读取、关闭响应并正常返回，后续事件不再交付。 */
  @Test
  void callerCanStopReadingEarly() {
    handler.set(exchange -> sse(exchange, "data: a\n\ndata: b\n\ndata: c\n\n"));
    List<String> seen = new ArrayList<>();

    transport.execute(
        request(),
        GENEROUS,
        HttpSseLimits.DEFAULT,
        event -> {
          seen.add(event.data());
          return false;
        });

    assertEquals(List.of("a"), seen);
  }

  /** 回调抛出的运行时异常原样传播（不被包装成传输错误）。 */
  @Test
  void callbackExceptionPropagatesUnchanged() {
    handler.set(exchange -> sse(exchange, "data: a\n\n"));
    IllegalStateException failure = new IllegalStateException("handler broke");

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                transport.execute(
                    request(),
                    GENEROUS,
                    HttpSseLimits.DEFAULT,
                    event -> {
                      throw failure;
                    }));

    assertSame(failure, thrown);
  }

  /** 非 2xx：HTTP_STATUS 携带状态码、按上限截断的 UTF-8 错误体（保留换行）与白名单内的响应头；未知或敏感头被丢弃。 */
  @Test
  void non2xxCarriesBoundedErrorBodyAndSafeHeaders() {
    String body = "第一行\r\n第二行 " + "x".repeat(200);
    handler.set(
        exchange -> {
          exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
          exchange.getResponseHeaders().add("X-Request-Id", "req-1");
          exchange.getResponseHeaders().add("X-Internal-Secret", "s3cr3t-value");
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(429, bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });

    TransportException error =
        assertThrows(
            TransportException.class,
            () -> execute(GENEROUS, new HttpSseLimits(1024, 1024, 1024 * 1024, 64)));

    assertEquals(TransportErrorKind.HTTP_STATUS, error.kind());
    assertEquals(429, error.statusCode());
    assertTrue(error.isErrorBodyTruncated());
    assertEquals(64, error.errorBodyBytes().length);
    assertTrue(error.errorBodyUtf8().startsWith("第一行\r\n第二行"));
    assertFalse(error.getMessage().contains("第一行"), "error body must not leak into the message");
    assertEquals(List.of("req-1"), error.safeHeaders().get("x-request-id"));
    assertFalse(error.toString().contains("s3cr3t-value"));
    assertFalse(String.valueOf(error.safeHeaders()).contains("s3cr3t-value"));
  }

  /** 错误体上限为 Integer.MAX_VALUE 时不发生整型溢出，完整读取且不标记截断。 */
  @Test
  void errorBodyLimitAtIntegerMaxDoesNotOverflow() {
    handler.set(exchange -> respond(exchange, 500, "text/plain", "boom"));

    TransportException error =
        assertThrows(
            TransportException.class,
            () -> execute(GENEROUS, new HttpSseLimits(1024, 1024, 1024, Integer.MAX_VALUE)));

    assertEquals("boom", error.errorBodyUtf8());
    assertFalse(error.isErrorBodyTruncated());
  }

  /** 2xx 但 Content-Type 不是 UTF-8 的 text/event-stream：INVALID_RESPONSE，且消息不含远端头值。 */
  @Test
  void invalidContentTypeIsRejectedWithoutLeakingTheHeaderValue() {
    handler.set(exchange -> respond(exchange, 200, "text/html; secret-marker", "<html/>"));

    TransportException error =
        assertThrows(TransportException.class, () -> execute(GENEROUS, HttpSseLimits.DEFAULT));

    assertEquals(TransportErrorKind.INVALID_RESPONSE, error.kind());
    assertFalse(error.getMessage().contains("secret-marker"));
    assertTrue(JdkHttpSseTransport.isValidEventStreamContentType("text/event-stream"));
    assertTrue(
        JdkHttpSseTransport.isValidEventStreamContentType("Text/Event-Stream; charset=\"UTF-8\""));
    assertFalse(
        JdkHttpSseTransport.isValidEventStreamContentType("text/event-stream; charset=latin1"));
    assertFalse(JdkHttpSseTransport.isValidEventStreamContentType(" "));
    assertFalse(JdkHttpSseTransport.isValidEventStreamContentType(null));
  }

  /** 重定向不被跟随：3xx 作为 HTTP_STATUS 失败；构造时拒绝会跟随重定向的 HttpClient。 */
  @Test
  void redirectsAreNeverFollowed() {
    handler.set(
        exchange -> {
          exchange.getResponseHeaders().add("Location", "http://127.0.0.1:1/elsewhere");
          exchange.sendResponseHeaders(302, -1);
        });

    TransportException error =
        assertThrows(TransportException.class, () -> execute(GENEROUS, HttpSseLimits.DEFAULT));
    assertEquals(TransportErrorKind.HTTP_STATUS, error.kind());
    assertEquals(302, error.statusCode());

    try (HttpClient following =
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()) {
      assertThrows(
          IllegalArgumentException.class, () -> new JdkHttpSseTransport(following, watchdog));
    }
  }

  /** 连接失败：IO，且消息不含请求 URI。 */
  @Test
  void connectionFailureIsIoWithoutLeakingTheUri() throws IOException {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    HttpRequest unreachable =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + closedPort + "/secret-path"))
            .build();

    TransportException error =
        assertThrows(
            TransportException.class,
            () -> transport.execute(unreachable, GENEROUS, HttpSseLimits.DEFAULT, event -> true));

    assertEquals(TransportErrorKind.IO, error.kind());
    assertFalse(error.getMessage().contains("secret-path"));
  }

  /** 读取中途连接被重置：已交付的事件保留，随后以 IO 失败结束（不当作正常 EOF）。 */
  @Test
  void connectionResetMidStreamIsIo() throws Exception {
    CountDownLatch firstEvent = new CountDownLatch(1);
    try (ServerSocket socket = new ServerSocket(0)) {
      Thread.ofVirtual()
          .start(
              () -> {
                try (var client = socket.accept()) {
                  OutputStream out = client.getOutputStream();
                  byte[] event = "data: first-event\n\n".getBytes(StandardCharsets.UTF_8);
                  out.write(
                      ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                              + "Transfer-Encoding: chunked\r\n\r\n"
                              + Integer.toHexString(event.length)
                              + "\r\n")
                          .getBytes(StandardCharsets.UTF_8));
                  out.write(event);
                  out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                  out.flush();
                  firstEvent.await(5, TimeUnit.SECONDS);
                  // SO_LINGER=0 使 close 发送 RST，模拟连接被强行重置。
                  client.setSoLinger(true, 0);
                } catch (Exception ignored) {
                  // 测试结束时 socket 关闭属于预期。
                }
              });
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + socket.getLocalPort() + "/io"))
              .build();
      List<String> seen = new ArrayList<>();

      TransportException error =
          assertThrows(
              TransportException.class,
              () ->
                  transport.execute(
                      request,
                      GENEROUS,
                      HttpSseLimits.DEFAULT,
                      event -> {
                        seen.add(event.data());
                        firstEvent.countDown();
                        return true;
                      }));

      assertEquals(TransportErrorKind.IO, error.kind());
      assertEquals(List.of("first-event"), seen);
    }
  }

  /** 响应头迟迟不到：以闲置/总时长中较短者作为响应头超时，分类为 TIMEOUT。 */
  @Test
  void slowHeadersTimeOut() {
    CountDownLatch release = new CountDownLatch(1);
    handler.set(
        exchange -> {
          release.await(5, TimeUnit.SECONDS);
          sse(exchange, "data: late\n\n");
        });
    try {
      TransportException error =
          assertThrows(
              TransportException.class,
              () ->
                  execute(
                      new ModelCallTimeoutPolicy(Duration.ofSeconds(5), Duration.ofMillis(200)),
                      HttpSseLimits.DEFAULT));
      assertEquals(TransportErrorKind.TIMEOUT, error.kind());
    } finally {
      release.countDown();
    }
  }

  /** 响应体闲置超时：watchdog 关闭阻塞中的读取，以 TIMEOUT（而不是正常 EOF）结束，已交付的事件保留。 */
  @Test
  void idleBodyTimesOutAndClosesTheBlockedRead() {
    CountDownLatch release = new CountDownLatch(1);
    handler.set(
        exchange -> {
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          OutputStream out = exchange.getResponseBody();
          out.write("data: first\n\n".getBytes(StandardCharsets.UTF_8));
          out.flush();
          release.await(5, TimeUnit.SECONDS);
        });
    List<String> seen = new ArrayList<>();
    try {
      TransportException error =
          assertThrows(
              TransportException.class,
              () ->
                  transport.execute(
                      request(),
                      new ModelCallTimeoutPolicy(Duration.ofSeconds(5), Duration.ofMillis(300)),
                      HttpSseLimits.DEFAULT,
                      event -> seen.add(event.data())));
      assertEquals(TransportErrorKind.TIMEOUT, error.kind());
      assertTrue(error.getMessage().contains("idle"));
      assertEquals(List.of("first"), seen);
    } finally {
      release.countDown();
    }
  }

  /** 持续活动只延长闲置期限，不延长总时长：一直有数据的流仍在总时长到期时以 TIMEOUT 结束。 */
  @Test
  void activityExtendsIdleButNotTotalDeadline() {
    AtomicBoolean stop = new AtomicBoolean();
    handler.set(
        exchange -> {
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          OutputStream out = exchange.getResponseBody();
          while (!stop.get()) {
            out.write("data: tick\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(50);
          }
        });
    try {
      long start = System.nanoTime();
      TransportException error =
          assertThrows(
              TransportException.class,
              () ->
                  transport.execute(
                      request(),
                      new ModelCallTimeoutPolicy(Duration.ofMillis(600), Duration.ofMillis(300)),
                      HttpSseLimits.DEFAULT,
                      event -> true));
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertEquals(TransportErrorKind.TIMEOUT, error.kind());
      assertTrue(error.getMessage().contains("Total"));
      assertTrue(elapsedMillis >= 500, "total deadline must not fire early: " + elapsedMillis);
    } finally {
      stop.set(true);
    }
  }

  /** 中断调用线程即取消：阻塞的读取立即返回，以 CANCELLED 结束并保留中断标记，连接随之关闭。 */
  @Test
  void interruptCancelsTheBlockedRead() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch connectionClosed = new CountDownLatch(1);
    handler.set(
        exchange -> {
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          OutputStream out = exchange.getResponseBody();
          out.write("data: first\n\n".getBytes(StandardCharsets.UTF_8));
          out.flush();
          // 持续写注释行直到客户端关闭连接导致写失败。
          try {
            while (!release.await(50, TimeUnit.MILLISECONDS)) {
              out.write(": ping\n".getBytes(StandardCharsets.UTF_8));
              out.flush();
            }
          } catch (IOException closed) {
            connectionClosed.countDown();
          }
        });
    CountDownLatch firstEvent = new CountDownLatch(1);
    AtomicReference<Throwable> outcome = new AtomicReference<>();
    AtomicBoolean interruptedAfter = new AtomicBoolean();
    Thread caller =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    transport.execute(
                        request(),
                        GENEROUS,
                        HttpSseLimits.DEFAULT,
                        event -> {
                          firstEvent.countDown();
                          return true;
                        });
                  } catch (Throwable thrown) {
                    outcome.set(thrown);
                  }
                  interruptedAfter.set(Thread.currentThread().isInterrupted());
                });
    try {
      assertTrue(firstEvent.await(5, TimeUnit.SECONDS));
      caller.interrupt();
      caller.join(TimeUnit.SECONDS.toMillis(5));
      assertFalse(caller.isAlive());
      TransportException error = (TransportException) outcome.get();
      assertEquals(TransportErrorKind.CANCELLED, error.kind());
      assertTrue(interruptedAfter.get());
      assertTrue(connectionClosed.await(5, TimeUnit.SECONDS), "the connection must be closed");
    } finally {
      release.countDown();
    }
  }

  /** 单行超过上限：INVALID_RESPONSE；默认上限接受 64 KiB 以上、1 MiB 以内的单行，拒绝超过 1 MiB 的单行。 */
  @Test
  void enforcesLineLimits() {
    String wide = "y".repeat(100 * 1024);
    handler.set(exchange -> sse(exchange, "data: " + wide + "\n\n"));
    assertEquals(
        List.of(new ServerSentEvent(null, wide)), execute(GENEROUS, HttpSseLimits.DEFAULT));

    TransportException small =
        assertThrows(
            TransportException.class,
            () -> execute(GENEROUS, new HttpSseLimits(1024, 1024 * 1024, 1024 * 1024, 1024)));
    assertEquals(TransportErrorKind.INVALID_RESPONSE, small.kind());

    handler.set(exchange -> sse(exchange, "data: " + "z".repeat(1024 * 1024 + 1) + "\n\n"));
    TransportException huge =
        assertThrows(TransportException.class, () -> execute(GENEROUS, HttpSseLimits.DEFAULT));
    assertEquals(TransportErrorKind.INVALID_RESPONSE, huge.kind());
  }

  /** 事件名缺省时为 null；空 data 字段的事件照常交付。 */
  @Test
  void eventWithoutNameHasNullName() {
    handler.set(exchange -> sse(exchange, "data:\n\n"));

    List<ServerSentEvent> events = execute(GENEROUS, HttpSseLimits.DEFAULT);

    assertEquals(1, events.size());
    assertNull(events.get(0).event());
    assertEquals("", events.get(0).data());
  }

  private List<ServerSentEvent> execute(ModelCallTimeoutPolicy policy, HttpSseLimits limits) {
    List<ServerSentEvent> events = new ArrayList<>();
    transport.execute(request(), policy, limits, events::add);
    return events;
  }

  private HttpRequest request() {
    return HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/stream"))
        .build();
  }

  private static void sse(HttpExchange exchange, String body) throws IOException {
    respond(exchange, 200, "text/event-stream; charset=utf-8", body);
  }

  private static void respond(HttpExchange exchange, int status, String contentType, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", contentType);
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
  }
}
