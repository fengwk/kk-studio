package fun.fengwk.kkstudio.web.runtime;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.transaction.PlatformTransactionManager;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTransport;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentSessionListener;
import fun.fengwk.kkstudio.harness.infra.dispatch.HarnessWorkDispatcher;
import fun.fengwk.kkstudio.harness.infra.dispatch.HarnessWorkDispatcherConfig;
import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlHarnessStore;
import fun.fengwk.kkstudio.harness.infra.realtime.BusRealtimeEventSink;
import fun.fengwk.kkstudio.harness.infra.realtime.BusRealtimeEventSource;
import fun.fengwk.kkstudio.harness.infra.resource.LocalFileResourceStore;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfigProvider;
import fun.fengwk.kkstudio.harness.runtime.port.ModelGateway;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.port.ToolHistoryActionResolver;
import fun.fengwk.kkstudio.harness.runtime.port.ToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.processor.ModelProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ModelProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ProcessorLeaseConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.StreamFlushConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicyProvider;
import fun.fengwk.kkstudio.harness.runtime.retry.ModelHttpErrorPolicy;
import fun.fengwk.kkstudio.harness.runtime.retry.ModelHttpErrorPolicyProvider;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.platform.catalog.provider.configuration.AgentProviderConfigurationCodec;
import fun.fengwk.kkstudio.platform.catalog.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillSyncOrchestrator;
import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillSyncOrchestratorFactory;
import fun.fengwk.kkstudio.platform.environment.update.EnvironmentUpdateService;
import fun.fengwk.kkstudio.platform.harness.configuration.HarnessDispatcherProperties;
import fun.fengwk.kkstudio.platform.harness.configuration.HarnessExecutionAdmissionProperties;
import fun.fengwk.kkstudio.platform.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.platform.harness.model.DatabaseProviderResolutionService;
import fun.fengwk.kkstudio.platform.harness.model.ProviderRequestPreviewService;
import fun.fengwk.kkstudio.platform.harness.resource.ManagedResourceDownloadService;
import fun.fengwk.kkstudio.platform.harness.thread.command.DatabaseTurnResolver;
import fun.fengwk.kkstudio.platform.harness.thread.query.ModelRequestDebugService;
import fun.fengwk.kkstudio.platform.plugin.PluginProperties;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginResourceGateway;
import fun.fengwk.kkstudio.platform.plugin.resource.StoragePluginResourceGateway;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.share.notification.NotificationBus;
import fun.fengwk.kkstudio.share.notification.NotificationLimits;

import javax.sql.DataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Web 组合根：把 platform 的 gateway / resolver 与 infra 的 PostgreSQL 持久化和调度适配装配为完整的 Harness Runtime。
 *
 * <p>进程内 worker dispatcher 只受 {@code workers-enabled} 控制；关闭时控制/查询平面（{@link
 * HarnessRuntime}、store、processor 与 realtime 适配）仍然可用，只是不启动调度。跨节点提示统一经通知组合根持有的唯一总线发布；processor 关闭与
 * executor shutdown 由 Spring 按依赖逆序 destroy 保证。
 *
 * <p>Advanced/resource 等部署软策略从共享 {@link SystemSettingsSnapshot} 在装配期读取（DB 变更需重启）；aiRuntime 的 retry
 * 经 {@link InvocationRetryPolicyProvider} 每次判定点现读。本组合根不持有任何硬编码的重复默认值。
 *
 * <p>所有 executor 线程均为 daemon 并以 {@code destroyMethod = "shutdown"} 交给 Spring 持有生命周期；dispatcher 使用
 * fail-fast 单线程 drain executor 与 bounded AbortPolicy worker executor，Skill 同步使用部署级容量的 bounded
 * AbortPolicy executor。
 *
 * <p>依赖 Environment 会话核心（{@code EnvironmentCapabilityTransport}）的组件只在这里装配：Platform 的自动配置不参与 Harness
 * 组合，任何只装配 Platform 的上下文都不会因为缺少该传输而启动失败。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
  HarnessRuntimeProperties.class,
  HarnessDispatcherProperties.class,
  HarnessExecutionAdmissionProperties.class
})
public class HarnessRuntimeConfiguration {

  /** 每个 skill 同步并发槽位可排队的任务数：容量有界，突发只排队不无界建线程。 */
  private static final int SKILL_SYNC_QUEUE_PER_SLOT = 8;

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  public HarnessStore harnessStore(
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      NotificationBus notificationBus) {
    return new PostgresqlHarnessStore(
        dataSource, transactionManager, UUID::randomUUID, notificationBus);
  }

