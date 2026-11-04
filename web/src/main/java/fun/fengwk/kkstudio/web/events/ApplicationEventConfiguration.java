package fun.fengwk.kkstudio.web.events;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.canvas.infra.function.ClaimedRun;
import fun.fengwk.kkstudio.canvas.infra.postgresql.CanvasFunctionWorkStore;
import fun.fengwk.kkstudio.canvas.notification.CanvasVersionEventSource;
import fun.fengwk.kkstudio.canvas.notification.CanvasVersionNotifications;
import fun.fengwk.kkstudio.harness.api.HarnessChangeSubscriptions;
import fun.fengwk.kkstudio.harness.codec.RealtimeEventJsonCodec;
import fun.fengwk.kkstudio.platform.cluster.RealtimeSubscriptionService;
import fun.fengwk.kkstudio.platform.environment.EnvironmentChangeNotifications;
import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillSyncOrchestrator;
import fun.fengwk.kkstudio.platform.harness.dispatch.HarnessWorkDispatcher;
import fun.fengwk.kkstudio.platform.harness.notification.HarnessChangeNotifications;
import fun.fengwk.kkstudio.platform.harness.notification.HarnessNotificationBinding;
import fun.fengwk.kkstudio.platform.harness.realtime.HarnessStreamCatchupService;
import fun.fengwk.kkstudio.platform.harness.realtime.PostgresqlRealtimeEventSource;
import fun.fengwk.kkstudio.platform.harness.realtime.RealtimeBus;
import fun.fengwk.kkstudio.platform.harness.realtime.RealtimeEventSource;
import fun.fengwk.kkstudio.platform.pluginhost.catalog.PluginCatalogRefreshTrigger;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsChangeHandler;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.project.notification.ProjectChangeNotifications;
import fun.fengwk.kkstudio.project.reconcile.IssueReconciler;
import fun.fengwk.kkstudio.web.events.postgresql.PostgresqlNotificationHandler;
import fun.fengwk.kkstudio.web.events.postgresql.PostgresqlNotificationLoop;
import fun.fengwk.kkstudio.work.BoundedWorkDispatcher;
import fun.fengwk.kkstudio.work.DurableWorkStore;
import fun.fengwk.kkstudio.work.WorkClaim;

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

  /** 全应用共享一个专用 PostgreSQL connection，固定监听全部低延迟通知 channel。 */
  @Bean(destroyMethod = "close")
  public PostgresqlNotificationLoop postgresqlNotificationLoop(
      DataSource dataSource,
      HarnessWorkDispatcher dispatcher,
      @Qualifier("issueReconcileDispatcher")
          BoundedWorkDispatcher<WorkClaim> issueReconcileDispatcher,
      @Qualifier("canvasFunctionDispatcher")
          BoundedWorkDispatcher<ClaimedRun> canvasFunctionDispatcher,
      HarnessChangeNotifications harnessNotifications,
      CanvasVersionNotifications canvasVersionNotifications,
      ProjectChangeNotifications projectChangeNotifications,
      SystemSettingsChangeHandler systemSettingsChangeHandler,
      EnvironmentSkillSyncOrchestrator environmentSkillSyncOrchestrator,
      EnvironmentChangeNotifications environmentChangeNotifications,
      PostgresqlNotificationHandler pluginCatalogNotificationHandler,
      SystemSettingsSnapshot systemSettingsSnapshot) {
    SystemSettings.Advanced advanced = systemSettingsSnapshot.get().advanced();
    HarnessNotificationBinding threadVersions = harnessNotifications.threadVersionsNotification();
    HarnessNotificationBinding executionTree = harnessNotifications.executionTreeNotification();
    HarnessNotificationBinding interactions = harnessNotifications.interactionsNotification();
    return new PostgresqlNotificationLoop(
        dataSource,
        List.of(
            // 1. Durable Work 唤醒：payload 是就绪行的 target type，只唤醒负责该类型的调度器；调度器通过 FOR UPDATE SKIP
            // LOCKED
            // 并发抢占。重连后无法得知漏掉了哪些类型，全部唤醒一次。
            new PostgresqlNotificationHandler(
                DurableWorkStore.CHANNEL,
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
                threadVersions.channel(),
                threadVersions.onNotification(),
                threadVersions.onResync()),
            // 3. 画布版本失效：Canvas 拥有 canvas_revision 通道、payload 协议与 revision fan-out；Web 只建立 LISTEN
            // 并把信号转发给浏览器事件通道以触发图谱更新
            new PostgresqlNotificationHandler(
                CanvasVersionNotifications.CHANNEL,
                canvasVersionNotifications::onNotification,
                canvasVersionNotifications::broadcastResync),
            // 4. Project/Issue 失效：生产方拥有通道与订阅入口，数据库事实提交后按 projectId 提示浏览器回读权威 Snapshot
            new PostgresqlNotificationHandler(
                ProjectChangeNotifications.CHANNEL,
                projectChangeNotifications::onNotification,
                projectChangeNotifications::broadcastResync),
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
                executionTree.channel(), executionTree.onNotification(), executionTree.onResync()),
            // 8. 待处理交互失效：ToolInvocation 进出审批/人工输入等待后提交，payload 是真实执行根 id
            new PostgresqlNotificationHandler(
                interactions.channel(), interactions.onNotification(), interactions.onResync()),
            // 9. 环境连接变更：payload 是环境 id，不是执行根；环境精准失效，交互全量对账。
            new PostgresqlNotificationHandler(
                EnvironmentChangeNotifications.CHANNEL,
                payload -> {
                  environmentChangeNotifications.onNotification(payload);
                  interactions.onResync().run();
                },
                () -> {
                  environmentChangeNotifications.broadcastResync();
                  interactions.onResync().run();
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
      HarnessChangeSubscriptions harnessChanges,
      RealtimeEventSource realtimeSource,
      RealtimeSubscriptionService realtimeSubscriptions,
      CanvasVersionEventSource canvasVersionSource,
      ProjectChangeNotifications projectChangeNotifications,
      EnvironmentChangeNotifications environmentChangeNotifications,
      ApplicationEventSettings settings) {
    return new ApplicationEventHub(
        harnessChanges,
        realtimeSource,
        realtimeSubscriptions,
        canvasVersionSource,
        projectChangeNotifications,
        environmentChangeNotifications,
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
      BoundedWorkDispatcher<WorkClaim> issueReconcileDispatcher,
      BoundedWorkDispatcher<ClaimedRun> canvasFunctionDispatcher) {
    return switch (targetType) {
      case IssueReconciler.TARGET_TYPE -> issueReconcileDispatcher::wake;
      case CanvasFunctionWorkStore.TARGET_TYPE -> canvasFunctionDispatcher::wake;
      default -> harnessDispatcher::wake;
    };
  }
}
