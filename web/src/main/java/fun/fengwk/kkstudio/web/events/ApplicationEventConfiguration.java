package fun.fengwk.kkstudio.web.events;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.canvas.infra.function.CanvasFunctionDispatcher;
import fun.fengwk.kkstudio.harness.infra.dispatch.HarnessWorkDispatcher;
import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlRealtimeEventSource;
import fun.fengwk.kkstudio.harness.infra.realtime.RealtimeBus;
import fun.fengwk.kkstudio.harness.infra.realtime.RealtimeEventSource;
import fun.fengwk.kkstudio.harness.runtime.HarnessThreadChangeSource;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;
import fun.fengwk.kkstudio.platform.environment.cluster.RealtimeSubscriptionService;
import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillSyncOrchestrator;
import fun.fengwk.kkstudio.platform.harness.realtime.HarnessStreamCatchupService;
import fun.fengwk.kkstudio.platform.pluginhost.catalog.PluginCatalogRefreshTrigger;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsChangeHandler;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.project.reconcile.IssueReconcileDispatcher;
import fun.fengwk.kkstudio.web.events.postgresql.PostgresqlNotificationHandler;
import fun.fengwk.kkstudio.web.events.postgresql.PostgresqlNotificationLoop;
import fun.fengwk.kkstudio.web.project.ProjectInvalidationHub;
import fun.fengwk.kkstudio.work.DurableWorkQueue;

import javax.sql.DataSource;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** 事件通道组合：严格帧 codec 与传输无关的 {@link ApplicationEventHub}。 */
@Configuration(proxyBeanMethods = false)
public class ApplicationEventConfiguration {

  @Bean
  public EventFrameCodec eventFrameCodec() {
    return new EventFrameCodec(new RealtimeEventJsonCodec());
  }

  /**
   * 浏览器事件通道的软策略快照：读取共享启动快照 {@link SystemSettingsSnapshot} 的 SystemSettings.Advanced {@code
   * applicationEvent*} 字段（装配期一次 DB 读取，DB 变更需重启生效），供 Hub 缓冲上限、发送队列上界、send timeout 与 heartbeat 间隔使用。
   */
  @Bean
  public ApplicationEventSettings applicationEventSettings(
      SystemSettingsSnapshot systemSettingsSnapshot) {
    SystemSettings.Advanced advanced = systemSettingsSnapshot.get().advanced();
    return new ApplicationEventSettings(
        advanced.applicationEventQueueCapacity(),
        advanced.applicationEventMaxBytes(),
        advanced.applicationEventSendTimeoutMillis(),
        advanced.applicationEventHeartbeatIntervalMillis());
  }

  /**
   * 内部观察者（TaskTool / PluginPromptTaskService）的 Thread change 源：复用 {@link ThreadVersionEventSource}
   * 的原子订阅与 resync 语义，只保留纯 wake 信号。
   */
  @Bean
  public HarnessThreadChangeSource harnessThreadChangeSource(
      ThreadVersionEventSource versionSource) {
    return new WebHarnessThreadChangeSource(versionSource);
  }

