package fun.fengwk.kkstudio.web.runtime;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentReadyListener;
import fun.fengwk.kkstudio.core.ai.runtime.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.core.ai.runtime.resource.ManagedResourceDownloadService;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.port.ModelGateway;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.port.ToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.processor.ModelProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ModelProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ProcessorLeaseConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.spring.dispatch.HarnessWorkDispatcher;
import fun.fengwk.kkstudio.harness.runtime.spring.dispatch.HarnessWorkDispatcherConfig;
import fun.fengwk.kkstudio.harness.runtime.spring.postgresql.PostgresqlHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.spring.postgresql.PostgresqlWorkListener;
import fun.fengwk.kkstudio.harness.runtime.spring.redis.RealtimeEventTail;
import fun.fengwk.kkstudio.harness.runtime.spring.redis.RedisRealtimeConfig;
import fun.fengwk.kkstudio.harness.runtime.spring.redis.RedisRealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.spring.redis.RedisRealtimeEventTail;
import fun.fengwk.kkstudio.harness.runtime.spring.resource.LocalFileResourceStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import javax.sql.DataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Web 组合根：把 core 的 gateway / resolver 与 runtime-spring 的持久化、调度、Redis 适配装配为完整的 Harness Runtime。
 *
 * <p>进程内 worker（dispatcher + listener）只受 {@code workers-enabled} 控制；关闭时控制/查询平面（{@link
 * HarnessRuntime}、store、processor 与 realtime 适配）仍然可用，只是不启动调度。生命周期顺序：启动 dispatcher 后 listener，停止
 * listener 后 dispatcher；processor 关闭与 executor shutdown 由 Spring 按依赖逆序 destroy 保证。
 *
 * <p>所有 executor 线程均为 daemon 并以 {@code destroyMethod = "shutdown"} 交给 Spring 持有生命周期；dispatcher 使用
 * fail-fast 单线程 drain executor 与 bounded AbortPolicy worker executor。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HarnessRuntimeProperties.class)
public class HarnessRuntimeConfiguration {

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  public HarnessStore harnessStore(
      DataSource dataSource, PlatformTransactionManager transactionManager) {
    return new PostgresqlHarnessStore(dataSource, transactionManager, UUID::randomUUID);
  }

  /**
   * 内容寻址的本地文件 {@link ResourceStore}，根目录为 {@code environment-root/.kkstudio/resources/}，单对象上限由
   * {@code resource-max-bytes} 控制（默认 16 MiB）。启动时安全创建根目录。
   */
  @Bean
  public ResourceStore harnessResourceStore(HarnessRuntimeProperties properties) {
    Path root = properties.resolvedEnvironmentRoot().resolve(".kkstudio").resolve("resources");
    try {
      Files.createDirectories(root);
    } catch (IOException error) {
      throw new IllegalStateException("cannot create resource store root: " + root, error);
    }
    return new LocalFileResourceStore(root, properties.getResourceMaxBytes());
  }

  @Bean
  public ManagedResourceDownloadService managedResourceDownloadService(
      ResourceStore resourceStore) {
    return new ManagedResourceDownloadService(resourceStore);
  }

  @Bean
  public RedisRealtimeConfig redisRealtimeConfig(HarnessRuntimeProperties properties) {
    return new RedisRealtimeConfig(properties.getRedisPrefix(), properties.getRedisMaxLength());
  }

  @Bean
  public RealtimeEventSink realtimeEventSink(
      StringRedisTemplate stringRedisTemplate, RedisRealtimeConfig config) {
    return new RedisRealtimeEventSink(stringRedisTemplate, config, new RealtimeEventJsonCodec());
  }

  @Bean
  public RealtimeEventTail realtimeEventTail(
      StringRedisTemplate stringRedisTemplate, RedisRealtimeConfig config) {
    return new RedisRealtimeEventTail(stringRedisTemplate, config, new RealtimeEventJsonCodec());
  }

  @Bean
  public InvocationRetryPolicy invocationRetryPolicy() {
    return InvocationRetryPolicy.DEFAULT;
  }

  @Bean
  public ProcessorLeaseConfig processorLeaseConfig(HarnessRuntimeProperties properties) {
    return new ProcessorLeaseConfig(
        properties.getProcessorLeaseDuration(), properties.getProcessorHeartbeatInterval());
  }

  @Bean
  public ThreadProcessorConfig threadProcessorConfig(
      HarnessRuntimeProperties properties,
      ProcessorLeaseConfig leaseConfig,
      CompactionConfig compactionConfig) {
    return new ThreadProcessorConfig(
        leaseConfig,
        properties.getThreadStepLimit(),
        properties.getThreadResolveFailureDelay(),
        compactionConfig);
  }

  @Bean
  public ModelProcessorConfig modelProcessorConfig(
      HarnessRuntimeProperties properties,
      ProcessorLeaseConfig leaseConfig,
      InvocationRetryPolicy retryPolicy) {
    return new ModelProcessorConfig(
        leaseConfig,
        properties.getModelCheckpointFlushInterval(),
        retryPolicy,
        properties.getModelDispatchBusyFallbackDelay());
  }

  @Bean
  public ToolProcessorConfig toolProcessorConfig(
      HarnessRuntimeProperties properties,
      ProcessorLeaseConfig leaseConfig,
      InvocationRetryPolicy retryPolicy) {
    return new ToolProcessorConfig(
        leaseConfig,
        retryPolicy,
        properties.getToolPreflightFailureDelay(),
        properties.getToolDispatchBusyFallbackDelay());
  }

  @Bean(name = "harnessProcessorScheduler", destroyMethod = "shutdown")
  public ScheduledExecutorService harnessProcessorScheduler() {
    return Executors.newScheduledThreadPool(
        4, Thread.ofPlatform().name("harness-processor-", 0L).daemon(true).factory());
  }

