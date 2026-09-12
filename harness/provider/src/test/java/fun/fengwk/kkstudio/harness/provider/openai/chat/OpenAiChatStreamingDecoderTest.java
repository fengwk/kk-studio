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
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试意图：验证 OpenAI Chat Completions 在复杂网络传输层分片（包括极端 1 字节网络刷新、多字节 UTF-8 跨 chunk、随机 chunk 切分） 以及端到端本地
 * HTTP SSE 服务下的健壮流式解码与事件聚合。
 */
class OpenAiChatStreamingDecoderTest {

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
  @DisplayName("网络传输层 1 字节步长强制 flush 及多字节 UTF-8（中文、emoji）解码一致性")
  void testSingleByteChunksWithMultibyteUtf8() throws Exception {
    String sseData =
        """
        data: {"id":"c-utf8","choices":[{"index":0,"delta":{"content":"你好，世界！🌍"},"finish_reason":null}]}

        data: {"id":"c-utf8","choices":[{"index":0,"delta":{"content":"测试通过。"},"finish_reason":"stop"}]}

        data: [DONE]

        """;

    server.createContext(
        "/chat/completions",
        exchange -> {
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            byte[] bytes = sseData.getBytes(StandardCharsets.UTF_8);
            for (byte b : bytes) {
              os.write(b);
              os.flush();
            }
          }
        });

    OpenAiChatProviderAdapter adapter = new OpenAiChatProviderAdapter(transport, "test-key");
    ModelProvider provider = adapter.create(descriptor);

    ProviderRequest request =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
            List.of(),
            ProviderCacheControl.none());

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<ProviderCompletion> completionRef = new AtomicReference<>();

    provider.stream(
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
            latch.countDown();
          }
        });

    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertNotNull(completionRef.get());
    assertEquals("你好，世界！🌍测试通过。", completionRef.get().response().text());
    assertEquals(GenerationStopReason.COMPLETE, completionRef.get().response().stopReason());
  }

  @Test
  @DisplayName("随机分片步长模拟复杂网络抖动")
  void testRandomChunkSlices() throws Exception {
    String sseData =
        """
        data: {"id":"c-rnd","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"思考中..."},"finish_reason":null}]}

        data: {"id":"c-rnd","choices":[{"index":0,"delta":{"content":"最终答复。"},"finish_reason":"stop"}]}

        data: [DONE]

        """;

    server.createContext(
        "/chat/completions",
        exchange -> {
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            byte[] bytes = sseData.getBytes(StandardCharsets.UTF_8);
            Random rnd = new Random(42);
            int offset = 0;
            while (offset < bytes.length) {
              int sliceLen = Math.min(rnd.nextInt(7) + 1, bytes.length - offset);
              os.write(bytes, offset, sliceLen);
              os.flush();
              offset += sliceLen;
            }
          }
        });

    OpenAiChatProviderAdapter adapter = new OpenAiChatProviderAdapter(transport, "test-key");
    ModelProvider provider = adapter.create(descriptor);

    ProviderRequest request =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
            List.of(),
            ProviderCacheControl.none());

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<ProviderCompletion> completionRef = new AtomicReference<>();

    provider.stream(
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
            latch.countDown();
          }
        });

    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertNotNull(completionRef.get());
    assertEquals("最终答复。", completionRef.get().response().text());
    assertEquals("思考中...", completionRef.get().response().thinking());
    assertEquals(GenerationStopReason.COMPLETE, completionRef.get().response().stopReason());
  }

  @Test
  @DisplayName("端到端本地 HTTP 服务完整流式往返与事件派发")
  void testEndToEndHttpStreaming() throws Exception {
    server.createContext(
        "/chat/completions",
        exchange -> {
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(
                "data: {\"id\":\"http-1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.write(
                "data: {\"id\":\"http-1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\" from HTTP!\"},\"finish_reason\":\"stop\"}]}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
          }
        });

    OpenAiChatProviderAdapter adapter = new OpenAiChatProviderAdapter(transport, "test-api-key");
    ModelProvider provider = adapter.create(descriptor);

    ProviderRequest request =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
            List.of(),
            ProviderCacheControl.none());

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<ProviderCompletion> completionRef = new AtomicReference<>();
    List<ProviderStreamEvent> events = new ArrayList<>();

    provider.stream(
        request,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {
            events.add(event);
          }

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

    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertNotNull(completionRef.get());
    assertEquals("Hello from HTTP!", completionRef.get().response().text());
    assertEquals(GenerationStopReason.COMPLETE, completionRef.get().response().stopReason());
    assertEquals(2, events.size());
  }
}