  /** 全应用共享一个专用 PostgreSQL connection，固定监听全部低延迟通知 channel。 */
  @Bean(destroyMethod = "close")
  public PostgresqlNotificationLoop postgresqlNotificationLoop(
      DataSource dataSource,
      HarnessWorkDispatcher dispatcher,
      IssueReconcileDispatcher issueReconcileDispatcher,
      CanvasFunctionDispatcher canvasFunctionDispatcher,
      ThreadVersionHub threadVersionHub,
      CanvasVersionHub canvasVersionHub,
      ProjectInvalidationHub projectInvalidationHub,
      SystemSettingsChangeHandler systemSettingsChangeHandler,
      EnvironmentSkillSyncOrchestrator environmentSkillSyncOrchestrator,
      ExecutionTreeChangeHub executionTreeChangeHub,
      InteractionChangeHub interactionChangeHub,
      EnvironmentChangeHub environmentChangeHub,
      PostgresqlNotificationHandler pluginCatalogNotificationHandler,
      SystemSettingsSnapshot systemSettingsSnapshot) {
    SystemSettings.Advanced advanced = systemSettingsSnapshot.get().advanced();
    return new PostgresqlNotificationLoop(
        dataSource,
        List.of(
            // 1. Durable Work 唤醒：payload 是就绪行的 target type，只唤醒负责该类型的调度器；调度器通过 FOR UPDATE SKIP
            // LOCKED
            // 并发抢占。重连后无法得知漏掉了哪些类型，全部唤醒一次。
            new PostgresqlNotificationHandler(
                DurableWorkQueue.CHANNEL,
                targetType ->
                    durableWorkDispatcher(
                            targetType,
                            dispatcher,
                            issueReconcileDispatcher,
                            canvasFunctionDispatcher)
                        .run(),
                () -> {
                  dispatcher.wake();
                  issueReconcileDispatcher.wake();
                  canvasFunctionDispatcher.wake();
                }),
            // 2. 会话版本失效：Thread 版本号推进，通知 WebSocket 向前端广播版本事件以触发快照对账
            new PostgresqlNotificationHandler(
                ThreadVersionHub.CHANNEL,
                threadVersionHub::onNotification,
                threadVersionHub::broadcastResync),
            // 3. 画布版本失效：Canvas 文档版本号推进，通知 WebSocket 向前端广播版本事件以触发图谱更新
            new PostgresqlNotificationHandler(
                CanvasVersionHub.CHANNEL,
                canvasVersionHub::onNotification,
                canvasVersionHub::broadcastResync),
            // 4. Project/Issue 失效：数据库事实提交后按 projectId 提示浏览器回读权威 Snapshot
            new PostgresqlNotificationHandler(
                ProjectInvalidationHub.CHANNEL,
                projectInvalidationHub::onNotification,
                projectInvalidationHub::broadcastResync),
            // 5. 系统设置同步：集群任一节点修改全局设置提交后，广播通知所有节点原子回读最新快照
            new PostgresqlNotificationHandler(
                SystemSettingsChangeHandler.CHANNEL,
                systemSettingsChangeHandler::onNotification,
                systemSettingsChangeHandler::onResync),
            // 6. Skill Package 变更：payload 是 package 名，把该 Package 同步到本节点全部 READY 的 Environment；
            // 建连/重连时全量对账，通知丢失不改变 durable truth
            new PostgresqlNotificationHandler(
                EnvironmentSkillSyncOrchestrator.CHANNEL,
                environmentSkillSyncOrchestrator::onPackageChanged,
                environmentSkillSyncOrchestrator::reconcileReadyEnvironments),
            // 7. 执行树失效：Thread 写事务内由写入口把 payload 聚合为真实执行根 id，浏览器回读该根的树
            new PostgresqlNotificationHandler(
                ExecutionTreeChangeHub.CHANNEL,
                executionTreeChangeHub::onNotification,
                executionTreeChangeHub::broadcastResync),
            // 8. 待处理交互失效：ToolInvocation 进出审批/人工输入等待后提交，payload 是真实执行根 id
            new PostgresqlNotificationHandler(
                InteractionChangeHub.CHANNEL,
                interactionChangeHub::onNotification,
                interactionChangeHub::broadcastResync),
            // 9. 环境连接变更：payload 是环境 id，不是执行根；环境精准失效，交互全量对账。
            new PostgresqlNotificationHandler(
                EnvironmentChangeHub.CHANNEL,
                payload -> {
                  environmentChangeHub.onNotification(payload);
                  interactionChangeHub.broadcastResync();
                },
                () -> {
                  environmentChangeHub.broadcastResync();
                  interactionChangeHub.broadcastResync();
                }),
            // 10. Plugin 目录变更：集群任一节点提交配置后广播提示其它节点立即回读权威目录；payload 只作提示、不能作为可信状态，
            // 建连/重连时同样 resync，通知丢失由周期对账追平
            pluginCatalogNotificationHandler),
        Duration.ofMillis(advanced.postgresqlWorkNotificationPollMillis()),
        Duration.ofMillis(advanced.postgresqlWorkReconnectBackoffMillis()));
  }

