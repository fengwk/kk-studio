package fun.fengwk.kkstudio.harness.provider.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.provider.transport.HttpSseLimits;
import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.provider.transport.ServerSentEvent;
import fun.fengwk.kkstudio.harness.provider.transport.TransportErrorKind;
import fun.fengwk.kkstudio.harness.provider.transport.TransportException;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderProtocolEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/** 验证 OpenAI Responses ModelProvider 端到端请求装配、鉴权注入与流生命周期交互。 */
class OpenAiResponsesModelProviderTest {

  @FunctionalInterface
  private interface TransportAction {
    void execute(HttpRequest request, Frames frames) throws Exception;
  }

  /** 在调用线程上逐帧驱动适配器：onEvent 交付一帧，onFailure 以传输失败结束，onComplete 无动作（返回即流结束）。 */
  private record Frames(Predicate<ServerSentEvent> sink) {
    void onEvent(ServerSentEvent event) {
      sink.test(event);
    }

    void onComplete() {}

    void onFailure(TransportException error) {
      throw error;
    }
  }

  private static final class RecordingHandler implements ProviderStreamHandler {
    final List<ProviderStreamEvent> events = new ArrayList<>();
    final List<ProviderProtocolEvent> protocolEvents = new ArrayList<>();

    /** 回调到达的全局顺序，用于验证「raw 先于异常」这类跨通道顺序约束。 */
    final List<String> callbackOrder = new ArrayList<>();

    ProviderCompletion completion;
    ProviderException error;
    int errorCount;

    @Override
    public void onEvent(ProviderStreamEvent event) {
      events.add(event);
    }

    @Override
    public void onProtocolEvent(ProviderProtocolEvent event) {
      protocolEvents.add(event);
      callbackOrder.add("protocol:" + event.eventType());
    }

    @Override
    public void onComplete(ProviderCompletion completion) {
      this.completion = completion;
      callbackOrder.add("complete");
    }

