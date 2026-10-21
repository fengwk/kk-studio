package fun.fengwk.kkstudio.harness.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/** 专门针对 AnthropicModelProvider 及其内部回调各分支状态流转的轻量单元测试。 */
class AnthropicModelProviderUnitTest {

  private ProviderDescriptor descriptor;
  private ProviderRequest request;
  private HttpClient client;
  private ExecutorService exec;
  private ScheduledExecutorService sched;

  @BeforeEach
  void setUp() {
    descriptor =
        new ProviderDescriptor(
            "anthropic-unit",
            ProviderType.ANTHROPIC,
            "https://api.anthropic.com/v1",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(5), Duration.ofSeconds(3)),
            UUID.randomUUID());

    ModelDescriptor model =
        new ModelDescriptor(
            "anthropic-unit",
            "claude-3-5-sonnet",
            "claude-3-5-sonnet",
            Set.of(ModelInputModality.TEXT),
            true,
            false);

    request =
        new ProviderRequest(
            model,
            new ModelVariant("default"),
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

  @Test
  void handlesInvalidHeaderValueSafelyWithoutLeakingSecrets() {
    JdkHttpSseTransport mockTransport = new JdkHttpSseTransport(client, sched);
    String sensitiveKeyWithCRLF = "secret-key\r\nInjected-Header: evil";
    AnthropicModelProvider provider =
        new AnthropicModelProvider(
            mockTransport,
            descriptor,
            sensitiveKeyWithCRLF,
            URI.create("https://api.anthropic.com/v1/messages"));

    AtomicReference<ProviderException> caught = new AtomicReference<>();
    provider.stream(
        request,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event) {}

          @Override
          public void onComplete(ProviderCompletion completion) {}

          @Override
          public void onError(ProviderException error) {
            caught.set(error);
          }
        });