  @Bean
  public ThreadProcessor threadProcessor(
      HarnessStore store,
      TurnResolver turnResolver,
      ThreadProcessorConfig config,
      Clock clock,
      @Qualifier("harnessProcessorScheduler") ScheduledExecutorService scheduler,
      ObjectProvider<ToolResultHistoryMaterializer> materializerProvider) {
    return new ThreadProcessor(
        store, turnResolver, config, clock, scheduler, materializerProvider.getIfAvailable());
  }

  @Bean(destroyMethod = "close")
  public ModelProcessor modelProcessor(
      HarnessStore store,
      ModelGateway modelGateway,
      RealtimeEventSink realtimeEventSink,
      ModelProcessorConfig config,
      Clock clock,
      @Qualifier("harnessProcessorScheduler") ScheduledExecutorService scheduler) {
    return new ModelProcessor(store, modelGateway, realtimeEventSink, config, clock, scheduler);
  }

  @Bean(destroyMethod = "close")
  public ToolProcessor toolProcessor(
      HarnessStore store,
      ToolGateway toolGateway,
      RealtimeEventSink realtimeEventSink,
      ToolProcessorConfig config,
      Clock clock,
      @Qualifier("harnessProcessorScheduler") ScheduledExecutorService scheduler) {
    return new ToolProcessor(store, toolGateway, realtimeEventSink, config, clock, scheduler);
  }

  @Bean
  public HarnessRuntime harnessRuntime(
      HarnessStore store,
      Clock clock,
      ModelProcessor modelProcessor,
      ToolProcessor toolProcessor,
      ObjectProvider<ToolResultHistoryMaterializer> materializerProvider) {
    return new HarnessRuntime(
        store, clock, materializerProvider.getIfAvailable(), modelProcessor, toolProcessor);
  }

  /** fail-fast 单线程 drain executor：串行执行 drain，拒绝时同步抛错。 */
  @Bean(name = "harnessDispatcherDrainExecutor", destroyMethod = "shutdown")
  public ExecutorService harnessDispatcherDrainExecutor() {
    return Executors.newSingleThreadExecutor(
        Thread.ofPlatform().name("harness-dispatch-drain-", 0L).daemon(true).factory());
  }

  /** bounded worker executor：固定并发 + 有界队列 + AbortPolicy（fail-fast，绝不静默丢弃 handoff task）。 */
  @Bean(name = "harnessDispatcherWorkerExecutor", destroyMethod = "shutdown")
  public ExecutorService harnessDispatcherWorkerExecutor(HarnessRuntimeProperties properties) {
    int concurrency = properties.getDispatcherWorkerConcurrency();
    int queueCapacity = properties.getDispatcherWorkerQueueCapacity();
    if (concurrency <= 0) {
      throw new IllegalArgumentException("dispatcher worker concurrency must be positive");
    }
    if (queueCapacity <= 0) {
      throw new IllegalArgumentException("dispatcher worker queue capacity must be positive");
    }
    return new ThreadPoolExecutor(
        concurrency,
        concurrency,
        60L,
        TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(queueCapacity),
        Thread.ofPlatform().name("harness-dispatch-worker-", 0L).daemon(true).factory(),
        new ThreadPoolExecutor.AbortPolicy());
  }

  @Bean(name = "harnessDispatcherPollScheduler", destroyMethod = "shutdown")
  public ScheduledExecutorService harnessDispatcherPollScheduler() {
    return Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().name("harness-dispatch-poll-", 0L).daemon(true).factory());
  }

  @Bean
  public HarnessWorkDispatcher harnessWorkDispatcher(
      HarnessStore store,
      HarnessRuntimeProperties properties,
      Clock clock,
      @Qualifier("harnessDispatcherDrainExecutor") Executor drainExecutor,
      @Qualifier("harnessDispatcherWorkerExecutor") Executor workerExecutor,
      @Qualifier("harnessDispatcherPollScheduler") ScheduledExecutorService pollScheduler,
      ThreadProcessor threadProcessor,
      ModelProcessor modelProcessor,
      ToolProcessor toolProcessor) {
    HarnessWorkDispatcherConfig config =
        new HarnessWorkDispatcherConfig(
            properties.getDispatcherLeaseDuration(),
            properties.getDispatcherLeaseDuration(),
            properties.getDispatcherLeaseDuration(),
            properties.getDispatcherPollInterval(),
            properties.getDispatcherRejectionDelay(),
            properties.getDispatcherMaxDispatchTasks());
    return new HarnessWorkDispatcher(
        store,
        config,
        clock,
        drainExecutor,
        workerExecutor,
        pollScheduler,
        threadProcessor,
        modelProcessor,
        toolProcessor);
  }

  @Bean
  public PostgresqlWorkListener postgresqlWorkListener(
      DataSource dataSource, HarnessWorkDispatcher dispatcher) {
    return new PostgresqlWorkListener(dataSource, dispatcher::wake);
  }

  /** READY 事件只唤醒 Work dispatcher；实际 Environment 事实由 {@link TurnResolver} 在 resolve 时读取。 */
  @Bean
  public EnvironmentReadyListener harnessEnvironmentReadyListener(
      ObjectProvider<HarnessWorkDispatcher> dispatcherProvider) {
    return environmentName -> dispatcherProvider.ifAvailable(HarnessWorkDispatcher::wake);
  }

  @Bean
  public SmartLifecycle harnessRuntimeLifecycle(
      HarnessRuntimeProperties properties,
      HarnessWorkDispatcher dispatcher,
      PostgresqlWorkListener listener) {
    return new HarnessRuntimeLifecycle(properties.isWorkersEnabled(), dispatcher, listener);
  }
}
