package fun.fengwk.kkstudio.platform.harness.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderFactory;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.model.provider.impl.anthropic.AnthropicProviderAdapter;
import fun.fengwk.kkstudio.harness.model.provider.impl.gemini.GeminiProviderAdapter;
import fun.fengwk.kkstudio.harness.model.provider.impl.openai.chat.OpenAiChatProviderAdapter;
import fun.fengwk.kkstudio.harness.model.provider.impl.openai.responses.OpenAiResponsesProviderAdapter;
import fun.fengwk.kkstudio.harness.model.provider.impl.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 验证 4 个 named ProviderFactory bean 全部被收集到类型索引，同时验证生产 {@link PlatformModelGateway} 被组合进上下文。 */
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

  @Autowired private Map<ProviderType, ProviderFactory> providerFactories;

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
  }

  @Test
  void providerFactoriesLookupAllFourTypes() {
    assertSame(openaiProviderFactory, providerFactories.get(ProviderType.OPENAI));
    assertSame(
        openaiResponsesProviderFactory, providerFactories.get(ProviderType.OPENAI_RESPONSES));
    assertSame(anthropicProviderFactory, providerFactories.get(ProviderType.ANTHROPIC));
    assertSame(googleProviderFactory, providerFactories.get(ProviderType.GOOGLE));
    assertThrows(UnsupportedOperationException.class, () -> providerFactories.clear());
  }

  /** 意图：重复类型和空类型在建立索引时立即失败，而非覆盖已有注册。 */
  @Test
  void rejectsDuplicateAndNullProviderTypes() {
    DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    ProviderFactory first = mock(ProviderFactory.class);
    ProviderFactory second = mock(ProviderFactory.class);
    when(first.providerType()).thenReturn(ProviderType.OPENAI);
    when(second.providerType()).thenReturn(ProviderType.OPENAI);
    beans.registerSingleton("first", first);
    beans.registerSingleton("second", second);
    ModelExecutionConfiguration configuration = new ModelExecutionConfiguration();
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> configuration.providerFactories(beans.getBeanProvider(ProviderFactory.class)));
    assertTrue(error.getMessage().contains("duplicate ProviderFactory for OPENAI"));
    when(second.providerType()).thenReturn(null);
    assertThrows(
        NullPointerException.class,
        () -> configuration.providerFactories(beans.getBeanProvider(ProviderFactory.class)));
    assertThrows(NullPointerException.class, () -> configuration.providerFactories(null));
  }

  /** 意图：验证 Spring 装配的 ProviderFactory 返回原生 Harness Provider 适配器，并共享 transport。 */
  @Test
  void createsTheAdapterMatchingEachProviderType() {
    assertInstanceOf(OpenAiChatProviderAdapter.class, openaiProviderFactory.create("credential"));
    assertInstanceOf(
        OpenAiResponsesProviderAdapter.class, openaiResponsesProviderFactory.create("credential"));
    assertInstanceOf(AnthropicProviderAdapter.class, anthropicProviderFactory.create("credential"));
    assertInstanceOf(GeminiProviderAdapter.class, googleProviderFactory.create("credential"));
  }

  /** 意图：验证每个 Spring ProviderFactory 均能贯通原生 adapter 并创建对应 ModelProvider。 */
  @Test
  void createsNativeModelProviderThroughEveryFactory() {
    assertNativeModelProvider(openaiProviderFactory, ProviderType.OPENAI);
    assertNativeModelProvider(openaiResponsesProviderFactory, ProviderType.OPENAI_RESPONSES);
    assertNativeModelProvider(anthropicProviderFactory, ProviderType.ANTHROPIC);
    assertNativeModelProvider(googleProviderFactory, ProviderType.GOOGLE);
  }

  private static void assertNativeModelProvider(ProviderFactory factory, ProviderType type) {
    ModelProvider modelProvider =
        factory
            .create("credential")
            .create(
                new ProviderDescriptor(
                    "provider-" + type.wireValue(),
                    type,
                    "https://provider.example/v1",
                    ModelCallTimeoutPolicy.DEFAULT));
    assertNotNull(modelProvider);
  }
}
