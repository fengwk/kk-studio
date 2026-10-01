package fun.fengwk.kkstudio.harness.provider.openai.responses;

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

import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;
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

/** 验证真实网络套接字分片下 Responses SSE 流的端到端解码：覆盖多字节 UTF-8 边界与粘包。 */
class OpenAiResponsesStreamingDecoderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

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
        "Test system instruction.",
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

  private byte[] loadFixtureBytes(String resourcePath) throws IOException {
    try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
      assertNotNull(in, "fixture resource not found: " + resourcePath);
      return in.readAllBytes();
    }
  }

  private ProviderCompletion streamAndAwait(ModelProvider provider, ProviderRequest request)
      throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<ProviderCompletion> completionRef = new AtomicReference<>();
    AtomicReference<ProviderException> errorRef = new AtomicReference<>();
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
            errorRef.set(error);
            latch.countDown();
          }
        });
    assertTrue(latch.await(5, TimeUnit.SECONDS), "streaming call timed out");
    if (errorRef.get() != null) {
      throw errorRef.get();
    }
    ProviderCompletion comp = completionRef.get();
    assertNotNull(comp);
    return comp;
  }

  private static void assertToolResultItem(
      JsonNode item, String callId, String text, boolean hasBreakpoint) {
    assertEquals("function_call_output", item.path("type").asText());
    assertEquals(callId, item.path("call_id").asText());
    assertTrue(item.path("output").isArray());
    assertEquals(1, item.path("output").size());
    JsonNode block = item.path("output").get(0);
    assertEquals("input_text", block.path("type").asText());
    assertEquals(text, block.path("text").asText());
    assertEquals(hasBreakpoint, block.has("prompt_cache_breakpoint"));
    if (hasBreakpoint) {
      assertEquals("explicit", block.path("prompt_cache_breakpoint").path("mode").asText());
    }
    assertFalse(item.has("prompt_cache_breakpoint"));
    assertFalse(item.has("prompt_cache_key"));
    assertFalse(item.has("prompt_cache_options"));
  }

  /**
   * 验证 gpt-5.6 显式缓存的三轮离线 HTTP 交互：并行工具端点继承、输入块转换与 native signed replay 保真， 且缓存 key
   * 稳定、sourcePrefixHash 与 automatic 编码一致。
   */
  @Test
  void test_multiTurnExplicitCache_withParallelToolsAndNativeReplay() throws Exception {
    String userSchema =
        "{\"type\":\"object\",\"properties\":{\"user_id\":{\"type\":\"string\"}},\"required\":[\"user_id\"]}";
    String orderSchema =
        "{\"type\":\"object\",\"properties\":{\"order_id\":{\"type\":\"string\"}},\"required\":[\"order_id\"]}";
    List<ProviderToolDefinition> tools =
        List.of(
            new ProviderToolDefinition("lookup_user", "lookup", userSchema),
            new ProviderToolDefinition("fetch_orders", "orders", userSchema),
            new ProviderToolDefinition("calculate_discount", "discount", orderSchema));

    List<byte[]> fixtureList =
        List.of(
            loadFixtureBytes("fixtures/multi-turn-cache-round1.sse"),
            loadFixtureBytes("fixtures/multi-turn-cache-round2.sse"),
            loadFixtureBytes("fixtures/multi-turn-cache-round3.sse"));

    List<JsonNode> capturedRequests = new CopyOnWriteArrayList<>();
    AtomicInteger requestCounter = new AtomicInteger(0);
    AtomicReference<Throwable> serverError = new AtomicReference<>();

    server.createContext(
        "/responses",
        exchange -> {
          try {
            capturedRequests.add(MAPPER.readTree(exchange.getRequestBody().readAllBytes()));
            int idx = requestCounter.getAndIncrement();
            if (idx < fixtureList.size()) {
              byte[] resp = fixtureList.get(idx);
              exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
              exchange.sendResponseHeaders(200, resp.length);
              try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
                os.flush();
              }
            } else {
              exchange.sendResponseHeaders(404, -1);
            }
          } catch (Throwable t) {
            serverError.set(t);
            try {
              exchange.sendResponseHeaders(500, -1);
            } catch (IOException ignored) {
            }
          } finally {
            exchange.close();
          }
        });

    ProviderDescriptor descriptor = createDescriptor();
    ModelProvider provider =
        new OpenAiResponsesProviderAdapter(
                transport,
                "sk-test",
                new OpenAiResponsesConfig(OpenAiPromptCacheMode.GPT_5_6_EXPLICIT))
            .create(descriptor);

    String affinityKey = "test_session_affinity_key";
    ProviderCacheControl cacheControl =
        ProviderCacheControl.breakpoints(
            PromptCacheRetention.SHORT, affinityKey, Set.of(PromptCacheBreakpoint.CONVERSATION));
    ModelDescriptor model =
        new ModelDescriptor(
            "openai_test",
            "gpt-5.6",
            "gpt-5.6",
            Set.of(ModelInputModality.TEXT),
            true,
            true,
            createRequest().model().pricing());
    ModelVariant variant = new ModelVariant("default", "medium");
    String sys = "Test system instruction.";

    // Round 1
    String userText = "Check customer orders and discount.";
    ProviderMessage userMsg =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock(userText)));
    ProviderRequest req1 =
        new ProviderRequest(model, variant, 1024, sys, List.of(userMsg), tools, cacheControl);
    ProviderCompletion comp1 = streamAndAwait(provider, req1);
    assertEquals("Round 1 reasoning summary", comp1.response().thinking());
    assertEquals(2, comp1.response().toolCalls().size());
    assertNotNull(comp1.replayState());
    JsonNode replay1 = comp1.replayState().payload().get("output");

    // Round 2: 追加 ASSISTANT 1 及两个并行 TOOL
    String t1Text = "{\"name\":\"Alice\",\"status\":\"VIP\"}";
    String t2Text = "{\"orders\":[\"ord_999\"]}";
    ProviderMessage asstMsg1 =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderThinkingBlock(comp1.response().thinking()),
                new ProviderToolCallBlock(comp1.response().toolCalls().get(0)),
                new ProviderToolCallBlock(comp1.response().toolCalls().get(1))),
            comp1.replayState());
    ProviderMessage toolMsg1 =
        new ProviderMessage(
            ProviderMessageRole.TOOL,
            List.of(
                new ProviderToolResultBlock(
                    "call_round1_1",
                    "lookup_user",
                    List.of(new ProviderTextBlock(t1Text)),
                    false,
                    null)));
    ProviderMessage toolMsg2 =
        new ProviderMessage(
            ProviderMessageRole.TOOL,
            List.of(
                new ProviderToolResultBlock(
                    "call_round1_2",
                    "fetch_orders",
                    List.of(new ProviderTextBlock(t2Text)),
                    false,
                    null)));
    ProviderRequest req2 =
        new ProviderRequest(
            model,
            variant,
            1024,
            sys,
            List.of(userMsg, asstMsg1, toolMsg1, toolMsg2),
            tools,
            cacheControl);
    ProviderCompletion comp2 = streamAndAwait(provider, req2);
    assertEquals("Round 2 reasoning summary", comp2.response().thinking());
    assertEquals(1, comp2.response().toolCalls().size());
    assertNotNull(comp2.replayState());
    JsonNode replay2 = comp2.replayState().payload().get("output");

    // Round 3: 追加 ASSISTANT 2 及 TOOL 3
    String t3Text = "{\"discount_percent\":15}";
    ProviderMessage asstMsg2 =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderThinkingBlock(comp2.response().thinking()),
                new ProviderToolCallBlock(comp2.response().toolCalls().get(0))),
            comp2.replayState());
    ProviderMessage toolMsg3 =
        new ProviderMessage(
            ProviderMessageRole.TOOL,
            List.of(
                new ProviderToolResultBlock(
                    "call_round2_1",
                    "calculate_discount",
                    List.of(new ProviderTextBlock(t3Text)),
                    false,
                    null)));
    ProviderRequest req3 =
        new ProviderRequest(
            model,
            variant,
            1024,
            sys,
            List.of(userMsg, asstMsg1, toolMsg1, toolMsg2, asstMsg2, toolMsg3),
            tools,
            cacheControl);
    ProviderCompletion comp3 = streamAndAwait(provider, req3);
    assertEquals("The discount is applied successfully.", comp3.response().text());
    assertEquals(GenerationStopReason.COMPLETE, comp3.response().stopReason());
    assertNotNull(comp3.replayState());

    assertEquals(3, capturedRequests.size());
    assertNull(serverError.get(), "local HTTP server must complete all fixture responses");

    // 1. Root options 与 key 稳定
    for (JsonNode root : capturedRequests) {
      assertEquals("explicit", root.path("prompt_cache_options").path("mode").asText());
      assertEquals("30m", root.path("prompt_cache_options").path("ttl").asText());
      assertEquals(affinityKey, root.path("prompt_cache_key").asText());
      assertEquals(sys, root.path("instructions").asText());
      assertEquals("gpt-5.6", root.path("model").asText());
      assertTrue(root.path("stream").asBoolean());
      assertFalse(root.path("store").asBoolean());
      assertFalse(root.has("prompt_cache_retention"));
    }

    // 2. Request 1: USER 标
    JsonNode input1 = capturedRequests.get(0).get("input");
    assertEquals(1, input1.size());
    assertEquals(
        "explicit",
        input1.get(0).path("content").get(0).path("prompt_cache_breakpoint").path("mode").asText());

    // 3. Request 2: USER 和并行最后 tool 标且第一 tool 不标；native 原样全等
    JsonNode input2 = capturedRequests.get(1).get("input");
    assertEquals(6, input2.size());
    assertEquals(
        "explicit",
        input2.get(0).path("content").get(0).path("prompt_cache_breakpoint").path("mode").asText());
    assertEquals(replay1.get(0), input2.get(1));
    assertEquals(replay1.get(1), input2.get(2));
    assertEquals(replay1.get(2), input2.get(3));
    assertToolResultItem(input2.get(4), "call_round1_1", t1Text, false);
    assertToolResultItem(input2.get(5), "call_round1_2", t2Text, true);

    // 4. Request 3: 保留 USER/上一批最后 tool 并标当前 tool；native 原样全等
    JsonNode input3 = capturedRequests.get(2).get("input");
    assertEquals(9, input3.size());
    assertEquals(
        "explicit",
        input3.get(0).path("content").get(0).path("prompt_cache_breakpoint").path("mode").asText());
    assertEquals(replay1.get(0), input3.get(1));
    assertEquals(replay1.get(1), input3.get(2));
    assertEquals(replay1.get(2), input3.get(3));
    assertToolResultItem(input3.get(4), "call_round1_1", t1Text, false);
    assertToolResultItem(input3.get(5), "call_round1_2", t2Text, true);
    assertEquals(replay2.get(0), input3.get(6));
    assertEquals(replay2.get(1), input3.get(7));
    assertToolResultItem(input3.get(8), "call_round2_1", t3Text, true);

    // 5. 各 completion.sourcePrefixHash 与 default automatic encoder 一致
    OpenAiResponsesRequestEncoder autoEncoder = new OpenAiResponsesRequestEncoder();
    OpenAiResponsesConfig autoConfig = OpenAiResponsesConfig.defaultConfig();
    assertEquals(
        autoEncoder.encode(req1, descriptor, autoConfig).sourcePrefixHash(),
        comp1.replayState().sourcePrefixHash());
    assertEquals(
        autoEncoder.encode(req2, descriptor, autoConfig).sourcePrefixHash(),
        comp2.replayState().sourcePrefixHash());
    assertEquals(
        autoEncoder.encode(req3, descriptor, autoConfig).sourcePrefixHash(),
        comp3.replayState().sourcePrefixHash());
  }
}
