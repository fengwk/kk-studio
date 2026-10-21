package fun.fengwk.kkstudio.harness.provider.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.provider.ScriptedSseTransport;
import fun.fengwk.kkstudio.harness.provider.transport.HttpSseLimits;
import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.provider.transport.ServerSentEvent;
import fun.fengwk.kkstudio.harness.provider.transport.TransportErrorKind;
import fun.fengwk.kkstudio.harness.provider.transport.TransportException;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderProtocolEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.net.URI;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/** GeminiModelProvider 生命周期调度与传输层交互单元测试。 */
class GeminiModelProviderUnitTest {

  private ProviderDescriptor descriptor;
  private ProviderRequest request;
  private HttpClient client;
  private ExecutorService exec;
  private ScheduledExecutorService sched;

  @BeforeEach
  void setUp() {
    descriptor =
        new ProviderDescriptor(
            "google-unit",
            ProviderType.GOOGLE,
            "https://generativelanguage.googleapis.com",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(5), Duration.ofSeconds(3)),
            UUID.randomUUID());

    ModelDescriptor model =
        new ModelDescriptor(
            "google-unit",
            "gemini-2.5-flash",
            "gemini-2.5-flash",
            Set.of(ModelInputModality.TEXT),
            true,
            false);

    ModelVariant variant = new ModelVariant("default");
    request =
        new ProviderRequest(
            model,
            variant,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hello")))),
            List.of(),
            ProviderCacheControl.none());

