package fun.fengwk.kkstudio.platform.harness.model;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.provider.anthropic.AnthropicProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.gemini.GeminiProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.openai.chat.OpenAiChatProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.openai.responses.OpenAiResponsesProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.runtime.admission.ConcurrencyAdmission;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactories;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactory;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.port.ModelGateway;
import fun.fengwk.kkstudio.platform.harness.configuration.HarnessExecutionAdmissionProperties;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 生产 {@code ModelExecution} 适配器的 Spring 装配。
 *
 * <p>Provider I/O 运行在 Java 21 虚拟线程上。executor 是 Spring 拥有的基础设施资源，随应用上下文 关闭；它只承载阻塞的 Provider
 * 调用，绝不承载持久化 actor 续体。
 *
 * <p>每个 {@link ProviderFactory} bean 都有稳定的唯一名称，可以独立替换而不影响其余 Provider 类型。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HarnessExecutionAdmissionProperties.class)
public class ModelExecutionConfiguration {

  @Bean(name = "modelExecutionAdmission")
  @ConditionalOnMissingBean(name = "modelExecutionAdmission")
  public ConcurrencyAdmission modelExecutionAdmission(
      HarnessExecutionAdmissionProperties properties) {
    return new ConcurrencyAdmission(properties.getModel());
  }

  /** 阻塞 Provider I/O 使用的虚拟线程 executor。 */
  @Bean(name = "modelExecutionExecutor", destroyMethod = "close")
  @ConditionalOnMissingBean(name = "modelExecutionExecutor")
  public ExecutorService modelExecutionExecutor() {
    return Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("model-provider-", 0L).factory());
  }

  /** 模型调用固定使用 HTTP/1.1，避免明文网关链路的 h2c 升级；禁止重定向并复用受管 worker。 */
  @Bean(name = "modelExecutionHttpClient", destroyMethod = "close")
  @ConditionalOnMissingBean(name = "modelExecutionHttpClient")
  public HttpClient modelExecutionHttpClient(
      @Qualifier("modelExecutionExecutor") ExecutorService modelExecutionExecutor) {
    return HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER)
        .executor(modelExecutionExecutor)
        .build();
  }

  /** 生产环境流式传输巡检专用的 daemon 调度器，随应用上下文关闭。 */
  @Bean(name = "modelExecutionWatchdogScheduler", destroyMethod = "shutdownNow")
  @ConditionalOnMissingBean(name = "modelExecutionWatchdogScheduler")
  public ScheduledExecutorService modelExecutionWatchdogScheduler() {
    return Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().name("model-watchdog-", 0L).daemon(true).factory());
  }

  /** 基于 JDK 21 HttpClient、受管虚拟线程 worker 与 Watchdog 调度器的长生命周期受管 SSE 传输器。 */
  @Bean(name = "modelExecutionTransport")
  @ConditionalOnMissingBean(name = "modelExecutionTransport")
  public JdkHttpSseTransport modelExecutionTransport(
      @Qualifier("modelExecutionHttpClient") HttpClient httpClient,
      @Qualifier("modelExecutionExecutor") ExecutorService modelExecutionExecutor,
      @Qualifier("modelExecutionWatchdogScheduler") ScheduledExecutorService scheduler) {
    return new JdkHttpSseTransport(httpClient, modelExecutionExecutor, scheduler);
  }

  @Bean
  @ConditionalOnMissingBean
  public Clock modelExecutionClock() {
    return Clock.systemUTC();
  }

  /**
   * 生产 {@link PlatformModelGateway}：与测试共用唯一构造器。Busy 重试延迟是 live supplier——每次 {@code Busy} 判定从
   * SystemSettingsSnapshot 现读 {@code tool.modelGatewayBusyRetryMillis}。任何自定义 {@link ModelGateway}
   * bean 都会抑制该默认实现。
   */
  @Bean
  @ConditionalOnMissingBean(ModelGateway.class)
  public PlatformModelGateway platformModelGateway(
      ProviderResolutionService providerResolution,
      @Qualifier("modelExecutionExecutor") ExecutorService modelExecutionExecutor,
      SystemSettingsSnapshot systemSettingsSnapshot,
      @Qualifier("modelExecutionAdmission") ConcurrencyAdmission admission) {
    return new PlatformModelGateway(
        providerResolution,
        modelExecutionExecutor,
        () -> Duration.ofMillis(systemSettingsSnapshot.get().tool().modelGatewayBusyRetryMillis()),
        admission);
  }

  @Bean(name = "openaiProviderFactory")
  @ConditionalOnMissingBean(name = "openaiProviderFactory")
  public ProviderFactory openaiProviderFactory(
      @Qualifier("modelExecutionTransport") JdkHttpSseTransport transport) {
    return ProviderFactory.of(
        ProviderType.OPENAI,
        OpenAiChatProviderAdapter::promptCacheCapability,
        (credential, configJson) ->
            new OpenAiChatProviderAdapter(transport, credential, configJson));
  }

  @Bean(name = "openaiResponsesProviderFactory")
  @ConditionalOnMissingBean(name = "openaiResponsesProviderFactory")
  public ProviderFactory openaiResponsesProviderFactory(
      @Qualifier("modelExecutionTransport") JdkHttpSseTransport transport) {
    return ProviderFactory.of(
        ProviderType.OPENAI_RESPONSES,
        OpenAiResponsesProviderAdapter::resolvePromptCacheCapability,
        (credential, configJson) ->
            new OpenAiResponsesProviderAdapter(transport, credential, configJson));
  }

  @Bean(name = "anthropicProviderFactory")
  @ConditionalOnMissingBean(name = "anthropicProviderFactory")
  public ProviderFactory anthropicProviderFactory(
      @Qualifier("modelExecutionTransport") JdkHttpSseTransport transport) {
    return ProviderFactory.of(
        ProviderType.ANTHROPIC,
        PromptCacheCapability.breakpoints(
            Set.of(PromptCacheRetention.SHORT, PromptCacheRetention.LONG),
            EnumSet.of(
                PromptCacheBreakpoint.SYSTEM,
                PromptCacheBreakpoint.TOOLS,
                PromptCacheBreakpoint.CONVERSATION)),
        (credential, configJson) ->
            new AnthropicProviderAdapter(transport, credential, configJson));
  }

  @Bean(name = "googleProviderFactory")
  @ConditionalOnMissingBean(name = "googleProviderFactory")
  public ProviderFactory googleProviderFactory(
      @Qualifier("modelExecutionTransport") JdkHttpSseTransport transport) {
    return ProviderFactory.of(
        ProviderType.GOOGLE,
        PromptCacheCapability.automatic(),
        (credential, configJson) -> new GeminiProviderAdapter(transport, credential));
  }

  @Bean
  public ProviderFactories providerFactories(ObjectProvider<ProviderFactory> providerFactoryBeans) {
    return new ProviderFactories(providerFactoryBeans.orderedStream().toList());
  }
}
