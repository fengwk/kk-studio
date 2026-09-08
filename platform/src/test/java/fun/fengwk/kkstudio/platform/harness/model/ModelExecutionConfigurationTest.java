package fun.fengwk.kkstudio.platform.harness.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import fun.fengwk.kkstudio.harness.provider.anthropic.AnthropicProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactories;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactory;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.harness.model.provider.GoogleProviderAdapter;
import fun.fengwk.kkstudio.platform.harness.model.provider.OpenAiProviderAdapter;
import fun.fengwk.kkstudio.platform.harness.model.provider.OpenAiResponsesProviderAdapter;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;

import java.net.http.HttpClient;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

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
  @Qualifier("modelExecutionWatchdogScheduler")
  private ScheduledExecutorService modelExecutionWatchdogScheduler;

  @Autowired
  @Qualifier("modelExecutionTransport")
  private JdkHttpSseTransport modelExecutionTransport;

  @Test
  void composesPlatformModelGatewayWithSharedExecutorAndConfig() {
    assertNotNull(platformModelGateway);
  }

  /** 意图：原生 Provider 复用 Spring 托管 worker，禁止重定向，并由独立 Watchdog 调度器驱动超时。 */
  @Test
  void configuresManagedNativeProviderTransport() {
    assertEquals(HttpClient.Redirect.NEVER, modelExecutionHttpClient.followRedirects());
    assertSame(modelExecutionExecutor, modelExecutionHttpClient.executor().orElseThrow());
    assertNotNull(modelExecutionTransport);
    assertFalse(modelExecutionWatchdogScheduler.isShutdown());
  }

  @Test
  void registersAllFourNamedProviderFactoryBeans() {
    assertEquals(ProviderType.OPENAI, openaiProviderFactory.providerType());
    assertEquals(ProviderType.OPENAI_RESPONSES, openaiResponsesProviderFactory.providerType());
    assertEquals(ProviderType.ANTHROPIC, anthropicProviderFactory.providerType());
    assertEquals(ProviderType.GOOGLE, googleProviderFactory.providerType());
    assertEquals(
        Set.of(PromptCacheRetention.SHORT, PromptCacheRetention.LONG),
        anthropicProviderFactory.promptCacheCapability().supportedRetentions());
    assertEquals(
        EnumSet.allOf(PromptCacheBreakpoint.class),
        anthropicProviderFactory.promptCacheCapability().supportedBreakpoints());
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

  @Test
  void createsTheAdapterMatchingEachProviderType() {
    assertInstanceOf(OpenAiProviderAdapter.class, openaiProviderFactory.create("credential", "{}"));
    assertInstanceOf(
        OpenAiResponsesProviderAdapter.class,
        openaiResponsesProviderFactory.create("credential", "{}"));
    assertInstanceOf(
        AnthropicProviderAdapter.class, anthropicProviderFactory.create("credential", "{}"));
    assertInstanceOf(GoogleProviderAdapter.class, googleProviderFactory.create("credential", "{}"));
  }
}
