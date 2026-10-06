package fun.fengwk.kkstudio.harness.provider;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import fun.fengwk.kkstudio.harness.provider.anthropic.AnthropicProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.gemini.GeminiProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.openai.chat.OpenAiChatProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.openai.responses.OpenAiResponsesProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.transport.HttpSseCallback;
import fun.fengwk.kkstudio.harness.provider.transport.HttpSseLimits;
import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.ProviderProtocolOptions;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 无网络请求体预览（{@link ProviderAdapter#encodeRequestBody}）的逐字节契约。
 *
 * <p>测试意图：把「adapter 直接预览」与「transport 实际发往本地上游的 body」钉在同一份请求上逐字节比对，证明预览复用与正式 {@link
 * ModelProvider#stream} 完全相同的 encoder 与 configuration；请求形态覆盖 native protocolOptions、prompt cache 与
 * assistant replay，因此任何「用默认配置另起一条编码路径」的实现都会在字节层暴露。同时钉住预览的三个安全不变量：不触发 transport、编码失败原样抛出 {@link
 * ProviderException}、不支持的 adapter 显式失败而不是静默返回空预览。
 */
class ProviderRequestBodyPreviewTest {

  private static final String API_KEY = "sk-preview-test-key";
  private static final String TOOL_CALL_ID = "call_preview_1";
  private static final String TOOL_NAME = "preview_tool";
  private static final String REPLAY_TEXT = "replayed answer";

  private HttpServer server;
  private int port;
  private HttpClient client;
  private ExecutorService workers;
  private ScheduledExecutorService scheduler;
  private final AtomicReference<byte[]> capturedBody = new AtomicReference<>();
  private CountDownLatch requestLatch;

  @BeforeEach
  void setUp() throws IOException {
    requestLatch = new CountDownLatch(1);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    port = server.getAddress().getPort();
    server.createContext(
        "/",
        exchange -> {
          byte[] body = exchange.getRequestBody().readAllBytes();
          capturedBody.set(body);
          byte[] response = "data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, response.length);
          try (var out = exchange.getResponseBody()) {
            out.write(response);
          }
          requestLatch.countDown();
        });
    server.start();

    workers = Executors.newCachedThreadPool();
    scheduler = Executors.newSingleThreadScheduledExecutor();
    client =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
    if (client != null) {
      client.shutdownNow();
    }
    if (workers != null) {
      workers.shutdownNow();
    }
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }

  /** 被测协议及其端点解析、原生选项与「配置已生效」证据。 */
  private enum Protocol {
    OPENAI_CHAT(
        "openai-chat",
        "/v1",
        "gpt-4o",
        ProviderType.OPENAI,
        "{\"temperature\":0.2}",
        ProviderReplayFormat.OPENAI_CHAT,
        "prompt_cache_key"),
    OPENAI_RESPONSES(
        "openai-responses",
        "/v1",
        "gpt-5.4-mini",
        ProviderType.OPENAI_RESPONSES,
        "{\"temperature\":0.2}",
        null,
        "prompt_cache_key"),
    ANTHROPIC(
        "anthropic",
        "/v1",
        "claude-3-5-sonnet",
        ProviderType.ANTHROPIC,
        "{\"temperature\":0.2}",
        ProviderReplayFormat.ANTHROPIC_MESSAGES,
        "cache_control"),
    GEMINI(
        "gemini",
        "/v1beta",
        "gemini-2.5-flash",
        ProviderType.GOOGLE,
        "{\"generationConfig\":{\"temperature\":0.2}}",
        null,
        "temperature");

    private final String label;
    private final String basePath;
    private final String wireModelId;
    private final ProviderType providerType;
    private final String nativeOptionsJson;
    private final ProviderReplayFormat replayFormat;

    /** 非默认 configuration 或 native option 真正生效时必须出现在请求体里的片段。 */
    private final String evidenceToken;

    Protocol(
        String label,
        String basePath,
        String wireModelId,
        ProviderType providerType,
        String nativeOptionsJson,
        ProviderReplayFormat replayFormat,
        String evidenceToken) {
      this.label = label;
      this.basePath = basePath;
      this.wireModelId = wireModelId;
      this.providerType = providerType;
      this.nativeOptionsJson = nativeOptionsJson;
      this.replayFormat = replayFormat;
      this.evidenceToken = evidenceToken;
    }
  }

  @ParameterizedTest(name = "{0} 预览 == transport 实际发送体")
  @EnumSource(Protocol.class)
  void previewMatchesTransportSentBody(Protocol protocol) throws Exception {
    ProviderAdapter adapter =
        adapterFor(protocol, new JdkHttpSseTransport(client, workers, scheduler));
    ProviderDescriptor descriptor = descriptorFor(protocol);
    ProviderRequest request = richRequest(protocol);

    byte[] sent = captureSentBody(adapter, descriptor, request);
    byte[] previewed = adapter.encodeRequestBody(request, descriptor);

    assertArrayEquals(
        sent, previewed, "preview must be byte-identical to the sent body: " + protocol);
    // 证据片段确保等价不是「两边都退化为默认配置」：cache hint 与 native option 必须真正进入请求体
    assertTrue(
        new String(sent, StandardCharsets.UTF_8).contains(protocol.evidenceToken),
        "非默认 configuration/native option must reach the wire body: " + protocol);
  }

  @ParameterizedTest(name = "{0} replay 预览 == transport 实际发送体")
  @EnumSource(
      value = Protocol.class,
      names = {"OPENAI_CHAT", "ANTHROPIC"})
  void previewMatchesTransportSentBodyForReplayState(Protocol protocol) throws Exception {
    ProviderAdapter adapter =
        adapterFor(protocol, new JdkHttpSseTransport(client, workers, scheduler));
    ProviderDescriptor descriptor = descriptorFor(protocol);
    ProviderRequest request = replayRequest(protocol, descriptor);

    byte[] sent = captureSentBody(adapter, descriptor, request);
    byte[] previewed = adapter.encodeRequestBody(request, descriptor);

    assertArrayEquals(sent, previewed, "preview must match the replay body: " + protocol);
    assertTrue(
        new String(sent, StandardCharsets.UTF_8).contains(REPLAY_TEXT),
        "replay message must be materialized, never silently dropped: " + protocol);
  }

  /** 预览是无网络入口：任何协议、任何配置都不得触发 transport。 */
  @Test
  @DisplayName("encodeRequestBody 对四协议均零 transport 调用")
  void previewNeverTouchesTransport() {
    AtomicInteger transportCalls = new AtomicInteger();
    JdkHttpSseTransport offline = offlineTransport(transportCalls);

    for (Protocol protocol : Protocol.values()) {
      ProviderAdapter adapter = adapterFor(protocol, offline);
      byte[] previewed = adapter.encodeRequestBody(richRequest(protocol), descriptorFor(protocol));
      assertNotNull(previewed, protocol.toString());
      assertTrue(previewed.length > 0, protocol.toString());
    }
    assertEquals(0, transportCalls.get(), "preview must never reach transport");
  }

  /** 编码失败必须原样传播 INVALID_REQUEST，而不是被预览入口吞掉或改写。 */
  @Test
  @DisplayName("encodeRequestBody 原样传播 encoder 的 INVALID_REQUEST")
  void previewPropagatesEncoderFailureUnchanged() {
    AtomicInteger transportCalls = new AtomicInteger();
    ProviderAdapter adapter = adapterFor(Protocol.OPENAI_CHAT, offlineTransport(transportCalls));
    // chat 的 native options 声明 n=2 与 runtime 所有权冲突，编码器以 INVALID_REQUEST 明确拒绝
    ProviderRequest invalid =
        new ProviderRequest(
            model(Protocol.OPENAI_CHAT),
            new ModelVariant("default", null, new ProviderProtocolOptions("{\"n\":2}")),
            1024,
            "Preview system instruction.",
            List.of(userMessage()),
            List.of(),
            ProviderCacheControl.none());

    ProviderException error =
        assertThrows(
            ProviderException.class,
            () -> adapter.encodeRequestBody(invalid, descriptorFor(Protocol.OPENAI_CHAT)));

    assertEquals(ProviderErrorKind.INVALID_REQUEST, error.kind());
    assertEquals(0, transportCalls.get(), "被拒绝的请求不得触发 transport");
  }

  /** 未提供预览能力的 adapter 必须显式失败，避免调用方把「无预览」误当成「空预览」。 */
  @Test
  @DisplayName("不支持预览的 adapter 显式抛 UnsupportedOperationException")
  void adaptersWithoutPreviewSupportFailExplicitly() {
    ProviderAdapter unsupported =
        new ProviderAdapter() {
          @Override
          public ProviderType providerType() {
            return ProviderType.OPENAI;
          }

          @Override
          public ModelProvider create(ProviderDescriptor descriptor) {
            throw new UnsupportedOperationException("stub adapter does not create providers");
          }
        };

    UnsupportedOperationException error =
        assertThrows(
            UnsupportedOperationException.class,
            () ->
                unsupported.encodeRequestBody(
                    richRequest(Protocol.OPENAI_CHAT), descriptorFor(Protocol.OPENAI_CHAT)));

    assertTrue(error.getMessage().contains("OPENAI"), error.getMessage());
  }

  /** 记录 transport 是否被调用；一旦调用即失败，证明预览路径纯本地。 */
  private JdkHttpSseTransport offlineTransport(AtomicInteger calls) {
    return new JdkHttpSseTransport(client, workers, scheduler) {
      @Override
      public ProviderStream stream(
          HttpRequest request,
          ModelCallTimeoutPolicy policy,
          HttpSseLimits limits,
          HttpSseCallback callback) {
        calls.incrementAndGet();
        throw new AssertionError("preview must not call transport");
      }
    };
  }

  private byte[] captureSentBody(
      ProviderAdapter adapter, ProviderDescriptor descriptor, ProviderRequest request)
      throws InterruptedException {
    capturedBody.set(null);
    requestLatch = new CountDownLatch(1);
    adapter.create(descriptor).stream(request, noopHandler());
    assertTrue(requestLatch.await(10, TimeUnit.SECONDS), "request must reach the local upstream");
    byte[] body = capturedBody.get();
    assertNotNull(body, "upstream must capture the request body");
    return body;
  }

  private ProviderAdapter adapterFor(Protocol protocol, JdkHttpSseTransport transport) {
    return switch (protocol) {
      case OPENAI_CHAT -> new OpenAiChatProviderAdapter(
          transport, API_KEY, "{\"promptCacheRetention\":\"SHORT\"}");
      case OPENAI_RESPONSES -> new OpenAiResponsesProviderAdapter(
          transport, API_KEY, "{\"promptCacheRetention\":\"SHORT\"}");
      case ANTHROPIC -> new AnthropicProviderAdapter(transport, API_KEY);
      case GEMINI -> new GeminiProviderAdapter(transport, API_KEY);
    };
  }

  private ProviderDescriptor descriptorFor(Protocol protocol) {
    return new ProviderDescriptor(
        protocol.label,
        protocol.providerType,
        "http://127.0.0.1:" + port + protocol.basePath,
        new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)));
  }

  private static ModelDescriptor model(Protocol protocol) {
    return new ModelDescriptor(
        protocol.label,
        protocol.wireModelId,
        protocol.wireModelId,
        Set.of(ModelInputModality.TEXT),
        true,
        false);
  }

  private static ModelVariant variant(Protocol protocol) {
    return new ModelVariant(
        "default", null, new ProviderProtocolOptions(protocol.nativeOptionsJson));
  }

  private static ProviderMessage userMessage() {
    return new ProviderMessage(
        ProviderMessageRole.USER, List.of(new ProviderTextBlock("run the tool")));
  }

  private static ProviderMessage toolCallMessage() {
    return new ProviderMessage(
        ProviderMessageRole.ASSISTANT,
        List.of(
            new ProviderToolCallBlock(
                new ProviderToolCall(TOOL_CALL_ID, TOOL_NAME, "{\"query\":\"42\"}"))));
  }

  private static ProviderMessage toolResultMessage() {
    return new ProviderMessage(
        ProviderMessageRole.TOOL,
        List.of(
            new ProviderToolResultBlock(
                TOOL_CALL_ID, TOOL_NAME, List.of(new ProviderTextBlock("42")), false, "{}")));
  }

  /** native options + prompt cache + 工具往返：覆盖三种厂商原生事实的合并与打标。 */
  private static ProviderRequest richRequest(Protocol protocol) {
    return new ProviderRequest(
        model(protocol),
        variant(protocol),
        1024,
        "Preview system instruction.",
        List.of(userMessage(), toolCallMessage(), toolResultMessage()),
        List.of(),
        cacheControl(protocol));
  }

  /** 兼容的 durable replay 与正式 transport 必须使用相同的编码结果。 */
  private static ProviderRequest replayRequest(Protocol protocol, ProviderDescriptor descriptor) {
    ModelDescriptor model = model(protocol);
    ProviderReplayState replayState =
        new ProviderReplayState(
            protocol.replayFormat, descriptor.affinity(model.modelId()), replayPayload(protocol));
    ProviderMessage assistant =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderTextBlock(REPLAY_TEXT)),
            replayState);
    return new ProviderRequest(
        model,
        variant(protocol),
        1024,
        "Preview system instruction.",
        List.of(userMessage(), assistant),
        List.of(),
        cacheControl(protocol));
  }

  private static JsonNode replayPayload(Protocol protocol) {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("role", "assistant");
    if (protocol.replayFormat == ProviderReplayFormat.ANTHROPIC_MESSAGES) {
      ArrayNode content = payload.putArray("content");
      ObjectNode text = content.addObject();
      text.put("type", "text");
      text.put("text", REPLAY_TEXT);
    } else {
      payload.put("content", REPLAY_TEXT);
    }
    return payload;
  }

  /** Gemini 只支持隐式缓存，显式 cacheControl 会被编码器拒绝，因此只在该协议退化为 NONE。 */
  private static ProviderCacheControl cacheControl(Protocol protocol) {
    return switch (protocol) {
      case OPENAI_CHAT, OPENAI_RESPONSES, ANTHROPIC -> ProviderCacheControl.session(
          PromptCacheRetention.SHORT, "00000000-0000-0000-0000-000000000001");
      case GEMINI -> ProviderCacheControl.none();
    };
  }

  private static ProviderStreamHandler noopHandler() {
    return new ProviderStreamHandler() {
      @Override
      public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

      @Override
      public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

      @Override
      public void onError(ProviderException error, ProviderStream stream) {}
    };
  }
}
