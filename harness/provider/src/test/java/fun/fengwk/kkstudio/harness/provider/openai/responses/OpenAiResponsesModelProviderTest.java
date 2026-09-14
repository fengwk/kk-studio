package fun.fengwk.kkstudio.harness.provider.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.provider.transport.HttpOpenMetadata;
import fun.fengwk.kkstudio.harness.provider.transport.HttpSseCallback;
import fun.fengwk.kkstudio.harness.provider.transport.HttpSseLimits;
import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.provider.transport.ServerSentEvent;
import fun.fengwk.kkstudio.harness.provider.transport.TransportErrorKind;
import fun.fengwk.kkstudio.harness.provider.transport.TransportException;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheMode;
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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

/** 验证 OpenAI Responses ModelProvider 端到端请求装配、鉴权注入与流生命周期交互。 */
class OpenAiResponsesModelProviderTest {

  private static final ProviderStream INERT_STREAM =
      new ProviderStream() {
        @Override
        public void cancel() {}

        @Override
        public boolean isCancelled() {
          return false;
        }
      };

  @FunctionalInterface
  private interface TransportAction {
    void execute(HttpRequest request, HttpSseCallback callback) throws Exception;
  }

  private static final class RecordingHandler implements ProviderStreamHandler {
    final List<ProviderStreamEvent> events = new ArrayList<>();
    ProviderCompletion completion;
    ProviderException error;

    @Override
    public void onEvent(ProviderStreamEvent event, ProviderStream stream) {
      events.add(event);
    }

    @Override
    public void onComplete(ProviderCompletion completion, ProviderStream stream) {
      this.completion = completion;
    }

    @Override
    public void onError(ProviderException error, ProviderStream stream) {
      this.error = error;
    }
  }

  private HttpClient httpClient;
  private ExecutorService workerExecutor;
  private ScheduledExecutorService scheduler;

  @BeforeEach
  void setUp() {
    httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    workerExecutor = Executors.newSingleThreadExecutor();
    scheduler = Executors.newSingleThreadScheduledExecutor();
  }

  @AfterEach
  void tearDown() {
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

  private JdkHttpSseTransport stubTransport(TransportAction action) {
    return new JdkHttpSseTransport(httpClient, workerExecutor, scheduler) {
      @Override
      public ProviderStream stream(
          HttpRequest request,
          ModelCallTimeoutPolicy timeoutPolicy,
          HttpSseLimits limits,
          HttpSseCallback callback) {
        try {
          action.execute(request, callback);
        } catch (RuntimeException exception) {
          throw exception;
        } catch (Exception exception) {
          throw new RuntimeException(exception);
        }
        return INERT_STREAM;
      }
    };
  }

  private ProviderDescriptor createDescriptor() {
    return new ProviderDescriptor(
        "openai_test",
        ProviderType.OPENAI_RESPONSES,
        "https://api.openai.com/v1",
        new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(60)),
        UUID.fromString("11111111-1111-1111-1111-111111111111"));
  }

  private ModelDescriptor createModel() {
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
    return new ModelDescriptor(
        "openai_test",
        "gpt-5.4-mini",
        "gpt-5.4-mini",
        Set.of(ModelInputModality.TEXT),
        true,
        true,
        pricing);
  }