    @Override
    public void onError(ProviderException error) {
      this.error = error;
      errorCount++;
      callbackOrder.add("error");
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
    return new JdkHttpSseTransport(httpClient, scheduler) {
      @Override
      public void execute(
          HttpRequest request,
          ModelCallTimeoutPolicy timeoutPolicy,
          HttpSseLimits limits,
          Predicate<ServerSentEvent> onEvent) {
        try {
          action.execute(request, new Frames(onEvent));
        } catch (RuntimeException exception) {
          throw exception;
        } catch (Exception exception) {
          throw new RuntimeException(exception);
        }
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
    return new ModelDescriptor(
        "openai_test", "gpt-5.4-mini", "gpt-5.4-mini", Set.of(ModelInputModality.TEXT), true, true);
  }

  private ProviderRequest createRequest() {
    return new ProviderRequest(
        createModel(),
        new ModelVariant("default"),
        1024,
        "Test system instruction.",
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

    ModelProvider provider = adapter.create(createDescriptor());
    provider.stream(createRequest(), new RecordingHandler());
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

  /**
   * 测试意图：每条 transport 帧在进入规范化状态机之前 exactly-once 上报一条原生协议帧。
   *
   * <p>覆盖三层事件名来源（SSE {@code event} 名、payload 的 JSON {@code type}、通用兜底名）、keepalive ping 与 {@code
   * [DONE]} 哨兵的身份归一，并确认 normalized 增量不受原生通道影响。
   */
  @Test
  void test_rawProtocolFramesEmittedExactlyOnceInOrder() {
    String created = "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_raw\"}}";
    String unknownEvent = "{\"type\":\"response.web_search_call.searching\",\"item_id\":\"ws_1\"}";
    String completed = "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_raw\"}}";
    JdkHttpSseTransport transport =
        stubTransport(
            (req, cb) -> {
              cb.onEvent(new ServerSentEvent("response.created", created));
              cb.onEvent(new ServerSentEvent("response.web_search_call.searching", unknownEvent));
              cb.onEvent(new ServerSentEvent(null, unknownEvent));
              cb.onEvent(new ServerSentEvent("ping", ""));
              cb.onEvent(new ServerSentEvent(null, "[DONE]"));
              cb.onEvent(new ServerSentEvent(null, "{\"not_an_event\":true}"));
              cb.onEvent(new ServerSentEvent(null, completed));
              cb.onComplete();
            });

    OpenAiResponsesProviderAdapter adapter = new OpenAiResponsesProviderAdapter(transport, "key");
    ModelProvider provider = adapter.create(createDescriptor());
    RecordingHandler handler = new RecordingHandler();

    provider.stream(createRequest(), handler);

    // 7 条 transport 帧 -> exactly-once 的 7 条原生帧回调，顺序与事件名解析规则一致
    assertEquals(
        List.of(
            "response.created",
            "response.web_search_call.searching",
            "response.web_search_call.searching",
            "ping",
            "done",
            "openai.response.event",
            "response.completed"),
        handler.protocolEvents.stream().map(ProviderProtocolEvent::eventType).toList());
    // payload 原样透出：轻量帧不重写、不截断、不反序列化重建
    assertEquals(created, handler.protocolEvents.get(0).data());
    assertEquals(unknownEvent, handler.protocolEvents.get(1).data());
    assertEquals("", handler.protocolEvents.get(3).data());
    assertEquals("[DONE]", handler.protocolEvents.get(4).data());
    assertEquals(completed, handler.protocolEvents.get(6).data());
    // normalized 行为不回归：completed 仍完成，未识别的官方事件仍不产生规范化增量
    assertNotNull(handler.completion);
    assertEquals(GenerationStopReason.COMPLETE, handler.completion.response().stopReason());
    assertTrue(handler.events.isEmpty());
  }

  /** 测试意图：failed 帧必须「先 raw 再异常」，错误通道不吞掉同帧的原生事实。 */
  @Test
  void test_failedFrameRawCallbackPrecedesError() {
    String failed =
        "{\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"server_error\",\"message\":\"boom\"}}}";
    JdkHttpSseTransport transport =
        stubTransport((req, cb) -> cb.onEvent(new ServerSentEvent("response.failed", failed)));

    OpenAiResponsesProviderAdapter adapter = new OpenAiResponsesProviderAdapter(transport, "key");
    ModelProvider provider = adapter.create(createDescriptor());
    RecordingHandler handler = new RecordingHandler();

    provider.stream(createRequest(), handler);

    assertEquals(List.of("protocol:response.failed", "error"), handler.callbackOrder);
    assertEquals(failed, handler.protocolEvents.get(0).data());
    assertNotNull(handler.error);
  }

  /** 测试意图：官方顶层 error 经实际 transport/accumulator 派发；raw 只上报一次且先于错误，后续终态不得完成。 */
  @Test
  void test_topLevelErrorFrameRawCallbackPrecedesErrorAndPreventsCompletion() {
    for (String code : List.of("rate_limit_exceeded", "context_length_exceeded")) {
      String error =
          "{\"type\":\"error\",\"code\":\""
              + code
              + "\",\"message\":\"upstream failed\",\"param\":null,\"sequence_number\":7}";
      JdkHttpSseTransport transport =
          stubTransport(
              (req, cb) -> {
                cb.onEvent(
                    new ServerSentEvent(
                        "rate_limit_exceeded".equals(code) ? "error" : null, error));
                cb.onEvent(
                    new ServerSentEvent(
                        "response.completed",
                        "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}"));
                cb.onComplete();
              });
      OpenAiResponsesProviderAdapter adapter = new OpenAiResponsesProviderAdapter(transport, "key");
      RecordingHandler handler = new RecordingHandler();

      adapter.create(createDescriptor()).stream(createRequest(), handler);

      assertEquals(List.of("protocol:error", "error"), handler.callbackOrder);
      assertEquals(1, handler.protocolEvents.size());
      assertEquals(error, handler.protocolEvents.get(0).data());
      assertNotNull(handler.error);
      assertEquals(
          "rate_limit_exceeded".equals(code)
              ? ProviderErrorKind.TRANSIENT
              : ProviderErrorKind.OVERFLOW,
          handler.error.kind());
      assertEquals(error, handler.error.getMessage());
      assertNull(handler.completion);
      assertTrue(handler.events.isEmpty());
    }
  }

  /** 测试意图：畸形帧同样先 exactly-once 上报原生帧再进入错误通道，原生观测不因解析失败而丢失。 */
  @Test
  void test_malformedFrameRawCallbackPrecedesError() {
    JdkHttpSseTransport transport =
        stubTransport((req, cb) -> cb.onEvent(new ServerSentEvent(null, "this-is-not-valid-json")));

    OpenAiResponsesProviderAdapter adapter = new OpenAiResponsesProviderAdapter(transport, "key");
    ModelProvider provider = adapter.create(createDescriptor());
    RecordingHandler handler = new RecordingHandler();

    provider.stream(createRequest(), handler);

    assertEquals(List.of("protocol:openai.response.event", "error"), handler.callbackOrder);
    assertEquals("this-is-not-valid-json", handler.protocolEvents.get(0).data());
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, handler.error.kind());
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

    // 2. 构造器支持并严格校验 configJson
    OpenAiResponsesProviderAdapter adapterWithJson =
        new OpenAiResponsesProviderAdapter(transport, "key", "{\"promptCacheRetention\":\"LONG\"}");
    assertNotNull(adapterWithJson);

    // 3. malformed configJson 在构造期确定性失败
    assertThrows(
        IllegalArgumentException.class,
        () -> new OpenAiResponsesProviderAdapter(transport, "key", "{invalid json"));

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
        PromptCacheRetention.LONG,
        OpenAiResponsesProviderAdapter.resolvePromptCacheRetention(
            "{\"promptCacheRetention\":\"LONG\"}"));
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
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
            List.of(invalidTool),
            ProviderCacheControl.none());

    RecordingHandler handler = new RecordingHandler();
    provider.stream(invalidRequest, handler);
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
    provider.stream(createRequest(), handler);
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

    provider.stream(createRequest(), handler);
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

    provider.stream(createRequest(), handler);
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

    provider.stream(createRequest(), handler);
    assertNotNull(handler.error);
    assertEquals(ProviderErrorKind.TRANSIENT, handler.error.kind());
    assertEquals("OpenAI Responses I/O error", handler.error.getMessage());

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

  /** 验证传输抛出的未分类运行时异常原样传播（由上层按不确定终态收敛且只记录异常类型）。 */
  @Test
  void test_unclassifiedTransportExceptionPropagates() {
    IllegalStateException crash = new IllegalStateException("fatal crash");
    ModelProvider provider =
        new OpenAiResponsesProviderAdapter(
                stubTransport(
                    (req, frames) -> {
                      throw crash;
                    }),
                "key")
            .create(createDescriptor());

    assertSame(
        crash,
        assertThrows(
            IllegalStateException.class,
            () -> provider.stream(createRequest(), new RecordingHandler())));
  }

  /** 测试意图：用户 handler 抛错后流已封口：异常传播给调用方，之后的传输失败不再回调 onError。 */
  @Test
  void test_handlerFailureIsTerminalAndPropagates() {
    ModelProvider provider =
        new OpenAiResponsesProviderAdapter(
                stubTransport(
                    (req, frames) -> {
                      frames.onEvent(
                          new ServerSentEvent(
                              "response.output_text.delta",
                              "{\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}"));
                      frames.onFailure(new TransportException(TransportErrorKind.IO, "late"));
                    }),
                "key")
            .create(createDescriptor());
    AtomicReference<Integer> errors = new AtomicReference<>(0);

    assertThrows(
        IllegalStateException.class,
        () ->
            provider.stream(
                createRequest(),
                new ProviderStreamHandler() {
                  @Override
                  public void onEvent(ProviderStreamEvent event) {
                    throw new IllegalStateException("user handler failed");
                  }

                  @Override
                  public void onComplete(ProviderCompletion completion) {}

                  @Override
                  public void onError(ProviderException error) {
                    errors.set(errors.get() + 1);
                  }
                }));
    assertEquals(0, errors.get());
  }
}
