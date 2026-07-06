package fun.fengwk.kkstudio.core.agent.runtime.configuration;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import fun.fengwk.kkstudio.agent.provider.ProviderManager;
import fun.fengwk.kkstudio.agent.provider.ProviderManagerImpl;
import fun.fengwk.kkstudio.core.agent.runtime.provider.AgentProviderInfoResolver;
import fun.fengwk.kkstudio.core.agent.runtime.provider.ConfiguredAgentProviderInfoResolver;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/**
 * @author fengwk
 */
public class AgentRuntimeAutoConfigurationTest {

  @Test
  public void shouldCreateDefaultRuntimeBeans() {
    AgentRuntimeAutoConfiguration configuration = new AgentRuntimeAutoConfiguration();
    AgentRuntimeProperties properties = new AgentRuntimeProperties();

    AgentProviderInfoResolver resolver = configuration.agentProviderInfoResolver(properties);
    ProviderManager providerManager = configuration.providerManager();
    Executor taskExecutor = configuration.agentRunTaskExecutor();
    ExecutorService workerExecutorService = configuration.agentToolWorkerExecutorService();
    ScheduledExecutorService scheduledExecutorService =
        configuration.agentRuntimeScheduledExecutorService();

    try {
      assertInstanceOf(ConfiguredAgentProviderInfoResolver.class, resolver);
      assertInstanceOf(ProviderManagerImpl.class, providerManager);
      assertInstanceOf(SimpleAsyncTaskExecutor.class, taskExecutor);
      assertNotNull(workerExecutorService);
      assertNotNull(scheduledExecutorService);
    } finally {
      workerExecutorService.shutdownNow();
      scheduledExecutorService.shutdownNow();
    }
  }
}