  private ProviderRequest createRequest() {
    return new ProviderRequest(
        createModel(),
        new ModelVariant("default"),
        1024,
        List.of(
            new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
        List.of(),
        ProviderCacheControl.none());
  }

  /** 验证带有 apiKey 时注入 Bearer 鉴权头，且请求端点、方法与 Content-Type 符合 Responses 规范。 */
  @Test
  void test_authenticatedRequestAssembly() {
    AtomicReference<HttpRequest> capturedRequest = new AtomicReference<>();
    OpenAiResponsesProviderAdapter adapter =
        new OpenAiResponsesProviderAdapter(
            stubTransport((req, cb) -> capturedRequest.set(req)), "sk-valid-key");
    assertEquals(ProviderType.OPENAI_RESPONSES, adapter.providerType());
    // 缺省即 AUTOMATIC：不发送任何 cache hint。
    assertEquals(PromptCacheMode.AUTOMATIC, adapter.promptCacheCapability().mode());
    assertFalse(adapter.promptCacheCapability().supports(PromptCacheRetention.SHORT));
    assertTrue(adapter.promptCacheCapability().supports(PromptCacheRetention.NONE));

    ModelProvider provider = adapter.create(createDescriptor());
    ProviderStream stream = provider.stream(createRequest(), new RecordingHandler());

    assertNotNull(stream);
    HttpRequest req = capturedRequest.get();
    assertNotNull(req);
    assertEquals("POST", req.method());
    assertEquals("https://api.openai.com/v1/responses", req.uri().toString());
    assertEquals("application/json", req.headers().firstValue("Content-Type").orElse(""));
    assertEquals("text/event-stream", req.headers().firstValue("Accept").orElse(""));
    assertEquals("Bearer sk-valid-key", req.headers().firstValue("Authorization").orElse(""));
  }

  /** 验证无 apiKey 或为空格时以匿名方式发起请求，绝不注入 Authorization 头。 */
  @Test
  void test_anonymousRequestAssembly() {
    AtomicReference<HttpRequest> capturedRequest = new AtomicReference<>();
    OpenAiResponsesProviderAdapter adapter =
        new OpenAiResponsesProviderAdapter(
            stubTransport((req, cb) -> capturedRequest.set(req)), "   ");
    ModelProvider provider = adapter.create(createDescriptor());

    provider.stream(createRequest(), new RecordingHandler());

    HttpRequest req = capturedRequest.get();
    assertNotNull(req);
    assertTrue(req.headers().firstValue("Authorization").isEmpty());
  }

  /** 验证端到端成功生命周期：通过回调注入 SSE 事件并触发 onComplete。 */
  @Test
  void test_endToEndStreamLifecycle() {
    JdkHttpSseTransport transport =
        stubTransport(
            (req, cb) -> {
              cb.onOpen(new HttpOpenMetadata(200, Map.of()));
              cb.onEvent(
                  new ServerSentEvent(
                      null, "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_life\"}}"));
              cb.onEvent(
                  new ServerSentEvent(
                      null, "{\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}"));
              cb.onEvent(
                  new ServerSentEvent(
                      null,
                      "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_life\",\"status\":\"completed\"}}"));
              cb.onComplete();
            });

    OpenAiResponsesProviderAdapter adapter = new OpenAiResponsesProviderAdapter(transport, "key");
    ModelProvider provider = adapter.create(createDescriptor());
    RecordingHandler handler = new RecordingHandler();

    provider.stream(createRequest(), handler);

    assertEquals(1, handler.events.size());
    assertNotNull(handler.completion);
    assertEquals("hello", handler.completion.response().text());
    assertEquals(GenerationStopReason.COMPLETE, handler.completion.response().stopReason());
  }

  /** 验证底座传输层异常被安全映射并投递给 handler 的 onError。 */
  @Test
  void test_transportFailureMappedToOnError() {
    JdkHttpSseTransport transport =
        stubTransport(
            (req, cb) ->
                cb.onFailure(
                    new TransportException(TransportErrorKind.TIMEOUT, "connect timed out")));

    OpenAiResponsesProviderAdapter adapter = new OpenAiResponsesProviderAdapter(transport, "key");
    ModelProvider provider = adapter.create(createDescriptor());
    RecordingHandler handler = new RecordingHandler();

    provider.stream(createRequest(), handler);

    assertNotNull(handler.error);
    assertEquals(ProviderErrorKind.TRANSIENT, handler.error.kind());
  }

  /** 验证 adapter 元数据及流关闭等生命周期路径。 */
  @Test
  void test_adapterMetadataAndLifecycleBranches() {
    JdkHttpSseTransport transport =
        stubTransport(
            (req, cb) -> {
              cb.onOpen(new HttpOpenMetadata(200, Map.of()));
              cb.onComplete();
            });

    OpenAiResponsesProviderAdapter adapter = new OpenAiResponsesProviderAdapter(transport, "key");
    assertEquals(ProviderType.OPENAI_RESPONSES, adapter.providerType());

    ModelProvider provider = adapter.create(createDescriptor());
    assertNotNull(provider);
    provider.stream(createRequest(), new RecordingHandler());
  }

  /** 验证 ModelProvider.descriptor() 返回关联的 ProviderDescriptor，以及 Adapter.toString() 输出格式正确。 */
  @Test
  void test_providerDescriptorAndToString() {
    OpenAiResponsesProviderAdapter adapter =
        new OpenAiResponsesProviderAdapter(stubTransport((req, cb) -> {}), "key");
    ProviderDescriptor desc = createDescriptor();
    ModelProvider provider = adapter.create(desc);

    assertNotNull(provider);
    assertTrue(provider instanceof OpenAiResponsesModelProvider);
    assertEquals(desc, ((OpenAiResponsesModelProvider) provider).descriptor());
    assertEquals(
        "OpenAiResponsesProviderAdapter[providerType=OPENAI_RESPONSES]", adapter.toString());
  }

  /** 验证 Adapter 的各类重载构造器、null 边界校验、非匹配 ProviderType 拒绝及静态配置解析方法。 */
  @Test
  void test_adapterConstructorsAndStaticApisAndBoundaries() {
    JdkHttpSseTransport transport = stubTransport((req, cb) -> {});

    // 1. 构造器 transport 为空抛出 NullPointerException
    assertThrows(NullPointerException.class, () -> new OpenAiResponsesProviderAdapter(null, "key"));

    // 2. 构造器支持 configJson 解析
    OpenAiResponsesProviderAdapter adapterWithJson =
        new OpenAiResponsesProviderAdapter(
            transport, "key", "{\"openAiPromptCacheMode\":\"LEGACY\"}");
    assertEquals(PromptCacheMode.AFFINITY, adapterWithJson.promptCacheCapability().mode());

    // 3. 构造器传入 null OpenAiResponsesConfig 自动回退为默认配置
    OpenAiResponsesProviderAdapter adapterWithNullConfig =
        new OpenAiResponsesProviderAdapter(transport, "key", (OpenAiResponsesConfig) null);
    assertEquals(PromptCacheMode.AUTOMATIC, adapterWithNullConfig.promptCacheCapability().mode());

    // 4. descriptor 为空或 ProviderType 不匹配时抛出异常
    assertThrows(NullPointerException.class, () -> adapterWithJson.create(null));
    ProviderDescriptor anthropicDesc =
        new ProviderDescriptor(
            "anthropic",
            ProviderType.ANTHROPIC,
            "https://api.anthropic.com",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)),
            UUID.randomUUID());
    assertThrows(IllegalArgumentException.class, () -> adapterWithJson.create(anthropicDesc));

