package fun.fengwk.kkstudio.harness.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.provider.transport.HttpSseCallback;
import fun.fengwk.kkstudio.harness.provider.transport.HttpSseLimits;
import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.provider.transport.ServerSentEvent;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 验证 transport 每一条 SSE 帧的原生 exactly-once 回调：事件名推导、回调顺序、cancel/terminal 后的静默，以及原生 payload 不进入
 * normalized 可观测面。
 */
class AnthropicProtocolEventTest {

  private static final String MESSAGES_URI = "https://api.anthropic.com/v1/messages";

  private HttpClient client;
  private ExecutorService exec;
  private ScheduledExecutorService sched;
  private ProviderDescriptor descriptor;
  private ProviderRequest request;

  @BeforeEach
  void setUp() {
    descriptor =
        new ProviderDescriptor(
            "anthropic-protocol-event",
            ProviderType.ANTHROPIC,
            "https://api.anthropic.com/v1",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(5), Duration.ofSeconds(3)),
            UUID.randomUUID());
    ModelDescriptor model =
        new ModelDescriptor(
            "anthropic-protocol-event",
            "claude-3-5-sonnet",
            "claude-3-5-sonnet",
            Set.of(ModelInputModality.TEXT),
            true,
            false,
            new ModelPricing(
                "USD",
                "tier-1",
                "default",
                BigDecimal.ONE,
                "v1",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO));
    request =
        new ProviderRequest(
            model,
            new ModelVariant("default"),
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("hi")))),
            List.of(),
            ProviderCacheControl.none());
    client = HttpClient.newHttpClient();
    exec = Executors.newSingleThreadExecutor();
    sched = Executors.newSingleThreadScheduledExecutor();
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

  /** 测试意图：每条帧恰好一次原生回调且先于同帧 normalized/error 回调，terminal 后静默，原生 payload 不进入 normalized 可观测面。 */
  @Test
  void emitsExactlyOneProtocolEventPerFrameBeforeNormalizedCallbacks() {
    RecordingHandler handler = new RecordingHandler();
    AtomicReference<HttpSseCallback> callbackRef = new AtomicReference<>();
    stream(handler, callbackRef);
    HttpSseCallback callback = callbackRef.get();
    assertNotNull(callback);

    callback.onEvent(
        new ServerSentEvent(
            "message_start",
            "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":7}}}"));
    callback.onEvent(new ServerSentEvent("ping", "{\"type\":\"ping\"}"));
    callback.onEvent(
        new ServerSentEvent(
            "content_block_start",
            "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"));
    callback.onEvent(
        new ServerSentEvent(
            "content_block_delta",
            "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}"));
    callback.onEvent(
        new ServerSentEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"));
    callback.onEvent(
        new ServerSentEvent(
            "future_event", "{\"type\":\"future_event\",\"probe\":\"opaque-marker\"}"));
    callback.onEvent(
        new ServerSentEvent(null, "{\"type\":\"extra_unknown\",\"probe\":\"opaque-marker\"}"));
    callback.onEvent(new ServerSentEvent(null, "[DONE]"));
    callback.onEvent(new ServerSentEvent(null, ""));
    callback.onEvent(
        new ServerSentEvent(
            "message_delta",
            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":3}}"));
    callback.onEvent(new ServerSentEvent("message_stop", "{\"type\":\"message_stop\"}"));
    callback.onComplete();

    assertEquals(
        List.of(
            "message_start",
            "ping",
            "content_block_start",
            "content_block_delta",
            "content_block_stop",
            "future_event",
            "extra_unknown",
            "done",
            "anthropic.message.event",
            "message_delta",
            "message_stop"),
        handler.protocolEvents.stream().map(ProviderProtocolEvent::eventType).toList());

    // 同帧内原生回调必须先于 normalized 回调
    int protocolIndex = handler.ordered.indexOf("protocol:content_block_delta");
    int normalizedIndex = handler.ordered.indexOf("event:TextDelta");
    assertFalse(protocolIndex < 0 || normalizedIndex < 0, handler.ordered.toString());
    assertFalse(normalizedIndex < protocolIndex, handler.ordered.toString());

    // blank data 与 [DONE] 原样保留
    assertEquals("", handler.protocolEvents.get(8).data());
    assertEquals("[DONE]", handler.protocolEvents.get(7).data());

    // normalized 语义不变
    assertNotNull(handler.completion.get());
    assertEquals("Hi", handler.completion.get().response().text());

    // 原生 payload 不进入 normalized event、ProviderResponse 或事件 toString
    assertFalse(handler.protocolEvents.get(5).toString().contains("opaque-marker"));
    assertFalse(handler.normalizedEvents.toString().contains("opaque-marker"));
    assertFalse(handler.completion.get().response().toString().contains("opaque-marker"));

    // terminal（onComplete）之后不再派发任何原生回调
    int afterComplete = handler.protocolEvents.size();
    callback.onEvent(new ServerSentEvent("late_event", "{\"type\":\"late_event\"}"));
    callback.onComplete();
    assertEquals(afterComplete, handler.protocolEvents.size());
  }

  /** 测试意图：error 帧先原生回调再按现有语义映射为终止错误，终止后不再回调。 */
  @Test
  void emitsProtocolEventBeforeTerminalErrorAndStaysSilentAfterwards() {
    RecordingHandler handler = new RecordingHandler();
    AtomicReference<HttpSseCallback> callbackRef = new AtomicReference<>();
    stream(handler, callbackRef);
    HttpSseCallback callback = callbackRef.get();

    callback.onEvent(
        new ServerSentEvent(
            "message_start", "{\"type\":\"message_start\",\"message\":{\"usage\":{}}}"));
    callback.onEvent(
        new ServerSentEvent(
            "error",
            "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"boom\"}}"));

    // 原生回调先于 error 终态，且每条帧恰好一次
    assertEquals(List.of("protocol:message_start", "protocol:error", "error"), handler.ordered);
    assertNotNull(handler.error.get());
    assertEquals(ProviderErrorKind.TRANSIENT, handler.error.get().kind());

    callback.onEvent(new ServerSentEvent("message_stop", "{\"type\":\"message_stop\"}"));
    assertEquals(2, handler.protocolEvents.size());
  }

  /** 测试意图：cancel 之后不得再派发任何原生或 normalized 回调。 */
  @Test
  void stopsEmittingProtocolEventsAfterCancel() {
    RecordingHandler handler = new RecordingHandler();
    AtomicReference<HttpSseCallback> callbackRef = new AtomicReference<>();
    ProviderStream stream = stream(handler, callbackRef);

    stream.cancel();
    callbackRef
        .get()
        .onEvent(
            new ServerSentEvent(
                "message_start", "{\"type\":\"message_start\",\"message\":{\"usage\":{}}}"));
    callbackRef.get().onComplete();

    assertEquals(List.of(), handler.protocolEvents);
    assertEquals(List.of(), handler.ordered);
  }

  /** 真实 transport 端到端：每个解析出的 SSE 帧恰好一次原生回调，comment/无 data 帧不产生回调。 */
  /** 测试意图：真实 transport 下每个解析出的 SSE 帧恰好一次原生回调，comment 与无 data 帧不产生回调。 */
  @Test
  void deliversExactlyOneProtocolEventPerTransportFrame() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/messages", exchange -> respondSse(exchange, SSE_BODY));
    server.start();
    try {
      ProviderDescriptor localDescriptor =
          new ProviderDescriptor(
              "anthropic-protocol-event",
              ProviderType.ANTHROPIC,
              "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
              new ModelCallTimeoutPolicy(Duration.ofSeconds(5), Duration.ofSeconds(3)),
              UUID.randomUUID());
      RecordingHandler handler = new RecordingHandler();
      JdkHttpSseTransport transport = new JdkHttpSseTransport(client, exec, sched);
      AnthropicProviderAdapter adapter = new AnthropicProviderAdapter(transport, "key");
      adapter.create(localDescriptor).stream(request, handler);

      assertTrue(handler.await(5, TimeUnit.SECONDS), "stream must terminate");
      assertEquals(
          List.of(
              "message_start",
              "ping",
              "content_block_start",
              "content_block_stop",
              "future_event",
              "done",
              "anthropic.message.event",
              "message_delta",
              "message_stop"),
          handler.protocolEvents.stream().map(ProviderProtocolEvent::eventType).toList());
      assertNotNull(handler.completion.get());
      assertEquals("Hi", handler.completion.get().response().text());
    } finally {
      server.stop(0);
    }
  }

  private static void respondSse(HttpExchange exchange, String body) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
    exchange.sendResponseHeaders(200, 0);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(body.getBytes(StandardCharsets.UTF_8));
      os.flush();
    }
  }

  /** 线缆形态：comment 行、无 data 帧不产生原生事件，空 data 帧与 [DONE] 哨兵必须原样回调。 */
  private static final String SSE_BODY =
      """
      : keep-alive

      event: message_start
      data: {"type":"message_start","message":{"usage":{"input_tokens":1}}}

      event: ping
      data: {"type":"ping"}

      event: content_block_start
      data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":"Hi"}}

      event: content_block_stop
      data: {"type":"content_block_stop","index":0}

      event: future_event
      data: {"type":"future_event","probe":"opaque-marker"}

      data: [DONE]

      data:

      event: message_delta
      data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}

      event: message_stop
      data: {"type":"message_stop"}

      """;

  /** 事件名推导：非空 SSE name 优先，其次 [DONE] 哨兵，其次 JSON type，最后稳定默认名；payload 始终原样保留。 */
  /** 测试意图：事件名推导优先级（SSE name → [DONE] → JSON type → 稳定默认名）并原样保留 payload。 */
  @Test
  void derivesProtocolEventNameAndKeepsPayloadVerbatim() {
    assertEquals("message_start", protocolEvent("message_start", "{}").eventType());
    assertEquals("ping", protocolEvent("ping", "[DONE]").eventType());
    assertEquals("done", protocolEvent(null, "[DONE]").eventType());
    assertEquals("done", protocolEvent("  ", " [DONE] ").eventType());
    assertEquals(
        "content_block_delta",
        protocolEvent(null, "{\"type\":\"content_block_delta\",\"index\":0}").eventType());
    assertEquals("anthropic.message.event", protocolEvent(null, "not-json").eventType());
    assertEquals("anthropic.message.event", protocolEvent(null, "{\"foo\":1}").eventType());
    assertEquals("anthropic.message.event", protocolEvent(null, "{\"type\":\"  \"}").eventType());
    assertEquals("anthropic.message.event", protocolEvent(null, "").eventType());
    assertEquals("", protocolEvent(null, "").data());
    assertEquals("[DONE]", protocolEvent(null, "[DONE]").data());
  }

  private static ProviderProtocolEvent protocolEvent(String eventName, String data) {
    return AnthropicModelProvider.toProtocolEvent(new ServerSentEvent(eventName, data));
  }

  /** 以捕获 callback 的假 transport 启动请求；调用方通过返回的 stream 与 callback 驱动帧与终态。 */
  private ProviderStream stream(
      RecordingHandler handler, AtomicReference<HttpSseCallback> callbackRef) {
    JdkHttpSseTransport transport =
        new JdkHttpSseTransport(client, exec, sched) {
          @Override
          public ProviderStream stream(
              HttpRequest httpRequest,
              ModelCallTimeoutPolicy timeoutPolicy,
              HttpSseLimits limits,
              HttpSseCallback callback) {
            callbackRef.set(callback);
            return new ProviderStream() {
              @Override
              public void cancel() {}

              @Override
              public boolean isCancelled() {
                return false;
              }
            };
          }
        };
    AnthropicModelProvider provider =
        new AnthropicModelProvider(transport, descriptor, "key", URI.create(MESSAGES_URI));
    return provider.stream(request, handler);
  }

  private static final class RecordingHandler implements ProviderStreamHandler {

    final List<ProviderProtocolEvent> protocolEvents =
        Collections.synchronizedList(new ArrayList<>());
    final List<ProviderStreamEvent> normalizedEvents =
        Collections.synchronizedList(new ArrayList<>());
    final List<String> ordered = Collections.synchronizedList(new ArrayList<>());
    final AtomicReference<ProviderCompletion> completion = new AtomicReference<>();
    final AtomicReference<ProviderException> error = new AtomicReference<>();
    private final CountDownLatch terminal = new CountDownLatch(1);

    boolean await(long timeout, TimeUnit unit) throws InterruptedException {
      return terminal.await(timeout, unit);
    }

    @Override
    public void onProtocolEvent(ProviderProtocolEvent event, ProviderStream stream) {
      protocolEvents.add(event);
      ordered.add("protocol:" + event.eventType());
    }

    @Override
    public void onEvent(ProviderStreamEvent event, ProviderStream stream) {
      normalizedEvents.add(event);
      ordered.add("event:" + event.getClass().getSimpleName());
    }

    @Override
    public void onComplete(ProviderCompletion completion, ProviderStream stream) {
      this.completion.set(completion);
      ordered.add("complete");
      terminal.countDown();
    }

    @Override
    public void onError(ProviderException error, ProviderStream stream) {
      this.error.set(error);
      ordered.add("error");
      terminal.countDown();
    }
  }
}
