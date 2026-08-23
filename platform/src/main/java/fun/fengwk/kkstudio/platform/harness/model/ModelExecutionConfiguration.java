package fun.fengwk.kkstudio.platform.harness.model;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactories;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactory;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.port.ModelGateway;
import fun.fengwk.kkstudio.platform.harness.model.provider.AnthropicProviderAdapter;
import fun.fengwk.kkstudio.platform.harness.model.provider.GoogleProviderAdapter;
import fun.fengwk.kkstudio.platform.harness.model.provider.OpenAiProviderAdapter;
import fun.fengwk.kkstudio.platform.harness.model.provider.OpenAiResponsesProviderAdapter;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 生产 {@code ModelExecution} 适配器的 Spring 装配。
 *
 * <p>Provider I/O 运行在 Java 21 虚拟线程上。executor 是 Spring 拥有的基础设施资源，随应用上下文 关闭；它只承载阻塞的 Provider
 * 调用，绝不承载持久化 actor 续体。
 *
 * <p>每个 {@link ProviderFactory} bean 都有稳定的唯一名称，可以独立替换而不影响其余 Provider 类型。
 */
@Configuration(proxyBeanMethods = false)
public class ModelExecutionConfiguration {

  /** 阻塞 Provider I/O 使用的虚拟线程 executor。 */
  @Bean(name = "modelExecutionExecutor", destroyMethod = "close")
  @ConditionalOnMissingBean(name = "modelExecutionExecutor")
  public ExecutorService modelExecutionExecutor() {
    return Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("model-provider-", 0L).factory());
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
      SystemSettingsSnapshot systemSettingsSnapshot) {
    return new PlatformModelGateway(
        providerResolution,
        modelExecutionExecutor,
        () -> Duration.ofMillis(systemSettingsSnapshot.get().tool().modelGatewayBusyRetryMillis()));
  }

  @Bean(name = "openaiProviderFactory")
  @ConditionalOnMissingBean(name = "openaiProviderFactory")
  public ProviderFactory openaiProviderFactory() {
    return ProviderFactory.of(
        ProviderType.OPENAI,
        PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)),
        (credential, configJson) -> new OpenAiProviderAdapter(credential));
  }

  @Bean(name = "openaiResponsesProviderFactory")
  @ConditionalOnMissingBean(name = "openaiResponsesProviderFactory")
  public ProviderFactory openaiResponsesProviderFactory() {
    return ProviderFactory.of(
        ProviderType.OPENAI_RESPONSES,
        PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)),
        (credential, configJson) -> new OpenAiResponsesProviderAdapter(credential));
  }

  @Bean(name = "anthropicProviderFactory")
  @ConditionalOnMissingBean(name = "anthropicProviderFactory")
  public ProviderFactory anthropicProviderFactory() {
    return ProviderFactory.of(
        ProviderType.ANTHROPIC,
        PromptCacheCapability.breakpoints(
            Set.of(PromptCacheRetention.SHORT),
            EnumSet.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS)),
        (credential, configJson) -> new AnthropicProviderAdapter(credential));
  }

  @Bean(name = "googleProviderFactory")
  @ConditionalOnMissingBean(name = "googleProviderFactory")
  public ProviderFactory googleProviderFactory() {
    return ProviderFactory.of(
        ProviderType.GOOGLE,
        PromptCacheCapability.automatic(),
        (credential, configJson) -> new GoogleProviderAdapter(credential));
  }

  @Bean
  public ProviderFactories providerFactories(ObjectProvider<ProviderFactory> providerFactoryBeans) {
    return new ProviderFactories(providerFactoryBeans.orderedStream().toList());
  }
}
