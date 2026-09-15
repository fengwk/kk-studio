package fun.fengwk.kkstudio.harness.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicReference;

/** 基于真实本地 HttpServer 与真 JdkHttpSseTransport 的端到端集成测试。 */
class AnthropicModelProviderIntegrationTest {

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

  @Test
  void performsSuccessfulStreamingCallWithHeadersAndBodyVerification() throws Exception {
    AtomicReference<String> receivedAuthHeader = new AtomicReference<>();
    AtomicReference<String> receivedBearerHeader = new AtomicReference<>();
    AtomicReference<String> receivedVersionHeader = new AtomicReference<>();
    AtomicReference<String> receivedBetaHeader = new AtomicReference<>();
    AtomicReference<String> receivedPath = new AtomicReference<>();
    CountDownLatch serverReceivedLatch = new CountDownLatch(1);

    server.createContext(
        "/v1/messages",
        exchange -> {
          receivedAuthHeader.set(exchange.getRequestHeaders().getFirst("x-api-key"));
          receivedBearerHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
          receivedVersionHeader.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
          receivedBetaHeader.set(exchange.getRequestHeaders().getFirst("anthropic-beta"));
          receivedPath.set(exchange.getRequestURI().getPath());
          serverReceivedLatch.countDown();

          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);

          try (OutputStream os = exchange.getResponseBody()) {
            os.write(
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_e2e\",\"usage\":{\"input_tokens\":8,\"output_tokens\":1}}}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.write(
                "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.write(
                "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello World\"}}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.write(
                "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.write(
                "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":5}}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.write(
                "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();
          }
        });

