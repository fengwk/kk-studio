package fun.fengwk.kkstudio.platform.harness.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import fun.fengwk.kkstudio.harness.provider.anthropic.AnthropicConfiguration;
import fun.fengwk.kkstudio.harness.provider.anthropic.AnthropicProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.anthropic.AnthropicThinkingMode;
import fun.fengwk.kkstudio.harness.provider.gemini.GeminiProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.openai.chat.OpenAiChatProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.openai.responses.OpenAiResponsesProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactories;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactory;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 验证 {@link ModelExecutionConfiguration} 暴露的 4 个 named ProviderFactory bean 全部被收集到 {@link
 * ProviderFactories}，避免 Spring 按返回类型匹配时丢失 bean；同时验证生产 {@link PlatformModelGateway} 被组合进上下文。
 */
class ModelExecutionConfigurationTest extends PostgresSpringTestSupport {

  @Autowired
  @Qualifier("openaiProviderFactory")
  private ProviderFactory openaiProviderFactory;

  @Autowired
  @Qualifier("openaiResponsesProviderFactory")
  private ProviderFactory openaiResponsesProviderFactory;

  @Autowired
  @Qualifier("anthropicProviderFactory")
  private ProviderFactory anthropicProviderFactory;

  @Autowired
  @Qualifier("googleProviderFactory")
  private ProviderFactory googleProviderFactory;

  @Autowired private ProviderFactories providerFactories;

  @Autowired private PlatformModelGateway platformModelGateway;

  @Autowired
  @Qualifier("modelExecutionExecutor")
  private ExecutorService modelExecutionExecutor;

  @Autowired
  @Qualifier("modelExecutionHttpClient")
  private HttpClient modelExecutionHttpClient;

  @Autowired
  @Qualifier("systemProxySelector")
  private ProxySelector systemProxySelector;

  @Autowired
  @Qualifier("modelExecutionWatchdogScheduler")
  private ScheduledExecutorService modelExecutionWatchdogScheduler;

  @Autowired
  @Qualifier("modelExecutionTransport")
  private JdkHttpSseTransport modelExecutionTransport;

  @Test
  void composesPlatformModelGatewayWithSharedExecutorAndConfig() {
    assertNotNull(platformModelGateway);
  }

  /** 意图：原生 Provider 固定 HTTP/1.1、复用受管 worker、禁止重定向，并由独立 Watchdog 驱动超时。 */
  @Test
  void configuresManagedNativeProviderTransport() {
    assertEquals(HttpClient.Version.HTTP_1_1, modelExecutionHttpClient.version());
    assertEquals(HttpClient.Redirect.NEVER, modelExecutionHttpClient.followRedirects());
    assertSame(modelExecutionExecutor, modelExecutionHttpClient.executor().orElseThrow());
    assertSame(systemProxySelector, modelExecutionHttpClient.proxy().orElseThrow());
    assertNotNull(modelExecutionTransport);
    assertFalse(modelExecutionWatchdogScheduler.isShutdown());
  }

