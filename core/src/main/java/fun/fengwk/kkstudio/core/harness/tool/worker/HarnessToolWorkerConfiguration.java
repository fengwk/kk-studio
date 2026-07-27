package fun.fengwk.kkstudio.core.harness.tool.worker;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.core.environment.gateway.EnvironmentReadyListener;
import fun.fengwk.kkstudio.core.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.core.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.harness.runtime.port.ActivationNotifier;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicyResolver;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactories;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInterceptorChain;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ArtifactStore;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolInvocationTransactions;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolRegistry;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolWorker;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolWorkerConfig;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolTransport;

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
  @ConditionalOnBean(ToolFactories.class)
  @ConditionalOnMissingBean
  public ToolRegistry toolRegistry(ToolFactories toolFactories) {
    return toolFactories::find;
  }

  /**
   * Production READY bridge. Uses a lazy {@link ToolWorker} so Gateway construction does not form a
   * cycle with this listener.
   */
  @Bean
  @ConditionalOnMissingBean
  public EnvironmentReadyListener environmentReadyListener(
      ObjectProvider<ToolWorker> toolWorker,
      @Qualifier("toolWorkerScheduler") ScheduledExecutorService toolWorkerScheduler) {
    return new ToolWorkerEnvironmentReadyListener(toolWorker, toolWorkerScheduler);
  }

  @Bean
  @ConditionalOnBean({ToolRegistry.class, RemoteToolTransport.class})
  @ConditionalOnMissingBean
  public ToolWorker toolWorker(
      ToolInvocationTransactions transactions,
      ToolRegistry registry,
      RemoteToolTransport remoteTransport,
      ToolInterceptorChain interceptorChain,
      ArtifactStore artifactStore,
      InvocationRetryPolicyResolver retryPolicyResolver,
      RealtimeEventSink realtimeEventSink,
      ActivationNotifier activationNotifier,
      ToolWorkerConfig config,
      Clock clock,
      @Qualifier("toolWorkerScheduler") ScheduledExecutorService toolWorkerScheduler) {
    return new ToolWorker(
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
        () -> UUID.randomUUID().toString());
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
