package fun.fengwk.kkstudio.web;

import fun.fengwk.kkstudio.agent.provider.ProviderInfo;
import fun.fengwk.kkstudio.agent.provider.ProviderManager;
import fun.fengwk.kkstudio.agent.provider.ProviderType;
import fun.fengwk.kkstudio.core.agent.runtime.provider.AgentProviderInfoResolver;
import java.time.Duration;
import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import fun.fengwk.kkstudio.agent.provider.ProviderInfo;
import fun.fengwk.kkstudio.agent.provider.ProviderManager;
import fun.fengwk.kkstudio.agent.provider.ProviderType;
import fun.fengwk.kkstudio.core.agent.runtime.provider.AgentProviderInfoResolver;
import java.time.Duration;
import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * 本地 H2 验收模式配置。
 *
 * <p>该模式下使用本地 H2、最小 stub provider，以及同步 run executor，便于手工验收。
 *
 * @author fengwk
 */
@Configuration
@Profile("local-h2")
public class LocalH2AcceptanceConfiguration {

  @Bean
  public AcceptanceStubProviderManager acceptanceStubProviderManager() {
    return new AcceptanceStubProviderManager();
  }

  @Bean
  @Primary
  public ProviderManager providerManager(
      AcceptanceStubProviderManager acceptanceStubProviderManager) {
    return acceptanceStubProviderManager;
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
