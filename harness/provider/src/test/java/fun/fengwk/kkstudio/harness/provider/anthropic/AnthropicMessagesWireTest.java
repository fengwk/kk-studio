package fun.fengwk.kkstudio.harness.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDocumentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderImageBlock;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Anthropic Messages 协议与流式传输线缆级（wire）行为综合验证套件。
 *
 * <p>本测试覆盖上游 LangChain4j 1.20.0 规范中针对 Anthropic Messages HTTP/SSE 线缆契约、
 * 消息结构编排、模型标识与输出预算透传、推理参数、多模态媒体、工具往返交互、提示缓存断点、 流式生命周期状态机与回调异常防护等所有可表达的原生协议行为。
 */
class AnthropicMessagesWireTest {

  private static final String TEST_API_KEY = "sk-ant-test-wire-key-98765";
  private static final String BASE64_PNG =
      "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
  private static final String BASE64_JPEG =
      "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////wgALCAABAAEBAREA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA=";
  private static final String BASE64_PDF =
      "data:application/pdf;base64,JVBERi0xLjQgZHVtbXkgcGRmIGNvbnRlbnQ=";

  private HttpServer server;
  private int port;
  private ExecutorService workerExecutor;
  private ScheduledExecutorService scheduler;
  private HttpClient client;
  private JdkHttpSseTransport transport;
  private AnthropicProviderAdapter adapter;
  private final ObjectMapper mapper = new ObjectMapper();
  private final AnthropicRequestEncoder encoder = new AnthropicRequestEncoder();

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
    adapter = new AnthropicProviderAdapter(transport, TEST_API_KEY);
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

  // ==========================================
  // Group 1: Wire HTTP Headers & Request Body Format
  // ==========================================

  /**
   * 验证 HTTP POST 到 /messages 的端点路径、Content-Type/Accept 头、anthropic-version、双认证头、beta 头缺席及 body 中
   * stream=true。
   */
  @Test
  void should_send_correct_native_streaming_http_request() throws Exception {
    AtomicReference<RecordedRequest> recorded = new AtomicReference<>();
    CountDownLatch serverLatch = new CountDownLatch(1);

    server.createContext(
        "/v1/messages",
        exchange -> {
          recorded.set(recordExchange(exchange));
          serverLatch.countDown();
          respondSseText(exchange, "msg_req_1", "OK");
        });

    ProviderDescriptor descriptor = createDescriptor(null);
    AnthropicModelProvider provider = (AnthropicModelProvider) adapter.create(descriptor);
    ProviderRequest request =
        createRequest("claude-3-5-sonnet", null, List.of(userTextMsg("ping")), null, null);

    RecordingStreamHandler handler = new RecordingStreamHandler();
    provider.stream(request, handler);

    assertTrue(serverLatch.await(5, TimeUnit.SECONDS), "mock server should receive HTTP request");
    assertTrue(handler.await(5, TimeUnit.SECONDS), "streaming call should complete");

    RecordedRequest req = recorded.get();
    assertNotNull(req);
    assertEquals("POST", req.method);
    assertEquals("/v1/messages", req.path);
    assertEquals("application/json", req.firstHeader("Content-Type"));
    assertEquals("text/event-stream", req.firstHeader("Accept"));
    assertEquals("2023-06-01", req.firstHeader("anthropic-version"));
    assertEquals(TEST_API_KEY, req.firstHeader("x-api-key"));
    assertEquals("Bearer " + TEST_API_KEY, req.firstHeader("Authorization"));
    assertNull(
        req.firstHeader("anthropic-beta"), "standard native path must omit anthropic-beta header");

    JsonNode bodyJson = mapper.readTree(req.bodyBytes);
    assertTrue(bodyJson.path("stream").asBoolean(), "stream field must be true");
    assertEquals("claude-3-5-sonnet", bodyJson.path("model").asText());
  }

  /** 验证通过真实端点流式调用完成完整的消息创建与文本聚合流程。 */
  @Test
  void should_stream_create_message_and_aggregate_response() throws Exception {
    server.createContext(
        "/v1/messages", exchange -> respondSseText(exchange, "msg_create_1", "Hello, world!"));

    ProviderDescriptor descriptor = createDescriptor(null);
    AnthropicModelProvider provider = (AnthropicModelProvider) adapter.create(descriptor);
    ProviderRequest request =
        createRequest("claude-3-5-sonnet", null, List.of(userTextMsg("hi")), null, null);

    RecordingStreamHandler handler = new RecordingStreamHandler();
    provider.stream(request, handler);

    assertTrue(handler.await(5, TimeUnit.SECONDS), "stream should finish");
    assertNotNull(handler.completion.get());
    assertEquals("Hello, world!", handler.completion.get().response().text());
    assertEquals(GenerationStopReason.COMPLETE, handler.completion.get().response().stopReason());
  }

  /** 验证当没有提供 API Key 时适配器发送请求时不包含认证请求头，且服务端鉴权失败时正确映射为 AUTHENTICATION 错误。 */
  @Test
  void should_omit_auth_headers_and_map_server_authentication_failure_when_credential_absent()
      throws Exception {
    AtomicReference<RecordedRequest> recorded = new AtomicReference<>();
    server.createContext(
        "/v1/messages",
        exchange -> {
          recorded.set(recordExchange(exchange));
          byte[] errBytes =
              "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"missing api key\"}}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(401, errBytes.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(errBytes);
          }
        });

    AnthropicProviderAdapter unauthenticatedAdapter = new AnthropicProviderAdapter(transport, null);
    ProviderDescriptor descriptor = createDescriptor(null);
    AnthropicModelProvider provider =
        (AnthropicModelProvider) unauthenticatedAdapter.create(descriptor);
    ProviderRequest request =
        createRequest("claude-3-5-sonnet", null, List.of(userTextMsg("hi")), null, null);

    RecordingStreamHandler handler = new RecordingStreamHandler();
    provider.stream(request, handler);