  /** 流式增量使用独立 LISTEN 连接：realtime 的高频小帧与 durable work / 失效提示互不阻塞，重连也只 resync 实时订阅。 */
  @Bean(destroyMethod = "close")
  public PostgresqlNotificationLoop realtimeNotificationLoop(
      DataSource dataSource,
      RealtimeBus realtimeBus,
      HarnessStreamCatchupService streamCatchupService,
      SystemSettingsSnapshot systemSettingsSnapshot) {
    SystemSettings.Advanced advanced = systemSettingsSnapshot.get().advanced();
    return createRealtimeNotificationLoop(
        dataSource,
        realtimeBus,
        streamCatchupService,
        Duration.ofMillis(advanced.postgresqlWorkNotificationPollMillis()),
        Duration.ofMillis(advanced.postgresqlWorkReconnectBackoffMillis()));
  }

  static PostgresqlNotificationLoop createRealtimeNotificationLoop(
      DataSource dataSource,
      RealtimeBus realtimeBus,
      HarnessStreamCatchupService streamCatchupService,
      Duration notificationPollInterval,
      Duration reconnectBackoff) {
    return new PostgresqlNotificationLoop(
        dataSource,
        List.of(
            new PostgresqlNotificationHandler(
                PostgresqlRealtimeEventSource.CHANNEL,
                realtimeBus::onNotification,
                realtimeBus::onResync),
            // 2. 跨节点流式追帧：每条通知只让接收节点对 active owner 做一次 attempt fence。重连不重放历史追帧，
            // 也不回查 head；没有 active owner 时下一次真实请求或 checkpoint 自然收敛。
            new PostgresqlNotificationHandler(
                HarnessStreamCatchupService.CHANNEL,
                streamCatchupService::onNotification,
                () -> {})),
        notificationPollInterval,
        reconnectBackoff);
  }

  @Bean
  public PostgresqlNotificationHandler pluginCatalogNotificationHandler(
      PluginCatalogRefreshTrigger trigger) {
    return new PostgresqlNotificationHandler(
        PluginConfigurationRepository.CHANNEL,
        ignored -> trigger.requestRefresh(),
        trigger::requestRefresh);
  }

  @Bean(destroyMethod = "close")
  public ApplicationEventHub applicationEventHub(
      ThreadVersionEventSource threadVersionSource,
      RealtimeEventSource realtimeSource,
      RealtimeSubscriptionService realtimeSubscriptions,
      CanvasVersionEventSource canvasVersionSource,
      ProjectInvalidationHub projectInvalidationHub,
      ExecutionTreeChangeHub executionTreeChangeHub,
      InteractionChangeHub interactionChangeHub,
      EnvironmentChangeHub environmentChangeHub,
      ApplicationEventSettings settings) {
    return new ApplicationEventHub(
        threadVersionSource,
        realtimeSource,
        realtimeSubscriptions,
        canvasVersionSource,
        projectInvalidationHub,
        executionTreeChangeHub,
        interactionChangeHub,
        environmentChangeHub,
        settings.queueCapacity());
  }

  /** 全应用事件连接共享一个 heartbeat scheduler；每次 tick 只做异步发送队列入队。 */
  @Bean(name = "applicationEventHeartbeatScheduler", destroyMethod = "shutdown")
  public ScheduledExecutorService applicationEventHeartbeatScheduler() {
    return Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().name("application-event-heartbeat").daemon(true).factory());
  }

  private static Runnable durableWorkDispatcher(
      String targetType,
      HarnessWorkDispatcher harnessDispatcher,
      IssueReconcileDispatcher issueReconcileDispatcher,
      CanvasFunctionDispatcher canvasFunctionDispatcher) {
    return switch (targetType) {
      case IssueReconcileDispatcher.TARGET_TYPE -> issueReconcileDispatcher::wake;
      case CanvasFunctionDispatcher.TARGET_TYPE -> canvasFunctionDispatcher::wake;
      default -> harnessDispatcher::wake;
    };
  }
}
