package fun.fengwk.kkstudio.harness.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.provider.transport.TransportErrorKind;
import fun.fengwk.kkstudio.harness.provider.transport.TransportException;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * 原生 Anthropic 错误处理、超时契约与流式错误脱敏集成测试套件。
 *
 * <p>本套件不依赖任何 LangChain4j 运行时或 SDK 门面，直接验证 AnthropicErrorMapper、JdkHttpSseTransport 与
 * AnthropicModelProvider 在错误响应、超时及 SSE 错误帧上的确定性脱敏与生命周期行为。
 */
class AnthropicErrorHandlingTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private HttpServer server;
  private int port;
  private ExecutorService workerExecutor;
  private ScheduledExecutorService scheduler;
  private HttpClient client;
  private JdkHttpSseTransport transport;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    port = server.getAddress().getPort();
    server.start();

    workerExecutor = Executors.newCachedThreadPool();
    scheduler = Executors.newSingleThreadScheduledExecutor();

    client =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    transport = new JdkHttpSseTransport(client, workerExecutor, scheduler);
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
    if (client != null) {
      client.shutdownNow();
    }
    if (workerExecutor != null) {
      workerExecutor.shutdownNow();
    }
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }

  /**
   * 对应上游 AnthropicChatModelErrorsTest#should_handle_error_responses 的 8 状态码错误映射矩阵。
   *
   * <p>测试意图：验证 upstream HTTP 状态码 400/401/403/404/413/429/500/503 分别被精准映射为 kk-studio 的
   * ProviderErrorKind，且异常消息完整暴露原始 HTTP 状态码与响应正文，绝不向外暴露内部 transport message 或底层异常栈（cause 严格为 null）。
   */
  @ParameterizedTest
  @MethodSource("errorMatrix")
  void should_handle_error_responses(int httpStatusCode, ProviderErrorKind expectedKind) {
    String fakeToken = "fake-token-leaked-" + UUID.randomUUID();
    String rawResponseBody =
        """
        {
          "type": "error",
          "error": {
            "type": "does_not_matter",
            "message": "Upstream error detail with token %s for status %d"
          }
        }
        """
            .formatted(fakeToken, httpStatusCode);

    TransportException transportException =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "Internal transport status error: " + fakeToken,
            httpStatusCode,
            rawResponseBody.getBytes(StandardCharsets.UTF_8),
            null,
            new RuntimeException("Sensitive underlying network cause: " + fakeToken));

    ProviderException mapped = AnthropicErrorMapper.mapTransportException(transportException);

    assertNotNull(mapped, "mapped exception must not be null");
    assertEquals(
        expectedKind,
        mapped.kind(),
        () -> "HTTP " + httpStatusCode + " must map to " + expectedKind);
    assertEquals(
        "HTTP " + httpStatusCode + "\n" + rawResponseBody,
        mapped.getMessage(),
        () -> "HTTP " + httpStatusCode + " must retain raw HTTP status and body");
    assertNull(
        mapped.getCause(),
        "cause must be strictly null to avoid leaking transport stack or underlying details");

    // 严格断言上游 body 保留但内部 transport message 与底层 cause 绝不泄露
    assertTrue(mapped.getMessage().contains(fakeToken));
    assertFalse(
        mapped.getMessage().contains("Internal transport"),
        "message must never leak internal transport exception message");
    assertFalse(
        mapped.getMessage().contains("Sensitive underlying network cause"),
        "message must never leak underlying network cause");
  }

  static Stream<Arguments> errorMatrix() {
    return Stream.of(
        Arguments.of(400, ProviderErrorKind.INVALID_REQUEST),
        Arguments.of(401, ProviderErrorKind.AUTHENTICATION),
        Arguments.of(403, ProviderErrorKind.AUTHENTICATION),
        Arguments.of(404, ProviderErrorKind.INVALID_REQUEST),
        Arguments.of(413, ProviderErrorKind.INVALID_REQUEST),
        Arguments.of(429, ProviderErrorKind.TRANSIENT),
        Arguments.of(500, ProviderErrorKind.TRANSIENT),
        Arguments.of(503, ProviderErrorKind.TRANSIENT));
  }

  /**
   * 对应上游 AnthropicChatModelErrorsTest#should_handle_timeout 的 1/10/100ms 超时参数维度。
   *
   * <p>测试意图：验证在指定毫秒级超时（1ms, 10ms, 100ms）下，通过真实 JdkHttpSseTransport 和本地阻塞 HttpServer， 触发超时 Watchdog
   * 时精准产生 TransportErrorKind.TIMEOUT，并由 AnthropicErrorMapper 映射为脱敏的 ProviderErrorKind.TRANSIENT（消息为
   * "Anthropic request timed out"），且整个流生命周期决不触发 onComplete 回调。
   */
  @ParameterizedTest
  @ValueSource(ints = {1, 10, 100})
  void should_handle_timeout(int millis) throws Exception {
    CountDownLatch serverBlockedLatch = new CountDownLatch(1);
    server.createContext(
        "/v1/messages",
        exchange -> {
          try {
            serverBlockedLatch.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
          }
        });

    ModelCallTimeoutPolicy timeoutPolicy =
        new ModelCallTimeoutPolicy(Duration.ofMillis(millis), Duration.ofMillis(millis));
    ProviderDescriptor descriptor =
        new ProviderDescriptor(
            "anthropic-timeout-test",
            ProviderType.ANTHROPIC,
            "http://127.0.0.1:" + port + "/v1",
            timeoutPolicy,
            UUID.randomUUID());

    AnthropicProviderAdapter adapter = new AnthropicProviderAdapter(transport, "dummy-key");
    AnthropicModelProvider modelProvider = (AnthropicModelProvider) adapter.create(descriptor);

    ProviderRequest request = createTestRequest("anthropic-timeout-test", "claude-3-5-sonnet");

    AtomicReference<ProviderException> errorRef = new AtomicReference<>();
    AtomicReference<ProviderCompletion> completionRef = new AtomicReference<>();
    List<ProviderStreamEvent> events = new CopyOnWriteArrayList<>();
    CountDownLatch callbackLatch = new CountDownLatch(1);

    modelProvider.stream(
        request,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {
            events.add(event);
          }

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {
            completionRef.set(completion);
            callbackLatch.countDown();
          }

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            errorRef.set(error);
            callbackLatch.countDown();
          }
        });

    try {
      assertTrue(
          callbackLatch.await(5, TimeUnit.SECONDS),
          "stream should trigger timeout callback within 5 seconds");
      assertNull(completionRef.get(), "onComplete must never be called on timeout");
      assertTrue(events.isEmpty(), "no events must be delivered on timeout");

      ProviderException error = errorRef.get();
      assertNotNull(error, "onError must be called with ProviderException");
      assertEquals(
          ProviderErrorKind.TRANSIENT, error.kind(), "timeout error kind must be TRANSIENT");
      assertEquals(
          "Anthropic request timed out",
          error.getMessage(),
          "timeout error message must be fixed and sanitized");
      assertNull(error.getCause(), "cause must be null");
    } finally {
      serverBlockedLatch.countDown();
    }
  }

  /**
   * 对应上游 DefaultAnthropicClientTest#shouldHandleStreamingError。
   *
   * <p>测试意图：验证接收到 SSE 协议级 "error" 事件帧时，系统正确解析 error envelope 并由 AnthropicErrorMapper 映射为
   * ProviderErrorKind.TRANSIENT 且完整保留事件 envelope JSON；同时确保底层流被立即取消， 且后续到达的事件或完成信号被完全静默，只触发且严格触发一次
   * onError。
   */
  @Test
  void shouldHandleStreamingError() throws Exception {
    CountDownLatch serverReceivedLatch = new CountDownLatch(1);
    server.createContext(
        "/v1/messages",
        exchange -> {
          serverReceivedLatch.countDown();
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);

          try (OutputStream os = exchange.getResponseBody()) {
            String sseErrorEvent =
                "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"Rate limit exceeded: quota 100 req/min exceeded with token fake-token-leak-999 on cluster node-42\"}}\n\n";
            os.write(sseErrorEvent.getBytes(StandardCharsets.UTF_8));
            os.flush();

            // 尝试在 error 后继续写入事件和结束标记，验证 bridge 静默与流取消
            os.write(
                "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"late text\"}}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.write(
                "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();
          } catch (Exception ignored) {
            // 客户端因取消而关闭连接属于预期行为
          }
        });

    ProviderDescriptor descriptor =
        new ProviderDescriptor(
            "anthropic-stream-err",
            ProviderType.ANTHROPIC,
            "http://127.0.0.1:" + port + "/v1",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(5), Duration.ofSeconds(3)),
            UUID.randomUUID());

    AnthropicProviderAdapter adapter = new AnthropicProviderAdapter(transport, "test-key");
    AnthropicModelProvider modelProvider = (AnthropicModelProvider) adapter.create(descriptor);

    ProviderRequest request = createTestRequest("anthropic-stream-err", "claude-3-5-sonnet");

    AtomicReference<ProviderException> errorRef = new AtomicReference<>();
    AtomicReference<ProviderCompletion> completionRef = new AtomicReference<>();
    List<ProviderStreamEvent> events = new CopyOnWriteArrayList<>();
    AtomicInteger errorCount = new AtomicInteger();
    CountDownLatch errorLatch = new CountDownLatch(1);

    ProviderStream stream =
        modelProvider.stream(
            request,
            new ProviderStreamHandler() {
              @Override
              public void onEvent(ProviderStreamEvent event, ProviderStream st) {
                events.add(event);
              }

              @Override
              public void onComplete(ProviderCompletion completion, ProviderStream st) {
                completionRef.set(completion);
              }

              @Override
              public void onError(ProviderException error, ProviderStream st) {
                errorCount.incrementAndGet();
                errorRef.set(error);
                errorLatch.countDown();
              }
            });

    assertTrue(errorLatch.await(5, TimeUnit.SECONDS), "stream should receive error callback");
    assertEquals(1, errorCount.get(), "onError must be called exactly once (terminal-once)");
    assertNull(completionRef.get(), "onComplete must not be called after an error");
    assertTrue(events.isEmpty(), "events arriving after error event must be silenced");

    ProviderException error = errorRef.get();
    assertNotNull(error);
    assertEquals(
        ProviderErrorKind.TRANSIENT, error.kind(), "rate_limit_error in SSE must map to TRANSIENT");
    assertTrue(error.getMessage().contains("rate_limit_error"));
    assertTrue(error.getMessage().contains("Rate limit exceeded"));
    assertTrue(error.getMessage().contains("fake-token-leak-999"));
    assertTrue(error.getMessage().contains("node-42"));
    assertNull(error.getCause(), "cause must be null");
  }

  /**
   * 验证通过真实本地 HTTP 服务端返回非 200 HTTP 状态时，全链路通过 AnthropicModelProvider.stream 派发异常并完整暴露响应正文。
   *
   * <p>测试意图：验证端到端 HTTP 层面的 500 故障在真实网络调用下精准流转至 ProviderStreamHandler.onError， 且不产生任何
   * ProviderCompletion。
   */
  @Test
  void should_propagate_http_status_error_via_model_provider_stream() throws Exception {
    server.createContext(
        "/v1/messages",
        exchange -> {
          byte[] errorBody =
              "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"Internal backend crash fake-secret-500\"}}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(500, errorBody.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(errorBody);
          }
        });

    ProviderDescriptor descriptor =
        new ProviderDescriptor(
            "anthropic-http-err",
            ProviderType.ANTHROPIC,
            "http://127.0.0.1:" + port + "/v1",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(5), Duration.ofSeconds(3)),
            UUID.randomUUID());

    AnthropicProviderAdapter adapter = new AnthropicProviderAdapter(transport, "test-key");
    AnthropicModelProvider modelProvider = (AnthropicModelProvider) adapter.create(descriptor);

    ProviderRequest request = createTestRequest("anthropic-http-err", "claude-3-5-sonnet");

    AtomicReference<ProviderException> errorRef = new AtomicReference<>();
    AtomicReference<ProviderCompletion> completionRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    modelProvider.stream(
        request,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {
            completionRef.set(completion);
            latch.countDown();
          }

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            errorRef.set(error);
            latch.countDown();
          }
        });

    assertTrue(latch.await(5, TimeUnit.SECONDS), "stream should complete with error");
    assertNull(completionRef.get(), "onComplete must not be called on HTTP 500");
    ProviderException error = errorRef.get();
    assertNotNull(error);
    assertEquals(ProviderErrorKind.TRANSIENT, error.kind());
    assertEquals(
        "HTTP 500\n{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"Internal backend crash fake-secret-500\"}}",
        error.getMessage());
    assertNull(error.getCause());
  }

  /**
   * 验证从测试资源文件 error-response.json 读取的错误数据同样能够被完整保留并正确映射。
   *
   * <p>测试意图：验证按包结构存放的结构化测试 fixture 正确加载，且其原始正文在异常消息中原样保留。
   */
  @Test
  void should_handle_error_response_from_fixture_resource() throws IOException {
    byte[] fixtureBytes;
    try (InputStream in = getClass().getResourceAsStream("fixtures/error-response.json")) {
      assertNotNull(in, "fixtures/error-response.json must exist in test classpath");
      fixtureBytes = in.readAllBytes();
    }

    JsonNode fixtureNode = MAPPER.readTree(fixtureBytes);
    assertTrue(fixtureNode.has("error"));

    TransportException ex =
        new TransportException(
            TransportErrorKind.HTTP_STATUS, "transport message", 400, fixtureBytes, null, null);

    ProviderException mapped = AnthropicErrorMapper.mapTransportException(ex);
    assertNotNull(mapped);
    assertEquals(ProviderErrorKind.INVALID_REQUEST, mapped.kind());
    assertEquals(
        "HTTP 400\n" + new String(fixtureBytes, StandardCharsets.UTF_8), mapped.getMessage());
    assertNull(mapped.getCause());
    assertFalse(mapped.getMessage().contains("transport message"));
  }

  private static ProviderRequest createTestRequest(String providerName, String modelName) {
    ModelDescriptor model =
        new ModelDescriptor(
            providerName,
            modelName,
            modelName,
            Set.of(ModelInputModality.TEXT),
            true,
            false,
            new ModelPricing(
                "USD",
                "tier-1",
                "default",
                BigDecimal.ONE,
                "v1",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO));

    return new ProviderRequest(
        model,
        new ModelVariant("default"),
        1024,
        "Test system instruction.",
        List.of(
            new ProviderMessage(
                ProviderMessageRole.USER, List.of(new ProviderTextBlock("Test ping")))),
        List.of(),
        ProviderCacheControl.none());
  }
}
