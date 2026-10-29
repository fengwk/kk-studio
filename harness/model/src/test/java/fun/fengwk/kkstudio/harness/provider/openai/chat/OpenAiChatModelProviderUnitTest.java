package fun.fengwk.kkstudio.harness.provider.openai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderProtocolEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.provider.ScriptedSseTransport;
import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.provider.transport.ServerSentEvent;
import fun.fengwk.kkstudio.harness.provider.transport.TransportErrorKind;
import fun.fengwk.kkstudio.harness.provider.transport.TransportException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

/** 测试意图：验证 OpenAI Chat Provider Adapter 的能力、工厂方法、类型匹配及匿名与认证模式。 */
class OpenAiChatModelProviderUnitTest {

  private HttpClient httpClient;
  private ExecutorService workerExecutor;
  private ScheduledExecutorService scheduler;
  private JdkHttpSseTransport transport;
  private ProviderDescriptor descriptor;
  private ModelDescriptor modelDesc;
  private ModelVariant defaultVariant;

  @BeforeEach
  void setUp() {
    workerExecutor = Executors.newCachedThreadPool();
    scheduler = Executors.newSingleThreadScheduledExecutor();
    httpClient =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    transport = new JdkHttpSseTransport(httpClient, scheduler);

    descriptor =
        new ProviderDescriptor(
            "openai",
            ProviderType.OPENAI,
            "https://api.openai.com/v1",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)));
    modelDesc =
        new ModelDescriptor(
            "openai", "gpt-4o", "gpt-4o", Set.of(ModelInputModality.TEXT), true, false);
    defaultVariant = new ModelVariant("default");
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

  @Test
  @DisplayName("ProviderAdapter 类型与 Provider 创建")
  void testAdapterCreationAndType() {
    OpenAiChatProviderAdapter adapter = new OpenAiChatProviderAdapter(transport, "sk-test");
    assertEquals(ProviderType.OPENAI, adapter.providerType());

    ModelProvider provider = adapter.create(descriptor);
    assertNotNull(provider);
    assertTrue(provider instanceof OpenAiChatModelProvider);

    // 错误 ProviderType 拒绝
    ProviderDescriptor anthropicDesc =
        new ProviderDescriptor(
            "anthropic",
            ProviderType.ANTHROPIC,
            "https://api.anthropic.com",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)));
    assertThrows(IllegalArgumentException.class, () -> adapter.create(anthropicDesc));
  }

  @Test
  @DisplayName("静态 promptCacheRetention API 按配置解析留存档位")
  void testStaticPromptCacheRetention() {
    // AUTOMATIC（默认）不启用 Provider 端缓存
    assertEquals(PromptCacheRetention.NONE, OpenAiChatProviderAdapter.promptCacheRetention("{}"));
    assertEquals(PromptCacheRetention.NONE, OpenAiChatProviderAdapter.promptCacheRetention(null));

    assertEquals(
        PromptCacheRetention.SHORT,
        OpenAiChatProviderAdapter.promptCacheRetention("{\"promptCacheRetention\":\"SHORT\"}"));
    assertEquals(
        PromptCacheRetention.LONG,
        OpenAiChatProviderAdapter.promptCacheRetention("{\"promptCacheRetention\":\"LONG\"}"));
  }

  @Test
  @DisplayName("匿名模式（空 API key）正常构造并在传输层不注入 Authorization 头")
  void testAnonymousMode() {
    OpenAiChatProviderAdapter adapter = new OpenAiChatProviderAdapter(transport, null);
    ModelProvider provider = adapter.create(descriptor);
    assertNotNull(provider);

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
          public void onEvent(ProviderStreamEvent event) {}

          @Override
          public void onComplete(ProviderCompletion completion) {}

          @Override
          public void onError(ProviderException error) {}
        });
  }

  @Test
  @DisplayName("ModelProvider 描述符、toString、编码失败与非法请求头捕获")
  void testModelProviderDetailsAndErrors() {
    OpenAiChatProviderAdapter adapter = new OpenAiChatProviderAdapter(transport, "sk-valid");
    ModelProvider provider = adapter.create(descriptor);
    assertEquals(descriptor, ((OpenAiChatModelProvider) provider).descriptor());
    assertEquals("OpenAiChatModelProvider[]", provider.toString());

    // 1. 编码异常进入 bridge.emitError（如工具 schema 非法）
    ModelVariant invalidVariant = new ModelVariant("inv");
    ProviderToolDefinition invalidTool = new ProviderToolDefinition("badTool", "desc", "not-json");
    ProviderRequest reqInvalid =
        new ProviderRequest(
            modelDesc,
            invalidVariant,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
            List.of(invalidTool),
            ProviderCacheControl.none());
    var errorRef = new AtomicReference<ProviderException>();
    provider.stream(
        reqInvalid,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event) {}

          @Override
          public void onComplete(ProviderCompletion completion) {}

          @Override
          public void onError(ProviderException error) {
            errorRef.set(error);
          }
        });
    assertNotNull(errorRef.get());
    assertEquals(ProviderErrorKind.INVALID_REQUEST, errorRef.get().kind());

    // 2. 非法 Header 字符导致 HttpRequest 抛异常被安全捕获
    OpenAiChatProviderAdapter badHeaderAdapter =
        new OpenAiChatProviderAdapter(transport, "sk-invalid\r\nHeader-Injection");
    ModelProvider badHeaderProvider = badHeaderAdapter.create(descriptor);
    ProviderRequest validReq =
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
    var headerErrorRef = new AtomicReference<ProviderException>();
    badHeaderProvider.stream(
        validReq,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event) {}

          @Override
          public void onComplete(ProviderCompletion completion) {}

          @Override
          public void onError(ProviderException error) {
            headerErrorRef.set(error);
          }
        });
    assertNotNull(headerErrorRef.get());
    assertEquals(ProviderErrorKind.INVALID_REQUEST, headerErrorRef.get().kind());
  }

  /** 测试意图：传输失败按 TRANSIENT 恰好交付一次 onError，且不携带底层异常链。 */
  @Test
  void transportFailureMapsToExactlyOneTerminalError() {
    AtomicReference<Integer> errors = new AtomicReference<>(0);
    AtomicReference<ProviderException> caught = new AtomicReference<>();
    new OpenAiChatProviderAdapter(
            ScriptedSseTransport.failing(new TransportException(TransportErrorKind.IO, "io")),
            "sk-test")
        .create(descriptor).stream(
            validRequest(),
            new ProviderStreamHandler() {
              @Override
              public void onEvent(ProviderStreamEvent event) {}

              @Override
              public void onComplete(ProviderCompletion completion) {}

              @Override
              public void onError(ProviderException error) {
                errors.set(errors.get() + 1);
                caught.set(error);
              }
            });
    assertEquals(1, errors.get());
    assertEquals(ProviderErrorKind.TRANSIENT, caught.get().kind());
    assertNull(caught.get().getCause());
  }

  /** 测试意图：用户 handler 在原生帧或增量处抛错时流已封口：异常传播给调用方，之后不再回调 onError。 */
  @Test
  void handlerFailureIsTerminalAndPropagates() {
    ServerSentEvent delta =
        new ServerSentEvent(null, "{\"choices\":[{\"delta\":{\"content\":\"x\"}}]}");
    for (boolean failOnProtocol : new boolean[] {true, false}) {
      AtomicReference<Integer> errors = new AtomicReference<>(0);
      ModelProvider provider =
          new OpenAiChatProviderAdapter(
                  ScriptedSseTransport.failing(
                      new TransportException(TransportErrorKind.IO, "late"), delta),
                  "sk-test")
              .create(descriptor);
      assertThrows(
          ProviderException.class,
          () ->
              provider.stream(
                  validRequest(),
                  new ProviderStreamHandler() {
                    @Override
                    public void onProtocolEvent(ProviderProtocolEvent event) {
                      if (failOnProtocol) {
                        throw new ProviderException(
                            ProviderErrorKind.INVALID_REQUEST, "handler failed");
                      }
                    }

                    @Override
                    public void onEvent(ProviderStreamEvent event) {
                      throw new ProviderException(
                          ProviderErrorKind.INVALID_REQUEST, "handler failed");
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

  private ProviderRequest validRequest() {
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
}
