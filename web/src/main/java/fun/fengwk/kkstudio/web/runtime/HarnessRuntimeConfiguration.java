package fun.fengwk.kkstudio.web.runtime;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentReadyListener;
import fun.fengwk.kkstudio.core.ai.runtime.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.core.ai.runtime.resource.ManagedResourceDownloadService;
import fun.fengwk.kkstudio.core.ai.runtime.task.SystemPromptPreviewService;
import fun.fengwk.kkstudio.core.ai.runtime.task.SystemPromptPreviewServiceFactory;
import fun.fengwk.kkstudio.core.systemsettings.SystemSettings;
import fun.fengwk.kkstudio.core.systemsettings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.harness.infra.dispatch.HarnessWorkDispatcher;
import fun.fengwk.kkstudio.harness.infra.dispatch.HarnessWorkDispatcherConfig;
import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlHarnessStore;
import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlRealtimeEventSink;
import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlRealtimeEventSource;
import fun.fengwk.kkstudio.harness.infra.postgresql.RealtimeNotificationCodec;
import fun.fengwk.kkstudio.harness.infra.resource.LocalFileResourceStore;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfigProvider;
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
import fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicyProvider;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import javax.sql.DataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Web 组合根：把 core 的 gateway / resolver 与 infra 的 PostgreSQL 持久化和调度适配装配为完整的 Harness Runtime。
 *
 * <p>进程内 worker dispatcher 只受 {@code workers-enabled} 控制；关闭时控制/查询平面（{@link
 * HarnessRuntime}、store、processor 与 realtime 适配）仍然可用，只是不启动调度。PostgreSQL notification loop
 * 由事件组合根独立持有；processor 关闭与 executor shutdown 由 Spring 按依赖逆序 destroy 保证。
 *
 * <p>Advanced/resource 等部署软策略从共享 {@link SystemSettingsSnapshot} 在装配期读取（DB 变更需重启）；aiRuntime 的 retry
 * 经 {@link InvocationRetryPolicyProvider} 每次判定点现读。本组合根不持有任何硬编码的重复默认值。
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
   * 内容寻址的本地文件 {@link ResourceStore}，根目录为 {@code environment-root/.kkstudio/resources/}，单对象上限由数据库
   * SystemSettings.Advanced 的 {@code resourceMaxBytes} 控制（共享启动快照，DB 变更需重启生效）。启动时安全创建根目录。
   */
  @Bean
  public ResourceStore harnessResourceStore(
      HarnessRuntimeProperties properties, SystemSettingsSnapshot systemSettingsSnapshot) {
    Path root = properties.resolvedEnvironmentRoot().resolve(".kkstudio").resolve("resources");
    int maxBytes = Math.toIntExact(systemSettingsSnapshot.get().advanced().resourceMaxBytes());
    try {
      Files.createDirectories(root);
    } catch (IOException error) {
      throw new IllegalStateException("cannot create resource store root: " + root, error);
    }
    return new LocalFileResourceStore(root, maxBytes);
  }

  @Bean
  public ManagedResourceDownloadService managedResourceDownloadService(
      ResourceStore resourceStore) {
    return new ManagedResourceDownloadService(resourceStore);
  }

  @Bean
  public RealtimeNotificationCodec realtimeNotificationCodec() {
    return new RealtimeNotificationCodec();
  }

  @Bean
  public RealtimeEventSink realtimeEventSink(
      DataSource dataSource, RealtimeNotificationCodec notificationCodec) {
    return new PostgresqlRealtimeEventSink(new JdbcTemplate(dataSource), notificationCodec);
  }

  @Bean(destroyMethod = "close")
  public PostgresqlRealtimeEventSource realtimeEventSource(
      RealtimeNotificationCodec notificationCodec) {
    return new PostgresqlRealtimeEventSource(notificationCodec);
  }

  /** Model / Tool 调用的全局重试策略现读通道：每次 retry 判定点从 SystemSettings.AiRuntime 映射。 */
  @Bean
  public InvocationRetryPolicyProvider invocationRetryPolicyProvider(
      SystemSettingsSnapshot systemSettingsSnapshot) {
    return () -> {
      SystemSettings.AiRuntime aiRuntime = systemSettingsSnapshot.get().aiRuntime();
      return new InvocationRetryPolicy(
          aiRuntime.retryMaxRetries(),
          aiRuntime.retryBackoffStrategy(),
          Duration.ofMillis(aiRuntime.retryBaseDelayMillis()),
          Duration.ofMillis(aiRuntime.retryMaxDelayMillis()));
    };
  }

  /** processor 共用的 claim/lease 与 heartbeat 节奏：读取共享启动快照的 SystemSettings.Advanced。 */
  @Bean
  public ProcessorLeaseConfig processorLeaseConfig(SystemSettingsSnapshot systemSettingsSnapshot) {
    SystemSettings.Advanced advanced = systemSettingsSnapshot.get().advanced();
    return new ProcessorLeaseConfig(
        Duration.ofMillis(advanced.processorLeaseDurationMillis()),
        Duration.ofMillis(advanced.processorHeartbeatIntervalMillis()));
  }

  @Bean
  public ThreadProcessorConfig threadProcessorConfig(
      ProcessorLeaseConfig leaseConfig,
      CompactionConfigProvider compactionConfigProvider,
      SystemSettingsSnapshot systemSettingsSnapshot) {
    SystemSettings.Advanced advanced = systemSettingsSnapshot.get().advanced();
    return new ThreadProcessorConfig(
        leaseConfig,
        Duration.ofMillis(advanced.threadResolveFailureDelayMillis()),
        compactionConfigProvider);
  }

  @Bean
  public ModelProcessorConfig modelProcessorConfig(
      ProcessorLeaseConfig leaseConfig,
      InvocationRetryPolicyProvider retryPolicyProvider,
      SystemSettingsSnapshot systemSettingsSnapshot) {
    return new ModelProcessorConfig(
        leaseConfig,
        retryPolicyProvider,
        Duration.ofMillis(
            systemSettingsSnapshot.get().advanced().modelDispatchBusyFallbackDelayMillis()));
  }

  @Bean
  public ToolProcessorConfig toolProcessorConfig(
      ProcessorLeaseConfig leaseConfig,
      InvocationRetryPolicyProvider retryPolicyProvider,
      SystemSettingsSnapshot systemSettingsSnapshot) {
    SystemSettings.Advanced advanced = systemSettingsSnapshot.get().advanced();
    return new ToolProcessorConfig(
        leaseConfig,
        retryPolicyProvider,
        Duration.ofMillis(advanced.toolPreflightFailureDelayMillis()),
        Duration.ofMillis(advanced.toolDispatchBusyFallbackDelayMillis()));
  }

  /**
   * Model / Tool / Thread-resolve 共用的 lease heartbeat 调度池。
   *
   * <p>心跳任务本身只是一次 {@code harness_work} 单行 renew，1 个线程足够正常续期。第 2 个线程只兜底极端情况：某次 renew 等行锁，或
   * lost-ownership 回调里同步 {@code handle.cancel()} 时，不把其余 claim 的续期堵在同一条线程上。
   */
  @Bean(name = "harnessProcessorScheduler", destroyMethod = "shutdown")
  public ScheduledExecutorService harnessProcessorScheduler() {
    return Executors.newScheduledThreadPool(
        2, Thread.ofPlatform().name("harness-processor-", 0L).daemon(true).factory());
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

  @Bean
  public SystemPromptPreviewService systemPromptPreviewService(
      HarnessRuntime runtime, SystemPromptPreviewServiceFactory factory) {
    return factory.create(runtime);
  }

  /** fail-fast 单线程 drain executor：串行执行 drain，拒绝时同步抛错。 */
  @Bean(name = "harnessDispatcherDrainExecutor", destroyMethod = "shutdown")
  public ExecutorService harnessDispatcherDrainExecutor() {
    return Executors.newSingleThreadExecutor(
        Thread.ofPlatform().name("harness-dispatch-drain-", 0L).daemon(true).factory());
  }

  /** bounded worker executor：固定并发 + 有界队列 + AbortPolicy（fail-fast，绝不静默丢弃 handoff task）。 */
  @Bean(name = "harnessDispatcherWorkerExecutor", destroyMethod = "shutdown")
  public ExecutorService harnessDispatcherWorkerExecutor(
      SystemSettingsSnapshot systemSettingsSnapshot) {
    SystemSettings.Advanced advanced = systemSettingsSnapshot.get().advanced();
    int concurrency = advanced.dispatcherWorkerConcurrency();
    int queueCapacity = advanced.dispatcherWorkerQueueCapacity();
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
      SystemSettingsSnapshot systemSettingsSnapshot,
      Clock clock,
      @Qualifier("harnessDispatcherDrainExecutor") Executor drainExecutor,
      @Qualifier("harnessDispatcherWorkerExecutor") Executor workerExecutor,
      @Qualifier("harnessDispatcherPollScheduler") ScheduledExecutorService pollScheduler,
      ThreadProcessor threadProcessor,
      ModelProcessor modelProcessor,
      ToolProcessor toolProcessor) {
    // dispatcher 的 lease/poll/rejection/预算：读取共享启动快照的 SystemSettings.Advanced。
    SystemSettings.Advanced advanced = systemSettingsSnapshot.get().advanced();
    Duration dispatcherLease = Duration.ofMillis(advanced.dispatcherLeaseDurationMillis());
    HarnessWorkDispatcherConfig config =
        new HarnessWorkDispatcherConfig(
            dispatcherLease,
            dispatcherLease,
            dispatcherLease,
            Duration.ofMillis(advanced.dispatcherPollIntervalMillis()),
            Duration.ofMillis(advanced.dispatcherRejectionDelayMillis()),
            advanced.dispatcherMaxDispatchTasks());
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

  /** READY 事件只唤醒 Work dispatcher；实际 Environment 事实由 {@link TurnResolver} 在 resolve 时读取。 */
  @Bean
  public EnvironmentReadyListener harnessEnvironmentReadyListener(
      ObjectProvider<HarnessWorkDispatcher> dispatcherProvider) {
    return environmentName -> dispatcherProvider.ifAvailable(HarnessWorkDispatcher::wake);
  }

  @Bean
  public SmartLifecycle harnessRuntimeLifecycle(
      HarnessRuntimeProperties properties, HarnessWorkDispatcher dispatcher) {
    return new HarnessRuntimeLifecycle(properties.isWorkersEnabled(), dispatcher);
  }
}