    exec = Executors.newSingleThreadExecutor();
    sched = Executors.newSingleThreadScheduledExecutor();
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.shutdownNow();
    }
    if (exec != null) {
      exec.shutdownNow();
    }
    if (sched != null) {
      sched.shutdownNow();
    }
  }

  /** 验证 descriptor() 与 toString() 行为。 */
  @Test
  void verifiesDescriptorAndToString() {
    JdkHttpSseTransport transport = new JdkHttpSseTransport(client, sched);
    GeminiModelProvider provider = new GeminiModelProvider(transport, descriptor, "k");

    assertEquals(descriptor, provider.descriptor());
    assertEquals("GeminiModelProvider[]", provider.toString());
  }

  /** 验证非法 Header（如含换行符的 API Key）时抛出 INVALID_REQUEST。 */
  @Test
  void handlesInvalidHeaderValue() {
    JdkHttpSseTransport transport = new JdkHttpSseTransport(client, sched);
    GeminiModelProvider provider = new GeminiModelProvider(transport, descriptor, "bad\nkey");

    AtomicReference<ProviderException> errorRef = new AtomicReference<>();
    provider.stream(
        request,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event) {}

          @Override
          public void onError(ProviderException error) {
            errorRef.set(error);
          }

          @Override
          public void onComplete(ProviderCompletion completion) {}
        });

    assertNotNull(errorRef.get());
    assertEquals(ProviderErrorKind.INVALID_REQUEST, errorRef.get().kind());
  }

  /** 验证非法 endpoint 导致 resolveStreamUri 失败时抛出 INVALID_REQUEST。 */
  @Test
  void handlesInvalidEndpointStreamUriFailure() {
    JdkHttpSseTransport transport = new JdkHttpSseTransport(client, sched);
    ProviderDescriptor badEndpointDesc =
        new ProviderDescriptor(
            "bad-desc",
            ProviderType.GOOGLE,
            "https://generativelanguage.googleapis.com?query=not_allowed",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(5), Duration.ofSeconds(3)),
            UUID.randomUUID());

    GeminiModelProvider provider = new GeminiModelProvider(transport, badEndpointDesc, "k");

    AtomicReference<ProviderException> errorRef = new AtomicReference<>();
    provider.stream(
        request,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event) {}

          @Override
          public void onError(ProviderException error) {
            errorRef.set(error);
          }

          @Override
          public void onComplete(ProviderCompletion completion) {}
        });

    assertNotNull(errorRef.get());
    assertEquals(ProviderErrorKind.INVALID_REQUEST, errorRef.get().kind());
  }

  /**
   * 测试意图：普通用户媒体 data URI 不合规（无 base64 标记的 percent/普通 data、非法 Base64、空载荷）时，编码器在发起 HTTP 之前就以一次
   * INVALID_REQUEST 终态失败，transport 完全不被调用。
   */
  @Test
  void rejectsInvalidMediaDataUriBeforeTransport() {
    AtomicInteger streamCalls = new AtomicInteger(0);
    JdkHttpSseTransport countingTransport =
        new JdkHttpSseTransport(client, sched) {
          @Override
          public void execute(
              HttpRequest req,
              ModelCallTimeoutPolicy timeoutPolicy,
              HttpSseLimits limits,
              Predicate<ServerSentEvent> onEvent) {
            streamCalls.incrementAndGet();
          }
        };
    GeminiModelProvider provider = new GeminiModelProvider(countingTransport, descriptor, "k");

    for (String source :
        List.of("data:image/png,abc", "data:image/png;base64,%%%", "data:image/png;base64,")) {
      ProviderRequest mediaRequest =
          new ProviderRequest(
              request.model(),
              request.variant(),
              1024,
              request.systemInstruction(),
              List.of(
                  new ProviderMessage(
                      ProviderMessageRole.USER,
                      List.of(new ProviderImageBlock("image/png", source)))),
              List.of(),
              ProviderCacheControl.none());

      AtomicInteger errors = new AtomicInteger(0);
      AtomicReference<ProviderException> errorRef = new AtomicReference<>();
      provider.stream(
          mediaRequest,
          new ProviderStreamHandler() {
            @Override
            public void onEvent(ProviderStreamEvent event) {}

            @Override
            public void onError(ProviderException error) {
              errors.incrementAndGet();
              errorRef.set(error);
            }

            @Override
            public void onComplete(ProviderCompletion completion) {}
          });

      assertEquals(1, errors.get());
      assertNotNull(errorRef.get());
      assertEquals(ProviderErrorKind.INVALID_REQUEST, errorRef.get().kind());
    }
    assertEquals(0, streamCalls.get());
  }

  /**
   * 验证带有凭据时设置 x-goog-api-key header，并且空凭据时以匿名方式请求（不添加 header）；请求 URL 完全由 descriptor endpoint 与
   * model id 解析，构造参数不再携带 URL。
   */
  @Test
  void passesApiKeyHeaderWhenCredentialProvided() {
    URI targetUri =
        URI.create(
            "https://generativelanguage.googleapis.com/models/gemini-2.5-flash:streamGenerateContent?alt=sse");
    ScriptedSseTransport keyed = ScriptedSseTransport.events();
    new GeminiModelProvider(keyed, descriptor, "test-api-key-123").stream(request, new Recording());
    HttpRequest sent = keyed.requests().get(0);
    assertEquals("test-api-key-123", sent.headers().firstValue("x-goog-api-key").orElse(null));
    assertEquals(targetUri, sent.uri());

    ScriptedSseTransport anonymous = ScriptedSseTransport.events();
    new GeminiModelProvider(anonymous, descriptor, null).stream(request, new Recording());
    assertTrue(anonymous.requests().get(0).headers().firstValue("x-goog-api-key").isEmpty());
  }

  /** 验证传输失败（发送阶段或读到一半）按 TRANSIENT 恰好交付一次 onError。 */
  @Test
  void transportFailureMapsToExactlyOneTerminalError() {
    for (ScriptedSseTransport transport :
        List.of(
            ScriptedSseTransport.failing(new TransportException(TransportErrorKind.IO, "io")),
            ScriptedSseTransport.failing(
                new TransportException(TransportErrorKind.IO, "reset"),
                new ServerSentEvent(null, CHUNK_HI)))) {
      Recording recording = new Recording();
      new GeminiModelProvider(transport, descriptor, "k").stream(request, recording);
      assertEquals(1, recording.errors.size());
      assertEquals(ProviderErrorKind.TRANSIENT, recording.errors.get(0).kind());
      assertNull(recording.completion);
    }
  }

  /** 验证端到端接收 chunk、完成响应交付。 */
  @Test
  void handlesSuccessfulStreamEventsAndCompletion() {
    Recording recording = new Recording();
    new GeminiModelProvider(
            ScriptedSseTransport.events(
                new ServerSentEvent(
                    "message",
                    "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi back\"}]},\"finishReason\":\"STOP\"}]}")),
            descriptor,
            "k")
        .stream(request, recording);

    assertEquals("hi back", recording.completion.response().text());
    assertEquals(GenerationStopReason.COMPLETE, recording.completion.response().stopReason());
  }

  /** 验证传输抛出的未分类运行时异常原样传播，由上层按不确定终态收敛。 */
  @Test
  void unclassifiedTransportExceptionPropagates() {
    IllegalStateException crash = new IllegalStateException("stream creation failed");
    JdkHttpSseTransport crashing =
        new JdkHttpSseTransport(client, sched) {
          @Override
          public void execute(
              HttpRequest req,
              ModelCallTimeoutPolicy timeout,
              HttpSseLimits limits,
              Predicate<ServerSentEvent> onEvent) {
            throw crash;
          }
        };
    assertSame(
        crash,
        assertThrows(
            IllegalStateException.class,
            () ->
                new GeminiModelProvider(crashing, descriptor, "k")
                    .stream(request, new Recording())));
  }

  /** 测试意图：用户 handler 在原生帧或增量处抛错时流已封口：异常传播给调用方，之后不再进入 onError。 */
  @Test
  void handlerFailureIsTerminalAndPropagates() {
    Recording protocolFailure = new Recording();
    protocolFailure.failOnProtocolEvent = true;
    assertThrows(
        ProviderException.class,
        () ->
            new GeminiModelProvider(
                    ScriptedSseTransport.events(new ServerSentEvent(null, "{\"candidates\":[]}")),
                    descriptor,
                    "k")
                .stream(request, protocolFailure));
    assertTrue(protocolFailure.errors.isEmpty());

    Recording deltaFailure = new Recording();
    deltaFailure.failOnEvent = true;
    assertThrows(
        ProviderException.class,
        () ->
            new GeminiModelProvider(
                    ScriptedSseTransport.events(
                        new ServerSentEvent(
                            null,
                            "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"x\"}]}}]}")),
                    descriptor,
                    "k")
                .stream(request, deltaFailure));
    assertTrue(deltaFailure.errors.isEmpty());
  }

  /** 验证非法 JSON 帧与缺少 finishReason 的流都收敛为 INVALID_RESPONSE。 */
  @Test
  void malformedOrUnfinishedStreamIsInvalidResponse() {
    Recording invalidJson = new Recording();
    new GeminiModelProvider(
            ScriptedSseTransport.events(new ServerSentEvent("message", "invalid-json")),
            descriptor,
            "k")
        .stream(request, invalidJson);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, invalidJson.errors.get(0).kind());

    Recording unfinished = new Recording();
    new GeminiModelProvider(ScriptedSseTransport.events(), descriptor, "k")
        .stream(request, unfinished);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, unfinished.errors.get(0).kind());
  }

  /**
   * 意图：每条 transport SSE frame 都必须恰好交付一次原生回调，且先 raw 再 normalized；eventType 优先 SSE name，
   * 缺省稳定为默认事件名，[DONE] 记为 done；error/blank frame 只要 transport 投递同样保留。
   */
  @Test
  void emitsExactlyOneProtocolEventPerTransportFrameBeforeNormalizedHandling() {
    String chunk2 =
        "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\" back\"}]},\"finishReason\":\"STOP\"}]}";
    String doneFrame = "[DONE]";
    String blankFrame = "   ";
    String errorFrame = "{\"error\":{\"code\":500,\"message\":\"boom\",\"status\":\"INTERNAL\"}}";
    List<String> protocolEventTypes = new ArrayList<>();
    List<String> protocolEventData = new ArrayList<>();
    List<String> deliveryOrder = new ArrayList<>();

    new GeminiModelProvider(
            ScriptedSseTransport.events(
                new ServerSentEvent("message", CHUNK_HI),
                new ServerSentEvent(null, chunk2),
                new ServerSentEvent("", doneFrame),
                new ServerSentEvent(null, blankFrame),
                new ServerSentEvent("error", errorFrame)),
            descriptor,
            "k")
        .stream(
            request,
            new ProviderStreamHandler() {
              @Override
              public void onEvent(ProviderStreamEvent event) {
                deliveryOrder.add("normalized");
              }

              @Override
              public void onProtocolEvent(ProviderProtocolEvent event) {
                deliveryOrder.add("protocol");
                protocolEventTypes.add(event.eventType());
                protocolEventData.add(event.data());
              }

              @Override
              public void onError(ProviderException error) {
                deliveryOrder.add("error");
              }

              @Override
              public void onComplete(ProviderCompletion completion) {}
            });

    // 5 条 transport frame -> 恰好 5 次原生回调，data 原样透传
    assertEquals(
        List.of(
            "message",
            "gemini.generateContent.response",
            "done",
            "gemini.generateContent.response",
            "error"),
        protocolEventTypes);
    assertEquals(List.of(CHUNK_HI, chunk2, doneFrame, blankFrame, errorFrame), protocolEventData);
    // 第一条 frame 的 raw 回调必须先于其规范化增量交付
    assertEquals(
        List.of("protocol", "normalized", "protocol", "normalized"), deliveryOrder.subList(0, 4));
    // error frame 同样先 raw 后 error 终态
    assertEquals(
        List.of("protocol", "error"),
        deliveryOrder.subList(deliveryOrder.size() - 2, deliveryOrder.size()));
  }

  private static final String CHUNK_HI =
      "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}]}}]}";

  /** 记录回调的 handler；可配置在原生帧或增量处抛错以模拟用户 handler 失败。 */
  private static final class Recording implements ProviderStreamHandler {
    final List<ProviderException> errors = new ArrayList<>();
    ProviderCompletion completion;
    boolean failOnProtocolEvent;
    boolean failOnEvent;

    @Override
    public void onProtocolEvent(ProviderProtocolEvent event) {
      if (failOnProtocolEvent) {
        throw new ProviderException(ProviderErrorKind.INVALID_REQUEST, "handler failed");
      }
    }

    @Override
    public void onEvent(ProviderStreamEvent event) {
      if (failOnEvent) {
        throw new ProviderException(ProviderErrorKind.INVALID_REQUEST, "handler failed");
      }
    }

    @Override
    public void onComplete(ProviderCompletion completion) {
      this.completion = completion;
    }

    @Override
    public void onError(ProviderException error) {
      errors.add(error);
    }
  }
}
