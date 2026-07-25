package fun.fengwk.kkstudio.core.harness.tool.worker;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.core.environment.gateway.EnvironmentDaemonGateway;
import fun.fengwk.kkstudio.core.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.core.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtensionHost;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservers;
import fun.fengwk.kkstudio.harness.runtime.port.ActivationNotifier;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicyResolver;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInterceptorChain;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ArtifactStore;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolInvocationTransactions;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolRegistry;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolWorker;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolWorkerConfig;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionLocation;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Wires SPI implementations to the location-agnostic durable Tool worker. */
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
    return Executors.newScheduledThreadPool(
        2, Thread.ofPlatform().name("tool-worker-", 0L).daemon(true).factory());
  }

  @Bean
  @ConditionalOnBean(HarnessExtensionHost.class)
  @ConditionalOnMissingBean
  public ToolRegistry toolRegistry(HarnessExtensionHost host) {
    return host::createTool;
  }

  @Bean
  @ConditionalOnBean({ToolRegistry.class, EnvironmentDaemonGateway.class})
  @ConditionalOnMissingBean
  public ToolWorker toolWorker(
      ToolInvocationTransactions transactions,
      ToolRegistry registry,
      EnvironmentDaemonGateway remoteTransport,
      ToolInterceptorChain interceptorChain,
      ArtifactStore artifactStore,
      InvocationRetryPolicyResolver retryPolicyResolver,
      RealtimeEventSink realtimeEventSink,
      ActivationNotifier activationNotifier,
      ToolWorkerConfig config,
      Clock clock,
      @Qualifier("toolWorkerScheduler") ScheduledExecutorService toolWorkerScheduler,
      HarnessLifecycleObservers lifecycleObservers) {
    ToolWorker worker =
        new ToolWorker(
            transactions,
            registry,
            remoteTransport,
            interceptorChain,
            artifactStore,
            retryPolicyResolver,
            realtimeEventSink,
            activationNotifier,
            config,
            clock,
            toolWorkerScheduler,
            lifecycleObservers,
            () -> UUID.randomUUID().toString());
    remoteTransport.setReadyDispatchExecutor(toolWorkerScheduler);
    remoteTransport.setEnvironmentReadyHandler(
        environmentName -> worker.dispatchNext(ToolExecutionLocation.ENVIRONMENT, environmentName));
    return worker;
  }

  @Bean
  public ToolWorkerLifecycle toolWorkerLifecycle(
      HarnessRuntimeProperties properties,
      ToolWorker worker,
      LiveEnvironmentRegistry environmentRegistry,
      @Qualifier("toolWorkerScheduler") ScheduledExecutorService scheduler) {
    return new ToolWorkerLifecycle(
        properties,
        worker,
        environmentRegistry,
        scheduler,
        properties.getToolRecoveryInterval(),
        properties.getToolRecoveryBatchSize());
  }
}
