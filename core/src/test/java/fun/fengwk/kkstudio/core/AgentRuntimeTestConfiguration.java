package fun.fengwk.kkstudio.core;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import fun.fengwk.kkstudio.agent.provider.ProviderInfo;
import fun.fengwk.kkstudio.agent.provider.ProviderManager;
import fun.fengwk.kkstudio.agent.provider.ProviderType;
import fun.fengwk.kkstudio.core.agent.runtime.provider.AgentProviderInfoResolver;
import fun.fengwk.kkstudio.core.testing.StubProviderManager;

import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * @author fengwk
 */
@Configuration
public class AgentRuntimeTestConfiguration {

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
            .streamIdleTimeout(Duration.ofSeconds(30))
            .build();
  }

  @Bean(name = "agentRunTaskExecutor")
  @Primary
  public Executor agentRunTaskExecutor() {
    return Runnable::run;
  }
}
