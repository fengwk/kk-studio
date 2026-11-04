package fun.fengwk.kkstudio.web.runtime;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;

import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinAgentDriver;
import fun.fengwk.kkstudio.harness.builtin.port.TurnResolver;
import fun.fengwk.kkstudio.harness.builtin.processor.ModelProcessor;
import fun.fengwk.kkstudio.harness.builtin.query.BuiltinRequestQuery;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverRegistry;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.harness.spi.compaction.CompactionConfigProvider;
import fun.fengwk.kkstudio.harness.spi.driver.AgentDriver;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.mcp.runtime.McpToolCatalog;
import fun.fengwk.kkstudio.platform.catalog.tool.PluginRuntimeToolCatalogSource;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalogSource;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.platform.harness.builtin.query.ModelRequestDebugService;
import fun.fengwk.kkstudio.platform.harness.builtin.query.ProviderRequestPreviewService;
import fun.fengwk.kkstudio.platform.harness.builtin.store.PostgresqlBuiltinHarnessStore;
import fun.fengwk.kkstudio.platform.harness.configuration.HarnessDispatcherProperties;
import fun.fengwk.kkstudio.platform.harness.configuration.HarnessExecutionAdmissionProperties;
import fun.fengwk.kkstudio.platform.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.platform.harness.dispatch.HarnessWorkDispatcher;
import fun.fengwk.kkstudio.platform.harness.dispatch.HarnessWorkDispatcherConfig;
import fun.fengwk.kkstudio.platform.harness.model.DatabaseProviderResolutionService;
import fun.fengwk.kkstudio.platform.harness.notification.HarnessChangeNotifications;
import fun.fengwk.kkstudio.platform.harness.thread.command.DatabaseTurnResolver;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginResourceGateway;
import fun.fengwk.kkstudio.platform.pluginhost.PluginProperties;
import fun.fengwk.kkstudio.platform.pluginhost.catalog.PluginCatalogRuntime;
import fun.fengwk.kkstudio.platform.pluginhost.resource.StoragePluginResourceGateway;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

import javax.sql.DataSource;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Web 组合根：把 platform 的 gateway / resolver 与 infra 的 PostgreSQL 持久化和调度适配装配为完整的 Harness Runtime。
 *
 * <p>Processor、realtime 与 Environment 会话各自放在独立配置中，由本类显式导入。进程内 worker dispatcher 只受 {@code
 * workers-enabled} 控制；关闭时控制/查询平面（{@link HarnessRuntime}、store、processor 与 realtime 适配）仍然可用，只是不启动调度。
 * PostgreSQL notification loop 由事件组合根独立持有。
 *
 * <p>所有 executor 线程均为 daemon 并以 {@code destroyMethod = "shutdown"} 交给 Spring 持有生命周期；dispatcher 使用
 * fail-fast 单线程 drain executor 与 bounded AbortPolicy worker executor。
 */
@Configuration(proxyBeanMethods = false)
@Import({
  HarnessProcessorConfiguration.class,
  HarnessRealtimeConfiguration.class,
  HarnessEnvironmentConfiguration.class
})
@EnableConfigurationProperties({
  HarnessRuntimeProperties.class,
  HarnessDispatcherProperties.class,
  HarnessExecutionAdmissionProperties.class
})
public class HarnessRuntimeConfiguration {

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  public PostgresqlBuiltinHarnessStore harnessStore(
      DataSource dataSource, PlatformTransactionManager transactionManager) {
    return new PostgresqlBuiltinHarnessStore(dataSource, transactionManager, UUID::randomUUID);
  }

  /**
   * Builtin Driver 的装配入口。
   *
   * <p>Driver 只在 Runtime 已开启的事务内工作，不执行外部 I/O。
   */
  @Bean
  public AgentDriver agentDriver(
      PostgresqlBuiltinHarnessStore store,
      TurnResolver turnResolver,
      ModelProcessor modelProcessor) {
    return new BuiltinAgentDriver(store, turnResolver, modelProcessor);
  }

