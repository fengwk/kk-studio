package fun.fengwk.kkstudio.harness.provider.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
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

/** 基于真实本地 HttpServer 与 JdkHttpSseTransport 的端到端离线流式集成测试。 */
class GeminiModelProviderIntegrationTest {

  private HttpServer server;
  private int port;
  private ExecutorService workerExecutor;
  private ScheduledExecutorService scheduler;
  private JdkHttpSseTransport transport;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    port = server.getAddress().getPort();
    server.start();

    workerExecutor = Executors.newCachedThreadPool();
    scheduler = Executors.newSingleThreadScheduledExecutor();

    HttpClient client =
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
    if (workerExecutor != null) {
      workerExecutor.shutdownNow();
    }
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }

  private ProviderDescriptor createDescriptor() {
    return new ProviderDescriptor(
        "google-test",
        ProviderType.GOOGLE,
        "http://127.0.0.1:" + port,
        new ModelCallTimeoutPolicy(Duration.ofSeconds(10), Duration.ofSeconds(5)),
        UUID.randomUUID());
  }

  private ProviderRequest createRequest() {
    ModelPricing pricing =
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
            BigDecimal.ZERO);
    ModelDescriptor model =
        new ModelDescriptor(
            "google-test",
            "gemini-2.5-flash",
            Set.of(ModelInputModality.TEXT),
            true,
            false,
            pricing);
    ModelVariant variant =
        new ModelVariant("default", null, null, null, null, null, null, List.of(), null);
    return new ProviderRequest(
        model,
        variant,
        List.of(
            new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hello")))),
        List.of(),
        ProviderCacheControl.none());
  }

  /** 验证端到端真实 SSE 流式传输，请求头携带 x-goog-api-key，事件逐步送达，最终成功交付。 */
  @Test
  void streamsRealSseResponseOverLocalHttpServer() throws Exception {
    AtomicReference<String> capturedApiKey = new AtomicReference<>();
    AtomicReference<String> capturedPath = new AtomicReference<>();

    server.createContext(
        "/models/gemini-2.5-flash:streamGenerateContent",
        exchange -> {
          capturedApiKey.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
          capturedPath.set(exchange.getRequestURI().toString());

          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);

          try (OutputStream os = exchange.getResponseBody()) {
            os.write(
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello \"}]}}]}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();

            Thread.sleep(20);

            os.write(
                ("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"world!\"}]},\"finishReason\":\"STOP\"}],"
                        + "\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":3,\"totalTokenCount\":8}}\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });

    ProviderDescriptor descriptor = createDescriptor();
    URI streamUri = GeminiEndpoints.resolveStreamUri(descriptor.endpoint(), "gemini-2.5-flash");
    GeminiModelProvider provider =
        new GeminiModelProvider(transport, descriptor, "secret-api-key-999", streamUri);

    List<ProviderStreamEvent> receivedEvents = new CopyOnWriteArrayList<>();
    AtomicReference<ProviderCompletion> completionRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    provider.stream(
        createRequest(),
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {
            receivedEvents.add(event);
          }

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            latch.countDown();
          }

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {
            completionRef.set(completion);
            latch.countDown();
          }
        });

    assertTrue(latch.await(5, TimeUnit.SECONDS), "streaming call timed out");

    // 验证发出的请求参数
    assertEquals("secret-api-key-999", capturedApiKey.get());
    assertTrue(capturedPath.get().contains("alt=sse"));

    // 验证接收的响应
    assertNotNull(completionRef.get());
    assertEquals("Hello world!", completionRef.get().response().text());
    assertEquals(GenerationStopReason.COMPLETE, completionRef.get().response().stopReason());
    assertEquals(ProviderReplayFormat.GEMINI_CONTENT, completionRef.get().replayState().format());

    // 验证事件流分发
    List<ProviderStreamEvent.TextDelta> textEvents =
        receivedEvents.stream()
            .filter(e -> e instanceof ProviderStreamEvent.TextDelta)
            .map(e -> (ProviderStreamEvent.TextDelta) e)
            .toList();
    assertEquals(2, textEvents.size());
    assertEquals("Hello ", textEvents.get(0).text());
    assertEquals("world!", textEvents.get(1).text());
  }

  /** 验证服务端返回 403 时，正确捕获并分类为 AUTHENTICATION。 */
  @Test
  void handlesHttp403ForbiddenOverRealNetwork() throws Exception {
    server.createContext(
        "/models/gemini-2.5-flash:streamGenerateContent",
        exchange -> {
          byte[] body =
              "{\"error\":{\"code\":403,\"message\":\"API key forbidden\",\"status\":\"PERMISSION_DENIED\"}}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(403, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });

    ProviderDescriptor descriptor = createDescriptor();
    URI streamUri = GeminiEndpoints.resolveStreamUri(descriptor.endpoint(), "gemini-2.5-flash");
    GeminiModelProvider provider =
        new GeminiModelProvider(transport, descriptor, "bad-key", streamUri);

    AtomicReference<ProviderException> caught = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    provider.stream(
        createRequest(),
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            caught.set(error);
            latch.countDown();
          }

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {
            latch.countDown();
          }
        });

    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertNotNull(caught.get());
    assertEquals(ProviderErrorKind.AUTHENTICATION, caught.get().kind());
  }
}
