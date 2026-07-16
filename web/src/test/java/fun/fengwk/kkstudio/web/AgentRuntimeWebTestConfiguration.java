package fun.fengwk.kkstudio.web;

import fun.fengwk.kkstudio.agent.provider.ProviderInfo;
import fun.fengwk.kkstudio.agent.provider.ProviderManager;
import fun.fengwk.kkstudio.agent.provider.ProviderType;
import fun.fengwk.kkstudio.core.agent.runtime.provider.AgentProviderInfoResolver;
import fun.fengwk.kkstudio.web.testing.StubProviderManager;
import java.time.Duration;
import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * @author fengwk
 */
@Configuration
public class AgentRuntimeWebTestConfiguration {

  @Bean
  public StubProviderManager stubProviderManager() {
    return new StubProviderManager();
  }

  @Bean
  @Primary
  public ProviderManager providerManager(StubProviderManager stubProviderManager) {
    return stubProviderManager;
  }

  @Bean
  @Primary
  public AgentProviderInfoResolver agentProviderInfoResolver() {
    return provider ->
        ProviderInfo.builder()
            .providerType(ProviderType.openai)
            .baseUrl("http://stub-provider")
            .apiKey("stub-api-key")
            .timeout(Duration.ofSeconds(30))
            .build();
  }

  @Bean(name = "agentRunTaskExecutor")
  @Primary
  public Executor agentRunTaskExecutor() {
    return Runnable::run;
  }

  /**
   * Force the SSE runtime to execute inline so {@code MockMvc#asyncDispatch} can deterministically
   * observe the streamed events without depending on the default {@link
   * org.springframework.core.task.SimpleAsyncTaskExecutor}'s daemon scheduling. Not marked {@link
   * Primary} so the existing {@code agentRunTaskExecutor} primary remains the autowire target for
   * callers that take a plain {@link Executor}.
   */
  @Bean(name = "agentEventStreamTaskExecutor")
  public Executor agentEventStreamTaskExecutor() {
    return Runnable::run;
  }
}
