package fun.fengwk.kkstudio.core.harness.tool.worker;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Wires SPI implementations to the single-path durable Tool worker. */
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

  /**
   * Dedicated executor for post-claim external Tool execution. Kept separate from {@link
   * #toolWorkerScheduler()} so watchdogs (heartbeat / deadline / partial flush) are not coupled to
   * potentially long-running blocking Tool I/O. Java 21 virtual threads are a natural fit for
   * blocking remote Tool sends; {@code destroyMethod = "shutdown"} keeps in-flight tasks owned by
   * Spring lifecycle but does not wait, matching the existing scheduler wiring.
   */
  @Bean(destroyMethod = "shutdown")
  @ConditionalOnMissingBean(name = "toolWorkerExecutor")
  public ExecutorService toolWorkerExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  @Bean
  @ConditionalOnBean(ToolFactories.class)
  @ConditionalOnMissingBean
  public ToolRegistry toolRegistry(ToolFactories toolFactories) {
    return toolFactories::find;
  }

  @Bean(destroyMethod = "stop")
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
      ToolWorkerConfig config,
      Clock clock,
      @Qualifier("toolWorkerScheduler") ScheduledExecutorService toolWorkerScheduler,
      @Qualifier("toolWorkerExecutor") ExecutorService toolWorkerExecutor) {
    return new ToolWorker(
        transactions,
        registry,
        remoteTransport,
        interceptorChain,
        artifactStore,
        retryPolicyResolver,
        realtimeEventSink,
        config,
        clock,
        toolWorkerScheduler,
        toolWorkerExecutor,
        () -> UUID.randomUUID().toString());
  }
}
