package fun.fengwk.kkstudio.web.runtime;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinAgentDriver;
import fun.fengwk.kkstudio.harness.builtin.port.ModelGateway;
import fun.fengwk.kkstudio.harness.builtin.port.TurnResolver;
import fun.fengwk.kkstudio.harness.builtin.processor.ModelProcessor;
import fun.fengwk.kkstudio.harness.builtin.processor.ModelProcessorConfig;
import fun.fengwk.kkstudio.harness.builtin.processor.ModelStreamConfig;
import fun.fengwk.kkstudio.harness.builtin.query.BuiltinRequestQuery;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTransport;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentSessionListener;
import fun.fengwk.kkstudio.harness.infra.dispatch.HarnessWorkDispatcher;
import fun.fengwk.kkstudio.harness.infra.dispatch.HarnessWorkDispatcherConfig;
import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlHarnessStore;
import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlRealtimeEventSink;
import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlRealtimeEventSource;
import fun.fengwk.kkstudio.harness.infra.postgresql.RealtimeNotificationCodec;
import fun.fengwk.kkstudio.harness.infra.realtime.RealtimeBus;
import fun.fengwk.kkstudio.harness.infra.realtime.RealtimePayloadBuffer;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfigProvider;
import fun.fengwk.kkstudio.harness.runtime.driver.AgentDriver;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitConfirmationRetry;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournal;
import fun.fengwk.kkstudio.harness.runtime.execution.FileExecutionExitJournal;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.port.ToolHistoryActionResolver;
import fun.fengwk.kkstudio.harness.runtime.port.ToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.harness.runtime.processor.ProcessorLeaseConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicyProvider;
import fun.fengwk.kkstudio.harness.runtime.retry.ModelHttpErrorPolicy;
import fun.fengwk.kkstudio.harness.runtime.retry.ModelHttpErrorPolicyProvider;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.ide.IdeLeaseCoordinator;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.mcp.runtime.McpToolCatalog;
import fun.fengwk.kkstudio.platform.catalog.provider.AgentProvider;
import fun.fengwk.kkstudio.platform.catalog.provider.AgentProviderConfigurationCodec;
import fun.fengwk.kkstudio.platform.catalog.provider.AgentProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.tool.PluginRuntimeToolCatalogSource;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalogSource;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodePresence;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterRealtimePayloadClient;
import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillSyncOrchestrator;
import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillSyncOrchestratorFactory;
import fun.fengwk.kkstudio.platform.harness.configuration.HarnessDispatcherProperties;
import fun.fengwk.kkstudio.platform.harness.configuration.HarnessExecutionAdmissionProperties;
import fun.fengwk.kkstudio.platform.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.platform.harness.model.DatabaseProviderResolutionService;
import fun.fengwk.kkstudio.platform.harness.model.ProviderRequestPreviewService;
import fun.fengwk.kkstudio.platform.harness.realtime.HarnessStreamCatchupNotificationCodec;
import fun.fengwk.kkstudio.platform.harness.realtime.HarnessStreamCatchupService;
import fun.fengwk.kkstudio.platform.harness.thread.command.DatabaseTurnResolver;
import fun.fengwk.kkstudio.platform.harness.thread.query.ModelRequestDebugService;
import fun.fengwk.kkstudio.platform.plugin.PluginProperties;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRuntime;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginResourceGateway;
import fun.fengwk.kkstudio.platform.plugin.resource.StoragePluginResourceGateway;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.web.environment.ide.IdeGenerationCloseListener;

import javax.sql.DataSource;

