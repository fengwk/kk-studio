package fun.fengwk.kkstudio.harness.provider.openai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderProtocolEvent;
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
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试意图：验证本地 HTTP SSE 服务下，每条 transport 帧都被原样、exactly-once 地回调为 native 协议事件，且 raw 回调严格先于该帧派生的
 * normalized 增量；DONE 使用 {@code done}，error 帧先 raw 再进入现有 error 分类。
 */
class OpenAiChatProtocolEventTest {

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
  @DisplayName("每帧 exactly-once raw 回调，raw 先于该帧的 normalized 增量，DONE 使用 done")
  void emitsRawProtocolEventOnceBeforeNormalizedDelta() throws Exception {
    List<Frame> frames =
        List.of(
            new Frame(
                null,
                "{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,"
                    + "\"delta\":{\"role\":\"assistant\",\"content\":\"Hel\"},\"finish_reason\":null}]}"),
            new Frame(
                "vendor.custom",
                "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lo\"},"
                    + "\"finish_reason\":\"stop\"}]}"),
            new Frame(null, "{\"id\":\"c1\",\"type\":\"vendor.type\",\"choices\":[]}"),
            new Frame(null, "[DONE]"));
    serve(frames);

    List<String> timeline = Collections.synchronizedList(new ArrayList<>());
    List<String> rawTypes = Collections.synchronizedList(new ArrayList<>());
    List<String> rawData = Collections.synchronizedList(new ArrayList<>());
    AtomicReference<ProviderCompletion> completionRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    provider().stream(
        request(),
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {
            timeline.add("delta:" + ((ProviderStreamEvent.TextDelta) event).text());
          }

          @Override
          public void onProtocolEvent(ProviderProtocolEvent event, ProviderStream stream) {
            rawTypes.add(event.eventType());
            rawData.add(event.data());
            timeline.add("raw:" + event.eventType());
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
    assertEquals("Hello", completionRef.get().response().text());
    assertEquals(GenerationStopReason.COMPLETE, completionRef.get().response().stopReason());

    // 事件名优先级：SSE name > payload object/type > chat.completion.chunk；[DONE] 为 done
    assertEquals(
        List.of("chat.completion.chunk", "vendor.custom", "vendor.type", "done"), rawTypes);
    // payload 不经反序列化重写，原样透传
    assertEquals(frames.stream().map(Frame::data).toList(), rawData);
    // 每条 raw 都紧邻它派生的 normalized 增量之前，且各自 exactly-once
    assertEquals(
        List.of(
            "raw:chat.completion.chunk",
            "delta:Hel",
            "raw:vendor.custom",
            "delta:lo",
            "raw:vendor.type",
            "raw:done"),
        timeline);
  }

  @Test
  @DisplayName("error 帧先 raw 回调再进入现有 error 分类，且不产生 completion")
  void emitsRawBeforeErrorClassification() throws Exception {
    List<Frame> frames =
        List.of(
            new Frame(
                null,
                "{\"id\":\"c-err\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"},"
                    + "\"finish_reason\":null}]}"),
            new Frame(
                "error.event",
                "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"boom\"}}"));
    serve(frames);

    List<String> timeline = Collections.synchronizedList(new ArrayList<>());
    AtomicReference<ProviderException> errorRef = new AtomicReference<>();
    AtomicReference<ProviderCompletion> completionRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    provider().stream(
        request(),
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {
            timeline.add("delta:" + ((ProviderStreamEvent.TextDelta) event).text());
          }

          @Override
          public void onProtocolEvent(ProviderProtocolEvent event, ProviderStream stream) {
            timeline.add("raw:" + event.eventType());
          }

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

    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertNull(completionRef.get());
    assertNotNull(errorRef.get());
    assertEquals(ProviderErrorKind.INVALID_REQUEST, errorRef.get().kind());
    // error 帧本身也是先 raw 后 error，且 delta 只来自 content 帧
    assertEquals(
        List.of("raw:chat.completion.chunk", "delta:partial", "raw:error.event"), timeline);
  }

  @Test
  @DisplayName("事件名回落：JSON object 优先于 type，空/非法判定字段回落到 chat.completion.chunk")
  void fallsBackToChunkEventType() throws Exception {
    List<Frame> frames =
        List.of(
            new Frame(null, ""),
            new Frame(null, "{\"object\":\"\",\"type\":123,\"choices\":[]}"),
            new Frame(null, "{\"object\":123,\"type\":\"\",\"choices\":[]}"),
            new Frame("", "{\"choices\":[]}"),
            new Frame(
                null, "{\"object\":\"vendor.object\",\"type\":\"ignored.type\",\"choices\":[]}"),
            new Frame(
                null,
                "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\","
                    + "\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"),
            new Frame(null, "[DONE]"));
    serve(frames);

    List<String> rawTypes = Collections.synchronizedList(new ArrayList<>());
    AtomicReference<ProviderCompletion> completionRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    provider().stream(
        request(),
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onProtocolEvent(ProviderProtocolEvent event, ProviderStream stream) {
            rawTypes.add(event.eventType());
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
    assertEquals("ok", completionRef.get().response().text());
    assertEquals(
        List.of(
            "chat.completion.chunk",
            "chat.completion.chunk",
            "chat.completion.chunk",
            "chat.completion.chunk",
            "vendor.object",
            "chat.completion.chunk",
            "done"),
        rawTypes);
  }

  @ParameterizedTest(name = "非 object payload [{0}] 先 raw 回调再以 INVALID_RESPONSE 失败")
  @ValueSource(strings = {"not-json", "[1,2]", "123"})
  void fallsBackToChunkTypeAndRejectsNonObjectPayload(String payload) throws Exception {
    serve(List.of(new Frame(null, payload)));

    List<String> rawTypes = Collections.synchronizedList(new ArrayList<>());
    AtomicReference<ProviderException> errorRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    provider().stream(
        request(),
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onProtocolEvent(ProviderProtocolEvent event, ProviderStream stream) {
            rawTypes.add(event.eventType());
          }

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {
            latch.countDown();
          }

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            errorRef.set(error);
            latch.countDown();
          }
        });

    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertNotNull(errorRef.get());
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, errorRef.get().kind());
    // 非法 payload 不能阻止该帧的 raw 回调
    assertEquals(List.of("chat.completion.chunk"), rawTypes);
  }

  private ModelProvider provider() {
    return new OpenAiChatProviderAdapter(transport, "test-key").create(descriptor);
  }

  private ProviderRequest request() {
    return new ProviderRequest(
        modelDesc,
        defaultVariant,
        1024,
        "Test system instruction.",
        List.of(
            new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
        List.of(),
        ProviderCacheControl.none());
  }

  private void serve(List<Frame> frames) {
    StringBuilder body = new StringBuilder();
    for (Frame frame : frames) {
      if (frame.eventName() != null) {
        body.append("event: ").append(frame.eventName()).append('\n');
      }
      body.append("data: ").append(frame.data()).append("\n\n");
    }
    byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
    server.createContext(
        "/chat/completions",
        exchange -> {
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(payload);
            os.flush();
          }
        });
  }

  /** 一帧 SSE：{@code eventName} 为可选 SSE event 名，{@code data} 为原始 data 行内容。 */
  private record Frame(String eventName, String data) {}
}
