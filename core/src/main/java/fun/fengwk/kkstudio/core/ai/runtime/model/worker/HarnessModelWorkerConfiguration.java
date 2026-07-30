package fun.fengwk.kkstudio.core.ai.runtime.model.worker;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.core.ai.runtime.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelExecutionResolver;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelInvocationTransactions;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelWorker;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelWorkerConfig;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicyResolver;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Spring composition for the durable Model worker and its execution scheduler. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HarnessRuntimeProperties.class)
public class HarnessModelWorkerConfiguration {

  @Bean(name = "modelWorkerScheduler", destroyMethod = "shutdown")
  public ScheduledExecutorService modelWorkerScheduler() {
    return Executors.newScheduledThreadPool(
        2, Thread.ofPlatform().name("model-worker-", 0L).daemon(true).factory());
  }

  @Bean
  public ModelWorkerConfig modelWorkerConfig(HarnessRuntimeProperties properties) {
    return new ModelWorkerConfig(
        properties.getModelWorkerLeaseDuration(),
        properties.getModelWorkerHeartbeatInterval(),
        properties.getModelWorkerActivityFlushInterval());
  }

  @Bean(destroyMethod = "stop")
  public ModelWorker modelWorker(
      ModelInvocationTransactions transactions,
      ModelExecutionResolver executionResolver,
      InvocationRetryPolicyResolver retryPolicyResolver,
      RealtimeEventSink realtimeEventSink,
      ModelWorkerConfig config,
      Clock clock,
      @Qualifier("modelWorkerScheduler") ScheduledExecutorService modelWorkerScheduler) {
    return new ModelWorker(
        transactions,
        executionResolver,
        retryPolicyResolver,
        realtimeEventSink,
        config,
        clock,
        modelWorkerScheduler,
        () -> UUID.randomUUID().toString());
  }
}
