package fun.fengwk.kkstudio.core.harness.tool.worker;

import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtensionHost;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservers;
import fun.fengwk.kkstudio.harness.runtime.task.TaskRuntime;
import fun.fengwk.kkstudio.harness.runtime.task.TaskTool;
import fun.fengwk.kkstudio.harness.runtime.task.WorkingCopyRevisionResolver;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInterceptorChain;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ArtifactStore;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.CloudToolWorker;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolInvocationTransactions;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolInvocationWorkerStore;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolRegistry;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolWorkerConfig;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires Cloud/Control SPI implementations to the database-backed durable Tool worker. */
@Configuration(proxyBeanMethods = false)
public class HarnessToolWorkerConfiguration {
  @Bean
  @ConditionalOnMissingBean
  public ToolWorkerConfig toolWorkerConfig() {
    return ToolWorkerConfig.DEFAULT;
  }

  @Bean(destroyMethod = "shutdown")
  @ConditionalOnMissingBean(name = "toolWorkerScheduler")
  public ScheduledExecutorService toolWorkerScheduler() {
    return Executors.newScheduledThreadPool(2);
  }

  @Bean
  @ConditionalOnMissingBean
  public WorkingCopyRevisionResolver workingCopyRevisionResolver() {
    return (policy, childSessionId) -> Optional.empty();
  }

  @Bean
  @ConditionalOnBean(TaskRuntime.class)
  @ConditionalOnMissingBean
  public TaskTool taskTool(
      TaskRuntime runtime, ScheduledExecutorService toolWorkerScheduler, Clock clock) {
    return new TaskTool(runtime, toolWorkerScheduler, clock);
  }

  @Bean
  @ConditionalOnBean(HarnessExtensionHost.class)
  @ConditionalOnMissingBean
  public ToolRegistry toolRegistry(HarnessExtensionHost host) {
    return host::createTool;
  }

  @Bean
  @ConditionalOnBean(ToolRegistry.class)
  @ConditionalOnMissingBean
  public CloudToolWorker cloudToolWorker(
      ToolInvocationWorkerStore store,
      ToolInvocationTransactions transactions,
      ToolRegistry registry,
      ToolInterceptorChain interceptorChain,
      ArtifactStore artifactStore,
      ToolWorkerConfig config,
      Clock clock,
      ScheduledExecutorService toolWorkerScheduler,
      HarnessLifecycleObservers lifecycleObservers) {
    return new CloudToolWorker(
        store,
        transactions,
        registry,
        interceptorChain,
        artifactStore,
        config,
        clock,
        toolWorkerScheduler,
        lifecycleObservers);
  }
}