  /**
   * 内容寻址的本地文件 {@link ResourceStore}，根目录取自 {@code kk-studio.harness.runtime.resource-root}，单对象上限由数据库
   * SystemSettings.Advanced 的 {@code resourceMaxBytes} 控制（共享启动快照，DB 变更需重启生效）。启动时安全创建根目录。
   */
  @Bean
  public ResourceStore harnessResourceStore(
      HarnessRuntimeProperties properties, SystemSettingsSnapshot systemSettingsSnapshot) {
    Path root = properties.resolvedResourceRoot();
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

  /** Plugin 资源端口依赖 HarnessStore，因此由完整 Harness Runtime 的组合根创建；媒体传输构造前先安装统一代理。 */
  @Bean
  @DependsOn("systemProxySelector")
  public PluginResourceGateway pluginResourceGateway(
      HarnessStore harnessStore,
      SessionBlobRefManager sessionBlobRefManager,
      StorageBlobManager storageBlobManager,
      StorageUploadService storageUploadService,
      PluginProperties properties) {
    return new StoragePluginResourceGateway(
        harnessStore, sessionBlobRefManager, storageBlobManager, storageUploadService, properties);
  }

  /** Realtime live overlay sink：单条 canonical EVENT 超过逻辑消息预算时降级为该 Thread 的 resync。 */
  @Bean
  public RealtimeEventSink realtimeEventSink(
      NotificationBus notificationBus, NotificationLimits notificationLimits) {
    return new BusRealtimeEventSink(notificationBus, notificationLimits.maxMessageBytes());
  }

  /** Realtime live overlay source：只维护本进程 per-Thread 订阅与完成围栏；唯一 REALTIME 总线订阅由通知组合根绑定。 */
  @Bean
  public BusRealtimeEventSource realtimeEventSource() {
    return new BusRealtimeEventSource();
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

  /** 模型 HTTP 状态策略现读通道：每次带 HTTP 状态的失败 retry 判定点按冻结 Provider 身份解析，Provider 覆盖优先于系统名单。 */
  @Bean
  public ModelHttpErrorPolicyProvider modelHttpErrorPolicyProvider(
      SystemSettingsSnapshot systemSettingsSnapshot,
      AgentProviderRepository agentProviderRepository,
      AgentProviderConfigurationCodec providerConfigurationCodec) {
    return providerName -> {
      // live 路径绝不允许 null/blank：冻结 Provider 身份来自 Invocation；未知名称才按“继承系统名单”处理。
      Objects.requireNonNull(providerName, "providerName");
      if (providerName.isBlank()) {
        throw new IllegalArgumentException("providerName must not be blank");
      }
      SystemSettings.AiRuntime aiRuntime = systemSettingsSnapshot.get().aiRuntime();
      List<Integer> effective = aiRuntime.modelHttpRetryStatusCodes();
      AgentProvider provider = agentProviderRepository.getByName(providerName);
      if (provider != null) {
        List<Integer> override =
            providerConfigurationCodec.readHttpRetryStatusCodes(provider.getConfigJson());
        if (override != null) {
          effective = override;
        }
      }
      return new ModelHttpErrorPolicy(effective);
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
      ModelHttpErrorPolicyProvider modelHttpErrorPolicyProvider,
      SystemSettingsSnapshot systemSettingsSnapshot,
      ToolHistoryActionResolver toolHistoryActionResolver) {
    return new ModelProcessorConfig(
        leaseConfig,
        retryPolicyProvider,
        Duration.ofMillis(
            systemSettingsSnapshot.get().advanced().modelDispatchBusyFallbackDelayMillis()),
        StreamFlushConfig.DEFAULT,
        toolHistoryActionResolver,
        modelHttpErrorPolicyProvider);
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
   * Model / Tool / Thread-resolve 共用的 lease heartbeat 定时分发调度池。
   *
   * <p>回调仅负责非阻塞状态检查与任务分派，绝不执行数据库事务或所有权取消操作。
   */
  @Bean(name = "harnessProcessorScheduler", destroyMethod = "shutdown")
  public ScheduledExecutorService harnessProcessorScheduler() {
    return Executors.newScheduledThreadPool(
        2, Thread.ofPlatform().name("harness-processor-timer-", 0L).daemon(true).factory());
  }

  /**
   * Model / Tool / Thread-resolve 心跳续租事务与失联取消执行器。
   *
   * <p>采用命名虚拟线程，与阻塞 Provider / Tool I/O 严格隔离，避免长耗时调用阻塞续租。
   */
  @Bean(name = "harnessHeartbeatWorkerExecutor", destroyMethod = "close")
  public ExecutorService harnessHeartbeatWorkerExecutor() {
    return Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("harness-heartbeat-worker-", 0L).factory());
  }

  @Bean
  public ThreadProcessor threadProcessor(
      HarnessStore store,
      TurnResolver turnResolver,
      ThreadProcessorConfig config,
      Clock clock,
      @Qualifier("harnessProcessorScheduler") ScheduledExecutorService scheduler,
      @Qualifier("harnessHeartbeatWorkerExecutor") Executor heartbeatWorker,
      ToolResultHistoryMaterializer materializer) {
    return new ThreadProcessor(
        store, turnResolver, config, clock, scheduler, heartbeatWorker, materializer);
  }

  @Bean(destroyMethod = "close")
  public ExecutorService harnessModelFlushExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  @Bean(destroyMethod = "close")
  public ModelProcessor modelProcessor(
      HarnessStore store,
      ModelGateway modelGateway,
      RealtimeEventSink realtimeEventSink,
      ModelProcessorConfig config,
      Clock clock,
      @Qualifier("harnessProcessorScheduler") ScheduledExecutorService scheduler,
      @Qualifier("harnessHeartbeatWorkerExecutor") Executor heartbeatWorker,
      @Qualifier("harnessModelFlushExecutor") ExecutorService flushExecutor) {
    return new ModelProcessor(
        store,
        modelGateway,
        realtimeEventSink,
        config,
        clock,
        scheduler,
        heartbeatWorker,
        flushExecutor);
  }

  @Bean(destroyMethod = "close")
  public ToolProcessor toolProcessor(
      HarnessStore store,
      ToolGateway toolGateway,
      RealtimeEventSink realtimeEventSink,
      ToolProcessorConfig config,
      Clock clock,
      @Qualifier("harnessProcessorScheduler") ScheduledExecutorService scheduler,
      @Qualifier("harnessHeartbeatWorkerExecutor") Executor heartbeatWorker) {
    return new ToolProcessor(
        store, toolGateway, realtimeEventSink, config, clock, scheduler, heartbeatWorker);
  }

  @Bean
  public HarnessRuntime harnessRuntime(
      HarnessStore store,
      Clock clock,
      TurnResolver turnResolver,
      CompactionConfigProvider compactionConfigProvider,
      ModelProcessor modelProcessor,
      ToolProcessor toolProcessor,
      ToolResultHistoryMaterializer materializer) {
    return new HarnessRuntime(
        store,
        clock,
        turnResolver,
        compactionConfigProvider,
        materializer,
        modelProcessor,
        toolProcessor);
  }

  /** 结构化 Model Request Debug 只读服务：依赖完整 Runtime，因此只在 Web 组合根创建。 */
  @Bean
  public ModelRequestDebugService modelRequestDebugService(
      HarnessRuntime runtime, DatabaseTurnResolver turnResolver, Clock clock) {
    return new ModelRequestDebugService(runtime, turnResolver, clock);
  }

  /**
   * 发送前请求预览：与正式发送共用同一 candidate 规划、resolver、materializer、Provider 解析与协议编码，因此同样只在完整 Runtime
   * 装配后创建；压缩判定复用 ThreadProcessor 的同一 {@link ThreadProcessorConfig#compactionProvider()}。
   */
  @Bean
  public ProviderRequestPreviewService providerRequestPreviewService(
      HarnessRuntime runtime,
      DatabaseTurnResolver turnResolver,
      DatabaseProviderResolutionService providerResolution,
      ThreadProcessorConfig threadProcessorConfig,
      SessionBlobRefManager sessionBlobRefManager,
      StorageBlobManager storageBlobManager,
      StorageUploadService storageUploadService,
      Clock clock) {
    return new ProviderRequestPreviewService(
        runtime,
        turnResolver,
        providerResolution,
        threadProcessorConfig.compactionProvider(),
        sessionBlobRefManager,
        storageBlobManager,
        storageUploadService,
        clock);
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
      HarnessDispatcherProperties dispatcherProperties) {
    int concurrency = dispatcherProperties.getWorker().getConcurrency();
    int queueCapacity = dispatcherProperties.getWorker().getQueueCapacity();
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
      @Qualifier("nodeInstanceId") UUID nodeInstanceId,
      HarnessStore store,
      HarnessDispatcherProperties dispatcherProperties,
      Clock clock,
      @Qualifier("harnessDispatcherDrainExecutor") Executor drainExecutor,
      @Qualifier("harnessDispatcherWorkerExecutor") Executor workerExecutor,
      @Qualifier("harnessDispatcherPollScheduler") ScheduledExecutorService pollScheduler,
      ThreadProcessor threadProcessor,
      ModelProcessor modelProcessor,
      ToolProcessor toolProcessor) {
    Duration dispatcherLease = dispatcherProperties.getLeaseDuration();
    HarnessWorkDispatcherConfig config =
        new HarnessWorkDispatcherConfig(
            dispatcherLease,
            dispatcherLease,
            dispatcherLease,
            dispatcherProperties.getPollInterval(),
            dispatcherProperties.getRejectionDelay(),
            dispatcherProperties.getMaxDispatchTasks());
    return new HarnessWorkDispatcher(
        nodeInstanceId,
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

  /**
   * Skill 同步专用 executor：固定并发 + 有界队列 + AbortPolicy。
   *
   * <p>每个任务在等待 capability 终态时阻塞至多一个 Package 的调用超时，因此并发必须受部署级容量约束；容量用尽时显式拒绝， 绝不无界建线程。被拒绝的同步不会重放：下一次
   * READY 或通知重连对账会重新收敛。
   */
  @Bean(name = "environmentSkillSyncExecutor", destroyMethod = "shutdown")
  public ExecutorService environmentSkillSyncExecutor(
      HarnessExecutionAdmissionProperties admissionProperties) {
    int concurrency = admissionProperties.getSkillSync();
    return new ThreadPoolExecutor(
        concurrency,
        concurrency,
        0L,
        TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(concurrency * SKILL_SYNC_QUEUE_PER_SLOT),
        Thread.ofVirtual().name("environment-skill-sync-", 0L).factory(),
        new ThreadPoolExecutor.AbortPolicy());
  }

  /**
   * Environment Skill Package 同步编排器。
   *
   * <p>它依赖 {@code EnvironmentCapabilityTransport}（即 Environment 会话核心），因此只在组合根装配：Platform 的 自动配置不参与
   * Harness 组合，任何只装配 Platform 的上下文都不会因为缺少该传输而启动失败。
   */
  @Bean
  public EnvironmentSkillSyncOrchestrator environmentSkillSyncOrchestrator(
      EnvironmentSkillSyncOrchestratorFactory orchestratorFactory,
      EnvironmentCapabilityTransport capabilityTransport,
      @Qualifier("environmentSkillSyncExecutor") ExecutorService environmentSkillSyncExecutor) {
    return orchestratorFactory.create(capabilityTransport, environmentSkillSyncExecutor);
  }

  /**
   * READY 事件的组合宿主：唤醒可选的 HarnessWorkDispatcher，触发该 Environment 的 Skill 全量同步，并让受管更新服务判定最终成功。
   *
   * <p>三个动作都立刻返回且各自隔离异常：READY 的会话状态推进会先落库，任何宿主回调失败都不得影响会话本身。
   *
   * <p>三个协作者都用延迟解析：dispatcher、Skill 同步编排器与更新服务都经由 capability 传输间接依赖本监听器（daemon server → listener →
   * 协作者），启动期直接注入会形成环。
   */
  @Bean
  public EnvironmentSessionListener compositeEnvironmentSessionListener(
      ObjectProvider<HarnessWorkDispatcher> dispatcherProvider,
      ObjectProvider<EnvironmentSkillSyncOrchestrator> orchestratorProvider,
      ObjectProvider<EnvironmentUpdateService> updateServiceProvider) {
    return environmentId -> {
      try {
        dispatcherProvider.ifAvailable(HarnessWorkDispatcher::wake);
      } catch (RuntimeException ignored) {
      }
      try {
        orchestratorProvider.ifAvailable(
            orchestrator -> orchestrator.onEnvironmentReady(environmentId));
      } catch (RuntimeException ignored) {
      }
      try {
        updateServiceProvider.ifAvailable(
            updateService -> updateService.onEnvironmentReady(environmentId));
      } catch (RuntimeException ignored) {
      }
    };
  }

  @Bean
  public SmartLifecycle harnessRuntimeLifecycle(
      HarnessRuntimeProperties properties, HarnessWorkDispatcher dispatcher) {
    return new HarnessRuntimeLifecycle(properties.isWorkersEnabled(), dispatcher);
  }
}