    ProviderDescriptor descriptor =
        new ProviderDescriptor(
            "anthropic-e2e",
            ProviderType.ANTHROPIC,
            "http://127.0.0.1:" + port + "/v1",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(5), Duration.ofSeconds(3)),
            UUID.randomUUID());

    AnthropicProviderAdapter adapter = new AnthropicProviderAdapter(transport, "sk-ant-test-key");
    AnthropicModelProvider modelProvider = (AnthropicModelProvider) adapter.create(descriptor);

    ProviderRequest request =
        new ProviderRequest(
            new ModelDescriptor(
                "anthropic-e2e",
                "claude-3-5-sonnet",
                "claude-3-5-sonnet",
                Set.of(ModelInputModality.TEXT),
                true,
                false,
                pricing()),
            new ModelVariant("default"),
            1024,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
            List.of(),
            ProviderCacheControl.none());

    List<ProviderStreamEvent> events = new CopyOnWriteArrayList<>();
    AtomicReference<ProviderCompletion> completionRef = new AtomicReference<>();
    CountDownLatch completionLatch = new CountDownLatch(1);

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
            completionLatch.countDown();
          }

          @Override
          public void onError(ProviderException error, ProviderStream stream) {}
        });

    assertTrue(serverReceivedLatch.await(5, TimeUnit.SECONDS), "server should receive request");
    assertEquals("sk-ant-test-key", receivedAuthHeader.get());
    assertEquals("Bearer sk-ant-test-key", receivedBearerHeader.get());
    assertEquals("2023-06-01", receivedVersionHeader.get());
    assertNull(receivedBetaHeader.get());
    assertEquals("/v1/messages", receivedPath.get());

    assertTrue(completionLatch.await(5, TimeUnit.SECONDS), "stream should complete");
    ProviderCompletion completion = completionRef.get();
    assertNotNull(completion);
    assertEquals("Hello World", completion.response().text());
    assertEquals(GenerationStopReason.COMPLETE, completion.response().stopReason());
    assertEquals(8, completion.response().usage().inputTokens());
    assertEquals(5, completion.response().usage().outputTokens());
  }

  @Test
  void mapsHttp401ToAuthenticationError() throws Exception {
    server.createContext(
        "/v1/messages",
        exchange -> {
          byte[] errorBody =
              "{\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid api key\"}}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(401, errorBody.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(errorBody);
          }
        });

    ProviderDescriptor descriptor =
        new ProviderDescriptor(
            "anthropic-auth-err",
            ProviderType.ANTHROPIC,
            "http://127.0.0.1:" + port + "/v1",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(5), Duration.ofSeconds(3)),
            UUID.randomUUID());

    AnthropicProviderAdapter adapter = new AnthropicProviderAdapter(transport, "invalid-key");
    AnthropicModelProvider modelProvider = (AnthropicModelProvider) adapter.create(descriptor);

    ProviderRequest request =
        new ProviderRequest(
            new ModelDescriptor(
                "anthropic-auth-err",
                "claude-3-5-sonnet",
                "claude-3-5-sonnet",
                Set.of(ModelInputModality.TEXT),
                true,
                false,
                pricing()),
            new ModelVariant("default"),
            1024,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
            List.of(),
            ProviderCacheControl.none());

    AtomicReference<ProviderException> errorRef = new AtomicReference<>();
    CountDownLatch errorLatch = new CountDownLatch(1);

    modelProvider.stream(
        request,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            errorRef.set(error);
            errorLatch.countDown();
          }
        });

    assertTrue(errorLatch.await(5, TimeUnit.SECONDS), "stream should report error");
    ProviderException error = errorRef.get();
    assertNotNull(error);
    assertEquals(ProviderErrorKind.AUTHENTICATION, error.kind());
    assertEquals(
        "HTTP 401\n{\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid api key\"}}",
        error.getMessage());
  }

  @Test
  void handlesClientCancellationSilently() throws Exception {
    CountDownLatch clientReceivedEventLatch = new CountDownLatch(1);
    server.createContext(
        "/v1/messages",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);

          try (OutputStream os = exchange.getResponseBody()) {
            os.write(
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{}}}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.write(
                "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"first\"}}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();

            // 等待客户端收到第一个事件后发出取消
            clientReceivedEventLatch.await(5, TimeUnit.SECONDS);

            // 尝试再发第二个事件
            os.write(
                "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"second\"}}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();
          } catch (Exception ignored) {
            // 客户端连接断开属于预期行为
          }
        });

    ProviderDescriptor descriptor =
        new ProviderDescriptor(
            "anthropic-cancel",
            ProviderType.ANTHROPIC,
            "http://127.0.0.1:" + port + "/v1",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(5), Duration.ofSeconds(3)),
            UUID.randomUUID());

    AnthropicProviderAdapter adapter = new AnthropicProviderAdapter(transport, "test-key");
    AnthropicModelProvider modelProvider = (AnthropicModelProvider) adapter.create(descriptor);

    ProviderRequest request =
        new ProviderRequest(
            new ModelDescriptor(
                "anthropic-cancel",
                "claude-3-5-sonnet",
                "claude-3-5-sonnet",
                Set.of(ModelInputModality.TEXT),
                true,
                false,
                pricing()),
            new ModelVariant("default"),
            1024,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
            List.of(),
            ProviderCacheControl.none());

    AtomicReference<ProviderStream> streamRef = new AtomicReference<>();
    List<ProviderStreamEvent> receivedEvents = new CopyOnWriteArrayList<>();

    ProviderStream stream =
        modelProvider.stream(
            request,
            new ProviderStreamHandler() {
              @Override
              public void onEvent(ProviderStreamEvent event, ProviderStream st) {
                receivedEvents.add(event);
                st.cancel(); // 收到第一个事件立即取消
                clientReceivedEventLatch.countDown();
              }

              @Override
              public void onComplete(ProviderCompletion completion, ProviderStream st) {}

              @Override
              public void onError(ProviderException error, ProviderStream st) {}
            });

    streamRef.set(stream);
    assertTrue(clientReceivedEventLatch.await(5, TimeUnit.SECONDS));
    assertTrue(stream.isCancelled());
  }

  @Test
  void handlesEncodeErrorSynchronously() {
    ProviderDescriptor descriptor =
        new ProviderDescriptor(
            "anthropic-err",
            ProviderType.ANTHROPIC,
            "http://127.0.0.1:" + port + "/v1",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(5), Duration.ofSeconds(3)),
            UUID.randomUUID());

    AnthropicProviderAdapter adapter = new AnthropicProviderAdapter(transport, "test-key");
    AnthropicModelProvider modelProvider = (AnthropicModelProvider) adapter.create(descriptor);
    assertEquals(descriptor, modelProvider.descriptor());
    assertTrue(modelProvider.toString().contains("AnthropicModelProvider"));

    // 传入非法工具 schema 触发 encode 阶段失败
    ModelVariant badVariant = new ModelVariant("bad");
    ProviderToolDefinition invalidTool =
        new ProviderToolDefinition("bad_tool", "desc", "{invalid_json");
    ProviderRequest request =
        new ProviderRequest(
            new ModelDescriptor(
                "anthropic-err",
                "claude-3-5-sonnet",
                "claude-3-5-sonnet",
                Set.of(ModelInputModality.TEXT),
                true,
                false,
                pricing()),
            badVariant,
            1024,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
            List.of(invalidTool),
            ProviderCacheControl.none());

    AtomicReference<ProviderException> caughtError = new AtomicReference<>();
    ProviderStream stream =
        modelProvider.stream(
            request,
            new ProviderStreamHandler() {
              @Override
              public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

              @Override
              public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

              @Override
              public void onError(ProviderException error, ProviderStream stream) {
                caughtError.set(error);
              }
            });

    assertNotNull(caughtError.get());
    assertEquals(ProviderErrorKind.INVALID_REQUEST, caughtError.get().kind());
  }

  @Test
  void handlesMalformedEventAndPrematureEof() throws Exception {
    CountDownLatch errorLatch = new CountDownLatch(1);
    AtomicReference<ProviderException> caughtError = new AtomicReference<>();

    server.createContext(
        "/v1/messages",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("event: message_start\ndata: {bad json\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
          }
        });

    ProviderDescriptor descriptor =
        new ProviderDescriptor(
            "anthropic-malformed",
            ProviderType.ANTHROPIC,
            "http://127.0.0.1:" + port + "/v1",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(5), Duration.ofSeconds(3)),
            UUID.randomUUID());

    AnthropicModelProvider modelProvider =
        (AnthropicModelProvider) new AnthropicProviderAdapter(transport, null).create(descriptor);

    ProviderRequest request =
        new ProviderRequest(
            new ModelDescriptor(
                "anthropic-malformed",
                "claude-3-5-sonnet",
                "claude-3-5-sonnet",
                Set.of(ModelInputModality.TEXT),
                true,
                false,
                pricing()),
            new ModelVariant("default"),
            1024,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
            List.of(),
            ProviderCacheControl.none());

    modelProvider.stream(
        request,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            caughtError.set(error);
            errorLatch.countDown();
          }
        });

    assertTrue(errorLatch.await(5, TimeUnit.SECONDS));
    assertNotNull(caughtError.get());
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, caughtError.get().kind());
  }

  private static ModelPricing pricing() {
    return new ModelPricing(
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
        BigDecimal.ZERO);
  }
}