    // 5. 静态 API 验证
    assertEquals(
        PromptCacheMode.AFFINITY,
        OpenAiResponsesProviderAdapter.resolvePromptCacheCapability(
                "{\"openAiPromptCacheMode\":\"LEGACY\"}")
            .mode());
    assertNotNull(OpenAiResponsesProviderAdapter.parseConfig("{}"));
  }

  /** 验证 ModelProvider.stream 方法对 request 与 handler 的 null 参数边界校验。 */
  @Test
  void test_streamNullArgumentBoundaries() {
    OpenAiResponsesProviderAdapter adapter =
        new OpenAiResponsesProviderAdapter(stubTransport((req, cb) -> {}), "key");
    ModelProvider provider = adapter.create(createDescriptor());

    assertThrows(NullPointerException.class, () -> provider.stream(null, new RecordingHandler()));
    assertThrows(NullPointerException.class, () -> provider.stream(createRequest(), null));
  }

  /** 验证请求编码失败（如工具 schema 非法）时，错误安全路由至 handler.onError 而不抛出未捕获异常。 */
  @Test
  void test_encoderFailureRoutedToHandler() {
    OpenAiResponsesProviderAdapter adapter =
        new OpenAiResponsesProviderAdapter(stubTransport((req, cb) -> {}), "key");
    ModelProvider provider = adapter.create(createDescriptor());

    ModelVariant invalidVariant = new ModelVariant("invalid-variant");
    ProviderToolDefinition invalidTool = new ProviderToolDefinition("badTool", "desc", "not-json");
    ProviderRequest invalidRequest =
        new ProviderRequest(
            createModel(),
            invalidVariant,
            1024,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
            List.of(invalidTool),
            ProviderCacheControl.none());

    RecordingHandler handler = new RecordingHandler();
    ProviderStream stream = provider.stream(invalidRequest, handler);

    assertNotNull(stream);
    assertNotNull(handler.error);
    assertEquals(ProviderErrorKind.INVALID_REQUEST, handler.error.kind());
  }

  /** 验证 apiKey 包含回车换行等非法字符时，Header 构建失败被安全捕获为 INVALID_REQUEST 且异常消息不泄露敏感信息。 */
  @Test
  void test_maliciousHeaderRejection() {
    String sensitiveMaliciousKey = "sk-secret-key\r\nInjected-Header: malicious";
    OpenAiResponsesProviderAdapter adapter =
        new OpenAiResponsesProviderAdapter(stubTransport((req, cb) -> {}), sensitiveMaliciousKey);
    ModelProvider provider = adapter.create(createDescriptor());

    RecordingHandler handler = new RecordingHandler();
    ProviderStream stream = provider.stream(createRequest(), handler);

    assertNotNull(stream);
    assertNotNull(handler.error);
    assertEquals(ProviderErrorKind.INVALID_REQUEST, handler.error.kind());
    assertFalse(handler.error.getMessage().contains("sk-secret-key"));
    assertFalse(handler.error.getMessage().contains("malicious"));
  }

  /** 验证 SSE 流事件解析失败（如非法 JSON 数据）时，错误经 onEvent 异常捕获并转发给 handler.onError。 */
  @Test
  void test_sseDecoderFailureInOnEvent() {
    JdkHttpSseTransport transport =
        stubTransport((req, cb) -> cb.onEvent(new ServerSentEvent(null, "this-is-not-valid-json")));

    OpenAiResponsesProviderAdapter adapter = new OpenAiResponsesProviderAdapter(transport, "key");
    ModelProvider provider = adapter.create(createDescriptor());
    RecordingHandler handler = new RecordingHandler();

    ProviderStream stream = provider.stream(createRequest(), handler);

    assertNotNull(stream);
    assertNotNull(handler.error);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, handler.error.kind());
  }

  /** 验证在流未收到 terminal completed 事件即触发 onComplete 时，finish 累积失败安全转入 handler.onError。 */
  @Test
  void test_incompleteCompletionFailureInOnComplete() {
    JdkHttpSseTransport transport =
        stubTransport(
            (req, cb) -> {
              cb.onEvent(
                  new ServerSentEvent(
                      null,
                      "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_premature\"}}"));
              cb.onComplete();
            });

    OpenAiResponsesProviderAdapter adapter = new OpenAiResponsesProviderAdapter(transport, "key");
    ModelProvider provider = adapter.create(createDescriptor());
    RecordingHandler handler = new RecordingHandler();

    ProviderStream stream = provider.stream(createRequest(), handler);

    assertNotNull(stream);
    assertNotNull(handler.error);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, handler.error.kind());
  }

  /** 验证 transport.stream 同步抛出 TransportException 时，安全映射为 ProviderException 并投递给 handler.onError。 */
  @Test
  void test_synchronousTransportExceptionMapping() {
    JdkHttpSseTransport ioTransport =
        stubTransport(
            (req, cb) -> {
              throw new TransportException(TransportErrorKind.IO, "synchronous io failed");
            });

    OpenAiResponsesProviderAdapter adapter = new OpenAiResponsesProviderAdapter(ioTransport, "key");
    ModelProvider provider = adapter.create(createDescriptor());
    RecordingHandler handler = new RecordingHandler();

    ProviderStream stream = provider.stream(createRequest(), handler);

    assertNotNull(stream);
    assertNotNull(handler.error);
    assertEquals(ProviderErrorKind.TRANSIENT, handler.error.kind());

    // 同时验证当 TransportErrorKind 为 CANCELLED 时，映射为 null 且不投递错误
    JdkHttpSseTransport cancelledTransport =
        stubTransport(
            (req, cb) -> {
              throw new TransportException(TransportErrorKind.CANCELLED, "cancelled");
            });

    OpenAiResponsesProviderAdapter cancelledAdapter =
        new OpenAiResponsesProviderAdapter(cancelledTransport, "key");
    ModelProvider cancelledProvider = cancelledAdapter.create(createDescriptor());
    RecordingHandler cancelledHandler = new RecordingHandler();

    cancelledProvider.stream(createRequest(), cancelledHandler);
    assertNull(cancelledHandler.error);
  }

  /** 验证 transport.stream 同步抛出未知运行时异常时，不透传敏感堆栈与原因，对外统一抛出脱敏的 RuntimeException。 */
  @Test
  void test_synchronousUnexpectedRuntimeExceptionSanitization() {
    JdkHttpSseTransport failingTransport =
        stubTransport(
            (req, cb) -> {
              throw new IllegalStateException(
                  "fatal crash with sensitive credential token=sk-12345");
            });

    OpenAiResponsesProviderAdapter adapter =
        new OpenAiResponsesProviderAdapter(failingTransport, "key");
    ModelProvider provider = adapter.create(createDescriptor());

    RuntimeException ex =
        assertThrows(
            RuntimeException.class, () -> provider.stream(createRequest(), new RecordingHandler()));

    assertEquals("transport execution failed", ex.getMessage());
    assertNull(ex.getCause());
  }

  /** 验证异步 onFailure 回调中 TransportErrorKind.CANCELLED 时映射为 null，不向 handler 投递错误。 */
  @Test
  void test_asyncTransportCancelledCallbackSilenced() {
    AtomicReference<HttpSseCallback> callbackRef = new AtomicReference<>();
    JdkHttpSseTransport transport = stubTransport((req, cb) -> callbackRef.set(cb));

    OpenAiResponsesProviderAdapter adapter = new OpenAiResponsesProviderAdapter(transport, "key");
    ModelProvider provider = adapter.create(createDescriptor());
    RecordingHandler handler = new RecordingHandler();

    provider.stream(createRequest(), handler);

    assertNotNull(callbackRef.get());
    callbackRef.get().onFailure(new TransportException(TransportErrorKind.CANCELLED, "cancelled"));
    assertNull(handler.error);
  }
}
