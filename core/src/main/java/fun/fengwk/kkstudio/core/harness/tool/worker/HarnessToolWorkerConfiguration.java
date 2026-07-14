package fun.fengwk.kkstudio.core.harness.tool.worker;

import fun.fengwk.kkstudio.harness.runtime.task.TaskRuntime;
import fun.fengwk.kkstudio.harness.runtime.task.TaskTool;
import fun.fengwk.kkstudio.harness.runtime.task.WorkspaceRevisionPort;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ArtifactStore;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.CloudToolWorker;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolInvocationTransactions;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolInvocationWorkerStore;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolRegistry;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolWorkerConfig;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import java.time.Clock;
import java.util.List;
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
  public WorkspaceRevisionPort workspaceRevisionPort() {
    return (workspaceId, policy, childSessionId) -> Optional.empty();
  }

  @Bean
  @ConditionalOnBean(TaskRuntime.class)
  @ConditionalOnMissingBean
  public TaskTool taskTool(
      TaskRuntime runtime, ScheduledExecutorService toolWorkerScheduler, Clock clock) {
    return new TaskTool(runtime, toolWorkerScheduler, clock);
  }

  @Bean
  @ConditionalOnBean(Tool.class)
  @ConditionalOnMissingBean
  public ToolRegistry toolRegistry(List<Tool> tools) {
    return (name, version) ->
        tools.stream()
            .filter(tool -> tool.descriptor().name().equals(name))
            .filter(tool -> tool.descriptor().version().equals(version))
            .findFirst();
  }

  @Bean
  @ConditionalOnBean(ToolRegistry.class)
  @ConditionalOnMissingBean
  public CloudToolWorker cloudToolWorker(
      ToolInvocationWorkerStore store,
      ToolInvocationTransactions transactions,
      ToolRegistry registry,
      ArtifactStore artifactStore,
      ToolWorkerConfig config,
      Clock clock,
      ScheduledExecutorService toolWorkerScheduler) {
    return new CloudToolWorker(
        store, transactions, registry, artifactStore, config, clock, toolWorkerScheduler);
  }
}
