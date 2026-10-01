package fun.fengwk.kkstudio.harness.provider.openai.chat;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.provider.transport.HttpOpenMetadata;
import fun.fengwk.kkstudio.harness.provider.transport.HttpSseCallback;
import fun.fengwk.kkstudio.harness.provider.transport.HttpSseLimits;
import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.provider.transport.ServerSentEvent;
import fun.fengwk.kkstudio.harness.provider.transport.TransportException;
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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
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
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 测试意图：端到端集成测试，验证本地 HTTP 服务下连续多步工具循环、HTTP 异常分类映射、流终态封闭与回调崩溃的单次终态。 */
class OpenAiChatModelProviderIntegrationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

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

  /**
   * 测试意图：real transport 下 finish_reason 后的语义帧必须 fail closed——handler 恰好收到一次 INVALID_RESPONSE
   * 终态错误，不产生 completion，也不因已收到 usage 而再发第二次终态。
   */
  @Test
  void rejectsSemanticFrameAfterFinishReasonWithExactlyOneTerminal() throws Exception {
    server.createContext(
        "/chat/completions",
        exchange -> {
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(
                ("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}\n\n"
                        + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\" polluted\"},\"finish_reason\":\"length\"}]}\n\n"
                        + "data: [DONE]\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();
          }
        });

    ModelProvider provider = new OpenAiChatProviderAdapter(transport, "sk-test").create(descriptor);
    AtomicInteger errors = new AtomicInteger(0);
    AtomicInteger completes = new AtomicInteger(0);
    AtomicReference<ProviderException> caught = new AtomicReference<>();
    CountDownLatch firstTerminal = new CountDownLatch(1);
    // 终态计数为 2：只允许一次终态，出现第二次即释放
    CountDownLatch duplicateTerminal = new CountDownLatch(2);

    provider.stream(
        simpleRequest(),
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {
            completes.incrementAndGet();
            firstTerminal.countDown();
            duplicateTerminal.countDown();
          }

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            errors.incrementAndGet();
            caught.set(error);
            firstTerminal.countDown();
            duplicateTerminal.countDown();
          }
        });

    assertTrue(firstTerminal.await(5, TimeUnit.SECONDS));
    assertFalse(duplicateTerminal.await(500, TimeUnit.MILLISECONDS));
    assertEquals(1, errors.get());
    assertEquals(0, completes.get());
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, caught.get().kind());
  }

  /**
   * 测试意图：provider 内部回调逃逸的非 ProviderException 由真实 transport 转换为 CALLBACK_FAILED，经 ErrorMapper 脱敏为
   * INVALID_RESPONSE 后 handler 恰好收到一次终态错误（不静默、不重复、无 completion）。用户 handler 自身抛错属于 0-onError
   * 契约，不在本例范围。
   */
  @Test
  void callbackCrashIsDeliveredAsExactlyOneTerminalError() throws Exception {
    server.createContext(
        "/chat/completions",
        exchange -> {
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();
          }
        });

    JdkHttpSseTransport crashingTransport =
        new JdkHttpSseTransport(httpClient, workerExecutor, scheduler) {
          @Override
          public ProviderStream stream(
              HttpRequest httpRequest,
              ModelCallTimeoutPolicy timeoutPolicy,
              HttpSseLimits limits,
              HttpSseCallback callback) {
            // 先让 provider 正常处理真实帧，再故意抛出非 ProviderException：真实 transport 的故障隔离会把它转成
            // CALLBACK_FAILED 终态，而不是让用户侧无终态挂死。
            HttpSseCallback crashingCallback =
                new HttpSseCallback() {
                  @Override
                  public void onOpen(HttpOpenMetadata metadata) {
                    callback.onOpen(metadata);
                  }

                  @Override
                  public void onEvent(ServerSentEvent event) {
                    callback.onEvent(event);
                    throw new IllegalStateException(
                        "deliberate non-ProviderException callback crash");
                  }

                  @Override
                  public void onComplete() {
                    callback.onComplete();
                  }

                  @Override
                  public void onFailure(TransportException error) {
                    callback.onFailure(error);
                  }
                };
            return super.stream(httpRequest, timeoutPolicy, limits, crashingCallback);
          }
        };
    ModelProvider provider =
        new OpenAiChatProviderAdapter(crashingTransport, "sk-test").create(descriptor);

    AtomicInteger errors = new AtomicInteger(0);
    AtomicInteger completes = new AtomicInteger(0);
    List<ProviderStreamEvent> deltas = new ArrayList<>();
    AtomicReference<ProviderException> caught = new AtomicReference<>();
    CountDownLatch firstTerminal = new CountDownLatch(1);
    // 终态计数为 2：只允许一次终态，出现第二次即释放
    CountDownLatch duplicateTerminal = new CountDownLatch(2);

    provider.stream(
        simpleRequest(),
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {
            deltas.add(event);
          }

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {
            completes.incrementAndGet();
            firstTerminal.countDown();
            duplicateTerminal.countDown();
          }

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            errors.incrementAndGet();
            caught.set(error);
            firstTerminal.countDown();
            duplicateTerminal.countDown();
          }
        });

    assertTrue(firstTerminal.await(5, TimeUnit.SECONDS));
    assertFalse(duplicateTerminal.await(500, TimeUnit.MILLISECONDS));
    // 崩溃前该帧已被 provider 正常交付，说明确实发生在回调内部而不是未开始流
    assertEquals(1, deltas.size());
    assertEquals("hi", ((ProviderStreamEvent.TextDelta) deltas.get(0)).text());
    assertEquals(1, errors.get());
    assertEquals(0, completes.get());
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, caught.get().kind());
    assertEquals("OpenAI invalid response", caught.get().getMessage());
    assertNull(caught.get().getCause());
  }

  /**
   * 测试意图：上游 should_cancel_streaming 与 cancelling-subscription-mid-stream 的协议级等价——客户端在首个事件回调里取消真实
   * HTTP 流后，取消返回即封口；服务端随后到达的帧与结束信号都不得再交付任何增量或终态。
   */
  @Test
  void clientCancellationMidStreamStopsDelivery() throws Exception {
    CountDownLatch clientCancelled = new CountDownLatch(1);
    CountDownLatch serverAttemptedLateFrames = new CountDownLatch(1);
    server.createContext(
        "/chat/completions",
        exchange -> {
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"first\"}}]}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();

            // 等服务端确认客户端已取消，再补发后续帧：这些帧必须被静默丢弃
            clientCancelled.await(5, TimeUnit.SECONDS);
            os.write(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"second\"},\"finish_reason\":\"stop\"}]}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
          } catch (Exception ignored) {
            // 取消导致连接提前断开属于预期行为
          } finally {
            serverAttemptedLateFrames.countDown();
          }
        });

    List<ProviderStreamEvent> deltas = new ArrayList<>();
    AtomicInteger terminals = new AtomicInteger();
    ProviderStream stream =
        new OpenAiChatProviderAdapter(transport, "sk-test")
            .create(descriptor).stream(
                simpleRequest(),
                new ProviderStreamHandler() {
                  @Override
                  public void onEvent(ProviderStreamEvent event, ProviderStream st) {
                    deltas.add(event);
                    st.cancel();
                    clientCancelled.countDown();
                  }

                  @Override
                  public void onComplete(ProviderCompletion completion, ProviderStream st) {
                    terminals.incrementAndGet();
                  }

                  @Override
                  public void onError(ProviderException error, ProviderStream st) {
                    terminals.incrementAndGet();
                  }
                });

    assertTrue(clientCancelled.await(5, TimeUnit.SECONDS));
    assertTrue(stream.isCancelled());
    assertTrue(
        serverAttemptedLateFrames.await(5, TimeUnit.SECONDS),
        "server must have attempted to deliver frames after cancel");
    assertEquals(1, deltas.size());
    assertEquals("first", ((ProviderStreamEvent.TextDelta) deltas.get(0)).text());
    assertEquals(0, terminals.get());
  }

  private ProviderRequest simpleRequest() {
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

  /**
   * 测试意图：捕获三轮本地 HTTP 工具续跑请求，验证显式缓存端点保留、并行工具批末端及文本 parts。 原生 reasoning_content
   * 与未知字段必须回放，tool-call-only assistant 不制造 content。
   */
  @Test
  @DisplayName("离线 HTTP：显式缓存三轮工具续跑")
  void testOfflineHttpExplicitPromptCacheMultiTurnCycle() throws Exception {
    List<JsonNode> capturedRequests = Collections.synchronizedList(new ArrayList<>());
    AtomicReference<Exception> serverError = new AtomicReference<>();
    CountDownLatch serverFinished = new CountDownLatch(3);
    List<byte[]> responses =
        List.of(
            loadFixtureBytes("explicit-cache-multiturn-turn1.sse"),
            loadFixtureBytes("explicit-cache-multiturn-turn2.sse"),
            loadFixtureBytes("explicit-cache-multiturn-turn3.sse"));
    server.createContext(
        "/chat/completions",
        exchange -> {
          try (exchange) {
            capturedRequests.add(MAPPER.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] response = responses.get(capturedRequests.size() - 1);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
              os.write(response);
              os.flush();
            }
          } catch (Exception e) {
            serverError.compareAndSet(null, e);
          } finally {
            serverFinished.countDown();
          }
        });

    OpenAiChatConfiguration explicitConfig =
        new OpenAiChatConfiguration(
            true, true, OpenAiChatConfiguration.PromptCacheMode.GPT_5_6_EXPLICIT);
    ModelProvider provider =
        new OpenAiChatProviderAdapter(transport, "sk-test", explicitConfig).create(descriptor);

    List<ProviderToolDefinition> tools =
        List.of(
            new ProviderToolDefinition("file_search", "Search files", "{\"type\":\"object\"}"),
            new ProviderToolDefinition("get_weather", "Get weather", "{\"type\":\"object\"}"),
            new ProviderToolDefinition("read_file", "Read file", "{\"type\":\"object\"}"));
    ProviderCacheControl cacheControl =
        ProviderCacheControl.breakpoints(
            PromptCacheRetention.SHORT,
            "test-explicit-key",
            EnumSet.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.CONVERSATION));

    // Turn 1: 初始用户提问
    ProviderMessage userMsg =
        new ProviderMessage(
            ProviderMessageRole.USER,
            List.of(new ProviderTextBlock("Find files and get weather in Tokyo")));
    ProviderRequest req1 =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(userMsg),
            tools,
            cacheControl);
    ProviderCompletion comp1 = executeStream(provider, req1);
    assertEquals(2, comp1.response().toolCalls().size());
    assertEquals(
        "Thinking: file_search and get_weather concurrently.", comp1.response().thinking());
    assertEquals("", comp1.response().text());
    ProviderToolCall callSearch = comp1.response().toolCalls().get(0);
    ProviderToolCall callWeather = comp1.response().toolCalls().get(1);

    // Turn 2: 并行两工具结果续跑
    ProviderMessage asst1 =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderThinkingBlock("Thinking: file_search and get_weather concurrently."),
                new ProviderToolCallBlock(callSearch),
                new ProviderToolCallBlock(callWeather)),
            comp1.replayState());
    ProviderMessage tool1 =
        new ProviderMessage(
            ProviderMessageRole.TOOL,
            List.of(
                new ProviderToolResultBlock(
                    callSearch.id(),
                    callSearch.name(),
                    List.of(new ProviderTextBlock("file report.txt found")),
                    false,
                    "{}")));
    ProviderMessage tool2 =
        new ProviderMessage(
            ProviderMessageRole.TOOL,
            List.of(
                new ProviderToolResultBlock(
                    callWeather.id(),
                    callWeather.name(),
                    List.of(new ProviderTextBlock("Tokyo 22C Sunny")),
                    false,
                    "{}")));
    ProviderRequest req2 =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(userMsg, asst1, tool1, tool2),
            tools,
            cacheControl);
    ProviderCompletion comp2 = executeStream(provider, req2);
    assertEquals(1, comp2.response().toolCalls().size());
    assertEquals("Thinking: read report content.", comp2.response().thinking());
    ProviderToolCall callRead = comp2.response().toolCalls().get(0);

    // Turn 3: 再次单工具结果续跑至文本输出
    ProviderMessage asst2 =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderThinkingBlock("Thinking: read report content."),
                new ProviderToolCallBlock(callRead)),
            comp2.replayState());
    ProviderMessage tool3 =
        new ProviderMessage(
            ProviderMessageRole.TOOL,
            List.of(
                new ProviderToolResultBlock(
                    callRead.id(),
                    callRead.name(),
                    List.of(new ProviderTextBlock("Report: System OK")),
                    false,
                    "{}")));
    ProviderRequest req3 =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(userMsg, asst1, tool1, tool2, asst2, tool3),
            tools,
            cacheControl);
    ProviderCompletion comp3 = executeStream(provider, req3);
    assertEquals("Tokyo is sunny and the report is all clear.", comp3.response().text());
    assertEquals(
        "Thinking: all info gathered, synthesizing final answer.", comp3.response().thinking());
    assertEquals(GenerationStopReason.COMPLETE, comp3.response().stopReason());

    // 捕获请求 Wire JSON 校验
    assertTrue(serverFinished.await(5, TimeUnit.SECONDS), "server handlers must finish");
    assertNull(serverError.get(), "server handlers must not fail");
    assertEquals(3, capturedRequests.size());
    JsonNode wireReq1 = capturedRequests.get(0);
    assertExplicitRootOptions(wireReq1, "test-explicit-key");
    assertEquals(2, wireReq1.path("messages").size());
    assertTextContent(wireReq1.path("messages").get(0), "Test system instruction.", true);
    assertTextContent(
        wireReq1.path("messages").get(1), "Find files and get weather in Tokyo", true);

    JsonNode wireReq2 = capturedRequests.get(1);
    assertExplicitRootOptions(wireReq2, "test-explicit-key");
    assertEquals(5, wireReq2.path("messages").size());
    assertTextContent(wireReq2.path("messages").get(0), "Test system instruction.", true);
    assertTextContent(
        wireReq2.path("messages").get(1), "Find files and get weather in Tokyo", true);
    assertWireAssistant(
        wireReq2.path("messages").get(2),
        "Thinking: file_search and get_weather concurrently.",
        "custom_v1",
        2);
    assertTextContent(wireReq2.path("messages").get(3), "file report.txt found", false);
    assertTextContent(wireReq2.path("messages").get(4), "Tokyo 22C Sunny", true);

    JsonNode wireReq3 = capturedRequests.get(2);
    assertExplicitRootOptions(wireReq3, "test-explicit-key");
    assertEquals(7, wireReq3.path("messages").size());
    assertTextContent(wireReq3.path("messages").get(0), "Test system instruction.", true);
    assertTextContent(
        wireReq3.path("messages").get(1), "Find files and get weather in Tokyo", true);
    assertWireAssistant(
        wireReq3.path("messages").get(2),
        "Thinking: file_search and get_weather concurrently.",
        "custom_v1",
        2);
    assertTextContent(wireReq3.path("messages").get(3), "file report.txt found", false);
    assertTextContent(wireReq3.path("messages").get(4), "Tokyo 22C Sunny", true);
    assertWireAssistant(
        wireReq3.path("messages").get(5), "Thinking: read report content.", "custom_v2", 1);
    assertTextContent(wireReq3.path("messages").get(6), "Report: System OK", true);
  }

  private static void assertExplicitRootOptions(JsonNode req, String expectedKey) {
    assertEquals("explicit", req.path("prompt_cache_options").path("mode").asText());
    assertEquals("30m", req.path("prompt_cache_options").path("ttl").asText());
    assertEquals(expectedKey, req.path("prompt_cache_key").asText());
  }

  private static void assertTextContent(JsonNode message, String expectedText, boolean breakpoint) {
    assertTrue(message.path("content").isArray(), "content must be parts array");
    JsonNode part = message.path("content").get(0);
    assertEquals("text", part.path("type").asText());
    assertEquals(expectedText, part.path("text").asText());
    if (breakpoint) {
      assertEquals(1, part.path("prompt_cache_breakpoint").size());
      assertEquals("explicit", part.path("prompt_cache_breakpoint").path("mode").asText());
    } else {
      assertFalse(part.has("prompt_cache_breakpoint"), "breakpoint must not be present");
    }
  }

  private static void assertWireAssistant(
      JsonNode message, String thinking, String customTag, int toolCallsCount) {
    assertEquals("assistant", message.path("role").asText());
    assertFalse(
        message.has("content"), "assistant without text content must not fabricate content");
    assertEquals(thinking, message.path("reasoning_content").asText());
    assertEquals(customTag, message.path("vendor_custom_tag").asText());
    assertEquals(toolCallsCount, message.path("tool_calls").size());
  }

  private static byte[] loadFixtureBytes(String name) throws IOException {
    try (InputStream is =
        OpenAiChatModelProviderIntegrationTest.class.getResourceAsStream("fixtures/" + name)) {
      if (is == null) {
        throw new IllegalArgumentException("resource not found: " + name);
      }
      return is.readAllBytes();
    }
  }

  private ProviderCompletion executeStream(ModelProvider provider, ProviderRequest request)
      throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<ProviderCompletion> comp = new AtomicReference<>();
    AtomicReference<ProviderException> err = new AtomicReference<>();
    provider.stream(
        request,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {
            comp.set(completion);
            latch.countDown();
          }

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            err.set(error);
            latch.countDown();
          }
        });
    assertTrue(latch.await(5, TimeUnit.SECONDS), "stream must complete within timeout");
    if (err.get() != null) {
      throw err.get();
    }
    assertNotNull(comp.get(), "completion must not be null");
    return comp.get();
  }
}