import java.net.http.HttpClient;
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
 * HarnessRuntime}、store、processor 与 realtime 适配）仍然可用，只是不启动调度。PostgreSQL notification loop
 * 由事件组合根独立持有；processor 关闭与 executor shutdown 由 Spring 按依赖逆序 destroy 保证。
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
  public PostgresqlHarnessStore harnessStore(
      DataSource dataSource, PlatformTransactionManager transactionManager) {
    return new PostgresqlHarnessStore(dataSource, transactionManager, UUID::randomUUID);
  }

  /**
   * 唯一的 Agent Driver：A 只存在 Builtin 实现，组合根直接装配，不做 kind 分发、不注入 no-op 或备用 Driver。
   *
   * <p>Driver 只在 Runtime 已开启的事务内工作，不执行外部 I/O。
   */
  @Bean
  public AgentDriver agentDriver(PostgresqlHarnessStore store, TurnResolver turnResolver) {
    return new BuiltinAgentDriver(store, turnResolver);
  }

  /** Builtin debug 专属只读查询；公共快照不暴露 ModelRequestSpec。 */
  @Bean
  public BuiltinRequestQuery builtinRequestQuery(PostgresqlHarnessStore store) {
    return new BuiltinRequestQuery(store, store);
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

  /** Harness 工具目录：插件快照的 Contributor 工具加 MCP 动态工具，每次租用冻结为一份不可变目录。 */
  @Bean
  public RuntimeToolCatalogSource runtimeToolCatalogSource(
      PluginCatalogRuntime pluginCatalogRuntime, McpToolCatalog mcpToolCatalog) {
    return new PluginRuntimeToolCatalogSource(pluginCatalogRuntime, mcpToolCatalog);
  }

  @Bean
  public RealtimeNotificationCodec realtimeNotificationCodec() {
    return new RealtimeNotificationCodec();
  }

  /** Oversize realtime payload 的 owner 短期内存；sink 写入、内部 controller 按 payloadId 读取，bus close 时释放。 */
  @Bean(destroyMethod = "close")
  public RealtimePayloadBuffer realtimePayloadBuffer() {
    return new RealtimePayloadBuffer();
  }

  /** 本地优先总线同时作为 Runtime 的 sink 与浏览器订阅的 source；REFERENCE 回拉在总线自有的虚拟线程上执行，随总线关闭。 */
  @Bean(destroyMethod = "close")
  public RealtimeBus realtimeBus(
      DataSource dataSource,
      RealtimeNotificationCodec notificationCodec,
      @Qualifier("nodeInstanceId") UUID nodeInstanceId,
      ClusterNodePresence clusterNodePresence,
      ClusterNodeRegistry clusterNodeRegistry,
      ClusterInternalAuthentication clusterInternalAuthentication,
      @Qualifier("clusterInternalHttpClient") HttpClient clusterInternalHttpClient,
      RealtimePayloadBuffer realtimePayloadBuffer) {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    ClusterRealtimePayloadClient payloadClient =
        new ClusterRealtimePayloadClient(
            clusterInternalHttpClient, clusterInternalAuthentication, clusterNodeRegistry);
    return new RealtimeBus(
        nodeInstanceId,
        notificationCodec,
        new PostgresqlRealtimeEventSource(notificationCodec),
        new PostgresqlRealtimeEventSink(
            jdbcTemplate, notificationCodec, nodeInstanceId, realtimePayloadBuffer),
        clusterNodePresence::hasOtherActiveNodes,
        payloadClient::fetch,
        realtimePayloadBuffer,
        Executors.newVirtualThreadPerTaskExecutor());
  }

  /** 流式追帧通知的严格 envelope codec；仅供 realtime 专用 LISTEN 连接与跨节点发布使用。 */
  @Bean
  public HarnessStreamCatchupNotificationCodec harnessStreamCatchupNotificationCodec() {
    return new HarnessStreamCatchupNotificationCodec();
  }

  /**
   * 流式追帧应用服务：本地 active owner 优先，只有存在其它活跃节点时才向专用 channel 发布一次提示。
   *
   * <p>本服务不创建任何 durable 写入；checkpoint 仍由 {@link ModelProcessor} 的 active execution 在提交后发布既有 {@code
   * MODEL_CHECKPOINT}。
   */
  @Bean
  public HarnessStreamCatchupService harnessStreamCatchupService(
      HarnessRuntime runtime,
      ModelProcessor modelProcessor,
      ClusterNodePresence clusterNodePresence,
      DataSource dataSource,
      @Qualifier("nodeInstanceId") UUID nodeInstanceId,
      HarnessStreamCatchupNotificationCodec notificationCodec) {
    return new HarnessStreamCatchupService(
        runtime,
        modelProcessor,
        clusterNodePresence,
        new JdbcTemplate(dataSource),
        nodeInstanceId,
        notificationCodec);
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
            providerConfigurationCodec.readHttpRetryStatusCodes(provider.configJson());
        if (override != null) {
          effective = override;
        }
      }
      return new ModelHttpErrorPolicy(effective);
    };
  }

  /** Model / Tool processor 共用的 claim/lease 与 heartbeat 节奏。 */
  @Bean
  public ProcessorLeaseConfig processorLeaseConfig(SystemSettingsSnapshot systemSettingsSnapshot) {
    SystemSettings.Advanced advanced = systemSettingsSnapshot.get().advanced();
    return new ProcessorLeaseConfig(
        Duration.ofMillis(advanced.processorLeaseDurationMillis()),
        Duration.ofMillis(advanced.processorHeartbeatIntervalMillis()));
  }

  @Bean
  public ThreadProcessorConfig threadProcessorConfig(
      CompactionConfigProvider compactionConfigProvider,
      SystemSettingsSnapshot systemSettingsSnapshot) {
    SystemSettings.Advanced advanced = systemSettingsSnapshot.get().advanced();
    return new ThreadProcessorConfig(
        Duration.ofMillis(advanced.threadResolveFailureDelayMillis()), compactionConfigProvider);
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
        ModelStreamConfig.DEFAULT,
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
   * Model / Tool 共用的 lease heartbeat 定时分发调度池。
   *
   * <p>回调仅负责非阻塞状态检查与任务分派，绝不执行数据库事务或所有权取消操作。
   */
  @Bean(name = "harnessProcessorScheduler", destroyMethod = "shutdown")
  public ScheduledExecutorService harnessProcessorScheduler() {
    return Executors.newScheduledThreadPool(
        2, Thread.ofPlatform().name("harness-processor-timer-", 0L).daemon(true).factory());
  }

  /**
   * Model / Tool 心跳续租事务与失联取消执行器。
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
      HarnessStore store, AgentDriver agentDriver, ThreadProcessorConfig config) {
    return new ThreadProcessor(store, agentDriver, config);
  }

  /** Model 执行线程与定时刷新任务：每个任务一个虚拟线程（执行线程在 Provider 调用期间阻塞）。 */
  @Bean(destroyMethod = "close")
  public ExecutorService harnessModelExecutionExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  /** 退出确认 write-ahead journal。部署目录必须是持久卷，且每个 Studio 实例独占；文件锁在进程生命周期内持有。 */
  @Bean(destroyMethod = "close")
  public ExecutionExitJournal executionExitJournal(
      @Value("${kk-studio.harness.runtime.exit-confirmation-directory}") String directory) {
    return new FileExecutionExitJournal(Path.of(directory));
  }

  @Bean
  public ExecutionExitConfirmer executionExitConfirmer(
      HarnessStore store, ExecutionExitJournal executionExitJournal, Clock clock) {
    return new ExecutionExitConfirmer(store, executionExitJournal, clock);
  }

  /** 每秒从 journal 派发一次重放；JDBC 只在 worker 线程执行，关闭时有界取消。 */
  @Bean(initMethod = "start", destroyMethod = "close")
  public ExecutionExitConfirmationRetry executionExitConfirmationRetry(
      ExecutionExitConfirmer executionExitConfirmer) {
    return new ExecutionExitConfirmationRetry(executionExitConfirmer);
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
      @Qualifier("harnessModelExecutionExecutor") ExecutorService executionExecutor,
      ExecutionExitConfirmer executionExitConfirmer) {
    return new ModelProcessor(
        store,
        modelGateway,
        realtimeEventSink,
        config,
        clock,
        scheduler,
        heartbeatWorker,
        executionExecutor,
        executionExitConfirmer);
  }

  @Bean(destroyMethod = "close")
  public ToolProcessor toolProcessor(
      HarnessStore store,
      ToolGateway toolGateway,
      RealtimeEventSink realtimeEventSink,
      ToolProcessorConfig config,
      Clock clock,
      @Qualifier("harnessProcessorScheduler") ScheduledExecutorService scheduler,
      @Qualifier("harnessHeartbeatWorkerExecutor") Executor heartbeatWorker,
      ExecutionExitConfirmer executionExitConfirmer,
      ToolResultHistoryMaterializer materializer) {
    return new ToolProcessor(
        store,
        toolGateway,
        realtimeEventSink,
        config,
        clock,
        scheduler,
        heartbeatWorker,
        executionExitConfirmer,
        materializer);
  }

  @Bean
  public HarnessRuntime harnessRuntime(
      HarnessStore store,
      Clock clock,
      AgentDriver agentDriver,
      CompactionConfigProvider compactionConfigProvider,
      ModelProcessor modelProcessor,
      ToolProcessor toolProcessor) {
    return new HarnessRuntime(
        store,
        agentDriver,
        compactionConfigProvider,
        modelProcessor::cancel,
        toolProcessor::cancel);
  }

  /** 结构化 Model Request Debug 只读服务：依赖完整 Runtime，因此只在 Web 组合根创建。 */
  @Bean
  public ModelRequestDebugService modelRequestDebugService(
      HarnessRuntime runtime,
      DatabaseTurnResolver turnResolver,
      AgentDefinitionRepository agentDefinitionRepository,
      AgentDefinitionConfigCodec agentDefinitionConfigCodec,
      BuiltinRequestQuery builtinRequestQuery,
      Clock clock) {
    return new ModelRequestDebugService(
        runtime,
        turnResolver,
        agentDefinitionRepository,
        agentDefinitionConfigCodec,
        builtinRequestQuery,
        clock);
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
   * 会话事件的组合宿主：READY 唤醒可选的 HarnessWorkDispatcher，并触发该 Environment 的 Skill 全量同步； Daemon 实例接管时关闭旧代际的
   * IDE 数据面。
   *
   * <p>每个动作都立刻返回且各自隔离异常：会话状态推进会先完成，任何宿主回调失败都不得影响会话本身。
   *
   * <p>协作者都用延迟解析：dispatcher、Skill 同步编排器与 IDE 换代监听器都经由 capability 传输间接依赖本监听器（daemon server →
   * listener → 协作者），启动期直接注入会形成环。
   */
  @Bean
  public EnvironmentSessionListener compositeEnvironmentSessionListener(
      ObjectProvider<HarnessWorkDispatcher> dispatcherProvider,
      ObjectProvider<EnvironmentSkillSyncOrchestrator> orchestratorProvider,
      ObjectProvider<IdeGenerationCloseListener> ideGenerationCloseProvider,
      ObjectProvider<IdeLeaseCoordinator> ideLeaseCoordinatorProvider) {
    return new EnvironmentSessionListener() {
      @Override
      public void onEnvironmentReady(EnvironmentId environmentId) {
        try {
          dispatcherProvider.ifAvailable(HarnessWorkDispatcher::wake);
        } catch (RuntimeException ignored) {
        }
        try {
          orchestratorProvider.ifAvailable(
              orchestrator -> orchestrator.onEnvironmentReady(environmentId));
        } catch (RuntimeException ignored) {
        }
      }

      @Override
      public void onHeartbeat(
          EnvironmentId environmentId, DaemonConnectionIdentity identity, Long ideLeaseRound) {
        // 心跳本身只负责投递轮次；使用证明必须由协调器回查事件节点后才可续租。
        try {
          ideLeaseCoordinatorProvider.ifAvailable(
              coordinator -> coordinator.onHeartbeat(environmentId, identity, ideLeaseRound));
        } catch (RuntimeException ignored) {
        }
      }

      @Override
      public void onDaemonInstanceReplaced(DaemonConnectionIdentity previous) {
        // 换进程接管：关闭旧代际的 IDE 反向流并撤销入口凭据，而不是只撤销票据。
        try {
          ideGenerationCloseProvider.ifAvailable(listener -> listener.onGenerationClosed(previous));
        } catch (RuntimeException ignored) {
        }
      }
    };
  }

  @Bean
  public SmartLifecycle harnessRuntimeLifecycle(
      HarnessRuntimeProperties properties, HarnessWorkDispatcher dispatcher) {
    return new HarnessRuntimeLifecycle(properties.isWorkersEnabled(), dispatcher);
  }
}