    assertTrue(handler.await(5, TimeUnit.SECONDS), "handler should receive error");
    RecordedRequest req = recorded.get();
    assertNotNull(req);
    assertNull(
        req.firstHeader("x-api-key"), "request must not send x-api-key when unauthenticated");
    assertNull(
        req.firstHeader("Authorization"),
        "request must not send Authorization header when unauthenticated");
    assertNotNull(handler.error.get());
    assertEquals(ProviderErrorKind.AUTHENTICATION, handler.error.get().kind());
  }

  /** 验证在正常原生请求体中不序列化诊断字段 diagnostics。 */
  @Test
  void should_omit_diagnostics_from_native_streaming_request() throws Exception {
    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request =
        createRequest("claude-3-5-sonnet", null, List.of(userTextMsg("test")), null, null);

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = mapper.readTree(encoded.bodyUtf8Bytes());
    assertFalse(root.has("diagnostics"), "request body must omit diagnostics field");
  }

  /** 验证原生请求体中不序列化 SDK 层扩展字段 custom_parameters。 */
  @Test
  void should_not_serialize_sdk_custom_parameters_wrapper() throws Exception {
    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request =
        createRequest("claude-3-5-sonnet", null, List.of(userTextMsg("test")), null, null);

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = mapper.readTree(encoded.bodyUtf8Bytes());
    assertFalse(root.has("custom_parameters"), "request body must not contain custom_parameters");
  }

  /** 验证在正常原生传输路径上不会附加 anthropic-beta 请求头。 */
  @Test
  void should_not_send_anthropic_beta_header() throws Exception {
    AtomicReference<RecordedRequest> recorded = new AtomicReference<>();
    CountDownLatch serverLatch = new CountDownLatch(1);

    server.createContext(
        "/v1/messages",
        exchange -> {
          recorded.set(recordExchange(exchange));
          serverLatch.countDown();
          respondSseText(exchange, "msg_beta_1", "ok");
        });

    ProviderDescriptor descriptor = createDescriptor(null);
    AnthropicModelProvider provider = (AnthropicModelProvider) adapter.create(descriptor);
    ProviderRequest request =
        createRequest("claude-3-5-sonnet", null, List.of(userTextMsg("test")), null, null);

    RecordingStreamHandler handler = new RecordingStreamHandler();
    provider.stream(request, handler);

    assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
    RecordedRequest req = recorded.get();
    assertNotNull(req);
    assertNull(req.firstHeader("anthropic-beta"), "native wire path does not invent beta headers");
  }

  /**
   * 验证在 BUDGET 思考模式且启用推理时，线缆请求发送 anthropic-beta: interleaved-thinking-2025-05-14，且 body 中 thinking
   * 包含 display=summarized。
   */
  @Test
  void should_send_interleaved_thinking_beta_header_when_budget_thinking_enabled()
      throws Exception {
    AtomicReference<RecordedRequest> recorded = new AtomicReference<>();
    CountDownLatch serverLatch = new CountDownLatch(1);

    server.createContext(
        "/v1/messages",
        exchange -> {
          recorded.set(recordExchange(exchange));
          serverLatch.countDown();
          respondSseText(exchange, "msg_budget_beta_1", "ok");
        });

    AnthropicProviderAdapter budgetAdapter =
        new AnthropicProviderAdapter(
            transport, TEST_API_KEY, new AnthropicConfiguration(AnthropicThinkingMode.BUDGET));
    ProviderDescriptor descriptor = createDescriptor(null);
    AnthropicModelProvider provider = (AnthropicModelProvider) budgetAdapter.create(descriptor);

    ModelDescriptor reasoningModel = createReasoningModelDescriptor("MiniMax-M3");
    ModelVariant variant = new ModelVariant("budget-v", "low");
    ProviderRequest request =
        new ProviderRequest(
            reasoningModel,
            variant,
            4096,
            "Test system instruction.",
            List.of(userTextMsg("test")),
            List.of(),
            ProviderCacheControl.none());

    RecordingStreamHandler handler = new RecordingStreamHandler();
    provider.stream(request, handler);

    assertTrue(serverLatch.await(5, TimeUnit.SECONDS), "server should receive request");
    RecordedRequest req = recorded.get();
    assertNotNull(req);
    assertEquals(
        "interleaved-thinking-2025-05-14",
        req.firstHeader("anthropic-beta"),
        "BUDGET mode with reasoning enabled must send interleaved thinking beta header");

    JsonNode bodyJson = mapper.readTree(req.bodyBytes);
    assertEquals("enabled", bodyJson.path("thinking").path("type").asText());
    assertEquals(2048, bodyJson.path("thinking").path("budget_tokens").asInt());
    assertEquals("summarized", bodyJson.path("thinking").path("display").asText());
    assertFalse(bodyJson.has("output_config"), "BUDGET mode must omit output_config");
  }

  /**
   * 验证在 ADAPTIVE 思考模式下即使启用推理，线缆请求也不发送 anthropic-beta，且 body 中 thinking 包含 display=summarized 与
   * output_config.effort。
   */
  @Test
  void should_not_send_anthropic_beta_header_when_adaptive_thinking_enabled() throws Exception {
    AtomicReference<RecordedRequest> recorded = new AtomicReference<>();
    CountDownLatch serverLatch = new CountDownLatch(1);

    server.createContext(
        "/v1/messages",
        exchange -> {
          recorded.set(recordExchange(exchange));
          serverLatch.countDown();
          respondSseText(exchange, "msg_adaptive_nobeta_1", "ok");
        });

    ProviderDescriptor descriptor = createDescriptor(null);
    AnthropicModelProvider provider = (AnthropicModelProvider) adapter.create(descriptor);

    ModelDescriptor reasoningModel = createReasoningModelDescriptor("claude-3-7-sonnet");
    ModelVariant variant = new ModelVariant("adaptive-v", "low");
    ProviderRequest request =
        new ProviderRequest(
            reasoningModel,
            variant,
            1024,
            "Test system instruction.",
            List.of(userTextMsg("test")),
            List.of(),
            ProviderCacheControl.none());

    RecordingStreamHandler handler = new RecordingStreamHandler();
    provider.stream(request, handler);

    assertTrue(serverLatch.await(5, TimeUnit.SECONDS), "server should receive request");
    RecordedRequest req = recorded.get();
    assertNotNull(req);
    assertNull(
        req.firstHeader("anthropic-beta"),
        "ADAPTIVE mode must omit anthropic-beta header even when reasoning is enabled");

    JsonNode bodyJson = mapper.readTree(req.bodyBytes);
    assertEquals("adaptive", bodyJson.path("thinking").path("type").asText());
    assertEquals("summarized", bodyJson.path("thinking").path("display").asText());
    assertEquals("low", bodyJson.path("output_config").path("effort").asText());
  }

  /** 验证配置的 beta 能力在单个 anthropic-beta 头内按声明顺序以逗号连接发送。 */
  @Test
  void should_send_configured_beta_features_in_one_ordered_header() throws Exception {
    AtomicReference<RecordedRequest> recorded = new AtomicReference<>();
    CountDownLatch serverLatch = new CountDownLatch(1);
    server.createContext(
        "/v1/messages",
        exchange -> {
          recorded.set(recordExchange(exchange));
          serverLatch.countDown();
          respondSseText(exchange, "msg_configured_beta_1", "ok");
        });

    AnthropicProviderAdapter configuredAdapter =
        new AnthropicProviderAdapter(
            transport,
            TEST_API_KEY,
            "{\"anthropicBetaFeatures\":[\"prompt-caching-2024-07-31\",\"context-1m-2025-08-07\"]}");
    ProviderDescriptor descriptor = createDescriptor(null);
    AnthropicModelProvider provider = (AnthropicModelProvider) configuredAdapter.create(descriptor);

    AnthropicEncodedRequest encoded =
        new AnthropicRequestEncoder(configuredAdapter.configuration())
            .encode(
                createRequest("claude-3-5-sonnet", null, List.of(userTextMsg("test")), null, null),
                descriptor);
    assertEquals(
        List.of("prompt-caching-2024-07-31", "context-1m-2025-08-07"), encoded.betaFeatures());

    RecordingStreamHandler handler = new RecordingStreamHandler();
    provider.stream(
        createRequest("claude-3-5-sonnet", null, List.of(userTextMsg("test")), null, null),
        handler);

    assertTrue(serverLatch.await(5, TimeUnit.SECONDS), "server should receive request");
    RecordedRequest req = recorded.get();
    assertNotNull(req);
    assertEquals(
        "prompt-caching-2024-07-31,context-1m-2025-08-07", req.firstHeader("anthropic-beta"));
  }

  /**
   * 验证配置能力与运行时强制的 interleaved thinking 合并为单个 anthropic-beta 头：配置顺序在前，强制能力追加在后且与已配置值 去重，不产生重复头字段。
   */
  @Test
  void should_send_configured_and_implicit_beta_features_deduped_in_one_header() throws Exception {
    AtomicReference<RecordedRequest> recorded = new AtomicReference<>();
    CountDownLatch serverLatch = new CountDownLatch(1);
    server.createContext(
        "/v1/messages",
        exchange -> {
          recorded.set(recordExchange(exchange));
          serverLatch.countDown();
          respondSseText(exchange, "msg_union_beta_1", "ok");
        });

    AnthropicProviderAdapter budgetAdapter =
        new AnthropicProviderAdapter(
            transport,
            TEST_API_KEY,
            "{\"anthropicThinkingMode\":\"BUDGET\",\"anthropicBetaFeatures\":[\"interleaved-thinking-2025-05-14\",\"prompt-caching-2024-07-31\"]}");
    ProviderDescriptor descriptor = createDescriptor(null);
    AnthropicModelProvider provider = (AnthropicModelProvider) budgetAdapter.create(descriptor);

    ProviderRequest request =
        new ProviderRequest(
            createReasoningModelDescriptor("MiniMax-M3"),
            new ModelVariant("budget-v", "low"),
            4096,
            "Test system instruction.",
            List.of(userTextMsg("test")),
            List.of(),
            ProviderCacheControl.none());

    RecordingStreamHandler handler = new RecordingStreamHandler();
    provider.stream(request, handler);

    assertTrue(serverLatch.await(5, TimeUnit.SECONDS), "server should receive request");
    RecordedRequest req = recorded.get();
    assertNotNull(req);
    assertEquals(
        "interleaved-thinking-2025-05-14,prompt-caching-2024-07-31",
        req.firstHeader("anthropic-beta"),
        "configured order wins and the runtime-required beta must not be duplicated");
    assertEquals(1, req.headers.get("anthropic-beta").size(), "exactly one anthropic-beta header");

    // 其余保护头照常发送
    assertEquals(TEST_API_KEY, req.firstHeader("x-api-key"));
    assertEquals("Bearer " + TEST_API_KEY, req.firstHeader("Authorization"));
    assertEquals("2023-06-01", req.firstHeader("anthropic-version"));
  }

  /**
   * 验证即使配置为 BUDGET 模式，当推理显式关闭（reasoningEffort 为 off）时，线缆请求不发送 anthropic-beta 且发送
   * thinking:{type:"disabled"}。
   */
  @Test
  void should_not_send_anthropic_beta_header_when_budget_mode_but_reasoning_disabled()
      throws Exception {
    AtomicReference<RecordedRequest> recorded = new AtomicReference<>();
    CountDownLatch serverLatch = new CountDownLatch(1);

    server.createContext(
        "/v1/messages",
        exchange -> {
          recorded.set(recordExchange(exchange));
          serverLatch.countDown();
          respondSseText(exchange, "msg_budget_noreason_1", "ok");
        });

    AnthropicProviderAdapter budgetAdapter =
        new AnthropicProviderAdapter(
            transport, TEST_API_KEY, new AnthropicConfiguration(AnthropicThinkingMode.BUDGET));
    ProviderDescriptor descriptor = createDescriptor(null);
    AnthropicModelProvider provider = (AnthropicModelProvider) budgetAdapter.create(descriptor);

    ModelDescriptor reasoningModel = createReasoningModelDescriptor("MiniMax-M3");
    ModelVariant variantNone = new ModelVariant("none-v", "off");
    ProviderRequest request =
        new ProviderRequest(
            reasoningModel,
            variantNone,
            1024,
            "Test system instruction.",
            List.of(userTextMsg("test")),
            List.of(),
            ProviderCacheControl.none());

    RecordingStreamHandler handler = new RecordingStreamHandler();
    provider.stream(request, handler);

    assertTrue(serverLatch.await(5, TimeUnit.SECONDS), "server should receive request");
    RecordedRequest req = recorded.get();
    assertNotNull(req);
    assertNull(
        req.firstHeader("anthropic-beta"),
        "BUDGET mode with reasoningEffort=off must omit anthropic-beta header");

    JsonNode bodyJson = mapper.readTree(req.bodyBytes);
    assertEquals(
        "disabled",
        bodyJson.path("thinking").path("type").asText(),
        "reasoningEffort=off must send thinking type disabled");
    assertFalse(bodyJson.has("output_config"), "reasoningEffort=off must omit output_config");
  }

  // ==========================================
  // Group 2: Model Name Pass-Through (8 upstream model name parameter cases)
  // ==========================================

  /** 验证上游全部 8 个预定义模型枚举名称（作为字符串）能够准确透传至线缆请求体中的 model 字段。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "claude-opus-4-8",
        "claude-opus-4-7",
        "claude-opus-4-6",
        "claude-sonnet-4-6",
        "claude-opus-4-5-20251101",
        "claude-sonnet-4-5-20250929",
        "claude-haiku-4-5-20251001",
        "claude-opus-4-1-20250805"
      })
  void should_support_all_enum_model_names(String modelName) throws Exception {
    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request =
        createRequest(modelName, null, List.of(userTextMsg("hi")), null, null);

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = mapper.readTree(encoded.bodyUtf8Bytes());
    assertEquals(
        modelName, root.path("model").asText(), "model field must match upstream enum string");
  }

  /** 验证任意字符串格式的模型名称能够无损透传至线缆请求体中。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "claude-opus-4-8",
        "claude-opus-4-7",
        "claude-opus-4-6",
        "claude-sonnet-4-6",
        "claude-opus-4-5-20251101",
        "claude-sonnet-4-5-20250929",
        "claude-haiku-4-5-20251001",
        "claude-opus-4-1-20250805"
      })
  void should_support_all_string_model_names(String modelName) throws Exception {
    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request =
        createRequest(modelName, null, List.of(userTextMsg("hi")), null, null);

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = mapper.readTree(encoded.bodyUtf8Bytes());
    assertEquals(
        modelName, root.path("model").asText(), "custom model string must pass through unmodified");
  }

  /** 验证请求中显式指定的模型名称正确写入线缆负载。 */
  @Test
  void should_respect_modelName_in_chat_request() throws Exception {
    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request =
        createRequest("claude-3-7-sonnet", null, List.of(userTextMsg("hi")), null, null);

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = mapper.readTree(encoded.bodyUtf8Bytes());
    assertEquals("claude-3-7-sonnet", root.path("model").asText());
  }

  /** 验证生效的 ModelDescriptor 模型名称正确序列化至线缆模型字段。 */
  @Test
  void should_encode_effective_model_name_from_descriptor() throws Exception {
    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request =
        createRequest("claude-3-haiku-20240307", null, List.of(userTextMsg("hi")), null, null);

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = mapper.readTree(encoded.bodyUtf8Bytes());
    assertEquals("claude-3-haiku-20240307", root.path("model").asText());
  }

  // ==========================================
  // Group 3: Output Budget & Protocol Defaults
  // ==========================================

  /** 验证请求 outputTokens 正确映射至原生 max_tokens 字段。 */
  @Test
  void encodesRequestOutputBudget() throws Exception {
    ModelVariant variant = new ModelVariant("output-budget");
    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request =
        createRequest("claude-3-5-sonnet", variant, 2048, List.of(userTextMsg("hi")), null, null);

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = mapper.readTree(encoded.bodyUtf8Bytes());

    assertEquals(2048, root.path("max_tokens").asInt());
  }

  /** 验证原生请求没有 stop-sequence 覆盖时省略 stop_sequences 字段。 */
  @Test
  void omitsStopSequencesWithoutNativeOverride() throws Exception {
    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request =
        createRequest("claude-3-5-sonnet", null, List.of(userTextMsg("hi")), null, null);

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = mapper.readTree(encoded.bodyUtf8Bytes());
    assertFalse(root.has("stop_sequences"), "stop_sequences field must be omitted when empty");
  }

  /** 验证请求中的 outputTokens 能够写入原生 max_tokens。 */
  @Test
  void should_respect_maxOutputTokens_in_chat_request() throws Exception {
    ModelVariant variant = new ModelVariant("v");
    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request =
        createRequest("claude-3-5-sonnet", variant, 4096, List.of(userTextMsg("hi")), null, null);

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = mapper.readTree(encoded.bodyUtf8Bytes());
    assertEquals(4096, root.path("max_tokens").asInt());
  }

  /** 验证测试构造器提供的默认请求 outputTokens 原样写入原生线缆。 */
  @Test
  void should_encode_default_request_output_tokens() throws Exception {
    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request =
        createRequest("claude-3-5-sonnet", null, List.of(userTextMsg("hi")), null, null);

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = mapper.readTree(encoded.bodyUtf8Bytes());
    assertEquals(1024, root.path("max_tokens").asInt(), "default max_tokens must be 1024");
  }

  /** 验证没有可选覆盖项时请求仍可编码，并保持原生协议默认。 */
  @Test
  void encodesRequestWithoutOptionalOverrides() throws Exception {
    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request =
        createRequest("claude-3-5-sonnet", null, List.of(userTextMsg("hi")), null, null);

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = mapper.readTree(encoded.bodyUtf8Bytes());
    assertFalse(root.has("temperature"));
    assertFalse(root.has("top_p"));
    assertFalse(root.has("top_k"));
    assertFalse(root.has("stop_sequences"));
  }

  /** 验证生效的请求 outputTokens 正确序列化至线缆请求负载中的 max_tokens。 */
  @Test
  void should_encode_effective_request_parameters() throws Exception {
    ModelVariant variant = new ModelVariant("override");
    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request =
        createRequest("claude-3-5-sonnet", variant, 512, List.of(userTextMsg("hi")), null, null);

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = mapper.readTree(encoded.bodyUtf8Bytes());

    assertEquals(512, root.path("max_tokens").asInt());
    assertFalse(root.has("temperature"));
    assertFalse(root.has("top_p"));
    assertFalse(root.has("top_k"));
    assertFalse(root.has("stop_sequences"));
  }

  /**
   * 验证在 kk-studio 中通过 ModelDescriptor.reasoning=true 与 ModelVariant.reasoningEffort 原生映射为 thinking
   * 与 output_config.effort。
   */
  @Test
  void should_encode_typed_reasoning_effort_as_adaptive_thinking() throws Exception {
    ModelDescriptor reasoningModel =
        new ModelDescriptor(
            "anthropic-reasoning",
            "claude-3-7-sonnet",
            "claude-3-7-sonnet",
            Set.of(ModelInputModality.TEXT),
            true,
            true,
            defaultPricing());
    ModelVariant variant = new ModelVariant("reasoning-v", "low");
    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request =
        new ProviderRequest(
            reasoningModel,
            variant,
            1024,
            "Test system instruction.",
            List.of(userTextMsg("solve this")),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = mapper.readTree(encoded.bodyUtf8Bytes());

    assertEquals("adaptive", root.path("thinking").path("type").asText());
    assertEquals("summarized", root.path("thinking").path("display").asText());
    assertEquals("low", root.path("output_config").path("effort").asText());
    assertEquals(List.of(), encoded.betaFeatures());
  }

  /** 验证通用请求参数中的 outputTokens 能够正常透传至线缆。 */
  @Test
  void should_respect_common_parameters_wrapped_in_integration_specific_class_in_chat_request()
      throws Exception {
    ModelVariant variant = new ModelVariant("common");
    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request =
        createRequest("claude-3-5-sonnet", variant, 1500, List.of(userTextMsg("hi")), null, null);

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = mapper.readTree(encoded.bodyUtf8Bytes());

    assertEquals(1500, root.path("max_tokens").asInt());
  }

  /** 验证默认变体仍使用请求级输出预算填充原生必需字段。 */
  @Test
  void should_use_native_defaults_when_common_variant_fields_absent() throws Exception {
    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request =
        createRequest("claude-3-5-sonnet", null, List.of(userTextMsg("hi")), null, null);

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = mapper.readTree(encoded.bodyUtf8Bytes());
    assertEquals(1024, root.path("max_tokens").asInt());
  }

  // ==========================================
  // Group 4: Message History Ordering & Structure
  // ==========================================

  /** 验证单个用户文本消息在线缆 messages 中编码为 role 为 user 的 content 数组。 */
  @Test
  void should_respect_user_message() throws Exception {
    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request =
        createRequest("claude-3-5-sonnet", null, List.of(userTextMsg("Hello Claude")), null, null);

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = mapper.readTree(encoded.bodyUtf8Bytes());

    JsonNode messages = root.path("messages");
    assertEquals(1, messages.size());
    assertEquals("user", messages.get(0).path("role").asText());
    assertEquals("text", messages.get(0).path("content").get(0).path("type").asText());
    assertEquals("Hello Claude", messages.get(0).path("content").get(0).path("text").asText());
  }

  /** 验证请求唯一的系统指令编码为顶级 system 数组中的单条 text 块，且不进入 messages。 */
  @Test
  void should_respect_system_message() throws Exception {
    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request =
        requestWithInstruction(
            "You are a helpful assistant.", List.of(userTextMsg("hi")), null, null);

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = mapper.readTree(encoded.bodyUtf8Bytes());

    JsonNode system = root.path("system");
    assertEquals(1, system.size());
    assertEquals("text", system.get(0).path("type").asText());
    assertEquals("You are a helpful assistant.", system.get(0).path("text").asText());

    JsonNode messages = root.path("messages");
    assertEquals(1, messages.size());
    assertEquals("user", messages.get(0).path("role").asText());
  }

  /** 验证多轮有序历史消息（USER -> ASSISTANT -> USER）在线缆上保持严格顺序，系统指令始终是顶层单块。 */
  @Test
  void should_respect_multiple_messages() throws Exception {
    ProviderMessage u1 = userTextMsg("User 1");
    ProviderMessage a1 =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT, List.of(new ProviderTextBlock("Assistant 1")));
    ProviderMessage u2 = userTextMsg("User 2");

    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request = requestWithInstruction("Sys", List.of(u1, a1, u2), null, null);

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = mapper.readTree(encoded.bodyUtf8Bytes());

    assertEquals(1, root.path("system").size());
    assertEquals("Sys", root.path("system").get(0).path("text").asText());

    JsonNode messages = root.path("messages");
    assertEquals(3, messages.size());
    assertEquals("user", messages.get(0).path("role").asText());
    assertEquals("User 1", messages.get(0).path("content").get(0).path("text").asText());
    assertEquals("assistant", messages.get(1).path("role").asText());
    assertEquals("Assistant 1", messages.get(1).path("content").get(0).path("text").asText());
    assertEquals("user", messages.get(2).path("role").asText());
    assertEquals("User 2", messages.get(2).path("content").get(0).path("text").asText());
  }

  /** 验证允许非 user 消息（如 ASSISTANT）作为第一条对话消息，并支持连续多条 USER 消息在线缆上传输。 */
  @Test
  void should_allow_non_user_message_as_first_message_and_consecutive_user_messages()
      throws Exception {
    ProviderMessage a1 =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT, List.of(new ProviderTextBlock("I am ready.")));
    ProviderMessage u1 = userTextMsg("First question");
    ProviderMessage u2 = userTextMsg("Second question immediately");

    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request =
        createRequest("claude-3-5-sonnet", null, List.of(a1, u1, u2), null, null);

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = mapper.readTree(encoded.bodyUtf8Bytes());

    JsonNode messages = root.path("messages");
    assertEquals(3, messages.size());
    assertEquals("assistant", messages.get(0).path("role").asText());
    assertEquals("user", messages.get(1).path("role").asText());
    assertEquals("user", messages.get(2).path("role").asText());
  }

  // ==========================================
  // Group 5: Multimodal & Document Formats
  // ==========================================

  /** 验证 base64 编码的单个图片内容在线缆上格式为 type=image, source.type=base64。 */
  @Test
  void should_accept_single_image_as_base64_encoded_string() throws Exception {
    ProviderImageBlock img = new ProviderImageBlock("image/png", BASE64_PNG);
    ProviderMessage msg = new ProviderMessage(ProviderMessageRole.USER, List.of(img));

    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request = createRequest("claude-3-5-sonnet", null, List.of(msg), null, null);

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode content =
        mapper.readTree(encoded.bodyUtf8Bytes()).path("messages").get(0).path("content").get(0);

    assertEquals("image", content.path("type").asText());
    assertEquals("base64", content.path("source").path("type").asText());
    assertEquals("image/png", content.path("source").path("media_type").asText());
    assertFalse(content.path("source").path("data").asText().isBlank());
  }

  /** 验证同一个消息中多个 base64 图片依次按序在线缆 content 数组中编码。 */
  @Test
  void should_accept_multiple_images_as_base64_encoded_strings() throws Exception {
    ProviderImageBlock img1 = new ProviderImageBlock("image/png", BASE64_PNG);
    ProviderImageBlock img2 = new ProviderImageBlock("image/jpeg", BASE64_JPEG);
    ProviderMessage msg = new ProviderMessage(ProviderMessageRole.USER, List.of(img1, img2));

    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request = createRequest("claude-3-5-sonnet", null, List.of(msg), null, null);

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode contents =
        mapper.readTree(encoded.bodyUtf8Bytes()).path("messages").get(0).path("content");

    assertEquals(2, contents.size());
    assertEquals("image/png", contents.get(0).path("source").path("media_type").asText());
    assertEquals("image/jpeg", contents.get(1).path("source").path("media_type").asText());
  }

  /** 验证公共 HTTP/HTTPS URL 格式的图片内容在线缆上格式为 type=image, source.type=url。 */
  @Test
  void should_accept_single_image_as_public_URL() throws Exception {
    ProviderImageBlock img = new ProviderImageBlock("image/jpeg", "https://example.com/photo.jpg");
    ProviderMessage msg = new ProviderMessage(ProviderMessageRole.USER, List.of(img));

    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request = createRequest("claude-3-5-sonnet", null, List.of(msg), null, null);

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode content =
        mapper.readTree(encoded.bodyUtf8Bytes()).path("messages").get(0).path("content").get(0);

    assertEquals("image", content.path("type").asText());
    assertEquals("url", content.path("source").path("type").asText());
    assertEquals("https://example.com/photo.jpg", content.path("source").path("url").asText());
  }

  /** 验证多个公共 URL 图片均能正确编码在线缆 content 数组中。 */
  @Test
  void should_accept_multiple_images_as_public_URLs() throws Exception {
    ProviderImageBlock img1 = new ProviderImageBlock("image/jpeg", "https://example.com/1.jpg");
    ProviderImageBlock img2 = new ProviderImageBlock("image/png", "https://example.com/2.png");
    ProviderMessage msg = new ProviderMessage(ProviderMessageRole.USER, List.of(img1, img2));

    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request = createRequest("claude-3-5-sonnet", null, List.of(msg), null, null);

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode contents =
        mapper.readTree(encoded.bodyUtf8Bytes()).path("messages").get(0).path("content");

    assertEquals(2, contents.size());
    assertEquals("https://example.com/1.jpg", contents.get(0).path("source").path("url").asText());
    assertEquals("https://example.com/2.png", contents.get(1).path("source").path("url").asText());
  }

  /** 验证 base64 编码的 PDF 文档在线缆上格式为 type=document, source.type=base64, media_type=application/pdf。 */
  @Test
  void should_accept_base64_pdf() throws Exception {
    ProviderDocumentBlock doc = new ProviderDocumentBlock("application/pdf", BASE64_PDF);
    ProviderMessage msg = new ProviderMessage(ProviderMessageRole.USER, List.of(doc));

    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request = createRequest("claude-3-5-sonnet", null, List.of(msg), null, null);

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode content =
        mapper.readTree(encoded.bodyUtf8Bytes()).path("messages").get(0).path("content").get(0);

    assertEquals("document", content.path("type").asText());
    assertEquals("base64", content.path("source").path("type").asText());
    assertEquals("application/pdf", content.path("source").path("media_type").asText());
    assertFalse(content.path("source").path("data").asText().isBlank());
  }

  /** 验证传入 URL 格式的 PDF 文档时被确定性拒绝并抛出 INVALID_REQUEST。 */
  @Test
  void should_reject_public_url_document_source() {
    ProviderDocumentBlock docWithUrl =
        new ProviderDocumentBlock("application/pdf", "https://example.com/report.pdf");
    ProviderMessage msg = new ProviderMessage(ProviderMessageRole.USER, List.of(docWithUrl));

    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request = createRequest("claude-3-5-sonnet", null, List.of(msg), null, null);

    ProviderException ex =
        assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
    assertTrue(ex.getMessage().contains("document source must be a valid base64 data URI"));
  }

  /** 验证传入不支持的图片格式（如 image/bmp）时被确定性拒绝。 */
  @Test
  void should_reject_unsupported_image_media_type() {
    ProviderImageBlock bmpImage =
        new ProviderImageBlock("image/bmp", "https://example.com/image.bmp");
    ProviderMessage msg = new ProviderMessage(ProviderMessageRole.USER, List.of(bmpImage));

    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request = createRequest("claude-3-5-sonnet", null, List.of(msg), null, null);

    ProviderException ex =
        assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
    assertTrue(ex.getMessage().contains("unsupported image media type"));
  }

  // ==========================================
  // Group 6: Tool Definitions & Tool Round-Trip
  // ==========================================

  /** 验证端到端工具定义下发、服务端 tool_use 事件解析、客户端执行后回传 tool_result 并最终完成问答的全链路。 */
  @Test
  void should_execute_a_tool_then_answer() throws Exception {
    AtomicInteger requestStep = new AtomicInteger(0);
    AtomicReference<RecordedRequest> firstReqRef = new AtomicReference<>();
    AtomicReference<RecordedRequest> secondReqRef = new AtomicReference<>();

    server.createContext(
        "/v1/messages",
        exchange -> {
          int step = requestStep.getAndIncrement();
          if (step == 0) {
            firstReqRef.set(recordExchange(exchange));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
              os.write(
                  ("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_tool_call\",\"usage\":{\"input_tokens\":20,\"output_tokens\":0}}}\n\n"
                          + "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"call_weather_1\",\"name\":\"get_weather\",\"input\":{}}}\n\n"
                          + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"location\\\":\\\"Tokyo\\\"}\"}}\n\n"
                          + "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                          + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":15}}\n\n"
                          + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
                      .getBytes(StandardCharsets.UTF_8));
              os.flush();
            }
          } else {
            secondReqRef.set(recordExchange(exchange));
            respondSseText(exchange, "msg_final_answer", "The weather in Tokyo is sunny and 22C.");
          }
        });

    ProviderDescriptor descriptor = createDescriptor(null);
    AnthropicModelProvider provider = (AnthropicModelProvider) adapter.create(descriptor);

    ProviderToolDefinition weatherTool =
        new ProviderToolDefinition(
            "get_weather",
            "Get current weather",
            "{\"type\":\"object\",\"properties\":{\"location\":{\"type\":\"string\"}},\"required\":[\"location\"]}");

    // Turn 1: 用户发起带工具的问题
    ProviderRequest request1 =
        createRequest(
            "claude-3-5-sonnet",
            null,
            List.of(userTextMsg("What is the weather in Tokyo?")),
            List.of(weatherTool),
            null);

    RecordingStreamHandler handler1 = new RecordingStreamHandler();
    provider.stream(request1, handler1);

    assertTrue(handler1.await(5, TimeUnit.SECONDS), "turn 1 should complete");
    ProviderCompletion comp1 = handler1.completion.get();
    assertNotNull(comp1);
    assertEquals(1, comp1.response().toolCalls().size());
    ProviderToolCall toolCall = comp1.response().toolCalls().get(0);
    assertEquals("call_weather_1", toolCall.id());
    assertEquals("get_weather", toolCall.name());
    assertEquals("{\"location\":\"Tokyo\"}", toolCall.argumentsJson());

    // 校验 Turn 1 的线缆请求包含 tools 定义
    JsonNode firstBody = mapper.readTree(firstReqRef.get().bodyBytes);
    assertEquals(1, firstBody.path("tools").size());
    assertEquals("get_weather", firstBody.path("tools").get(0).path("name").asText());

    // Turn 2: 携带 assistant tool_use 与 tool_result 回传
    ProviderMessage assistantMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT, List.of(new ProviderToolCallBlock(toolCall)));
    ProviderMessage toolResultMsg =
        new ProviderMessage(
            ProviderMessageRole.TOOL,
            List.of(
                new ProviderToolResultBlock(
                    "call_weather_1",
                    "get_weather",
                    List.of(new ProviderTextBlock("{\"temperature\":22,\"condition\":\"sunny\"}")),
                    false,
                    "{}")));

    ProviderRequest request2 =
        createRequest(
            "claude-3-5-sonnet",
            null,
            List.of(userTextMsg("What is the weather in Tokyo?"), assistantMsg, toolResultMsg),
            List.of(weatherTool),
            null);

    RecordingStreamHandler handler2 = new RecordingStreamHandler();
    provider.stream(request2, handler2);

    assertTrue(handler2.await(5, TimeUnit.SECONDS), "turn 2 should complete");
    ProviderCompletion comp2 = handler2.completion.get();
    assertNotNull(comp2);
    assertEquals("The weather in Tokyo is sunny and 22C.", comp2.response().text());
    assertEquals(GenerationStopReason.COMPLETE, comp2.response().stopReason());

    // 校验 Turn 2 的线缆请求格式：assistant tool_use + user tool_result
    JsonNode secondBody = mapper.readTree(secondReqRef.get().bodyBytes);
    JsonNode secondMessages = secondBody.path("messages");
    assertEquals(3, secondMessages.size());
    assertEquals("assistant", secondMessages.get(1).path("role").asText());
    assertEquals("tool_use", secondMessages.get(1).path("content").get(0).path("type").asText());
    assertEquals("user", secondMessages.get(2).path("role").asText());
    assertEquals("tool_result", secondMessages.get(2).path("content").get(0).path("type").asText());
    assertEquals(
        "call_weather_1",
        secondMessages.get(2).path("content").get(0).path("tool_use_id").asText());
  }

  /** 验证无参数工具（input={}）的调用与回传能够正确编码在线缆上。 */
  @Test
  void should_execute_a_tool_without_arguments_then_answer() throws Exception {
    ProviderToolCall noArgCall = new ProviderToolCall("call_time_1", "get_current_time", "{}");
    ProviderMessage assistantMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT, List.of(new ProviderToolCallBlock(noArgCall)));
    ProviderMessage toolResultMsg =
        new ProviderMessage(
            ProviderMessageRole.TOOL,
            List.of(
                new ProviderToolResultBlock(
                    "call_time_1",
                    "get_current_time",
                    List.of(new ProviderTextBlock("{\"time\":\"12:00:00\"}")),
                    false,
                    "{}")));

    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request =
        createRequest(
            "claude-3-5-sonnet",
            null,
            List.of(userTextMsg("What time is it?"), assistantMsg, toolResultMsg),
            null,
            null);

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = mapper.readTree(encoded.bodyUtf8Bytes());

    JsonNode assistantContent = root.path("messages").get(1).path("content").get(0);
    assertEquals("tool_use", assistantContent.path("type").asText());
    assertEquals("call_time_1", assistantContent.path("id").asText());
    assertTrue(assistantContent.path("input").isObject());
    assertEquals(0, assistantContent.path("input").size());

    JsonNode toolResultContent = root.path("messages").get(2).path("content").get(0);
    assertEquals("tool_result", toolResultContent.path("type").asText());
    assertEquals("call_time_1", toolResultContent.path("tool_use_id").asText());
  }

  /** 验证多个工具并发调用（parallel tool use）在 Assistant 消息中并列以及在 TOOL 消息中并列的结果回传。 */
  @Test
  void should_execute_multiple_tools_in_parallel_then_answer() throws Exception {
    ProviderToolCall call1 = new ProviderToolCall("c1", "tool_a", "{\"x\":1}");
    ProviderToolCall call2 = new ProviderToolCall("c2", "tool_b", "{\"y\":2}");

    ProviderMessage assistantMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderToolCallBlock(call1), new ProviderToolCallBlock(call2)));

    ProviderMessage toolResultMsg1 =
        new ProviderMessage(
            ProviderMessageRole.TOOL,
            List.of(
                new ProviderToolResultBlock(
                    "c1", "tool_a", List.of(new ProviderTextBlock("res1")), false, "{}")));
    ProviderMessage toolResultMsg2 =
        new ProviderMessage(
            ProviderMessageRole.TOOL,
            List.of(
                new ProviderToolResultBlock(
                    "c2", "tool_b", List.of(new ProviderTextBlock("res2")), false, "{}")));

    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request =
        createRequest(
            "claude-3-5-sonnet",
            null,
            List.of(userTextMsg("run tools"), assistantMsg, toolResultMsg1, toolResultMsg2),
            null,
            null);

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = mapper.readTree(encoded.bodyUtf8Bytes());

    JsonNode assistantBlocks = root.path("messages").get(1).path("content");
    assertEquals(2, assistantBlocks.size());
    assertEquals("c1", assistantBlocks.get(0).path("id").asText());
    assertEquals("c2", assistantBlocks.get(1).path("id").asText());

    JsonNode toolResultMsgNode1 = root.path("messages").get(2);
    assertEquals("user", toolResultMsgNode1.path("role").asText());
    assertEquals("c1", toolResultMsgNode1.path("content").get(0).path("tool_use_id").asText());

    JsonNode toolResultMsgNode2 = root.path("messages").get(3);
    assertEquals("user", toolResultMsgNode2.path("role").asText());
    assertEquals("c2", toolResultMsgNode2.path("content").get(0).path("tool_use_id").asText());
  }

  /** 验证执行失败的工具调用结果在线缆 tool_result 中携带 is_error=true。 */
  @Test
  void should_round_trip_tool_result_with_is_error() throws Exception {
    ProviderMessage toolResultMsg =
        new ProviderMessage(
            ProviderMessageRole.TOOL,
            List.of(
                new ProviderToolResultBlock(
                    "c_err",
                    "failing_tool",
                    List.of(new ProviderTextBlock("tool execution failed: timeout")),
                    true,
                    "{}")));

    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request =
        createRequest(
            "claude-3-5-sonnet", null, List.of(userTextMsg("hi"), toolResultMsg), null, null);

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = mapper.readTree(encoded.bodyUtf8Bytes());

    JsonNode resultBlock = root.path("messages").get(1).path("content").get(0);
    assertEquals("tool_result", resultBlock.path("type").asText());
    assertTrue(
        resultBlock.path("is_error").asBoolean(), "is_error must be true on failed tool result");
  }

  // ==========================================
  // Group 7: Prompt Caching Breakpoints & Retention
  // ==========================================

  /** 验证当缓存保留策略为 NONE 时线缆所有字段均不包含 cache_control 标记。 */
  @Test
  void should_not_place_cache_markers_when_retention_is_none() throws Exception {
    ProviderToolDefinition tool = new ProviderToolDefinition("t", "tool", "{\"type\":\"object\"}");

    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request =
        requestWithInstruction(
            "System prompt",
            List.of(userTextMsg("hi")),
            List.of(tool),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = mapper.readTree(encoded.bodyUtf8Bytes());

    assertFalse(root.path("system").get(0).has("cache_control"));
    assertFalse(root.path("tools").get(0).has("cache_control"));
    assertFalse(root.path("messages").get(0).path("content").get(0).has("cache_control"));
  }

  /** 验证 SYSTEM 缓存断点与 SHORT 保留策略在唯一的顶层 system 块上放置 ephemeral 标记且不带 ttl。 */
  @Test
  void should_cache_system_message() throws Exception {
    ProviderCacheControl cacheControl =
        new ProviderCacheControl(
            PromptCacheRetention.SHORT, "test-aff", Set.of(PromptCacheBreakpoint.SYSTEM));

    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request =
        requestWithInstruction("System prompt", List.of(userTextMsg("hi")), null, cacheControl);

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = mapper.readTree(encoded.bodyUtf8Bytes());

    JsonNode sysBlock = root.path("system").get(0);
    assertTrue(sysBlock.has("cache_control"));
    assertEquals("ephemeral", sysBlock.path("cache_control").path("type").asText());
    assertFalse(sysBlock.path("cache_control").has("ttl"), "SHORT retention must not have ttl");
  }

  /** 验证 TOOLS 缓存断点在线缆 tools 数组的最后一个工具定义上放置 cache_control 标记。 */
  @Test
  void should_cache_tools() throws Exception {
    ProviderToolDefinition tool1 =
        new ProviderToolDefinition("t1", "desc1", "{\"type\":\"object\"}");
    ProviderToolDefinition tool2 =
        new ProviderToolDefinition("t2", "desc2", "{\"type\":\"object\"}");
    ProviderCacheControl cacheControl =
        new ProviderCacheControl(
            PromptCacheRetention.SHORT, "test-aff", Set.of(PromptCacheBreakpoint.TOOLS));

    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request =
        createRequest(
            "claude-3-5-sonnet",
            null,
            List.of(userTextMsg("hi")),
            List.of(tool1, tool2),
            cacheControl);

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = mapper.readTree(encoded.bodyUtf8Bytes());

    JsonNode tools = root.path("tools");
    assertEquals(2, tools.size());
    assertFalse(tools.get(0).has("cache_control"));
    assertTrue(tools.get(1).has("cache_control"));
    assertEquals("ephemeral", tools.get(1).path("cache_control").path("type").asText());
  }

  /** 验证 SYSTEM 与 TOOLS 同时设置缓存断点时各自的末尾项均正确放置 cache_control。 */
  @Test
  void should_cache_system_message_and_tools() throws Exception {
    ProviderToolDefinition tool = new ProviderToolDefinition("t", "desc", "{\"type\":\"object\"}");
    ProviderCacheControl cacheControl =
        new ProviderCacheControl(
            PromptCacheRetention.SHORT,
            "test-aff",
            Set.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS));

    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request =
        requestWithInstruction("Sys", List.of(userTextMsg("hi")), List.of(tool), cacheControl);

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = mapper.readTree(encoded.bodyUtf8Bytes());

    assertTrue(root.path("system").get(0).has("cache_control"));
    assertTrue(root.path("tools").get(0).has("cache_control"));
  }

  /** 验证 LONG 保留策略在线缆 cache_control 中显式包含 ttl=1h 属性。 */
  @Test
  void should_cache_system_message_with_long_retention_and_1h_ttl() throws Exception {
    ProviderCacheControl cacheControl =
        new ProviderCacheControl(
            PromptCacheRetention.LONG, "test-aff", Set.of(PromptCacheBreakpoint.SYSTEM));

    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request =
        requestWithInstruction("Sys", List.of(userTextMsg("hi")), null, cacheControl);

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = mapper.readTree(encoded.bodyUtf8Bytes());

    JsonNode marker = root.path("system").get(0).path("cache_control");
    assertEquals("ephemeral", marker.path("type").asText());
    assertEquals("1h", marker.path("ttl").asText(), "LONG retention must specify 1h ttl");
  }

  /** 验证 CONVERSATION 缓存断点在最新的对话内容块上放置标记，并严格遵守 SHORT（无 ttl）与 LONG（1h ttl）区分。 */
  @Test
  void should_cache_conversation_block_with_short_and_long_ttl() throws Exception {
    ProviderMessage u1 = userTextMsg("Turn 1");
    ProviderMessage a1 =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT, List.of(new ProviderTextBlock("Reply 1")));
    ProviderMessage u2 = userTextMsg("Turn 2");

    ProviderDescriptor descriptor = createDescriptor(null);

    // 1. SHORT retention
    ProviderCacheControl shortCache =
        new ProviderCacheControl(
            PromptCacheRetention.SHORT, "test-aff", Set.of(PromptCacheBreakpoint.CONVERSATION));
    ProviderRequest reqShort =
        createRequest("claude-3-5-sonnet", null, List.of(u1, a1, u2), null, shortCache);
    AnthropicEncodedRequest encShort = encoder.encode(reqShort, descriptor);
    JsonNode rootShort = mapper.readTree(encShort.bodyUtf8Bytes());
    JsonNode lastBlockShort = rootShort.path("messages").get(2).path("content").get(0);
    assertTrue(lastBlockShort.has("cache_control"));
    assertEquals("ephemeral", lastBlockShort.path("cache_control").path("type").asText());
    assertFalse(lastBlockShort.path("cache_control").has("ttl"));

    // 2. LONG retention
    ProviderCacheControl longCache =
        new ProviderCacheControl(
            PromptCacheRetention.LONG, "test-aff", Set.of(PromptCacheBreakpoint.CONVERSATION));
    ProviderRequest reqLong =
        createRequest("claude-3-5-sonnet", null, List.of(u1, a1, u2), null, longCache);
    AnthropicEncodedRequest encLong = encoder.encode(reqLong, descriptor);
    JsonNode rootLong = mapper.readTree(encLong.bodyUtf8Bytes());
    JsonNode lastBlockLong = rootLong.path("messages").get(2).path("content").get(0);
    assertTrue(lastBlockLong.has("cache_control"));
    assertEquals("1h", lastBlockLong.path("cache_control").path("ttl").asText());
  }

  /** 验证当提示缓存保留策略不为 NONE 但断点集合为空时，请求编码器校验失败并抛出 INVALID_REQUEST。 */
  @Test
  void should_reject_cache_retention_without_breakpoints() {
    ProviderCacheControl emptyBreakpointsWithRetention =
        new ProviderCacheControl(PromptCacheRetention.SHORT, "test-aff", Set.of());
    ProviderDescriptor descriptor = createDescriptor(null);
    ProviderRequest request =
        createRequest(
            "claude-3-5-sonnet",
            null,
            List.of(userTextMsg("hi")),
            null,
            emptyBreakpointsWithRetention);

    ProviderException ex =
        assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
    assertTrue(ex.getMessage().contains("requires at least one breakpoint"));
  }

  // ==========================================
  // Group 8: SSE Streaming Lifecycle, Accumulation, Timeouts
  // ==========================================

  /** 验证完整的 SSE 流式生命周期：累计输入/输出与缓存用量计算、结束原因映射及消息 ID 解析。 */
  @Test
  void should_complete_standard_sse_with_cumulative_usage_stop_reason_and_message_id()
      throws Exception {
    server.createContext(
        "/v1/messages",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(
                ("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_cum_usage_1\",\"usage\":{\"input_tokens\":50,\"output_tokens\":0,\"cache_read_input_tokens\":20,\"cache_creation_input_tokens\":10}}}\n\n"
                        + "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                        + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello \"}}\n\n"
                        + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Anthropic!\"}}\n\n"
                        + "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                        + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":12}}\n\n"
                        + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();
          }
        });

    ProviderDescriptor descriptor = createDescriptor(null);
    AnthropicModelProvider provider = (AnthropicModelProvider) adapter.create(descriptor);
    ProviderRequest request =
        createRequest("claude-3-5-sonnet", null, List.of(userTextMsg("hi")), null, null);

    RecordingStreamHandler handler = new RecordingStreamHandler();
    provider.stream(request, handler);

    assertTrue(handler.await(5, TimeUnit.SECONDS));
    ProviderCompletion comp = handler.completion.get();
    assertNotNull(comp);
    assertEquals("Hello Anthropic!", comp.response().text());
    assertEquals(GenerationStopReason.COMPLETE, comp.response().stopReason());
    assertEquals("msg_cum_usage_1", comp.response().requestId());
    assertEquals(50, comp.response().usage().inputTokens());
    assertEquals(12, comp.response().usage().outputTokens());
    assertEquals(20, comp.response().usage().cacheReadTokens());
    assertEquals(10, comp.response().usage().cacheWriteTokens());
  }

  /** 验证客户端取消流后，stream.isCancelled() 返回 true，底层传输立即终止，且不向上层回调投递任何后续事件或错误。 */
  @Test
  void should_cancel_streaming() throws Exception {
    CountDownLatch clientCancelledLatch = new CountDownLatch(1);
    CountDownLatch serverFinishedLatch = new CountDownLatch(1);

    server.createContext(
        "/v1/messages",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(
                ("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_cancel\",\"usage\":{\"input_tokens\":5,\"output_tokens\":0}}}\n\n"
                        + "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                        + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"First chunk\"}}\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();

            assertTrue(clientCancelledLatch.await(5, TimeUnit.SECONDS));
            try {
              os.write(
                  ("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\" post-cancel chunk\"}}\n\n"
                          + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
                      .getBytes(StandardCharsets.UTF_8));
              os.flush();
            } catch (IOException ignored) {
            }
          } catch (Exception ignored) {
          } finally {
            serverFinishedLatch.countDown();
          }
        });

    ProviderDescriptor descriptor = createDescriptor(null);
    AnthropicModelProvider provider = (AnthropicModelProvider) adapter.create(descriptor);
    ProviderRequest request =
        createRequest("claude-3-5-sonnet", null, List.of(userTextMsg("hi")), null, null);

    AtomicBoolean gotFirstEvent = new AtomicBoolean(false);
    AtomicBoolean postCancelCallbackTriggered = new AtomicBoolean(false);
    CountDownLatch testDoneLatch = new CountDownLatch(1);

    provider.stream(
        request,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {
            if (gotFirstEvent.compareAndSet(false, true)) {
              stream.cancel();
              assertTrue(stream.isCancelled());
              clientCancelledLatch.countDown();
            } else {
              postCancelCallbackTriggered.set(true);
            }
          }

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {
            postCancelCallbackTriggered.set(true);
            testDoneLatch.countDown();
          }

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            postCancelCallbackTriggered.set(true);
            testDoneLatch.countDown();
          }
        });

    assertTrue(serverFinishedLatch.await(5, TimeUnit.SECONDS));
    Thread.sleep(150);

    assertFalse(
        postCancelCallbackTriggered.get(), "no callbacks may be delivered after cancellation");
  }

  /** 验证服务端未按时响应导致超时触发时，流传输器正确向上层报告 TRANSIENT 异常。 */
  @Test
  void should_map_native_stream_timeout_to_transient_error() throws Exception {
    server.createContext(
        "/v1/messages",
        exchange -> {
          try {
            Thread.sleep(1200);
          } catch (InterruptedException ignored) {
          }
          exchange.sendResponseHeaders(200, 0);
        });

    ModelCallTimeoutPolicy tightTimeout =
        new ModelCallTimeoutPolicy(Duration.ofMillis(300), Duration.ofMillis(300));
    ProviderDescriptor descriptor = createDescriptor(tightTimeout);
    AnthropicModelProvider provider = (AnthropicModelProvider) adapter.create(descriptor);
    ProviderRequest request =
        createRequest("claude-3-5-sonnet", null, List.of(userTextMsg("hi")), null, null);

    RecordingStreamHandler handler = new RecordingStreamHandler();
    provider.stream(request, handler);

    assertTrue(handler.await(5, TimeUnit.SECONDS), "timeout should trigger error");
    assertNotNull(handler.error.get());
    assertEquals(ProviderErrorKind.TRANSIENT, handler.error.get().kind());
    assertTrue(handler.error.get().getMessage().contains("timed out"));
  }

  /** 验证原生流式传输严格遵循配置的 ModelCallTimeoutPolicy 自定义超时策略。 */
  @Test
  void should_honor_native_stream_timeout_policy() throws Exception {
    server.createContext(
        "/v1/messages",
        exchange -> {
          try {
            Thread.sleep(1000);
          } catch (InterruptedException ignored) {
          }
          exchange.sendResponseHeaders(200, 0);
        });

    ModelCallTimeoutPolicy customPolicy =
        new ModelCallTimeoutPolicy(Duration.ofSeconds(2), Duration.ofMillis(250));
    ProviderDescriptor descriptor = createDescriptor(customPolicy);
    AnthropicModelProvider provider = (AnthropicModelProvider) adapter.create(descriptor);
    ProviderRequest request =
        createRequest("claude-3-5-sonnet", null, List.of(userTextMsg("hi")), null, null);

    RecordingStreamHandler handler = new RecordingStreamHandler();
    provider.stream(request, handler);

    assertTrue(handler.await(5, TimeUnit.SECONDS));
    assertNotNull(handler.error.get());
    assertEquals(ProviderErrorKind.TRANSIENT, handler.error.get().kind());
  }

  /** 验证 stream() 调用为非阻塞异步调用，回调在工作线程池中执行。 */
  @Test
  void should_chat_asynchronously() throws Exception {
    server.createContext(
        "/v1/messages", exchange -> respondSseText(exchange, "msg_async", "async reply"));

    ProviderDescriptor descriptor = createDescriptor(null);
    AnthropicModelProvider provider = (AnthropicModelProvider) adapter.create(descriptor);
    ProviderRequest request =
        createRequest("claude-3-5-sonnet", null, List.of(userTextMsg("hi")), null, null);

    RecordingStreamHandler handler = new RecordingStreamHandler();
    Thread callingThread = Thread.currentThread();
    provider.stream(request, handler);

    assertTrue(handler.await(5, TimeUnit.SECONDS));
    assertNotNull(handler.completion.get());
    assertFalse(handler.callbackThreads.isEmpty());
    assertFalse(
        handler.callbackThreads.contains(callingThread),
        "callbacks must be executed asynchronously on worker threads");
  }

  // ==========================================
  // Group 9: User Callback Failure & Terminal-Once
  // ==========================================

  /** 验证用户在 onEvent 回调中抛出运行时异常时，传输层抑制二次错误上报，绝不产生二次终态。 */
  @Test
  void should_stop_stream_without_secondary_terminal_callback_when_on_event_throws()
      throws Exception {
    server.createContext(
        "/v1/messages",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(
                ("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_err_call\",\"usage\":{\"input_tokens\":5,\"output_tokens\":0}}}\n\n"
                        + "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                        + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"chunk\"}}\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();
          }
        });

    ProviderDescriptor descriptor = createDescriptor(null);
    AnthropicModelProvider provider = (AnthropicModelProvider) adapter.create(descriptor);
    ProviderRequest request =
        createRequest("claude-3-5-sonnet", null, List.of(userTextMsg("hi")), null, null);

    AtomicInteger errorCallbackCount = new AtomicInteger(0);
    AtomicInteger completeCallbackCount = new AtomicInteger(0);
    CountDownLatch thrownLatch = new CountDownLatch(1);

    provider.stream(
        request,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {
            thrownLatch.countDown();
            throw new RuntimeException("intentional boom in onEvent");
          }

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {
            completeCallbackCount.incrementAndGet();
          }

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            errorCallbackCount.incrementAndGet();
          }
        });

    assertTrue(thrownLatch.await(5, TimeUnit.SECONDS));
    Thread.sleep(150);

    assertEquals(0, completeCallbackCount.get(), "no onComplete after callback failure");
    assertEquals(0, errorCallbackCount.get(), "no secondary onError triggered when onEvent throws");
  }

  /** 验证用户在 onComplete 回调中抛出运行时异常时，绝不触发二次终态 onError。 */
  @Test
  void should_not_emit_secondary_error_when_on_complete_throws() throws Exception {
    server.createContext(
        "/v1/messages", exchange -> respondSseText(exchange, "msg_complete_ex", "done"));

    ProviderDescriptor descriptor = createDescriptor(null);
    AnthropicModelProvider provider = (AnthropicModelProvider) adapter.create(descriptor);
    ProviderRequest request =
        createRequest("claude-3-5-sonnet", null, List.of(userTextMsg("hi")), null, null);

    AtomicInteger errorCallbackCount = new AtomicInteger(0);
    CountDownLatch completeLatch = new CountDownLatch(1);

    provider.stream(
        request,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {
            completeLatch.countDown();
            throw new RuntimeException("intentional boom in onComplete");
          }

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            errorCallbackCount.incrementAndGet();
          }
        });

    assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
    Thread.sleep(150);

    assertEquals(0, errorCallbackCount.get(), "onError must not be called if onComplete throws");
  }

  /** 验证当底层错误触发且用户 onError 回调中抛出运行时异常时，异常被安全隔离且不产生二次回调。 */
  @Test
  void should_isolate_exception_thrown_by_on_error() throws Exception {
    server.createContext(
        "/v1/messages",
        exchange -> {
          byte[] err =
              "{\"error\":{\"type\":\"api_error\",\"message\":\"fail\"}}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(500, err.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(err);
          }
        });

    ProviderDescriptor descriptor = createDescriptor(null);
    AnthropicModelProvider provider = (AnthropicModelProvider) adapter.create(descriptor);
    ProviderRequest request =
        createRequest("claude-3-5-sonnet", null, List.of(userTextMsg("hi")), null, null);

    CountDownLatch errorLatch = new CountDownLatch(1);

    provider.stream(
        request,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            errorLatch.countDown();
            throw new RuntimeException("intentional boom in onError");
          }
        });

    assertTrue(errorLatch.await(5, TimeUnit.SECONDS));
  }

  // ==========================================
  // Group 10: Cache Diagnostics Parsing
  // ==========================================

  /** 验证服务端返回的 message_start 事件中携带的 cache_miss_reason 能够正确解析并存入 rawUsageJson。 */
  @Test
  void should_parse_cache_miss_reason_from_streaming_message_start() throws Exception {
    server.createContext(
        "/v1/messages",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(
                ("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_diag\",\"usage\":{\"input_tokens\":10,\"output_tokens\":0},\"diagnostics\":{\"cache_miss_reason\":{\"type\":\"model_changed\",\"cache_missed_input_tokens\":1500}}}}\n\n"
                        + "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                        + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}\n\n"
                        + "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                        + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}\n\n"
                        + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();
          }
        });

    ProviderDescriptor descriptor = createDescriptor(null);
    AnthropicModelProvider provider = (AnthropicModelProvider) adapter.create(descriptor);
    ProviderRequest request =
        createRequest("claude-3-5-sonnet", null, List.of(userTextMsg("hi")), null, null);

    RecordingStreamHandler handler = new RecordingStreamHandler();
    provider.stream(request, handler);

    assertTrue(handler.await(5, TimeUnit.SECONDS));
    ProviderCompletion comp = handler.completion.get();
    assertNotNull(comp);
    JsonNode rawUsage = mapper.readTree(comp.response().rawUsageJson());
    assertTrue(rawUsage.has("cache_miss_reason"));
    assertEquals("model_changed", rawUsage.path("cache_miss_reason").path("type").asText());
    assertEquals(
        1500, rawUsage.path("cache_miss_reason").path("cache_missed_input_tokens").asLong());
  }

  /** 验证两轮交互中第一轮无诊断原因，第二轮返回 model_changed 诊断原因的序列化状态。 */
  @Test
  void should_parse_absent_then_model_changed_cache_miss_reason_across_two_streams()
      throws Exception {
    AtomicInteger turn = new AtomicInteger(0);
    server.createContext(
        "/v1/messages",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            if (turn.getAndIncrement() == 0) {
              os.write(
                  ("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_turn_1\",\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}\n\n"
                          + "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                          + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Turn 1\"}}\n\n"
                          + "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                          + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}\n\n"
                          + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
                      .getBytes(StandardCharsets.UTF_8));
            } else {
              os.write(
                  ("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_turn_2\",\"usage\":{\"input_tokens\":10,\"output_tokens\":0},\"diagnostics\":{\"cache_miss_reason\":{\"type\":\"model_changed\",\"cache_missed_input_tokens\":800}}}}\n\n"
                          + "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                          + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Turn 2\"}}\n\n"
                          + "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                          + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}\n\n"
                          + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
                      .getBytes(StandardCharsets.UTF_8));
            }
            os.flush();
          }
        });

    ProviderDescriptor descriptor = createDescriptor(null);
    AnthropicModelProvider provider = (AnthropicModelProvider) adapter.create(descriptor);

    // Turn 1
    RecordingStreamHandler handler1 = new RecordingStreamHandler();
    provider.stream(
        createRequest("claude-3-5-sonnet", null, List.of(userTextMsg("1")), null, null), handler1);
    assertTrue(handler1.await(5, TimeUnit.SECONDS));
    JsonNode usage1 = mapper.readTree(handler1.completion.get().response().rawUsageJson());
    assertFalse(usage1.has("cache_miss_reason"), "first turn should have no cache_miss_reason");

    // Turn 2
    RecordingStreamHandler handler2 = new RecordingStreamHandler();
    provider.stream(
        createRequest("claude-3-5-sonnet", null, List.of(userTextMsg("2")), null, null), handler2);
    assertTrue(handler2.await(5, TimeUnit.SECONDS));
    JsonNode usage2 = mapper.readTree(handler2.completion.get().response().rawUsageJson());
    assertTrue(usage2.has("cache_miss_reason"));
    assertEquals("model_changed", usage2.path("cache_miss_reason").path("type").asText());
  }

  // ==========================================
  // Helper Methods & Classes
  // ==========================================

  private ProviderDescriptor createDescriptor(ModelCallTimeoutPolicy timeoutPolicy) {
    return new ProviderDescriptor(
        "anthropic-wire-test",
        ProviderType.ANTHROPIC,
        "http://127.0.0.1:" + port + "/v1",
        timeoutPolicy != null
            ? timeoutPolicy
            : new ModelCallTimeoutPolicy(Duration.ofSeconds(5), Duration.ofSeconds(3)),
        UUID.randomUUID());
  }

  private ModelPricing defaultPricing() {
    return new ModelPricing(
        "USD",
        "standard",
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

  private ModelDescriptor createModelDescriptor(String modelName) {
    return new ModelDescriptor(
        "anthropic-wire-test",
        modelName,
        modelName,
        Set.of(ModelInputModality.TEXT, ModelInputModality.IMAGE, ModelInputModality.DOCUMENT),
        true,
        false,
        defaultPricing());
  }

  private ModelDescriptor createReasoningModelDescriptor(String modelName) {
    return new ModelDescriptor(
        "anthropic-wire-test",
        modelName,
        modelName,
        Set.of(ModelInputModality.TEXT, ModelInputModality.IMAGE, ModelInputModality.DOCUMENT),
        true,
        true,
        defaultPricing());
  }

  private ProviderRequest createRequest(
      String modelName,
      ModelVariant variant,
      List<ProviderMessage> messages,
      List<ProviderToolDefinition> tools,
      ProviderCacheControl cacheControl) {
    return createRequest(modelName, variant, 1024, messages, tools, cacheControl);
  }

  /** 指定系统指令的请求：系统指令是顶层字段，不再作为会话消息出现。 */
  private ProviderRequest requestWithInstruction(
      String systemInstruction,
      List<ProviderMessage> messages,
      List<ProviderToolDefinition> tools,
      ProviderCacheControl cacheControl) {
    return new ProviderRequest(
        createModelDescriptor("claude-3-5-sonnet"),
        new ModelVariant("default"),
        1024,
        systemInstruction,
        messages,
        tools != null ? tools : List.of(),
        cacheControl != null ? cacheControl : ProviderCacheControl.none());
  }

  private ProviderRequest createRequest(
      String modelName,
      ModelVariant variant,
      int outputTokens,
      List<ProviderMessage> messages,
      List<ProviderToolDefinition> tools,
      ProviderCacheControl cacheControl) {
    return new ProviderRequest(
        createModelDescriptor(modelName),
        variant != null ? variant : new ModelVariant("default"),
        outputTokens,
        "Test system instruction.",
        messages,
        tools != null ? tools : List.of(),
        cacheControl != null ? cacheControl : ProviderCacheControl.none());
  }

  private static ProviderMessage userTextMsg(String text) {
    return new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock(text)));
  }

  private static RecordedRequest recordExchange(HttpExchange exchange) throws IOException {
    String method = exchange.getRequestMethod();
    String path = exchange.getRequestURI().getPath();
    Map<String, List<String>> headers = exchange.getRequestHeaders();
    byte[] body = exchange.getRequestBody().readAllBytes();
    return new RecordedRequest(method, path, headers, body);
  }

  private static void respondSseText(HttpExchange exchange, String messageId, String text)
      throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
    exchange.sendResponseHeaders(200, 0);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(
          ("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\""
                  + messageId
                  + "\",\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}\n\n"
                  + "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                  + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\""
                  + text
                  + "\"}}\n\n"
                  + "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                  + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":5}}\n\n"
                  + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
              .getBytes(StandardCharsets.UTF_8));
      os.flush();
    }
  }

  private static class RecordedRequest {
    final String method;
    final String path;
    final Map<String, List<String>> headers;
    final byte[] bodyBytes;

    RecordedRequest(
        String method, String path, Map<String, List<String>> headers, byte[] bodyBytes) {
      this.method = method;
      this.path = path;
      this.headers = headers;
      this.bodyBytes = bodyBytes;
    }

    String firstHeader(String name) {
      List<String> values = headers.get(name);
      if (values != null && !values.isEmpty()) {
        return values.get(0);
      }
      return null;
    }
  }

  private static class RecordingStreamHandler implements ProviderStreamHandler {
    final List<ProviderStreamEvent> events = new CopyOnWriteArrayList<>();
    final AtomicReference<ProviderCompletion> completion = new AtomicReference<>();
    final AtomicReference<ProviderException> error = new AtomicReference<>();
    final CountDownLatch latch = new CountDownLatch(1);
    final Set<Thread> callbackThreads = Collections.synchronizedSet(new HashSet<>());

    @Override
    public void onEvent(ProviderStreamEvent event, ProviderStream stream) {
      callbackThreads.add(Thread.currentThread());
      events.add(event);
    }

    @Override
    public void onComplete(ProviderCompletion completion, ProviderStream stream) {
      callbackThreads.add(Thread.currentThread());
      this.completion.set(completion);
      latch.countDown();
    }

    @Override
    public void onError(ProviderException error, ProviderStream stream) {
      callbackThreads.add(Thread.currentThread());
      this.error.set(error);
      latch.countDown();
    }

    boolean await(long timeout, TimeUnit unit) throws InterruptedException {
      return latch.await(timeout, unit);
    }
  }
}
