package fun.fengwk.kkstudio.core.harness.model;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.core.harness.model.provider.AnthropicProviderAdapter;
import fun.fengwk.kkstudio.core.harness.model.provider.GoogleProviderAdapter;
import fun.fengwk.kkstudio.core.harness.model.provider.OpenAiProviderAdapter;
import fun.fengwk.kkstudio.core.harness.model.provider.OpenAiResponsesProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactories;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactory;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.time.Clock;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Spring wiring for the production {@code ModelExecution} adapter.
 *
 * <p>Provider I/O runs on Java 21 virtual threads. The executor is an infrastructure resource owned
 * by Spring and closes with the application context; it carries blocking Provider calls only, never
 * durable actor continuations.
 *
 * <p>每个 {@link ProviderFactory} bean 都有稳定的唯一名称，可以独立替换而不影响其余 Provider 类型。
 */
@Configuration(proxyBeanMethods = false)
public class ModelExecutionConfiguration {

  /** Virtual-thread executor for blocking Provider I/O. */
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
