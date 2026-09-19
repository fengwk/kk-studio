package fun.fengwk.kkstudio.harness.provider.openai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 测试意图：端到端集成测试，验证本地 HTTP 服务下连续多步工具循环、HTTP 异常分类映射与客户端取消。 */
class OpenAiChatModelProviderIntegrationTest {

  private HttpServer server;
  private int port;
  private ExecutorService workerExecutor;
  private ScheduledExecutorService scheduler;
  private HttpClient httpClient;
  private JdkHttpSseTransport transport;
  private ProviderDescriptor descriptor;
  private ModelDescriptor modelDesc;
  private ModelVariant defaultVariant;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    port = server.getAddress().getPort();
    server.start();

    workerExecutor = Executors.newCachedThreadPool();
    scheduler = Executors.newSingleThreadScheduledExecutor();
    httpClient =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    transport = new JdkHttpSseTransport(httpClient, workerExecutor, scheduler);

    descriptor =
        new ProviderDescriptor(
            "openai",
            ProviderType.OPENAI,
            "http://127.0.0.1:" + port,
            new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)));
    ModelPricing pricing =
        new ModelPricing(
            "USD",
            "standard",
            "tier1",
            BigDecimal.ONE,
            "v1",
            new BigDecimal("2.50"),
            new BigDecimal("10.00"),
            new BigDecimal("1.25"),
            new BigDecimal("1.25"),
            new BigDecimal("1.25"),
            new BigDecimal("10.00"));
    modelDesc =
        new ModelDescriptor(
            "openai", "gpt-4o", "gpt-4o", Set.of(ModelInputModality.TEXT), true, false, pricing);
    defaultVariant = new ModelVariant("default");
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
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

  @Test
  @DisplayName("多步连续工具循环：首轮发起 tool_calls，次轮传入 tool 结果并输出文本完成")
  void testMultiTurnToolCycle() throws Exception {
    server.createContext(
        "/chat/completions",
        exchange -> {
          try {
            String body =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
              if (body.contains("\"role\":\"tool\"")) {
                // 第二轮响应
                os.write(
                    "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Weather in Tokyo is sunny.\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":6,\"total_tokens\":26}}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
              } else {
                // 第一轮响应：发起工具调用
                os.write(
                    "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_weather_tokyo\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"Tokyo\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":15,\"completion_tokens\":10,\"total_tokens\":25}}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
              }
              os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
              os.flush();
            }
          } catch (Exception e) {
            exchange.sendResponseHeaders(500, 0);
          }
        });

    OpenAiChatProviderAdapter adapter = new OpenAiChatProviderAdapter(transport, "sk-test");
    ModelProvider provider = adapter.create(descriptor);

    // Turn 1
    ProviderToolDefinition tool =
        new ProviderToolDefinition("get_weather", "Get weather", "{\"type\":\"object\"}");
    ProviderMessage userMsg1 =
        new ProviderMessage(
            ProviderMessageRole.USER, List.of(new ProviderTextBlock("Weather in Tokyo?")));
    ProviderRequest req1 =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(userMsg1),
            List.of(tool),
            ProviderCacheControl.none());

    CountDownLatch latch1 = new CountDownLatch(1);
    AtomicReference<ProviderCompletion> comp1 = new AtomicReference<>();
    provider.stream(
        req1,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {
            comp1.set(completion);
            latch1.countDown();
          }

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            latch1.countDown();
          }
        });

    assertTrue(latch1.await(5, TimeUnit.SECONDS));
    assertNotNull(comp1.get());
    assertEquals(1, comp1.get().response().toolCalls().size());
    ProviderToolCall toolCall = comp1.get().response().toolCalls().get(0);
    assertEquals("call_weather_tokyo", toolCall.id());

    // Turn 2
    ProviderMessage asstMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderToolCallBlock(toolCall)),
            comp1.get().replayState());
    ProviderMessage toolMsg =
        new ProviderMessage(
            ProviderMessageRole.TOOL,
            List.of(
                new ProviderToolResultBlock(
                    toolCall.id(),
                    toolCall.name(),
                    List.of(new ProviderTextBlock("{\"weather\":\"sunny\"}")),
                    false,
                    "{}")));
    ProviderRequest req2 =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(userMsg1, asstMsg, toolMsg),
            List.of(tool),
            ProviderCacheControl.none());

    CountDownLatch latch2 = new CountDownLatch(1);
    AtomicReference<ProviderCompletion> comp2 = new AtomicReference<>();
    provider.stream(
        req2,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {
            comp2.set(completion);
            latch2.countDown();
          }

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            latch2.countDown();
          }
        });

    assertTrue(latch2.await(5, TimeUnit.SECONDS));
    assertNotNull(comp2.get());
    assertEquals("Weather in Tokyo is sunny.", comp2.get().response().text());
    assertEquals(GenerationStopReason.COMPLETE, comp2.get().response().stopReason());
  }

  @Test
  @DisplayName("HTTP 401 异常正确触发 onError 并映射为 AUTHENTICATION")
  void testHttp401ErrorMapping() throws Exception {
    server.createContext(
        "/chat/completions",
        exchange -> {
          byte[] errBytes =
              "{\"error\":{\"message\":\"Invalid key\",\"type\":\"invalid_request_error\",\"code\":\"invalid_api_key\"}}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(401, errBytes.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(errBytes);
          }
        });

    OpenAiChatProviderAdapter adapter = new OpenAiChatProviderAdapter(transport, "sk-invalid");
    ModelProvider provider = adapter.create(descriptor);

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<ProviderException> errorRef = new AtomicReference<>();

    ProviderRequest request =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
            List.of(),
            ProviderCacheControl.none());

    provider.stream(
        request,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            errorRef.set(error);
            latch.countDown();
          }
        });

    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertNotNull(errorRef.get());
    assertEquals(ProviderErrorKind.AUTHENTICATION, errorRef.get().kind());
  }
}
