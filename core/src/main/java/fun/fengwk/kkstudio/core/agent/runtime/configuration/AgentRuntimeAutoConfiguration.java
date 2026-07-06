package fun.fengwk.kkstudio.core.agent.runtime.configuration;

import fun.fengwk.kkstudio.agent.provider.ProviderManager;
import fun.fengwk.kkstudio.agent.provider.ProviderManagerImpl;
import fun.fengwk.kkstudio.core.agent.runtime.provider.AgentProviderInfoResolver;
import fun.fengwk.kkstudio.core.agent.runtime.provider.ConfiguredAgentProviderInfoResolver;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import fun.fengwk.kkstudio.agent.provider.ProviderManager;
import fun.fengwk.kkstudio.agent.provider.ProviderManagerImpl;
import fun.fengwk.kkstudio.core.agent.runtime.provider.AgentProviderInfoResolver;
import fun.fengwk.kkstudio.core.agent.runtime.provider.ConfiguredAgentProviderInfoResolver;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/**
 * @author fengwk
 */
@Configuration
@EnableConfigurationProperties(AgentRuntimeProperties.class)
public class AgentRuntimeAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public AgentProviderInfoResolver agentProviderInfoResolver(AgentRuntimeProperties properties) {
    return new ConfiguredAgentProviderInfoResolver(properties);
  }

  @Bean
  @ConditionalOnMissingBean
  public ProviderManager providerManager() {
    return new ProviderManagerImpl();
  }

  @Bean(name = "agentRunTaskExecutor")
  @ConditionalOnMissingBean(name = "agentRunTaskExecutor")
  public Executor agentRunTaskExecutor() {
    return new SimpleAsyncTaskExecutor("agent-run-");
  }

  @Bean(name = "agentEventStreamTaskExecutor")
  @ConditionalOnMissingBean(name = "agentEventStreamTaskExecutor")
  public Executor agentEventStreamTaskExecutor() {
    return new SimpleAsyncTaskExecutor("agent-event-stream-");
  }

  @Bean(name = "agentToolWorkerExecutorService", destroyMethod = "shutdown")
  @ConditionalOnMissingBean(name = "agentToolWorkerExecutorService")
  public ExecutorService agentToolWorkerExecutorService() {
    return Executors.newCachedThreadPool();
  }

  @Bean(name = "agentRuntimeScheduledExecutorService", destroyMethod = "shutdown")
  @ConditionalOnMissingBean(name = "agentRuntimeScheduledExecutorService")
  public ScheduledExecutorService agentRuntimeScheduledExecutorService() {
    return Executors.newSingleThreadScheduledExecutor();
  }
}
