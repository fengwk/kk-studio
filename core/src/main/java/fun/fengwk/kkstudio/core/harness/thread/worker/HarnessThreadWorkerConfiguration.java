package fun.fengwk.kkstudio.core.harness.thread.worker;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.core.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadMapper;
import fun.fengwk.kkstudio.harness.agent.extension.ProviderRequestInterceptorChain;
import fun.fengwk.kkstudio.harness.runtime.context.DefaultContextTransform;
import fun.fengwk.kkstudio.harness.runtime.context.SessionContextBuilder;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtensionHost;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservers;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryStore;
import fun.fengwk.kkstudio.harness.runtime.thread.CompactionService;
import fun.fengwk.kkstudio.harness.runtime.thread.DeltaFlushScheduler;
import fun.fengwk.kkstudio.harness.runtime.thread.InterceptingCompactionService;
import fun.fengwk.kkstudio.harness.runtime.thread.ProviderMessageProjector;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadKick;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadTransactions;
import fun.fengwk.kkstudio.harness.runtime.thread.TurnResourceResolver;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** ThreadProcessor 与 event-triggered tool port 的 Spring wiring；无周期性 Run poller。 */
@Configuration
@EnableConfigurationProperties(HarnessRuntimeProperties.class)
public class HarnessThreadWorkerConfiguration {

  @Bean(destroyMethod = "shutdown")
  public ExecutorService threadProcessorExecutor(ThreadProcessorConfig config) {
    int concurrency = config.maxExecutorConcurrency();
    return new ThreadPoolExecutor(
        concurrency,
        concurrency,
        60L,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(Math.max(concurrency * 4, concurrency)),
        r -> {
          Thread t = new Thread(r, "thread-processor");
          t.setDaemon(true);
          return t;
        },
        new ThreadPoolExecutor.AbortPolicy());
  }

  @Bean(destroyMethod = "shutdown")
  public ScheduledExecutorService threadDeltaScheduler() {
    return Executors.newSingleThreadScheduledExecutor(
        r -> {
          Thread t = new Thread(r, "thread-delta");
          t.setDaemon(true);
          return t;
        });
  }

  @Bean(name = "harnessWorkerScheduler", destroyMethod = "shutdown")
  @ConditionalOnMissingBean(name = "harnessWorkerScheduler")
  public ScheduledExecutorService harnessWorkerScheduler() {
    return Executors.newScheduledThreadPool(
        2,
        r -> {
          Thread t = new Thread(r, "harness-worker");
          t.setDaemon(true);
          return t;
        });
  }

  @Bean
  public ThreadProcessorConfig threadProcessorConfig() {
    return ThreadProcessorConfig.defaults();
  }

  @Bean
  public DeltaFlushScheduler threadDeltaFlushScheduler(
      ScheduledExecutorService threadDeltaScheduler) {
    return (delay, task) ->
        threadDeltaScheduler.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
  }

  @Bean
  public SessionContextBuilder sessionContextBuilder(
      SessionEntryStore entryStore, ObjectProvider<HarnessExtensionHost> extensionHost) {
    HarnessExtensionHost host = extensionHost.getIfAvailable();
    return new SessionContextBuilder(
        entryStore,
        new DefaultContextTransform(),
        host == null ? List.of() : host.contextTransforms());
  }

  @Bean
  public ProviderMessageProjector providerMessageProjector() {
    return new ProviderMessageProjector();
  }

  @Bean
  @ConditionalOnMissingBean
  public CompactionService compactionService() {
    return (sessionId, headEntryId, context) -> Optional.empty();
  }

  @Bean
  public ThreadProcessor threadProcessor(
      ThreadStore threadStore,
      ThreadInputStore inputStore,
      ThreadTransactions transactions,
      SessionEntryStore entryStore,
      ThreadProcessor.ThreadToolPort threadToolPort,
      SessionContextBuilder sessionContextBuilder,
      ProviderMessageProjector providerMessageProjector,
      TurnResourceResolver turnResourceResolver,
      CompactionService compactionService,
      ThreadProcessorConfig threadProcessorConfig,
      DeltaFlushScheduler threadDeltaFlushScheduler,
      ThreadIdGenerator threadIdGenerator,
      ExecutorService threadProcessorExecutor,
      @Qualifier("harnessWorkerScheduler") ScheduledExecutorService harnessWorkerScheduler,
      ObjectProvider<HarnessExtensionHost> extensionHost,
      HarnessLifecycleObservers lifecycleObservers) {
    HarnessExtensionHost host = extensionHost.getIfAvailable();
    ProviderRequestInterceptorChain interceptors =
        host == null
            ? new ProviderRequestInterceptorChain(List.of())
            : new ProviderRequestInterceptorChain(host.beforeProviderRequestInterceptors());
    CompactionService interceptingCompactionService =
        new InterceptingCompactionService(
            compactionService, host == null ? List.of() : host.beforeCompactionInterceptors());
    return new ThreadProcessor(
        threadStore,
        inputStore,
        transactions,
        entryStore,
        threadToolPort,
        sessionContextBuilder,
        providerMessageProjector,
        turnResourceResolver,
        interceptingCompactionService,
        threadProcessorConfig,
        Clock.systemUTC(),
        threadDeltaFlushScheduler,
        interceptors,
        lifecycleObservers,
        threadIdGenerator,
        threadProcessorExecutor,
        harnessWorkerScheduler);
  }

  @Bean
  public ThreadKick threadKick(ThreadProcessor threadProcessor) {
    return threadProcessor;
  }

  @Bean
  public ThreadRecoveryLifecycle threadRecoveryLifecycle(
      HarnessRuntimeProperties properties,
      HarnessThreadMapper threadMapper,
      ThreadKick threadKick,
      @Qualifier("harnessWorkerScheduler") ScheduledExecutorService harnessWorkerScheduler) {
    Duration interval =
        properties.getThreadRecoveryInterval() == null
            ? Duration.ofSeconds(30)
            : properties.getThreadRecoveryInterval();
    int batchSize =
        properties.getThreadRecoveryBatchSize() <= 0
            ? 100
            : properties.getThreadRecoveryBatchSize();
    return new ThreadRecoveryLifecycle(
        properties, threadMapper, threadKick, harnessWorkerScheduler, interval, batchSize);
  }
}
