package fun.fengwk.kkstudio.harness.provider.openai.responses;

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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 验证真实网络套接字分片下 Responses SSE 流的端到端解码：覆盖多字节 UTF-8 边界与粘包。 */
class OpenAiResponsesStreamingDecoderTest {

  private HttpServer server;
  private int port;
  private ExecutorService workerExecutor;
  private ScheduledExecutorService scheduler;
  private HttpClient httpClient;
  private JdkHttpSseTransport transport;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    port = server.getAddress().getPort();
    server.start();

    workerExecutor = Executors.newCachedThreadPool();
    scheduler = Executors.newSingleThreadScheduledExecutor();

    httpClient =
        HttpClient.newBuilder()
            .executor(workerExecutor)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    transport = new JdkHttpSseTransport(httpClient, workerExecutor, scheduler);
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

  private ProviderDescriptor createDescriptor() {
    return new ProviderDescriptor(
        "openai_test",
        ProviderType.OPENAI_RESPONSES,
        "http://127.0.0.1:" + port,
        new ModelCallTimeoutPolicy(Duration.ofSeconds(10), Duration.ofSeconds(10)),
        UUID.fromString("11111111-1111-1111-1111-111111111111"));
  }

  private ProviderRequest createRequest() {
    ModelPricing pricing =
        new ModelPricing(
            "USD",
            "tier-1",
            "default",
            BigDecimal.ONE,
            "v1",
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ONE);
    ModelDescriptor model =
        new ModelDescriptor(
            "openai_test",
            "gpt-5.4-mini",
            "gpt-5.4-mini",
            Set.of(ModelInputModality.TEXT),
            true,
            true,
            pricing);
    return new ProviderRequest(
        model,
        new ModelVariant("default"),
        1024,
        List.of(
            new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("hi")))),
        List.of(),
        ProviderCacheControl.none());
  }

  /** 模拟通过 TCP socket 以 1 字节切片输出多字节中文与 Emoji，验证端到端文本与推理摘要正确汇聚。 */
  @Test
  void test_streamingDecoder_multibyteUtf8SingleByteChunking() throws Exception {
    String payload =
        ": keep-alive\n\n"
            + "data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_dec\"}}\n\n"
            + "data: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"思考：测试中文 🤔 与表情符 🎉\"}\n\n"
            + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"你好世界！🌍 多字节切片安全验证。🚀\"}\n\n"
            + "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_dec\",\"status\":\"completed\","
            + "\"usage\":{\"input_tokens\":10,\"output_tokens\":20,\"total_tokens\":30}}}\n\n";

    byte[] fullBytes = payload.getBytes(StandardCharsets.UTF_8);

    server.createContext(
        "/responses",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            for (byte b : fullBytes) {
              os.write(new byte[] {b});
              os.flush();
            }
          }
        });

    OpenAiResponsesProviderAdapter adapter =
        new OpenAiResponsesProviderAdapter(transport, "sk-test");
    ModelProvider provider = adapter.create(createDescriptor());

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<ProviderCompletion> completionRef = new AtomicReference<>();

    provider.stream(
        createRequest(),
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
            latch.countDown();
          }
        });

    assertTrue(latch.await(5, TimeUnit.SECONDS), "streaming call timed out");
    ProviderCompletion completion = completionRef.get();
    assertNotNull(completion);
    ProviderResponse resp = completion.response();
    assertEquals("你好世界！🌍 多字节切片安全验证。🚀", resp.text());
    assertEquals("思考：测试中文 🤔 与表情符 🎉", resp.thinking());
    assertEquals(GenerationStopReason.COMPLETE, resp.stopReason());
  }
}