  /** Builtin debug 专属只读查询；公共快照不暴露 ModelRequestSpec。 */
  @Bean
  public BuiltinRequestQuery builtinRequestQuery(PostgresqlBuiltinHarnessStore store) {
    return new BuiltinRequestQuery(store, store);
  }

  /** Plugin 资源端口需要 Session Blob 引用与存储装配，由组合根创建；媒体传输构造前先安装统一代理。 */
  @Bean
  @DependsOn("systemProxySelector")
  public PluginResourceGateway pluginResourceGateway(
      SessionBlobRefManager sessionBlobRefManager,
      StorageBlobManager storageBlobManager,
      StorageUploadService storageUploadService,
      PluginProperties properties) {
    return new StoragePluginResourceGateway(
        sessionBlobRefManager, storageBlobManager, storageUploadService, properties);
  }

  /** Harness 工具目录：插件快照的 Contributor 工具加 MCP 动态工具，每次租用冻结为一份不可变目录。 */
  @Bean
  public RuntimeToolCatalogSource runtimeToolCatalogSource(
      PluginCatalogRuntime pluginCatalogRuntime, McpToolCatalog mcpToolCatalog) {
    return new PluginRuntimeToolCatalogSource(pluginCatalogRuntime, mcpToolCatalog);
  }

  @Bean
  public DriverRegistry driverRegistry(List<AgentDriver> drivers) {
    return new DriverRegistry(drivers);
  }

  @Bean
  public HarnessRuntime harnessRuntime(
      HarnessStore store,
      DriverRegistry driverRegistry,
      CompactionConfigProvider compactionConfigProvider,
      ToolProcessor toolProcessor) {
    return new HarnessRuntime(
        store,
        driverRegistry,
        compactionConfigProvider,
        driverRegistry::cancelWork,
        toolProcessor::cancel);
  }

  /**
   * Harness 变更订阅的公开入口，同时充当内部观察者（TaskTool / PluginPromptTaskService）的纯 wake 源：Thread version、执行树与待处理
   * 交互共用同一份注册与 resync 语义。
   */
  @Bean
  public HarnessChangeNotifications harnessChangeNotifications(
      ObjectProvider<HarnessRuntime> runtime) {
    // Runtime 依赖 ToolProcessor，而工具目录初始化期间会取得这个 wake 源；只在订阅时解析 Runtime。
    return new HarnessChangeNotifications(runtime::getObject);
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
      @Qualifier("harnessDispatcherDrainExecutor") Executor drainExecutor,
      @Qualifier("harnessDispatcherWorkerExecutor") Executor workerExecutor,
      @Qualifier("harnessDispatcherPollScheduler") ScheduledExecutorService pollScheduler,
      ThreadProcessor threadProcessor,
      ToolProcessor toolProcessor,
      DriverRegistry driverRegistry) {
    HarnessWorkDispatcherConfig config =
        new HarnessWorkDispatcherConfig(
            dispatcherProperties.getLeaseDuration(),
            dispatcherProperties.getPollInterval(),
            dispatcherProperties.getRejectionDelay(),
            dispatcherProperties.getMaxDispatchTasks());
    Map<WorkTargetType, Consumer<ClaimedWork>> handlers = new HashMap<>();
    handlers.put(WorkTargetType.THREAD, threadProcessor::process);
    handlers.put(WorkTargetType.TOOL, toolProcessor::process);
    handlers.putAll(driverRegistry.workHandlers());
    return new HarnessWorkDispatcher(
        nodeInstanceId, store, config, drainExecutor, workerExecutor, pollScheduler, handlers);
  }

  @Bean
  public SmartLifecycle harnessRuntimeLifecycle(
      HarnessRuntimeProperties properties,
      HarnessWorkDispatcher dispatcher,
      DriverRegistry driverRegistry,
      ToolProcessor toolProcessor,
      ObjectProvider<EnvironmentDaemonServer> daemonServerProvider) {
    return new HarnessRuntimeLifecycle(
        properties.isWorkersEnabled(),
        dispatcher,
        driverRegistry::hasActiveWork,
        toolProcessor,
        daemonServerProvider,
        properties.getDrainTimeout());
  }
}