  /** 意图：真实明文 HTTP 请求必须保留 JSON、无 h2c 升级头，并能读取 SSE 响应。 */
  @Test
  void sendsJsonAndReceivesSseWithoutH2cUpgrade() throws Exception {
    String body = "{\"model\":\"test\",\"messages\":[],\"stream\":true}";
    CompletableFuture<Void> requestVerified = new CompletableFuture<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          try (exchange) {
            try {
              assertEquals("HTTP/1.1", exchange.getProtocol());
              assertEquals("POST", exchange.getRequestMethod());
              assertEquals(
                  "application/json", exchange.getRequestHeaders().getFirst("Content-Type"));
              assertNull(exchange.getRequestHeaders().getFirst("Upgrade"));
              assertNull(exchange.getRequestHeaders().getFirst("HTTP2-Settings"));
              assertNull(exchange.getRequestHeaders().getFirst("Connection"));
              assertEquals(
                  body,
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
              requestVerified.complete(null);
            } catch (Throwable failure) {
              requestVerified.completeExceptionally(failure);
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("data: hello\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
          }
        });
    server.start();
    try {
      HttpRequest request =
          HttpRequest.newBuilder(
                  URI.create(
                      "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions"))
              .timeout(Duration.ofSeconds(5))
              .header("Content-Type", "application/json")
              .header("Accept", "text/event-stream")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      HttpResponse<String> response =
          modelExecutionHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
      requestVerified.get(5, TimeUnit.SECONDS);
      assertEquals(200, response.statusCode());
      assertEquals(HttpClient.Version.HTTP_1_1, response.version());
      assertEquals("data: hello\n\ndata: [DONE]\n\n", response.body());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void registersAllFourNamedProviderFactoryBeans() {
    assertEquals(ProviderType.OPENAI, openaiProviderFactory.providerType());
    assertEquals(ProviderType.OPENAI_RESPONSES, openaiResponsesProviderFactory.providerType());
    assertEquals(ProviderType.ANTHROPIC, anthropicProviderFactory.providerType());
    assertEquals(ProviderType.GOOGLE, googleProviderFactory.providerType());
    assertEquals(PromptCacheRetention.SHORT, anthropicProviderFactory.promptCacheRetention());
    assertEquals(PromptCacheRetention.NONE, googleProviderFactory.promptCacheRetention());
  }

  @Test
  void providerFactoriesLookupAllFourTypes() {
    assertNotNull(providerFactories.lookup(ProviderType.OPENAI).orElseThrow());
    assertNotNull(providerFactories.lookup(ProviderType.OPENAI_RESPONSES).orElseThrow());
    assertNotNull(providerFactories.lookup(ProviderType.ANTHROPIC).orElseThrow());
    assertNotNull(providerFactories.lookup(ProviderType.GOOGLE).orElseThrow());
    assertSame(openaiProviderFactory, providerFactories.lookup(ProviderType.OPENAI).orElseThrow());
    assertSame(
        openaiResponsesProviderFactory,
        providerFactories.lookup(ProviderType.OPENAI_RESPONSES).orElseThrow());
    assertSame(
        anthropicProviderFactory, providerFactories.lookup(ProviderType.ANTHROPIC).orElseThrow());
    assertSame(googleProviderFactory, providerFactories.lookup(ProviderType.GOOGLE).orElseThrow());
  }

  /** 意图：验证 Spring 装配的 ProviderFactory 返回原生 Harness Provider 适配器，并共享 transport。 */
  @Test
  void createsTheAdapterMatchingEachProviderType() {
    assertInstanceOf(
        OpenAiChatProviderAdapter.class, openaiProviderFactory.create("credential", "{}"));
    assertInstanceOf(
        OpenAiResponsesProviderAdapter.class,
        openaiResponsesProviderFactory.create("credential", "{}"));
    assertInstanceOf(
        AnthropicProviderAdapter.class, anthropicProviderFactory.create("credential", "{}"));
    assertInstanceOf(GeminiProviderAdapter.class, googleProviderFactory.create("credential", "{}"));
  }

  /** 意图：验证每个 Spring ProviderFactory 均能贯通原生 adapter 并创建对应 ModelProvider。 */
  @Test
  void createsNativeModelProviderThroughEveryFactory() {
    assertNativeModelProvider(openaiProviderFactory, ProviderType.OPENAI, "{}");
    assertNativeModelProvider(
        openaiResponsesProviderFactory,
        ProviderType.OPENAI_RESPONSES,
        "{\"promptCacheRetention\":\"SHORT\"}");
    assertNativeModelProvider(anthropicProviderFactory, ProviderType.ANTHROPIC, "{}");
    assertNativeModelProvider(googleProviderFactory, ProviderType.GOOGLE, "{}");
  }

  /** 意图：验证 OpenAI 家族工厂基于 configJson 解析留存档位，且任何配置（含 null/空）都返回非空档位。 */
  @Test
  void openAiFactoriesExposeConfigAwareRetention() {
    for (ProviderFactory factory : List.of(openaiProviderFactory, openaiResponsesProviderFactory)) {
      assertNotNull(factory.promptCacheRetention());
      assertNotNull(factory.promptCacheRetention(null));
      assertNotNull(factory.promptCacheRetention("{}"));
      assertNotNull(factory.promptCacheRetention("{\"promptCacheRetention\":\"SHORT\"}"));
      assertNotNull(factory.promptCacheRetention("{\"promptCacheRetention\":\"LONG\"}"));
    }
  }

  /** 意图：验证 Google 与 Anthropic 工厂暴露固定的标准留存档位。 */
  @Test
  void fixedFactoriesExposeExpectedRetention() {
    assertEquals(PromptCacheRetention.NONE, googleProviderFactory.promptCacheRetention());
    assertEquals(
        PromptCacheRetention.NONE,
        googleProviderFactory.promptCacheRetention("{\"ignored\":true}"));
    assertEquals(PromptCacheRetention.SHORT, anthropicProviderFactory.promptCacheRetention());
  }

  /** 意图：验证 Anthropic 工厂将配置中的 anthropicThinkingMode 传递给适配器。 */
  @Test
  void anthropicFactoryAppliesConfiguredThinkingMode() {
    AnthropicProviderAdapter adapter =
        (AnthropicProviderAdapter)
            anthropicProviderFactory.create("credential", "{\"anthropicThinkingMode\":\"BUDGET\"}");
    assertEquals(AnthropicThinkingMode.BUDGET, adapter.configuration().anthropicThinkingMode());
  }

  /** 意图：验证 Anthropic 工厂只解析 thinking mode，已被删除的 modelAliases 字段不再保留任何状态。 */
  @Test
  void anthropicFactoryIgnoresRemovedModelAliasConfiguration() {
    // 别名映射不再是配置能力：解析成功后配置对象与默认值完全相等，不保留任何别名状态。
    assertEquals(
        AnthropicConfiguration.defaults(),
        AnthropicConfiguration.parse("{\"modelAliases\":{\"MiniMax-M3\":\"wire-name\"}}"));
  }

  /** 意图：验证 Anthropic 工厂拒绝非法配置且不回显配置内容与敏感信息，因果链无暴露。 */
  @Test
  void anthropicFactoryRejectsMalformedConfigurationWithoutExposingInput() {
    String sensitiveConfig = "{\"anthropicThinkingMode\":\"SUPER_SECRET_VALUE\"}";
    ProviderException ex =
        assertThrows(
            ProviderException.class,
            () -> anthropicProviderFactory.create("credential", sensitiveConfig));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
    assertFalse(ex.getMessage().contains("SUPER_SECRET_VALUE"));
    assertNull(ex.getCause());

    String malformedJson = "{\"anthropicThinkingMode\":";
    ProviderException exMalformed =
        assertThrows(
            ProviderException.class,
            () -> anthropicProviderFactory.create("credential", malformedJson));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exMalformed.kind());
    assertNull(exMalformed.getCause());

    // 未知 provider 配置字段（含已删除的 modelAliases）必须被忽略，且配置对象绝不回显原始 payload。
    AnthropicConfiguration aliasIgnored =
        AnthropicConfiguration.parse("{\"modelAliases\":{\"key\":\" SUPER_SECRET_PAYLOAD \"}}");
    assertEquals(AnthropicConfiguration.defaults(), aliasIgnored);
    assertFalse(aliasIgnored.toString().contains("SUPER_SECRET_PAYLOAD"));

    String sensitiveValue = "{\"anthropicThinkingMode\":\" SENSITIVE_VALUE \"}";
    ProviderException exValue =
        assertThrows(
            ProviderException.class,
            () -> anthropicProviderFactory.create("credential", sensitiveValue));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exValue.kind());
    assertFalse(exValue.getMessage().contains("SENSITIVE_VALUE"));
    assertNull(exValue.getCause());
  }

  private static void assertNativeModelProvider(
      ProviderFactory factory, ProviderType type, String configJson) {
    ModelProvider modelProvider =
        factory
            .create("credential", configJson)
            .create(
                new ProviderDescriptor(
                    "provider-" + type.wireValue(),
                    type,
                    "https://provider.example/v1",
                    ModelCallTimeoutPolicy.DEFAULT,
                    UUID.randomUUID()));
    assertNotNull(modelProvider);
  }
}