    assertNotNull(caught.get());
    assertEquals(ProviderErrorKind.INVALID_REQUEST, caught.get().kind());
    assertFalse(caught.get().getMessage().contains("secret-key"));
    assertFalse(caught.get().getMessage().contains("evil"));
  }

  @Test
  void toStringDoesNotLeakDescriptorOrEndpoint() {
    JdkHttpSseTransport mockTransport = new JdkHttpSseTransport(client, sched);
    AnthropicModelProvider provider =
        new AnthropicModelProvider(
            mockTransport,
            descriptor,
            "secret-api-key",
            URI.create("https://api.anthropic.com/v1/messages"));

    String str = provider.toString();
    assertEquals("AnthropicModelProvider[]", str);
    assertFalse(str.contains("secret-api-key"));
    assertFalse(str.contains("anthropic.com"));
  }

  @Test
  void adapterWiringAndConfigurationBehavior() {
    JdkHttpSseTransport transport = new JdkHttpSseTransport(client, sched);

    // 1. 默认构造函数使用 ADAPTIVE
    AnthropicProviderAdapter defaultAdapter = new AnthropicProviderAdapter(transport, "key-1");
    assertEquals(
        AnthropicThinkingMode.ADAPTIVE, defaultAdapter.configuration().anthropicThinkingMode());
    assertEquals("AnthropicProviderAdapter[providerType=ANTHROPIC]", defaultAdapter.toString());

    // 2. configJson 构造函数解析 BUDGET
    AnthropicProviderAdapter budgetAdapter =
        new AnthropicProviderAdapter(transport, "key-2", "{\"anthropicThinkingMode\":\"BUDGET\"}");
    assertEquals(
        AnthropicThinkingMode.BUDGET, budgetAdapter.configuration().anthropicThinkingMode());

    // 3. 显式 AnthropicConfiguration 构造函数
    AnthropicConfiguration explicitBudgetConfig =
        new AnthropicConfiguration(AnthropicThinkingMode.BUDGET);
    AnthropicProviderAdapter explicitAdapter =
        new AnthropicProviderAdapter(transport, "key-3", explicitBudgetConfig);
    assertEquals(explicitBudgetConfig, explicitAdapter.configuration());

    // 4. static parseConfig 委托
    assertEquals(
        AnthropicThinkingMode.BUDGET,
        AnthropicProviderAdapter.parseConfig("{\"anthropicThinkingMode\":\"BUDGET\"}")
            .anthropicThinkingMode());

    // 5. 非法 JSON 在 adapter 构造时被拒绝
    assertThrows(
        ProviderException.class, () -> new AnthropicProviderAdapter(transport, "key", "not-json"));

    // 6. create(descriptor) 传递 configuration 到 AnthropicModelProvider
    AnthropicModelProvider budgetModelProvider =
        (AnthropicModelProvider) budgetAdapter.create(descriptor);
    assertEquals(
        AnthropicThinkingMode.BUDGET, budgetModelProvider.configuration().anthropicThinkingMode());
  }

  @Test
  void modelProviderEmitsInvalidRequestOnBudgetConstraintViolationWithoutInvokingTransport() {
    AtomicReference<Boolean> transportInvoked = new AtomicReference<>(false);
    JdkHttpSseTransport hookTransport =
        new JdkHttpSseTransport(client, sched) {
          @Override
          public void execute(
              HttpRequest request,
              ModelCallTimeoutPolicy timeoutPolicy,
              HttpSseLimits limits,
              Predicate<ServerSentEvent> onEvent) {
            transportInvoked.set(true);
          }
        };

    AnthropicConfiguration budgetConfig = new AnthropicConfiguration(AnthropicThinkingMode.BUDGET);
    AnthropicModelProvider provider =
        new AnthropicModelProvider(
            hookTransport,
            descriptor,
            "key",
            URI.create("https://api.anthropic.com/v1/messages"),
            budgetConfig);

    ModelDescriptor reasoningModel =
        new ModelDescriptor(
            "anthropic-unit",
            "claude-3-7-sonnet",
            "claude-3-7-sonnet",
            Set.of(ModelInputModality.TEXT),
            true,
            true);

    // 当 outputTokens 为 1 时，budgetTokens = min(2048, 1 - 1) = 0 <= 0 触发校验失败
    ProviderRequest violatingReq =
        new ProviderRequest(
            reasoningModel,
            new ModelVariant("default", "low"),
            1,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hello")))),
            List.of(),
            ProviderCacheControl.none());

    AtomicReference<ProviderException> caughtError = new AtomicReference<>();
    provider.stream(
        violatingReq,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event) {}

          @Override
          public void onComplete(ProviderCompletion completion) {}

          @Override
          public void onError(ProviderException error) {
            caughtError.set(error);
          }
        });

    assertFalse(
        transportInvoked.get(), "Transport must not be invoked on request encoding failure");
    assertNotNull(caughtError.get());
    assertEquals(ProviderErrorKind.INVALID_REQUEST, caughtError.get().kind());
    assertEquals("budget_tokens must be lower than max_tokens", caughtError.get().getMessage());
  }

  /** 测试意图：传输失败（发送阶段或读到一半）都被映射为恰好一次 onError，且不再交付其它终态。 */
  @Test
  void transportFailureMapsToExactlyOneTerminalError() {
    for (ScriptedSseTransport transport :
        List.of(
            ScriptedSseTransport.failing(
                new TransportException(TransportErrorKind.IO, "simulated io error")),
            ScriptedSseTransport.failing(
                new TransportException(TransportErrorKind.IO, "socket reset"), MESSAGE_START))) {
      Recording recording = new Recording();
      provider(transport).stream(request, recording);
      assertEquals(1, recording.errors.size());
      assertEquals(0, recording.completions);
      assertEquals(ProviderErrorKind.TRANSIENT, recording.errors.get(0).kind());
      assertEquals("Anthropic I/O error", recording.errors.get(0).getMessage());
    }
  }

  /** 测试意图：调用线程被中断（CANCELLED）时静默返回，不交付任何终态，由调用方按中断状态收敛。 */
  @Test
  void cancellationDeliversNoTerminal() {
    Recording recording = new Recording();
    provider(
        ScriptedSseTransport.failing(
            new TransportException(TransportErrorKind.CANCELLED, "cancelled"), MESSAGE_START))
        .stream(request, recording);
    assertTrue(recording.errors.isEmpty());
    assertEquals(0, recording.completions);
  }

  /** 测试意图：用户 handler 抛错后流已封口：异常传播给调用方，之后不再回调 onError / onComplete。 */
  @Test
  void handlerFailureIsTerminalAndPropagates() {
    Recording protocolFailure = new Recording();
    protocolFailure.failOnProtocolEvent = true;
    assertThrows(
        ProviderException.class,
        () ->
            provider(
                ScriptedSseTransport.events(new ServerSentEvent("message", "{\"type\":\"ping\"}")))
                .stream(request, protocolFailure));
    assertTrue(protocolFailure.errors.isEmpty());
    assertEquals(0, protocolFailure.completions);

    Recording deltaFailure = new Recording();
    deltaFailure.failOnEvent = true;
    assertThrows(
        ProviderException.class,
        () ->
            provider(
                ScriptedSseTransport.events(
                    MESSAGE_START,
                    new ServerSentEvent(
                        "content_block_start",
                        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"x\"}}")))
                .stream(request, deltaFailure));
    assertTrue(deltaFailure.errors.isEmpty());
    assertEquals(0, deltaFailure.completions);
  }

  /** 测试意图：传输抛出的未分类运行时异常原样传播（不伪造成可分类失败），由上层按不确定终态收敛。 */
  @Test
  void unclassifiedTransportExceptionPropagates() {
    RuntimeException crash = new RuntimeException("unexpected crash");
    JdkHttpSseTransport crashing =
        new JdkHttpSseTransport(client, sched) {
          @Override
          public void execute(
              HttpRequest request,
              ModelCallTimeoutPolicy timeoutPolicy,
              HttpSseLimits limits,
              Predicate<ServerSentEvent> onEvent) {
            throw crash;
          }
        };
    Recording recording = new Recording();
    assertSame(
        crash,
        assertThrows(RuntimeException.class, () -> provider(crashing).stream(request, recording)));
    assertTrue(recording.errors.isEmpty());
  }

  /** 测试意图：流内 error 事件按协议映射为 onError（限流为 TRANSIENT）。 */
  @Test
  void streamErrorEventMapsToError() {
    Recording recording = new Recording();
    provider(
        ScriptedSseTransport.events(
            new ServerSentEvent(
                "error", "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\"}}")))
        .stream(request, recording);
    assertEquals(1, recording.errors.size());
    assertEquals(ProviderErrorKind.TRANSIENT, recording.errors.get(0).kind());
  }

  /** 测试意图：流在没有任何消息的情况下结束是无效响应，而不是空成功。 */
  @Test
  void emptyStreamIsInvalidResponse() {
    Recording recording = new Recording();
    provider(ScriptedSseTransport.events()).stream(request, recording);
    assertEquals(1, recording.errors.size());
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, recording.errors.get(0).kind());
    assertEquals(0, recording.completions);
  }

  private static final ServerSentEvent MESSAGE_START =
      new ServerSentEvent(
          "message_start",
          "{\"type\":\"message_start\",\"message\":{\"id\":\"msg\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"claude\",\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}");

  private AnthropicModelProvider provider(JdkHttpSseTransport transport) {
    return new AnthropicModelProvider(
        transport, descriptor, "key", URI.create("https://api.anthropic.com/v1/messages"));
  }

  /** 记录全部回调的 handler；可配置在原生帧或增量处抛错以模拟用户 handler 失败。 */
  private static final class Recording implements ProviderStreamHandler {
    final List<ProviderException> errors = new ArrayList<>();
    int completions;
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
      completions++;
    }

    @Override
    public void onError(ProviderException error) {
      errors.add(error);
    }
  }
}
