package fun.fengwk.kkstudio.harness.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import fun.fengwk.kkstudio.harness.provider.transport.HttpOpenMetadata;
import fun.fengwk.kkstudio.harness.provider.transport.HttpSseCallback;
import fun.fengwk.kkstudio.harness.provider.transport.HttpSseLimits;
import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.provider.transport.ServerSentEvent;
import fun.fengwk.kkstudio.harness.provider.transport.TransportException;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderContentBlock;
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

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 显式付费门禁的历史端点 A/B 测量；仅访问 TEST_ANTHROPIC 凭据指向的线路，不重试。
 *
 * <p>两个独立 nonce session 各两次请求，避免 OLD 增量预热 NEW。stdout 只含固定分类、wire model、 marker 索引与数字
 * usage，可由运行者重定向到仓库外临时 artifact。缓存命中是观测而非协议保证。
 */
class AnthropicHistoryCacheLiveProbeTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String MODEL = "claude-fable-5-dd-3M-xaMiniM";
  private static final String MARKER = ",\"cache_control\":{\"type\":\"ephemeral\"}";
  private static final AnthropicConfiguration CONFIG =
      new AnthropicConfiguration(AnthropicThinkingMode.BUDGET);
  private static final ModelCallTimeoutPolicy TIMEOUT =
      new ModelCallTimeoutPolicy(Duration.ofSeconds(120), Duration.ofSeconds(120));

  // 免费验证真实 encoder 的历史端点、20-block 窗口之外的增量及字节级 OLD 对照。
  @Test
  void offlineShape() throws Exception {
    ProviderDescriptor descriptor = descriptor("https://example.invalid");
    ProviderMessage prefix = prefix();
    ProviderRequest warm = request(List.of(prefix));
    byte[] warmBytes = new AnthropicRequestEncoder(CONFIG).encode(warm, descriptor).bodyUtf8Bytes();
    assertEquals(List.of(0), markers(JSON.readTree(warmBytes)));
    List<ProviderMessage> history = new ArrayList<>(warm.messages());
    history.add(
        new ProviderMessage(ProviderMessageRole.ASSISTANT, List.of(new ProviderTextBlock("OK"))));
    history.add(increment());
    byte[] current =
        new AnthropicRequestEncoder(CONFIG).encode(request(history), descriptor).bodyUtf8Bytes();
    verify(current, false, false);
    byte[] old = oldBody(current);
    verify(old, true, false);
    JsonNode expected = JSON.readTree(current);
    ((ObjectNode) expected.path("messages").get(0).path("content").get(0)).remove("cache_control");
    assertEquals(expected, JSON.readTree(old));
    // 字节级 OLD 对照：OLD 体与 NEW 体只差被移除的 pre-assistant 历史端点 marker。
    assertEquals(
        withoutHistoryMarker(new String(current, StandardCharsets.UTF_8)),
        new String(old, StandardCharsets.UTF_8));
  }

  // 只在明确授权时烧配额；缺任一凭据失败，不静默 skip、改线路或改模型。
  @Test
  @EnabledIfEnvironmentVariable(named = "KK_STUDIO_REAL_CACHE_PROBE", matches = "true")
  void measuresIndependentNewAndOldSessions() {
    String base = System.getenv("TEST_ANTHROPIC_BASE_URL");
    String key = System.getenv("TEST_ANTHROPIC_API_KEY");
    assertTrue(
        base != null && !base.isBlank() && key != null && !key.isBlank(),
        "classification=MISSING_CREDENTIAL_PAIR");
    try (HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()) {
      ExecutorService workers = Executors.newCachedThreadPool();
      ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
      try {
        ProbeTransport transport = new ProbeTransport(client, workers, scheduler);
        ProviderDescriptor descriptor = descriptor(base);
        AnthropicModelProvider provider =
            new AnthropicModelProvider(
                transport, descriptor, key, AnthropicEndpoints.resolveMessagesUri(base), CONFIG);
        for (boolean old : List.of(false, true)) {
          transport.old = false;
          transport.warm = true;
          transport.group = old ? "OLD" : "NEW";
          List<ProviderMessage> history = new ArrayList<>(List.of(prefix()));
          ProviderCompletion warm = call(provider, request(history), transport);
          assertTrue(
              !warm.response().text().isBlank()
                  && warm.response().toolCalls().isEmpty()
                  && warm.response().thinking().isEmpty(),
              "classification=UNEXPECTED_ASSISTANT");
          // 真 response 的投影 + native replay，绝不使用虚构 assistant 预热。
          history.add(
              new ProviderMessage(
                  ProviderMessageRole.ASSISTANT,
                  List.of(new ProviderTextBlock(warm.response().text())),
                  warm.replayState()));
          history.add(increment());
          transport.old = old;
          transport.warm = false;
          call(provider, request(history), transport);
        }
        System.out.println("classification=OBSERVED calls=" + transport.calls);
      } finally {
        workers.shutdownNow();
        scheduler.shutdownNow();
      }
    } catch (AssertionError failure) {
      throw failure;
    } catch (Exception failure) {
      // 不把上游、URI、headers 或 cause 放进 Surefire 报告。
      throw new AssertionError("classification=LOCAL_OR_PROTOCOL_FAILURE");
    }
  }

  private static ProviderCompletion call(
      AnthropicModelProvider provider, ProviderRequest request, ProbeTransport transport)
      throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<ProviderCompletion> result = new AtomicReference<>();
    AtomicReference<String> error = new AtomicReference<>();
    ProviderStream stream =
        provider.stream(
            request,
            new ProviderStreamHandler() {
              @Override
              public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

              @Override
              public void onComplete(ProviderCompletion completion, ProviderStream stream) {
                result.set(completion);
                done.countDown();
              }

              @Override
              public void onError(ProviderException failure, ProviderStream stream) {
                error.set(failure.kind().name());
                done.countDown();
              }
            });
    if (!done.await(125, TimeUnit.SECONDS)) {
      stream.cancel();
      throw new AssertionError("classification=TIMEOUT calls=" + transport.calls);
    }
    if (error.get() != null) {
      throw new AssertionError("classification=" + error.get() + " calls=" + transport.calls);
    }
    assertTrue(result.get() != null, "classification=MISSING_COMPLETION");
    JsonNode raw = JSON.readTree(result.get().response().rawUsageJson());
    ObjectNode safe = JSON.createObjectNode();
    for (String field :
        List.of(
            "input_tokens",
            "output_tokens",
            "cache_read_input_tokens",
            "cache_creation_input_tokens")) {
      if (raw.path(field).isIntegralNumber()) {
        safe.put(field, raw.path(field).longValue());
      }
    }
    var usage = result.get().response().usage();
    System.out.println(
        "group="
            + transport.group
            + " round="
            + (transport.warm ? 1 : 2)
            + " classification=COMPLETE input="
            + usage.inputTokens()
            + " output="
            + usage.outputTokens()
            + " read="
            + usage.cacheReadTokens()
            + " write="
            + usage.cacheWriteTokens()
            + " writeLong="
            + usage.cacheWriteLongTokens()
            + " rawUsage="
            + safe);
    return result.get();
  }

  private static ProviderDescriptor descriptor(String base) {
    return new ProviderDescriptor(
        "minimax-anthropic", ProviderType.ANTHROPIC, base, TIMEOUT, UUID.randomUUID());
  }

  private static ProviderRequest request(List<ProviderMessage> messages) {
    return new ProviderRequest(
        new ModelDescriptor(
            "minimax-anthropic", "MiniMax-M3", MODEL, Set.of(ModelInputModality.TEXT), true, true),
        new ModelVariant("off", "off"),
        128,
        "answer OK only",
        messages,
        List.of(),
        new ProviderCacheControl(PromptCacheRetention.SHORT, "probe"));
  }

  private static ProviderMessage prefix() {
    // nonce 第一字节起隔离。固定分布的 6800 个常见词约 6–8k tokens，不靠长 system 命中。
    String[] words = {
      " apple", " river", " stone", " paper", " green", " table", " cloud", " light"
    };
    Random random = new Random(8173);
    StringBuilder text = new StringBuilder(UUID.randomUUID().toString());
    for (int i = 0; i < 6800; i++) {
      text.append(words[random.nextInt(words.length)]);
    }
    return new ProviderMessage(
        ProviderMessageRole.USER, List.of(new ProviderTextBlock(text.toString())));
  }

  private static ProviderMessage increment() {
    List<ProviderContentBlock> blocks = new ArrayList<>();
    for (int i = 0; i < 24; i++) {
      blocks.add(new ProviderTextBlock("item " + i + " answer OK"));
    }
    return new ProviderMessage(ProviderMessageRole.USER, blocks);
  }

  private static List<Integer> markers(JsonNode root) {
    List<Integer> indices = new ArrayList<>();
    int index = 0;
    for (JsonNode message : root.path("messages")) {
      for (JsonNode block : message.path("content")) {
        if (block.has("cache_control")) {
          indices.add(index);
        }
        index++;
      }
    }
    return indices;
  }

  private static void verify(byte[] body, boolean old, boolean warm) throws Exception {
    JsonNode root = JSON.readTree(body);
    assertEquals(MODEL, root.path("model").asText());
    assertEquals(128, root.path("max_tokens").asInt());
    assertEquals("disabled", root.path("thinking").path("type").asText());
    JsonNode system = root.path("system").get(0);
    assertEquals("answer OK only", system.path("text").asText());
    // 新契约：SHORT 留存固定给 system 文本块打 ephemeral marker；请求没有 tools 也就不存在 tools marker。
    assertEquals("ephemeral", system.path("cache_control").path("type").asText());
    assertFalse(system.path("cache_control").has("ttl"));
    assertFalse(root.has("tools"));
    List<Integer> positions = markers(root);
    // 历史端点 marker 加 system 固定 marker 不得超过原生 4 个上限。
    assertTrue(positions.size() + 1 <= 4, () -> "markers=" + positions);
    assertEquals(warm ? List.of(0) : old ? List.of(25) : List.of(0, 25), positions);
  }

  private static byte[] oldBody(byte[] current) {
    return withoutHistoryMarker(new String(current, StandardCharsets.UTF_8))
        .getBytes(StandardCharsets.UTF_8);
  }

  private static String withoutHistoryMarker(String body) {
    int first = historyMarkerIndex(body);
    return body.substring(0, first) + body.substring(first + MARKER.length());
  }

  /** 历史端点 marker 位于 messages 段内；system 段上的固定 marker 不参与 OLD 对照。 */
  private static int historyMarkerIndex(String body) {
    int messages = body.indexOf("\"messages\"");
    assertTrue(messages >= 0, "classification=MISSING_MESSAGES");
    int first = body.indexOf(MARKER, messages);
    assertTrue(
        first >= 0 && body.indexOf(MARKER, first + MARKER.length()) >= 0,
        "classification=MISSING_HISTORY_MARKER");
    return first;
  }

  /** 在真实 provider stream 边界只移除 OLD 历史 marker，然后仍委托生产 SSE transport。 */
  private static final class ProbeTransport extends JdkHttpSseTransport {
    private boolean old;
    private boolean warm;
    private String group;
    private int calls;

    private ProbeTransport(
        HttpClient client, ExecutorService workers, ScheduledExecutorService scheduler) {
      super(client, workers, scheduler);
    }

    @Override
    public ProviderStream stream(
        HttpRequest request,
        ModelCallTimeoutPolicy timeout,
        HttpSseLimits limits,
        HttpSseCallback callback) {
      try {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        request
            .bodyPublisher()
            .orElseThrow()
            .subscribe(
                new Flow.Subscriber<ByteBuffer>() {
                  @Override
                  public void onSubscribe(Flow.Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                  }

                  @Override
                  public void onNext(ByteBuffer buffer) {
                    byte[] chunk = new byte[buffer.remaining()];
                    buffer.get(chunk);
                    bytes.writeBytes(chunk);
                  }

                  @Override
                  public void onError(Throwable failure) {
                    throw new AssertionError("classification=BODY_PUBLISHER_FAILURE");
                  }

                  @Override
                  public void onComplete() {}
                });
        byte[] current = bytes.toByteArray();
        verify(current, false, warm);
        byte[] body = old ? oldBody(current) : current;
        verify(body, old, warm);
        if (!old) {
          assertTrue(Arrays.equals(current, body));
        }
        assertTrue(calls < 4, "classification=CALL_BUDGET_EXCEEDED");
        System.out.println(
            "group="
                + group
                + " round="
                + (warm ? 1 : 2)
                + " model="
                + MODEL
                + " markers="
                + markers(JSON.readTree(body)));
        HttpRequest.Builder outgoing =
            HttpRequest.newBuilder(request.uri())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        request
            .headers()
            .map()
            .forEach((name, values) -> values.forEach(value -> outgoing.header(name, value)));
        calls++;
        return super.stream(
            outgoing.build(),
            timeout,
            limits,
            new HttpSseCallback() {
              @Override
              public void onOpen(HttpOpenMetadata metadata) {
                System.out.println("status=" + metadata.statusCode());
                callback.onOpen(metadata);
              }

              @Override
              public void onEvent(ServerSentEvent event) {
                callback.onEvent(event);
              }

              @Override
              public void onComplete() {
                callback.onComplete();
              }

              @Override
              public void onFailure(TransportException failure) {
                callback.onFailure(failure);
              }
            });
      } catch (AssertionError failure) {
        throw failure;
      } catch (Exception failure) {
        throw new AssertionError("classification=WIRE_SHAPE_FAILURE");
      }
    }
  }
}
