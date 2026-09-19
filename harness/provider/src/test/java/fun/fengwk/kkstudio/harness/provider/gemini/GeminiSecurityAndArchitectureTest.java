package fun.fengwk.kkstudio.harness.provider.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** 验证 Gemini 组件的 toString、架构无外部 SDK 依赖与凭据安全约束。 */
class GeminiSecurityAndArchitectureTest {

  private HttpClient httpClient;
  private ExecutorService workerExecutor;
  private ScheduledExecutorService scheduler;

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
  void toStringNeverLeaksApiKeyOrRequestBody() {
    String sensitiveKey = "AIzaSySecretApiKey123456789";
    httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    workerExecutor = Executors.newSingleThreadExecutor();
    scheduler = Executors.newSingleThreadScheduledExecutor();
    JdkHttpSseTransport transport = new JdkHttpSseTransport(httpClient, workerExecutor, scheduler);

    GeminiProviderAdapter adapter = new GeminiProviderAdapter(transport, sensitiveKey);
    assertFalse(adapter.toString().contains(sensitiveKey));
    assertFalse(adapter.toString().contains("AIzaSy"));

    ProviderDescriptor descriptor =
        new ProviderDescriptor(
            "p1",
            ProviderType.GOOGLE,
            "https://generativelanguage.googleapis.com",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(10), Duration.ofSeconds(5)),
            UUID.randomUUID());

    GeminiModelProvider modelProvider = (GeminiModelProvider) adapter.create(descriptor);
    assertFalse(modelProvider.toString().contains(sensitiveKey));
    assertFalse(modelProvider.toString().contains("AIzaSy"));

    GeminiEncodedRequest encoded =
        new GeminiEncodedRequest(
            "{\"secret\":\"super_confidential\"}".getBytes(StandardCharsets.UTF_8),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    assertFalse(encoded.toString().contains("secret"));
    assertFalse(encoded.toString().contains("super_confidential"));
  }

  @Test
  void rejectsNonGoogleDescriptor() {
    httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    workerExecutor = Executors.newSingleThreadExecutor();
    scheduler = Executors.newSingleThreadScheduledExecutor();
    JdkHttpSseTransport transport = new JdkHttpSseTransport(httpClient, workerExecutor, scheduler);

    GeminiProviderAdapter adapter = new GeminiProviderAdapter(transport, "key");
    ProviderDescriptor anthropicDescriptor =
        new ProviderDescriptor(
            "p2",
            ProviderType.ANTHROPIC,
            "https://api.anthropic.com/v1",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(10), Duration.ofSeconds(5)),
            UUID.randomUUID());

    assertThrows(IllegalArgumentException.class, () -> adapter.create(anthropicDescriptor));
  }

  @Test
  void reportsPromptCacheAutomaticCapability() {
    httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    workerExecutor = Executors.newSingleThreadExecutor();
    scheduler = Executors.newSingleThreadScheduledExecutor();
    JdkHttpSseTransport transport = new JdkHttpSseTransport(httpClient, workerExecutor, scheduler);

    GeminiProviderAdapter adapter = new GeminiProviderAdapter(transport, "key");
    assertEquals(PromptCacheCapability.automatic(), adapter.promptCacheCapability());
    assertEquals(ProviderType.GOOGLE, adapter.providerType());
    assertEquals("GeminiProviderAdapter[providerType=GOOGLE]", adapter.toString());

    GeminiProviderAdapter singleParamAdapter = new GeminiProviderAdapter(transport);
    assertEquals(ProviderType.GOOGLE, singleParamAdapter.providerType());
  }

  @Test
  void architectureConstraints_noSpringOrGoogleSdkImports() throws Exception {
    Class<?>[] classes =
        new Class<?>[] {
          GeminiEndpoints.class,
          GeminiProviderAdapter.class,
          GeminiModelProvider.class,
          GeminiStreamBridge.class,
          GeminiRequestEncoder.class,
          GeminiStreamAccumulator.class,
          GeminiPrefixHasher.class,
          GeminiEncodedRequest.class,
          GeminiErrorMapper.class
        };

    for (Class<?> clazz : classes) {
      // 验证没有加载任何 org.springframework 或 com.google 类
      for (Class<?> iface : clazz.getInterfaces()) {
        assertFalse(iface.getName().startsWith("org.springframework"));
        assertFalse(iface.getName().startsWith("com.google"));
      }
    }
  }

  private static ModelDescriptor dummyModel() {
    ModelPricing pricing =
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
            BigDecimal.ZERO);
    return new ModelDescriptor(
        "gemini-2.5-flash",
        "gemini-2.5-flash",
        "gemini-2.5-flash",
        Set.of(ModelInputModality.TEXT),
        true,
        false,
        pricing);
  }

  private static final ModelVariant DEFAULT_VARIANT = new ModelVariant("default");

  /** 验证所有 JSON、SSE 以及 URI 解析失败时使用固定脱敏异常消息，绝不回显原始输入、payload 或底层异常原因。 */
  @Test
  void parsingErrors_useFixedRedactedMessages_neverLeakInputOrInternalCause() {
    String sensitiveData = "AIzaSySensitiveKeySecret12345";

    // 1. URI 解析异常脱敏
    IllegalArgumentException uriEx =
        assertThrows(
            IllegalArgumentException.class,
            () -> GeminiEndpoints.resolveBaseUri(":::invalid_" + sensitiveData));
    assertEquals("endpoint is not a valid URI", uriEx.getMessage());
    assertFalse(uriEx.getMessage().contains(sensitiveData));

    // 2. SSE JSON 解析异常脱敏
    ProviderStreamHandler noop =
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}
        };
    ProviderRequest req =
        new ProviderRequest(
            dummyModel(),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(),
            List.of(),
            ProviderCacheControl.none());
    ProviderDescriptor desc =
        new ProviderDescriptor(
            "p1",
            ProviderType.GOOGLE,
            "https://generativelanguage.googleapis.com",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(10), Duration.ofSeconds(5)),
            UUID.randomUUID());
    GeminiStreamAccumulator accumulator =
        new GeminiStreamAccumulator(
            req,
            desc,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            new GeminiStreamBridge(noop));

    ProviderException sseEx =
        assertThrows(
            ProviderException.class,
            () -> accumulator.handleEvent("message", "invalid_json_with_secret_" + sensitiveData));
    assertEquals("malformed SSE data JSON", sseEx.getMessage());
    assertFalse(sseEx.getMessage().contains(sensitiveData));

    // 3. Media URI 解析异常脱敏
    ProviderRequest reqInvalidMedia =
        new ProviderRequest(
            dummyModel(),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER,
                    List.of(new ProviderImageBlock("image/png", "ftp://secret_" + sensitiveData)))),
            List.of(),
            ProviderCacheControl.none());
    GeminiRequestEncoder encoder = new GeminiRequestEncoder();
    ProviderException mediaEx =
        assertThrows(ProviderException.class, () -> encoder.encode(reqInvalidMedia, desc));
    assertEquals("media fileUri scheme must be http, https or gs", mediaEx.getMessage());
    assertFalse(mediaEx.getMessage().contains(sensitiveData));
  }
}
