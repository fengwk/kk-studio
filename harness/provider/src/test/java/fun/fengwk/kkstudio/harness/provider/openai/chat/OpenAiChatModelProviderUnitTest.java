package fun.fengwk.kkstudio.harness.provider.openai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheMode;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
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
    transport = new JdkHttpSseTransport(httpClient, workerExecutor, scheduler);

    descriptor =
        new ProviderDescriptor(
            "openai",
            ProviderType.OPENAI,
            "https://api.openai.com/v1",
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
  @DisplayName("静态 promptCacheCapability API 支持三种模式解析")
  void testStaticPromptCacheCapability() {
    PromptCacheCapability capAuto = OpenAiChatProviderAdapter.promptCacheCapability("{}");
    assertEquals(PromptCacheMode.AUTOMATIC, capAuto.mode());

    PromptCacheCapability capLegacy =
        OpenAiChatProviderAdapter.promptCacheCapability("{\"openAiPromptCacheMode\":\"LEGACY\"}");
    assertEquals(PromptCacheMode.AFFINITY, capLegacy.mode());

    PromptCacheCapability capGpt =
        OpenAiChatProviderAdapter.promptCacheCapability(
            "{\"openAiPromptCacheMode\":\"GPT_5_6_EXPLICIT\"}");
    assertEquals(PromptCacheMode.BREAKPOINTS, capGpt.mode());
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

    ProviderStream stream =
        provider.stream(
            request,
            new ProviderStreamHandler() {
              @Override
              public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

              @Override
              public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

              @Override
              public void onError(ProviderException error, ProviderStream stream) {}
            });
    assertNotNull(stream);
    stream.cancel();
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
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
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
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            headerErrorRef.set(error);
          }
        });
    assertNotNull(headerErrorRef.get());
    assertEquals(ProviderErrorKind.INVALID_REQUEST, headerErrorRef.get().kind());
  }
}
